package org.jetbrains.plugins.setIp

import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.GutterDraggableObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.messages.MessageDialog
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.setIp.injectionUtils.LocalVariableAnalyzeResult
import java.awt.Cursor
import java.awt.dnd.DragSource

internal class SetIPArrowGutter(
        private val project: Project,
        private val commonTypeResolver: CommonTypeResolver,
        private val session: DebuggerSession
): GutterDraggableObject {

    override fun copy(line: Int, file: VirtualFile?, actionId: Int): Boolean {

        val classFile = currentJumpInfo?.classFile ?: return false
        val selected = localAnalysisByRenderLine(line) ?: return false

        if (!selected.isSafeLine) {
            val dialog = MessageDialog(project, "This jump is not safe! Continue?", "SetIP", arrayOf("Yes", "No way!"), 1, null, true)
            dialog.show()
            if (dialog.exitCode == 1) return false
        }

        return tryJumpToSelectedLine(selected, classFile)
    }

    companion object {
        private val GoodToMove = DragSource.DefaultMoveDrop
        private val BadToMove = Cursor(Cursor.CROSSHAIR_CURSOR)
        private val NoMove = DragSource.DefaultMoveNoDrop
    }

    override fun getCursor(line: Int, actionId: Int): Cursor {
        if (actionId != 2) return NoMove

        val selected =
                localAnalysisByRenderLine(line) ?: return NoMove

        return if (selected.isSafeLine) GoodToMove else BadToMove
    }

    private fun localAnalysisByRenderLine(line: Int) =
            currentJumpInfo?.linesToJump?.firstOrNull { it.sourceLine == line + 1 }

    private val currentJumpInfo: JumpLinesInfo? by lazy {
        tryGetLinesToJump(session) as? JumpLinesInfo
    }

    private fun tryJumpToSelectedLine(analyzeResult: LocalVariableAnalyzeResult, classFile: ByteArray): Boolean {

        val result = tryJumpToSelectedLine(
                session = session,
                project = project,
                targetLineInfo = analyzeResult,
                classFile = classFile,
                commonTypeResolver = commonTypeResolver
        )

        if (result) {
            session.xDebugSession?.run {
                ApplicationManager.getApplication().invokeLater {
                    session.refresh(true)
                    rebuildViews()
                    showExecutionPoint()
                }
            }
        }

        return result
    }
}