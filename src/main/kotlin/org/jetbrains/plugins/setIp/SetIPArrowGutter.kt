package org.jetbrains.plugins.setIp

import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.GutterDraggableObject
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.messages.MessageDialog
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.xdebugger.ui.DebuggerColors
import com.intellij.xdebugger.ui.DebuggerColors.EXECUTION_LINE_HIGHLIGHTERLAYER
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.event.MouseListener

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
        private val greenColor = if (EditorColorsManager.getInstance().isDarkEditor) Color(30,40, 0) else Color(199,225, 94)
        private val yellowColor = if (EditorColorsManager.getInstance().isDarkEditor) Color(50,40, 0) else Color(248,253, 185)
        private val safeLineAttribute = smartStepInto.clone().also { it.backgroundColor = greenColor }
        private val unsageLineAttribute = smartStepInto.clone().also { it.backgroundColor = yellowColor }
    }

    private fun resetHighlighters() {

        remove()

        val currentHighlighters = highlighters ?: return
        highlighters = null

        currentHighlighters.forEach {
            markupModel?.removeHighlighter(it)
        }
    }

    private fun updateHighlighters() {
        if (highlighters != null) return

        val lineCount = document?.lineCount
        val markupModel = markupModel
        if (markupModel != null && lineCount != null) {
            highlighters = currentJumpInfo?.linesToJump?.mapNotNull { line ->
                val lineToSet = line.sourceLine - 1
                if (lineToSet <= lineCount && lineToSet != currentLine) {
                    val attributes = if (line.isSafeLine) safeLineAttribute else unsageLineAttribute
                    markupModel.addLineHighlighter(lineToSet, EXECUTION_LINE_HIGHLIGHTERLAYER, attributes)
                } else null
            }
            reSetterByMouseLeave.install()
        }
    }

    private inline fun <reified T> Any?.castSafelyTo(): T? = this as? T

    private val reSetterByMouseLeave = object : MouseListener {

        private var installedGutterComponent: EditorGutterComponentEx? = null

        fun install() {

            val editor = session.xDebugSession
                    ?.currentPosition
                    ?.file
                    ?.let { FileEditorManager.getInstance(project).getSelectedEditor(it) }

            installedGutterComponent =
                    editor.castSafelyTo<TextEditor>()
                            ?.editor.castSafelyTo<EditorEx>()
                            ?.gutterComponentEx

            installedGutterComponent?.addMouseListener(this)
        }

        fun remove() {
            installedGutterComponent = null
            installedGutterComponent?.removeMouseListener(this)
        }

        override fun mouseReleased(e: MouseEvent?) {}

        override fun mouseEntered(e: MouseEvent?) {}

        override fun mouseClicked(e: MouseEvent?) {}

        override fun mousePressed(e: MouseEvent?) {}

        override fun mouseExited(e: MouseEvent?) {
            resetHighlighters()
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
        reSetterByMouseLeave.remove()
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