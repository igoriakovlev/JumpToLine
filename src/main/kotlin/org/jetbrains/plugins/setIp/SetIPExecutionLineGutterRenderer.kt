/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.setIp

import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.editor.markup.GutterDraggableObject
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.xdebugger.impl.XDebugSessionImpl
import javax.swing.Icon

internal class SetIPExecutionLineGutterRenderer(
    private val session: DebuggerSession,
    private val xsession: XDebugSessionImpl,
    private val project: Project,
    private val commonTypeResolver: CommonTypeResolver
) : GutterIconRenderer(), GutterMark {

    private val process = session.process
    private val context = process.debuggerContext
    private val threadProxy = context.threadProxy
    private val frame = context.frameProxy

    override fun hashCode(): Int = threadProxy.hashCode()

    override fun getIcon(): Icon =
            if (canJump.first) IconLoader.getIcon("/org/jetbrains/plugins/setIp/nextStatement.svg")
            else IconLoader.getIcon("/org/jetbrains/plugins/setIp/nextStatementFail.svg")

    override fun equals(other: Any?): Boolean = other is SetIPExecutionLineGutterRenderer && frame == other.frame

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun getDraggableObject(): GutterDraggableObject?
        = if (canJump.first) SetIPArrowGutter(project, commonTypeResolver, session) else null

    override fun isNavigateAction(): Boolean = canJump.first

    override fun getTooltipText(): String? = canJump.second

    private val canJump by lazy {
        checkCanJump(session, xsession)
    }
}

