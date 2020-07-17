package org.jetbrains.plugins.setIp

import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.application.ApplicationManager
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
import org.jetbrains.plugins.setIp.injectionUtils.onTrue
import org.w3c.dom.ranges.Range
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.awt.event.MouseListener

internal class SetIPArrowGutter(
        private val project: Project,
        private val commonTypeResolver: CommonTypeResolver,
        private val session: DebuggerSession
): GutterDraggableObject {

    private var highlighters: List<RangeHighlighter>? = null
    private var highlightersIsForGoto: Boolean = false

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

        reSetterByMouseLeave.remove()
        resetHighlighters()

        if (line == currentLine) return false

        val jumpInfo = currentJumpInfo ?: return false

        if (actionId == 1) {
            val gotoLine = jumpInfo.linesToGoto.firstOrNull { it.sourceLine - 1 == line } ?: return false
            tryJumpToByGoto(session, gotoLine.javaLine)
            return false
        }

        val firstLine = jumpInfo.firstLine
        if (firstLine != null && line == firstLine.sourceLine - 1) {
            tryJumpToByGoto(session, firstLine.javaLine)
            return false
        }

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
        private val WaitCursor = Cursor(Cursor.WAIT_CURSOR)
        private val smartStepInto =
                EditorColorsManager.getInstance().globalScheme.getAttributes(DebuggerColors.SMART_STEP_INTO_TARGET)
        private val greenColor = if (EditorColorsManager.getInstance().isDarkEditor) Color(30,40, 0) else Color(199,225, 94)
        private val yellowColor = if (EditorColorsManager.getInstance().isDarkEditor) Color(50,40, 0) else Color(248,253, 185)
        private val safeLineAttribute = smartStepInto.clone().also { it.backgroundColor = greenColor }
        private val unsageLineAttribute = smartStepInto.clone().also { it.backgroundColor = yellowColor }
    }

    private fun resetHighlighters() {

        val currentHighlighters = highlighters ?: return
        highlighters = null

        val application = ApplicationManager.getApplication()
        application.invokeLater {
            application.runWriteAction {
                currentHighlighters.forEach {
                    markupModel?.removeHighlighter(it)
                }
            }
        }
    }

    private fun updateHighlighters(highlightGoTo: Boolean) {
        if (highlighters != null && highlightGoTo == highlightersIsForGoto) return

        highlightersIsForGoto = highlightGoTo
        resetHighlighters()

        val lineCount = document?.lineCount
        val markupModel = markupModel
        if (markupModel != null && lineCount != null) {
            if (!highlightersIsForGoto) {

                val linesToJump = currentJumpInfo?.linesToJump
                val firstLine = currentJumpInfo?.firstLine

                if ((linesToJump?.any() == true) || firstLine != null) {

                    val jumpHighlighters = mutableListOf<RangeHighlighter>()

                    linesToJump?.forEach { lineToJump ->
                        val lineToSet = lineToJump.sourceLine - 1
                        (lineToSet <= lineCount && lineToSet != currentLine).onTrue {
                            val attributes = if (lineToJump.isSafeLine) safeLineAttribute else unsageLineAttribute
                            val highlighter = markupModel.addLineHighlighter(lineToSet, EXECUTION_LINE_HIGHLIGHTERLAYER, attributes)
                            jumpHighlighters.add(highlighter)
                        }
                    }

                    if (firstLine != null) {
                        val firstLineHighlighter = markupModel.addLineHighlighter(firstLine.sourceLine - 1, EXECUTION_LINE_HIGHLIGHTERLAYER, safeLineAttribute)
                        jumpHighlighters.add(firstLineHighlighter)
                    }

                    highlighters = jumpHighlighters
                }

            } else {
                highlighters = currentJumpInfo?.linesToGoto?.mapNotNull { line ->
                    val lineToSet = line.sourceLine - 1
                    if (lineToSet <= lineCount && lineToSet != currentLine) {
                        markupModel.addLineHighlighter(lineToSet, EXECUTION_LINE_HIGHLIGHTERLAYER, safeLineAttribute)
                    } else null
                }
            }
            reSetterByMouseLeave.install()
        }
    }

    private inline fun <reified T> Any?.castSafelyTo(): T? = this as? T

    private val reSetterByMouseLeave = object : MouseListener {

        private var installedGutterComponent: EditorGutterComponentEx? = null

        fun install() {

            if (installedGutterComponent?.mouseListeners?.contains(this) == true) return

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

        override fun mouseExited(e: MouseEvent?) {
            resetHighlighters()
        }

        override fun mouseClicked(e: MouseEvent?) {}
        override fun mousePressed(e: MouseEvent?) {}
    }

    override fun getCursor(line: Int, actionId: Int): Cursor {
        if (actionId != 1 && actionId != 2) return DefaultCursor
        updateHighlighters(actionId == 1)
        return if (highlighters == null) WaitCursor else DefaultCursor
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