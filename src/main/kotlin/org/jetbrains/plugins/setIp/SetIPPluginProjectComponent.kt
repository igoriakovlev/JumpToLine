/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.setIp

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.impl.XDebugSessionImpl

class SetIPPluginProjectComponent(private val project: Project) : ProjectComponent {

    override fun projectOpened() {
        val debuggerListener = object : DebuggerManagerListener {
            override fun sessionAttached(session: DebuggerSession?) {
                val xSession = session?.xDebugSession as? XDebugSessionImpl ?: return
                val typeResolver = CommonTypeResolver(project)
                val sessionHandler = SetIPSessionEvenHandler(session, xSession, project, typeResolver)
                xSession.addSessionListener(sessionHandler)
            }
        }

        (DebuggerManagerEx.getInstance(project) as? DebuggerManagerEx)
                ?.addDebuggerManagerListener(debuggerListener)
    }
}