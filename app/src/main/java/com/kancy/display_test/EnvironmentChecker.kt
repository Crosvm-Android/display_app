package com.kancy.display_test

import android.content.Context

/**
 * System environment and permission checker.
 */
class EnvironmentChecker(private val context: Context) {

    data class CheckResult(
        val passed: Boolean,
        val message: String,
        val suggestion: String? = null
    )

    data class EnvironmentReport(
        val rootAccess: CheckResult,
        val hiddenApiPolicy: CheckResult,
        val selinuxStatus: CheckResult,
        val crosvmService: CheckResult,
        val crosvmProcess: CheckResult
    ) {
        fun allPassed(): Boolean = rootAccess.passed &&
                                   hiddenApiPolicy.passed &&
                                   selinuxStatus.passed &&
                                   crosvmService.passed

        fun getFailures(): List<Pair<String, CheckResult>> {
            val failures = mutableListOf<Pair<String, CheckResult>>()
            if (!rootAccess.passed) failures.add("Root Access" to rootAccess)
            if (!hiddenApiPolicy.passed) failures.add("Hidden API Policy" to hiddenApiPolicy)
            if (!selinuxStatus.passed) failures.add("SELinux Status" to selinuxStatus)
            if (!crosvmService.passed) failures.add("Crosvm Service" to crosvmService)
            if (!crosvmProcess.passed) failures.add("Crosvm Process" to crosvmProcess)
            return failures
        }
    }

    fun checkEnvironment(manager: CrosvmDisplayManager): EnvironmentReport {
        return EnvironmentReport(
            rootAccess = checkRootAccess(manager),
            hiddenApiPolicy = checkHiddenApiPolicy(),
            selinuxStatus = checkSelinuxStatus(),
            crosvmService = checkCrosvmService(manager),
            crosvmProcess = checkCrosvmProcess()
        )
    }

    private fun checkRootAccess(manager: CrosvmDisplayManager): CheckResult {
        return try {
            val hasRoot = manager.initRoot()
            if (hasRoot) {
                CheckResult(true, "Root access granted")
            } else {
                CheckResult(
                    false,
                    "Root access denied",
                    "Grant root permission to this app via Magisk/SuperSU"
                )
            }
        } catch (e: Exception) {
            CheckResult(
                false,
                "Root check failed: ${e.message}",
                "Ensure device is rooted and root management app is installed"
            )
        }
    }

    private fun checkHiddenApiPolicy(): CheckResult {
        return try {
            val result = com.topjohnwu.superuser.Shell.cmd(
                "settings get global hidden_api_policy"
            ).exec()

            if (result.isSuccess && result.out.firstOrNull() == "1") {
                CheckResult(true, "Hidden API policy: permissive")
            } else {
                CheckResult(
                    false,
                    "Hidden API policy not set or restrictive",
                    "Run: settings put global hidden_api_policy 1"
                )
            }
        } catch (e: Exception) {
            CheckResult(false, "Cannot check hidden API policy", null)
        }
    }

    private fun checkSelinuxStatus(): CheckResult {
        return try {
            val result = com.topjohnwu.superuser.Shell.cmd("getenforce").exec()
            val status = result.out.firstOrNull() ?: "unknown"

            // Enforcing is acceptable: crosvm from a root shell runs in a permissive su/magisk
            // domain, so the app tries to work without globally disabling SELinux. Either mode
            // passes; if enforcing actually blocks something, "抓 SELinux 拒绝" surfaces it.
            if (status.contains("Permissive", ignoreCase = true)) {
                CheckResult(true, "SELinux: Permissive")
            } else {
                CheckResult(true, "SELinux: $status (trying without global permissive)")
            }
        } catch (e: Exception) {
            CheckResult(false, "Cannot check SELinux status", null)
        }
    }

    private fun checkCrosvmService(manager: CrosvmDisplayManager): CheckResult {
        return try {
            val available = manager.isServiceAvailable()
            if (available) {
                CheckResult(true, "crosvm_display service found")
            } else {
                CheckResult(
                    false,
                    "crosvm_display service not found",
                    "Start crosvm with --android-display-service crosvm_display"
                )
            }
        } catch (e: Exception) {
            CheckResult(false, "Cannot check crosvm service", null)
        }
    }

    private fun checkCrosvmProcess(): CheckResult {
        return try {
            val result = com.topjohnwu.superuser.Shell.cmd("ps | grep -i crosvm").exec()
            if (result.isSuccess && result.out.isNotEmpty()) {
                CheckResult(true, "Crosvm process running (${result.out.size} instance(s))")
            } else {
                CheckResult(
                    false,
                    "No crosvm process found",
                    "Start crosvm before connecting"
                )
            }
        } catch (e: Exception) {
            CheckResult(false, "Cannot check crosvm process", null)
        }
    }
}
