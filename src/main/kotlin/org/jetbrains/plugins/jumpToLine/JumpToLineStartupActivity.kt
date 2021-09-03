/*
 * Copyright 2020-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.jumpToLine

import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.ui.ExecutionPointHighlighter

class JumpToLineStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        val renderer = JumpToLineExecutionLineGutterRenderer(project)
        val executionPointHighlighter = ExecutionPointHighlighter(project)

        val debuggerListener = object : DebuggerManagerListener {
            override fun sessionAttached(session: DebuggerSession?) {
                if (session == null) return
                val xSession = session.xDebugSession ?: return
                if (xSession.debugProcess !is JavaDebugProcess) return

                val sessionHandler = JumpToLineSessionEventHandler(session, renderer, executionPointHighlighter)
                xSession.addSessionListener(sessionHandler)
            }
        }
        project.messageBus.connect().subscribe(DebuggerManagerListener.TOPIC, debuggerListener)

        val debuggerManagerListener = JumpToLineDebuggerManagerListener(renderer, executionPointHighlighter)
        project.messageBus.connect().subscribe(XDebuggerManager.TOPIC, debuggerManagerListener)
    }
}