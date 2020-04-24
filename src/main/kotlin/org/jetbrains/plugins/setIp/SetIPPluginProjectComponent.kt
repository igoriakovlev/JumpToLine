/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.setIp

import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.xdebugger.impl.XDebugSessionImpl

class SetIPStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        //instrument()

        val debuggerListener = object : DebuggerManagerListener {
            override fun sessionAttached(session: DebuggerSession?) {
                 val xSession = session?.xDebugSession as? XDebugSessionImpl ?: return
                val typeResolver = CommonTypeResolver(session.project)
                val sessionHandler = SetIPSessionEvenHandler(session, xSession, typeResolver)
                xSession.addSessionListener(sessionHandler)
            }
        }

        project.messageBus.connect().subscribe(DebuggerManagerListener.TOPIC, debuggerListener)
    }
}