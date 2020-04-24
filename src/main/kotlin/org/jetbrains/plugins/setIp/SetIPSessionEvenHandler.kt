/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.setIp

import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.ui.ExecutionPointHighlighter
import org.jetbrains.plugins.setIp.SetIPExecutionLineGutterRenderer

internal class SetIPSessionEvenHandler(
    private val session: DebuggerSession,
    private val xsession: XDebugSessionImpl,
    private val commonTypeResolver: CommonTypeResolver
) : XDebugSessionListener {

    private val project = session.project

    private val executionPointHighlighter = ExecutionPointHighlighter(project)
    override fun sessionPaused() {
        val currentPosition = xsession.currentPosition ?: return
        executionPointHighlighter.show(
            currentPosition,
            xsession.isTopFrameSelected,
            SetIPExecutionLineGutterRenderer(session, xsession, project, commonTypeResolver)
        )
        xsession.updateExecutionPosition()
    }

    override fun sessionResumed() {
        executionPointHighlighter.hide()
    }

    override fun sessionStopped() {
        executionPointHighlighter.hide()
    }
}