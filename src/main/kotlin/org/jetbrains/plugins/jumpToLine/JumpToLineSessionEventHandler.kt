/*
 * Copyright 2020-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.jumpToLine

import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.impl.ui.ExecutionPointHighlighter

internal class JumpToLineDebuggerManagerListener(
    private val renderer: JumpToLineExecutionLineGutterRenderer,
    private val highlighter: ExecutionPointHighlighter
) : XDebuggerManagerListener {
    override fun currentSessionChanged(previousSession: XDebugSession?, currentSession: XDebugSession?) {
        if (previousSession != null) {
            highlighter.hide()
        }
        val debuggerSession = (currentSession?.debugProcess as? JavaDebugProcess)?.debuggerSession ?: return
        val position = currentSession.currentPosition ?: return
        renderer.update(debuggerSession)
        highlighter.show(position, false, renderer)
    }
}

internal class JumpToLineSessionEventHandler(
    private val debuggerSession: DebuggerSession,
    private val renderer: JumpToLineExecutionLineGutterRenderer,
    private val highlighter: ExecutionPointHighlighter
) : XDebugSessionListener {

    override fun sessionPaused() {
        val position = debuggerSession.xDebugSession?.currentPosition ?: return
        renderer.update(debuggerSession)
        highlighter.show(position, false, renderer)
    }

    override fun sessionResumed() {
        highlighter.hide()
    }

    override fun sessionStopped() {
        highlighter.hide()
    }
}