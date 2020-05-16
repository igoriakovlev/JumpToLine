package org.jetbrains.plugins.setIp

import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.messages.MessageDialog
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.xdebugger.ui.DebuggerColors
import com.intellij.xdebugger.ui.DebuggerColors.EXECUTION_LINE_HIGHLIGHTERLAYER
import java.awt.Color
import java.awt.Cursor

internal class SetIPArrowGutter(
        private val project: Project,
        private val commonTypeResolver: CommonTypeResolver,
        private val session: DebuggerSession
): GutterDraggableObject {

    private var highlighters: List<RangeHighlighter>? = null

    private var documentCached: Document? = null
    private val document: Document? get() {
        if (documentCached != null) return documentCached
        documentCached = session.xDebugSession
                ?.currentPosition
                ?.file
                ?.let { PsiManager.getInstance(project).findFile(it) }
                ?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
        return documentCached
    }

    private var markupModelCached: MarkupModel? = null
    private val markupModel: MarkupModel? get() {
        if (markupModelCached != null) return markupModelCached

        markupModelCached = document?.let { DocumentMarkupModel.forDocument(it, project, true) }
        return markupModelCached
    }

    private val currentLine get() = session.xDebugSession?.currentPosition?.line

    override fun copy(line: Int, file: VirtualFile?, actionId: Int): Boolean {

        resetHighlighters()

        if (line == currentLine) return false

        val jumpInfo = currentJumpInfo ?: return false
        val selected = localAnalysisByRenderLine(line) ?: return false

        if (!selected.isSafeLine) {
            val dialog = MessageDialog(project, "This jump is not safe! Continue?", "SetIP", arrayOf("Yes", "No way!"), 1, null, true)
            dialog.show()
            if (dialog.exitCode == 1) return false
        }

        tryJumpToSelectedLine(
                session = session,
                targetLineInfo = selected,
                classFile = jumpInfo.classFile,
                commonTypeResolver = commonTypeResolver
        )

        return true
    }

    companion object {
        private val DefaultCursor = Cursor(Cursor.DEFAULT_CURSOR)
        private val smartStepInto =
                EditorColorsManager.getInstance().globalScheme.getAttributes(DebuggerColors.SMART_STEP_INTO_TARGET)
        private val greenAttribute = smartStepInto.clone().also { it.backgroundColor = Color(227,244, 189) }
        private val yellowAttribute = smartStepInto.clone().also { it.backgroundColor = Color(255,236, 154) }
    }

    private fun resetHighlighters() {
        highlighters?.forEach {
            markupModel?.removeHighlighter(it)
        }
        highlighters = null
    }

    private fun updateHighlighters() {
        if (highlighters != null) return

        val lineCount = document?.lineCount
        val markupModel = markupModel
        if (markupModel != null && lineCount != null) {
            highlighters = currentJumpInfo?.linesToJump?.mapNotNull { line ->
                val lineToSet = line.sourceLine - 1
                if (lineToSet <= lineCount && lineToSet != currentLine) {
                    val attributes = if (line.isSafeLine) greenAttribute else yellowAttribute
                    markupModel.addLineHighlighter(lineToSet, EXECUTION_LINE_HIGHLIGHTERLAYER, attributes)
                } else null
            }
        }
    }

    override fun getCursor(line: Int, actionId: Int): Cursor {
        if (actionId != 2) return DefaultCursor
        updateHighlighters()
        return DefaultCursor
    }

    private fun localAnalysisByRenderLine(line: Int) =
            currentJumpInfo?.linesToJump?.firstOrNull { it.sourceLine == line + 1 }

    fun reset() {
        resetHighlighters()
        currentJumpInfoCached = null
        markupModelCached = null
        documentCached = null
    }

    private var currentJumpInfoCached: GetLinesToJumpResult? = null

    private val currentJumpInfo: JumpLinesInfo? get() {
        synchronized(session) {
            if (currentJumpInfoCached != null) return currentJumpInfoCached as? JumpLinesInfo
            currentJumpInfoCached = tryGetLinesToJump(session)
            return currentJumpInfoCached as? JumpLinesInfo
        }
    }
}