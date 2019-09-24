/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.setIp

import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.GutterDraggableObject
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.xdebugger.impl.XDebugSessionImpl
import java.awt.Cursor
import java.awt.dnd.DragSource
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
            if (canJump.first) IconLoader.getIcon("/org/jetbrains/plugins/setIp/nextStatementFail.svg")
            else IconLoader.getIcon("/org/jetbrains/plugins/setIp/nextStatement.svg")

    override fun equals(other: Any?): Boolean = other is SetIPExecutionLineGutterRenderer && frame == other.frame

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun getDraggableObject(): GutterDraggableObject? {

        if (!canJump.first) return null

        return object : GutterDraggableObject {
            override fun copy(line: Int, file: VirtualFile?, actionId: Int): Boolean {
                val extension = file?.extension ?: return false

                if (extension != "java" && extension != "kt") return false

                if (!canSet(line)) return false

                return jumpLines?.let {
                    tryJumpToSelectedLine(line, it)
                } ?: false

            }

            override fun getCursor(line: Int, actionId: Int): Cursor =
                if (canSet(line)) DragSource.DefaultMoveDrop else DragSource.DefaultMoveNoDrop
        }
    }

    override fun isNavigateAction(): Boolean = canJump.first

    override fun getTooltipText(): String? = canJump.second

    private val canJump by lazy {
        checkCanJump(session, xsession)
    }

    private val jumpLines by lazy {

        val jumpLines = tryGetLinesToJump(session, project)
                ?: return@lazy null


//        jumpLines.second?.let {
//            parseKotlinSMAP(it, jumpLines.first, project)
//        }

        jumpLines.first
    }

    private fun tryJumpToSelectedLine(line: Int, lines: Set<Int>): Boolean {

        val result = tryJumpToSelectedLine(
                session = session,
                selectedLine = line + 1,
                availableGotoLines = lines,
                project = project,
                commonTypeResolver = commonTypeResolver
        )

        if (result) {
            session.xDebugSession?.run {
                ApplicationManager.getApplication().invokeLater {
                    session.refresh(true)
                    //rebuildViews()
                    showExecutionPoint()
                }
            }
        }

        return result
    }

    private fun canSet(line: Int): Boolean =
            jumpLines?.contains(line + 1) ?: false
}

