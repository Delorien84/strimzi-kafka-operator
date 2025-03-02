/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Metric;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricsRegistry;
import com.yammer.metrics.core.MetricsRegistryListener;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * A very simple Java agent which polls the value of the {@code kafka.server:type=KafkaServer,name=BrokerState}
 * Yammer Metric and once it reaches the value 3 (meaning "running as broker", see {@code kafka.server.BrokerState}),
 * creates a given file.
 * The presence of this file is tested via a Kube "exec" readiness probe to determine when the broker is ready.
 * It also exposes a REST endpoint for broker metrics.
 * <dl>
 *     <dt>{@code GET /v1/broker-state}</dt>
 *     <dd>Reflects the BrokerState metric, returning a JSON response e.g. {"brokerState": 3}.
 *      If broker state is RECOVERY(2), it includes remainingLogsToRecover and remainingLogsToRecover in the response e.g.
 *      {"brokerState": 2,
 *       "recovery": {
 *          "remainingLogsToRecover": 123,
 *          "remainingSegmentsToRecover": 456
 *        }
 *      }</dd>
 * </dl>
 */
public class KafkaAgent {
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaAgent.class);
    private static final String BROKER_STATE_PATH = "/v1/broker-state";
    private static final int HTTPS_PORT = 8443;
    private static final long GRACEFUL_SHUTDOWN_TIMEOUT_MS = 30 * 1000;

    // KafkaYammerMetrics class in Kafka 3.3+
    private static final String YAMMER_METRICS_IN_KAFKA_3_3_AND_LATER = "org.apache.kafka.server.metrics.KafkaYammerMetrics";
    // KafkaYammerMetrics class in Kafka 3.2-
    private static final String YAMMER_METRICS_IN_KAFKA_3_2_AND_EARLIER = "kafka.metrics.KafkaYammerMetrics";

    private static final byte BROKER_RUNNING_STATE = 3;
    private static final byte BROKER_RECOVERY_STATE = 2;
    private static final byte BROKER_UNKNOWN_STATE = 127;
    private File sessionConnectedFile;
    private File brokerReadyFile;
    private String sslKeyStorePath;
    private String sslKeyStorePassword;
    private String sslTruststorePath;
    private String sslTruststorePassword;
    private MetricName brokerStateName;
    private Gauge brokerState;
    private Gauge remainingLogsToRecover;
    private Gauge remainingSegmentsToRecover;
    private MetricName sessionStateName;
    private Gauge sessionState;
    private boolean pollerRunning;

    /**
     * Constructor of the KafkaAgent
     *
     * @param brokerReadyFile       File which is touched (created) when the broker is ready
     * @param sessionConnectedFile  File which is touched (created) when the Kafka broker connects successfully to ZooKeeper
     * @param sslKeyStorePath       Keystore containing the broker certificate
     * @param sslKeyStorePass       Password for keystore
     * @param sslTruststorePath     Truststore containing CA certs for authenticating clients
     * @param sslTruststorePass     Password for truststore
     */
    public KafkaAgent(File brokerReadyFile, File sessionConnectedFile, String sslKeyStorePath, String sslKeyStorePass, String sslTruststorePath, String sslTruststorePass) {
        this.brokerReadyFile = brokerReadyFile;
        this.sessionConnectedFile = sessionConnectedFile;
        this.sslKeyStorePath = sslKeyStorePath;
        this.sslKeyStorePassword = sslKeyStorePass;
        this.sslTruststorePath = sslTruststorePath;
        this.sslTruststorePassword = sslTruststorePass;
    }

    // public for testing
    /**
     * Constructor of the KafkaAgent
     *
     * @param brokerState                 Current state of the broker
     * @param remainingLogsToRecover      Number of remaining logs to recover
     * @param remainingSegmentsToRecover  Number of remaining segments to recover
     */
    public KafkaAgent(Gauge brokerState, Gauge remainingLogsToRecover, Gauge remainingSegmentsToRecover) {
        this.brokerState = brokerState;
        this.remainingLogsToRecover = remainingLogsToRecover;
        this.remainingSegmentsToRecover = remainingSegmentsToRecover;
    }

    private void run() {
        Thread pollerThread = new Thread(poller(),
                "KafkaAgentPoller");
        pollerThread.setDaemon(true);

        try {
            startBrokerStateServer();
        } catch (Exception e) {
            LOGGER.error("Could not start the server for broker state: ", e);
            throw new RuntimeException(e);
        }

        LOGGER.info("Starting metrics registry");
        MetricsRegistry metricsRegistry = metricsRegistry();

        metricsRegistry.addListener(new MetricsRegistryListener() {
            @Override
            public void onMetricRemoved(MetricName metricName) {
            }

            @Override
            public synchronized void onMetricAdded(MetricName metricName, Metric metric) {
                LOGGER.debug("Metric added {}", metricName);
                if (isBrokerState(metricName) && metric instanceof Gauge) {
                    brokerStateName = metricName;
                    brokerState = (Gauge) metric;
                } else if (isRemainingLogsToRecover(metricName) && metric instanceof Gauge) {
                    remainingLogsToRecover = (Gauge) metric;
                } else if (isRemainingSegmentsToRecover(metricName) && metric instanceof Gauge) {
                    remainingSegmentsToRecover = (Gauge) metric;
                } else if (isSessionState(metricName)
                        && metric instanceof Gauge) {
                    sessionStateName = metricName;
                    sessionState = (Gauge) metric;
                }

                if (brokerState != null && sessionState != null && !pollerRunning) {
                    LOGGER.info("Starting poller");
                    pollerThread.start();
                    pollerRunning = true;
                }
            }
        });
    }

    /**
     * Acquires the MetricsRegistry from the KafkaYammerMetrics class. Depending on the Kafka version we are on, it will
     * use reflection to use the right class to get it.
     *
     * @return  Metrics Registry object
     */
    private MetricsRegistry metricsRegistry()   {
        Object metricsRegistry;
        Class<?> yammerMetrics;

        try {
            // First we try to get the KafkaYammerMetrics class for Kafka 3.3+
            yammerMetrics = Class.forName(YAMMER_METRICS_IN_KAFKA_3_3_AND_LATER);
            LOGGER.info("Found class {} for Kafka 3.3 and newer.", YAMMER_METRICS_IN_KAFKA_3_3_AND_LATER);
        } catch (ClassNotFoundException e)    {
            LOGGER.info("Class {} not found. We are probably on Kafka 3.2 or older.", YAMMER_METRICS_IN_KAFKA_3_3_AND_LATER);

            // We did not find the KafkaYammerMetrics class from Kafka 3.3+. So we are probably on older Kafka version
            //     => we will try the older class for Kafka 3.2-.
            try {
                yammerMetrics = Class.forName(YAMMER_METRICS_IN_KAFKA_3_2_AND_EARLIER);
                LOGGER.info("Found class {} for Kafka 3.2 and older.", YAMMER_METRICS_IN_KAFKA_3_2_AND_EARLIER);
            } catch (ClassNotFoundException e2) {
                // No class was found for any Kafka version => we should fail
                LOGGER.error("Class {} not found. We are not on Kafka 3.2 or earlier either.", YAMMER_METRICS_IN_KAFKA_3_2_AND_EARLIER);
                throw new RuntimeException("Failed to find Yammer Metrics class", e2);
            }
        }

        try {
            Method method = yammerMetrics.getMethod("defaultRegistry");
            metricsRegistry = method.invoke(null);
        } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to get metrics registry", e);
        }

        if (metricsRegistry instanceof MetricsRegistry) {
            return (MetricsRegistry) metricsRegistry;
        } else {
            throw new RuntimeException("Metrics registry does not have the expected type");
        }
    }

    private boolean isBrokerState(MetricName name) {
        return "BrokerState".equals(name.getName())
                && "kafka.server".equals(name.getGroup())
                && "KafkaServer".equals(name.getType());
    }
    private boolean isRemainingLogsToRecover(MetricName name) {
        return "remainingLogsToRecover".equals(name.getName())
                && "kafka.log".equals(name.getGroup())
                && "LogManager".equals(name.getType());
    }
    private boolean isRemainingSegmentsToRecover(MetricName name) {
        return "remainingSegmentsToRecover".equals(name.getName())
                && "kafka.log".equals(name.getGroup())
                && "LogManager".equals(name.getType());
    }

    private boolean isSessionState(MetricName name) {
        return "SessionState".equals(name.getName())
                && "SessionExpireListener".equals(name.getType());
    }

    private void startBrokerStateServer() throws Exception {
        Server server = new Server();

        HttpConfiguration https = new HttpConfiguration();
        https.addCustomizer(new SecureRequestCustomizer());

        ServerConnector conn = new ServerConnector(server,
                new SslConnectionFactory(getSSLContextFactory(), "http/1.1"),
                new HttpConnectionFactory(https));
        conn.setPort(HTTPS_PORT);

        ContextHandler context = new ContextHandler(BROKER_STATE_PATH);
        context.setHandler(getServerHandler());

        server.setConnectors(new Connector[]{conn});
        server.setHandler(context);
        server.setStopTimeout(GRACEFUL_SHUTDOWN_TIMEOUT_MS);
        server.setStopAtShutdown(true);
        server.start();
    }

    /**
     * Creates a Handler instance to handle incoming HTTP requests
     *
     * @return Handler
     */
    // public for testing
    public Handler getServerHandler() {
        return new AbstractHandler() {
            @Override
            public void handle(String s, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                baseRequest.setHandled(true);

                Map<String, Object> brokerStateResponse = new HashMap<>();
                if (brokerState != null) {
                    if ((byte) brokerState.value() == BROKER_RECOVERY_STATE && remainingLogsToRecover != null && remainingSegmentsToRecover != null) {
                        Map<String, Object> recoveryState = new HashMap<>();
                        recoveryState.put("remainingLogsToRecover", remainingLogsToRecover.value());
                        recoveryState.put("remainingSegmentsToRecover", remainingSegmentsToRecover.value());
                        brokerStateResponse.put("brokerState", brokerState.value());
                        brokerStateResponse.put("recoveryState", recoveryState);
                    } else {
                        brokerStateResponse.put("brokerState", brokerState.value());
                    }

                    response.setStatus(HttpServletResponse.SC_OK);
                    String json = new ObjectMapper().writeValueAsString(brokerStateResponse);
                    response.getWriter().print(json);
                } else {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    response.getWriter().print("Broker state metric not found");
                }
            }
        };
    }


    private SslContextFactory getSSLContextFactory() {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();

        sslContextFactory.setKeyStorePath(sslKeyStorePath);
        sslContextFactory.setKeyStorePassword(sslKeyStorePassword);
        sslContextFactory.setKeyManagerPassword(sslKeyStorePassword);

        sslContextFactory.setTrustStorePath(sslTruststorePath);
        sslContextFactory.setTrustStorePassword(sslTruststorePassword);
        sslContextFactory.setNeedClientAuth(true);
        return  sslContextFactory;
    }

    private Runnable poller() {
        return new Runnable() {
            int i = 0;

            @Override
            public void run() {
                while (true) {
                    handleSessionState();

                    if (handleBrokerState()) {
                        break;
                    }

                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        // In theory this should never normally happen
                        LOGGER.warn("Unexpectedly interrupted");
                        break;
                    }
                }
                LOGGER.debug("Exiting thread");
            }

            boolean handleBrokerState() {
                LOGGER.trace("Polling {}", brokerStateName);
                boolean ready = false;
                byte observedState = (byte) brokerState.value();

                boolean stateIsRunning = BROKER_RUNNING_STATE <= observedState && BROKER_UNKNOWN_STATE != observedState;
                if (stateIsRunning) {
                    try {
                        LOGGER.trace("Running as server according to {} => ready", brokerStateName);
                        touch(brokerReadyFile);
                    } catch (IOException e) {
                        LOGGER.error("Could not write readiness file {}", brokerReadyFile, e);
                    }
                    ready = true;
                } else if (i++ % 60 == 0) {
                    LOGGER.debug("Metric {} = {}", brokerStateName, observedState);
                }
                return ready;
            }

            void handleSessionState() {
                LOGGER.trace("Polling {}", sessionStateName);
                String sessionStateStr = String.valueOf(sessionState.value());
                if ("CONNECTED".equals(sessionStateStr)) {
                    if (!sessionConnectedFile.exists()) {
                        try {
                            touch(sessionConnectedFile);
                        } catch (IOException e) {
                            LOGGER.error("Could not write session connected file {}", sessionConnectedFile, e);
                        }
                    }
                } else {
                    if (sessionConnectedFile.exists() && !sessionConnectedFile.delete()) {
                        LOGGER.error("Could not delete session connected file {}", sessionConnectedFile);
                    }
                    if (i++ % 60 == 0) {
                        LOGGER.debug("Metric {} = {}", sessionStateName, sessionStateStr);
                    }
                }
            }
        };
    }

    private void touch(File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            file.deleteOnExit();
        }
    }

    /**
     * Agent entry point
     * @param agentArgs The agent arguments
     */
    public static void premain(String agentArgs) {
        String[] args = agentArgs.split(":");
        if (args.length < 6) {
            LOGGER.error("Not enough arguments to parse {}", agentArgs);
            System.exit(1);
        } else {
            File brokerReadyFile = new File(args[0]);
            File sessionConnectedFile = new File(args[1]);
            String sslKeyStorePath = args[2];
            String sslKeyStorePass = args[3];
            String sslTrustStorePath = args[4];
            String sslTrustStorePass = args[5];
            if (brokerReadyFile.exists() && !brokerReadyFile.delete()) {
                LOGGER.error("Broker readiness file already exists and could not be deleted: {}", brokerReadyFile);
                System.exit(1);
            } else if (sessionConnectedFile.exists() && !sessionConnectedFile.delete()) {
                LOGGER.error("Session connected file already exists and could not be deleted: {}", sessionConnectedFile);
                System.exit(1);
            } else if (sslKeyStorePath.isEmpty() || sslTrustStorePath.isEmpty()) {
                LOGGER.error("SSLKeyStorePath or SSLTrustStorePath is empty: sslKeyStorePath={} sslTrustStore={} ", sslKeyStorePath, sslTrustStorePath);
                System.exit(1);
            } else if (sslKeyStorePass.isEmpty()) {
                LOGGER.error("Keystore password is empty");
                System.exit(1);
            } else if (sslTrustStorePass.isEmpty()) {
                LOGGER.error("Truststore password is empty");
                System.exit(1);
            } else {
                LOGGER.info("Starting KafkaAgent with brokerReadyFile={}, sessionConnectedFile={}, sslKeyStorePath={}, sslTrustStore={}",
                        brokerReadyFile, sessionConnectedFile, sslKeyStorePath, sslTrustStorePath);
                new KafkaAgent(brokerReadyFile, sessionConnectedFile, sslKeyStorePath, sslKeyStorePass, sslTrustStorePath, sslTrustStorePass).run();
            }
        }
    }
}
