/*
 * Copyright 2020-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.jumpToLine

import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.ui.ExecutionPointHighlighter

class JumpToLineStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val renderer = JumpToLineExecutionLineGutterRenderer(project)

        val projectConnection = project.messageBus.connect()
        val executionPointHighlighter = ExecutionPointHighlighter(project, projectConnection)

        val debuggerListener = object : DebuggerManagerListener {
            override fun sessionAttached(session: DebuggerSession?) {
                if (session == null) return
                val xSession = session.xDebugSession ?: return
                if (xSession.debugProcess !is JavaDebugProcess) return

                val sessionHandler = JumpToLineSessionEventHandler(session, renderer, executionPointHighlighter)
                xSession.addSessionListener(sessionHandler)
            }
        }

        projectConnection.subscribe(DebuggerManagerListener.TOPIC, debuggerListener)

        val debuggerManagerListener = JumpToLineDebuggerManagerListener(renderer, executionPointHighlighter)
        projectConnection.subscribe(XDebuggerManager.TOPIC, debuggerManagerListener)
    }
}