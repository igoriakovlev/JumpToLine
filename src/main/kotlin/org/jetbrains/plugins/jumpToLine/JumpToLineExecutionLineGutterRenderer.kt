/*
 * Copyright 2020-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.jumpToLine

import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.editor.markup.GutterDraggableObject
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.xdebugger.XDebuggerManager
import javax.swing.Icon

internal class JumpToLineExecutionLineGutterRenderer(private val project: Project) : GutterIconRenderer(), GutterMark {

    fun update(session: DebuggerSession) {
        canJump = checkCanJump(session)
        gutter.reset(session)
    }

    override fun hashCode(): Int = 0

    companion object {
        val jumpOkIcon: Icon get() = IconLoader.getIcon("/org/jetbrains/plugins/jumpToLine/nextStatement.svg", this.javaClass)
        val jumpFailIcon: Icon get() = IconLoader.getIcon("/org/jetbrains/plugins/jumpToLine/nextStatementFail.svg", this.javaClass)
    }

    override fun getIcon(): Icon =
        if (canJump.first) jumpOkIcon else jumpFailIcon

    override fun equals(other: Any?): Boolean = false

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun getDraggableObject(): GutterDraggableObject?
        = if (canJump.first && XDebuggerManager.getInstance(project).currentSession?.currentPosition != null) gutter else null

    override fun isNavigateAction(): Boolean = canJump.first

    override fun getTooltipText(): String = canJump.second

    private val gutter = JumpToLineArrowGutter(project)

    private var canJump: Pair<Boolean, String> = false to ""
}

