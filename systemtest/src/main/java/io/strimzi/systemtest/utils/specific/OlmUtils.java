/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.utils.specific;

import io.fabric8.openshift.api.model.operatorhub.v1alpha1.InstallPlan;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.resources.operator.SetupClusterOperator;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

public class OlmUtils {

    private static final Logger LOGGER = LogManager.getLogger(SetupClusterOperator.class);

    private OlmUtils() {}

    public static void waitUntilNonUsedInstallPlanIsPresent(String namespaceName) {
        TestUtils.waitFor("non used install plan to be present", Constants.OLM_UPGRADE_INSTALL_PLAN_POLL, Constants.OLM_UPGRADE_INSTALL_PLAN_TIMEOUT,
            () -> kubeClient().getNonApprovedInstallPlan(namespaceName) != null);
    }

    public static void waitUntilInstallPlanContainingCertainCsvIsPresent(String namespaceName, String csvName) {
        TestUtils.waitFor(String.format("non used install plan with CSV: {} to be present", csvName), Constants.OLM_UPGRADE_INSTALL_PLAN_POLL, Constants.OLM_UPGRADE_INSTALL_PLAN_TIMEOUT,
            () -> {
                if (kubeClient().getNonApprovedInstallPlan(namespaceName) != null) {
                    InstallPlan installPlan = kubeClient().getNonApprovedInstallPlan(namespaceName);
                    kubeClient().approveInstallPlan(namespaceName, installPlan.getMetadata().getName());
                    String currentCsv = installPlan.getSpec().getClusterServiceVersionNames().get(0).toString();

                    LOGGER.info("Waiting for CSV: {} to be present in InstallPlan, current CSV: {}", csvName, currentCsv);
                    if (currentCsv.contains(csvName)) {
                        return true;
                    }
                }
                return false;
            });
    }
}
