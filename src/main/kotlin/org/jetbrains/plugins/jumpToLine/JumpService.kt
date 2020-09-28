package org.jetbrains.plugins.jumpToLine

import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.messages.MessageDialog
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.plugins.jumpToLine.fus.FUSLogger
import org.jetbrains.plugins.jumpToLine.injectionUtils.LineSafetyStatus
import org.jetbrains.plugins.jumpToLine.injectionUtils.runSynchronouslyWithProgress


internal class JumpService(
        private val session: DebuggerSession,
        private val commonTypeResolver: CommonTypeResolver
) {
    var loadIsInProgress = false
        private set

    private var currentJumpResult: GetLinesToJumpResult? = null

    fun reset() {
        synchronized(session) {
            loadIsInProgress = false
            currentJumpResult = null
        }
    }

    fun gotoJavaLine(javaLine: Int) {
        session.project.runSynchronouslyWithProgress("Going to selected line...") { onFinish ->
            tryJumpToByGoto(session, javaLine, onFinish)
        }
    }

    fun tryJumpToLineImmediately(jumpToLine: Int): Boolean {

        val jumpInfo = tryGetJumpInfoImmediately() ?: return false
        val analyzeResult = jumpInfo.jumpAnalyzeResult ?: return false

        val targetByLine = jumpInfo
                .jumpAnalyzeResult
                .jumpAnalyzedTargets
                .firstOrNull { target ->
                    target.jumpTargetInfo.lines.any {
                        line -> line.sourceLine == jumpToLine + 1
                    }
                }
                ?: return false

        when (targetByLine.safeStatus) {
            LineSafetyStatus.NotSafe -> {
                val dialog = MessageDialog(session.project, "This jump could be potentially unsafe. Please, consider the risks. Do you want to continue?", "JumpToLine", arrayOf("Yes", "No"), 1, null, true)
                dialog.show()
                if (dialog.exitCode == 1) return false
            }
            LineSafetyStatus.UninitializedExist -> {
                ToolWindowManager.getInstance(session.project).notifyByBalloon(
                        ToolWindowId.DEBUG,
                        MessageType.WARNING,
                        "Some local variables were initialized to default values."
                )
            }
            else -> {}
        }

        session.project.runSynchronouslyWithProgress("Jumping to the selected line...") { onFinish ->
            tryJumpToSelectedLine(
                    session = session,
                    jumpAnalyzeTarget = targetByLine,
                    jumpAnalyzeAdditionalInfo = analyzeResult.jumpAnalyzeAdditionalInfo,
                    classFile = jumpInfo.classFile,
                    commonTypeResolver = commonTypeResolver,
                    onFinish = onFinish
            )
        }

        return true
    }

    fun tryGetJumpInfoImmediately(): JumpLinesInfo? {
        if (loadIsInProgress) return null
        if (currentJumpResult != null) return currentJumpResult as? JumpLinesInfo

        synchronized(session) {
            if (loadIsInProgress) return null
            if (currentJumpResult != null) return currentJumpResult as? JumpLinesInfo
            loadIsInProgress = true
        }

        session.project.runSynchronouslyWithProgress("Analyzing the lines...") { onFinish ->
            val myOnFinish: (GetLinesToJumpResult?) -> Unit = { result ->
                synchronized(session) {
                    currentJumpResult = result ?: UnknownErrorResult
                    loadIsInProgress = false
                    FUSLogger.log(
                            event = FUSLogger.JumpToLineEvent.GetLinesToJump,
                            status = if (result != null) FUSLogger.JumpToLineStatus.Success else FUSLogger.JumpToLineStatus.Success
                    )
                }
                onFinish()
            }
            tryGetLinesToJump(session, myOnFinish)
        }
        return currentJumpResult
    }
}