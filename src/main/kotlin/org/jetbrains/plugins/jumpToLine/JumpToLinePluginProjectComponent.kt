/*
 * Copyright 2020-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.jumpToLine

import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.xdebugger.impl.XDebugSessionImpl

class JumpToLineStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {

        val debuggerListener = object : DebuggerManagerListener {
            override fun sessionAttached(session: DebuggerSession?) {
                 val xSession = session?.xDebugSession as? XDebugSessionImpl ?: return
                val jumpService = JumpService(session, CommonTypeResolver(session.project))
                val sessionHandler = JumpToLineSessionEvenHandler(session, xSession, jumpService)
                xSession.addSessionListener(sessionHandler)
            }
        }

        project.messageBus.connect().subscribe(DebuggerManagerListener.TOPIC, debuggerListener)
    }
}