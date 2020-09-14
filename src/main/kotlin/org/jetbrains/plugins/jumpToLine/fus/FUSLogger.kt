package org.jetbrains.plugins.jumpToLine.fus

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.internal.statistic.eventLog.FeatureUsageData
import com.intellij.internal.statistic.service.fus.collectors.FUCounterUsageLogger

class FUSLogger {

    enum class JumpToLineEvent {
        GetLinesToJump,
        JumpToGreenLine,
        JumpToYellowLine,
        GoToLine,
    }

    enum class JumpToLineStatus {
        Success,
        Failed,
    }

    companion object {

        private val PLUGIN_VERSION: String by lazy {
            PluginManagerCore.getPlugins().firstOrNull { it.pluginId.idString == "org.jetbrains.jumpToLine" }?.version ?: "Unknown"
        }

        private val successContext = FeatureUsageData()
                .addData("plugin_version", PLUGIN_VERSION)
                .addData("status", "success")

        private val failedContext = FeatureUsageData()
                .addData("plugin_version", PLUGIN_VERSION)
                .addData("status", "failed")

        private val logger = FUCounterUsageLogger.getInstance()

        fun log(event: JumpToLineEvent, status: JumpToLineStatus) {
            logger.logEvent(
                    "ide.jumpToLine",
                    event.name,
                    if (status == JumpToLineStatus.Success) successContext else failedContext
            )
        }
    }
}