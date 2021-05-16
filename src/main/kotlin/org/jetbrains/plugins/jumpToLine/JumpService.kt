package org.jetbrains.plugins.jumpToLine

import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.messages.MessageDialog
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.plugins.jumpToLine.fus.FUSLogger
import org.jetbrains.plugins.jumpToLine.injectionUtils.JumpAnalyzeTarget
import org.jetbrains.plugins.jumpToLine.injectionUtils.LineInfo
import org.jetbrains.plugins.jumpToLine.injectionUtils.LineSafetyStatus
import org.jetbrains.plugins.jumpToLine.injectionUtils.runSynchronouslyWithProgress
import java.util.*


internal class JumpService(
        private val session: DebuggerSession,
        private val commonTypeResolver: CommonTypeResolver
) {
    private var loadIsInProgress = false

    private var currentJumpResult: GetLinesToJumpResult? = null

    fun reset() {
        synchronized(session) {
            loadIsInProgress = false
            currentJumpResult = null
        }
    }

    private fun gotoLine(line: LineInfo): Boolean {
        session.project.runSynchronouslyWithProgress("Going to selected line...") { onFinish ->
            tryJumpToByGoto(session, line.javaLine, onFinish)
        }
        return true
    }

    fun tryGotoLine(sourceLine: Int): Boolean {
        val jumpInfo = tryGetJumpInfo() ?: return false

        val gotoLine = jumpInfo.linesToGoto
                .firstOrNull { it.sourceLine == sourceLine }
                ?: return false

        return gotoLine(gotoLine)
    }

    fun tryJumpToLine(sourceLine: Int): Boolean {

        val jumpInfo = tryGetJumpInfo() ?: return false

        val firstLine = jumpInfo.firstLine
        if (firstLine != null && sourceLine == firstLine.sourceLine) {
            return gotoLine(firstLine)
        }

        val analyzeResult = jumpInfo.jumpAnalyzeResult ?: return false

        val targetsByLine = jumpInfo
                .jumpAnalyzeResult
                .jumpAnalyzedTargets
                .filter { it.jumpTargetInfo.lines.any { line -> line.sourceLine == sourceLine } }

        fun JumpAnalyzeTarget.isBetterThan(other: JumpAnalyzeTarget) = when {
            safeStatus < other.safeStatus -> true
            safeStatus == other.safeStatus -> jumpTargetInfo.instructionIndex < other.jumpTargetInfo.instructionIndex
            else -> false
        }

        var targetByLine = targetsByLine.firstOrNull() ?: return false
        for (current in targetsByLine) {
            if (current.isBetterThan(targetByLine)) {
                targetByLine = current
            }
        }

        when (targetByLine.safeStatus) {
            LineSafetyStatus.NotSafe -> {
                val dialog = MessageDialog(session.project, "This jump could be potentially unsafe. Please, consider the risks. Do you want to continue?", "JumpToLine", arrayOf("Yes", "No"), 1, null, true)
                dialog.show()
                if (dialog.exitCode == 1) return true
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

    fun tryGetJumpInfo(): JumpLinesInfo? {
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

    companion object {
        private val servicesCollection = WeakHashMap<DebuggerSession, JumpService>()

        fun getJumpService(session: DebuggerSession): JumpService = synchronized(servicesCollection) {
            servicesCollection.getOrPut(session) {
                JumpService(session, CommonTypeResolver(session.project))
            }
        }
    }
}