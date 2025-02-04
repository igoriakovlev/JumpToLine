package org.jetbrains.plugins.jumpToLine

import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.DebuggerSupport
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.XDebuggerUtilImpl
import com.intellij.xdebugger.impl.actions.DebuggerActionHandler
import com.intellij.xdebugger.impl.actions.XDebuggerActionBase
import com.intellij.xdebugger.impl.actions.XDebuggerSuspendedActionHandler

private class JumpToStatementHandler : XDebuggerSuspendedActionHandler() {

    override fun isEnabled(session: XDebugSession, dataContext: DataContext): Boolean {
        if (!isEnabled(session) || !super.isEnabled(session, dataContext))
            return false

        val xDebugSession = session as? XDebugSessionImpl ?: return false
        if (xDebugSession.debugProcess !is JavaDebugProcess) return false

        val position = XDebuggerUtilImpl.getCaretPosition(session.project, dataContext)

        val currentLine = session.currentPosition?.line ?: return false
        val positionLine = position?.line ?: return false

        if (currentLine == positionLine) return false

        return true
    }

    override fun perform(session: XDebugSession, dataContext: DataContext) {
        fun balloonError(errorText: String) {
            ToolWindowManager.getInstance(session.project).notifyByBalloon(
                ToolWindowId.DEBUG,
                MessageType.ERROR,
                errorText
            )
        }
        fun balloonDefaultError() = balloonError("Failed to skip code.")

        val debugProcess = session.debugProcess as? JavaDebugProcess ?: return balloonDefaultError()

        val position = XDebuggerUtilImpl.getCaretPosition(session.project, dataContext)

        val currentLine = session.currentPosition?.line ?: return balloonDefaultError()
        val positionLine = position?.line ?: return balloonDefaultError()

        if (currentLine == positionLine) return balloonDefaultError()

        val (canJump, error) = checkCanJump(debugProcess.debuggerSession)
        if (!canJump) return balloonError(error)

        val jumpService = JumpService.getJumpService(session.project)
        val sourceLine = position.line + 1
        if (!jumpService.tryJumpToLine(sourceLine, debugProcess.debuggerSession)) return balloonDefaultError()
    }
}

class JumpToStatementAction : XDebuggerActionBase(true) {
    override fun isEnabled(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return XDebuggerManager.getInstance(project).currentSession?.isSuspended ?: false
    }

    private val handler = JumpToStatementHandler()

    override fun getHandler(debuggerSupport: DebuggerSupport): DebuggerActionHandler = handler
}