/*
 * Copyright 2020-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.jumpToLine

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
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.xdebugger.ui.DebuggerColors
import com.intellij.xdebugger.ui.DebuggerColors.EXECUTION_LINE_HIGHLIGHTERLAYER
import org.jetbrains.plugins.jumpToLine.injectionUtils.LineSafetyStatus
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.awt.event.MouseListener

internal class JumpToLineArrowGutter(
        private val project: Project,
        private val session: DebuggerSession,
        private val jumpService: JumpService
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

        return if (actionId == 1) !jumpService.tryGotoLine(line)
        else !jumpService.tryJumpToLine(line)
    }

    companion object {
        private val DefaultCursor = Cursor(Cursor.DEFAULT_CURSOR)
        private val WaitCursor = Cursor(Cursor.WAIT_CURSOR)
        private val smartStepInto =
                EditorColorsManager.getInstance().globalScheme.getAttributes(DebuggerColors.SMART_STEP_INTO_TARGET)
        private val greenColor = JBColor.namedColor(
            "JumpToLine.dropLine.safe",
            JBColor(Color(199, 225, 94), Color(30, 40, 0))
        )
        private val yellowColor = JBColor.namedColor(
            "JumpToLine.dropLine.unsafe",
            JBColor(Color(248, 253, 185), Color(50, 40, 0))
        )
        private val safeLineAttribute = smartStepInto.clone().also { it.backgroundColor = greenColor }
        private val unsafeLineAttribute = smartStepInto.clone().also { it.backgroundColor = yellowColor }
    }

    private fun resetHighlighters() {

        if (highlighters == null) return

        synchronized(session) {
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
    }

    private fun updateHighlightersForJump(currentJumpInfo: JumpLinesInfo,
                                          markupModel: MarkupModel,
                                          lineCount: Int): List<RangeHighlighter>? {

        val jumpAnalyzeInfo = currentJumpInfo.jumpAnalyzeResult
        val firstLine = currentJumpInfo.firstLine
        if (jumpAnalyzeInfo == null && firstLine == null) return null

        val jumpHighlighters = mutableListOf<RangeHighlighter>()
        var yellowLineAdded = false

        if (jumpAnalyzeInfo != null) {
            val lineToBestStatus = mutableMapOf<Int, LineSafetyStatus>()

            for (currentInfo in jumpAnalyzeInfo.jumpAnalyzedTargets) {
                for (currentSourceLine in currentInfo.jumpTargetInfo.lines) {
                    val lineToSet = currentSourceLine.sourceLine - 1
                    if (lineToSet > lineCount || lineToSet == currentLine) continue

                    val currentBestStatus = lineToBestStatus[lineToSet]
                    if (currentBestStatus == null || currentBestStatus > currentInfo.safeStatus) {
                        lineToBestStatus[lineToSet] = currentInfo.safeStatus
                    }
                }
            }


            for (currentLineToStatus in lineToBestStatus) {
                val (lineToSet, currentSafeStatus) = currentLineToStatus

                val attributes = if (currentSafeStatus != LineSafetyStatus.NotSafe) safeLineAttribute else unsafeLineAttribute
                yellowLineAdded = yellowLineAdded || currentSafeStatus == LineSafetyStatus.NotSafe

                val highlighter = markupModel.addLineHighlighter(lineToSet, EXECUTION_LINE_HIGHLIGHTERLAYER, attributes)
                jumpHighlighters.add(highlighter)
            }
        }


        if (jumpHighlighters.size == 0 || yellowLineAdded) {
            ToolWindowManager.getInstance(project).notifyByBalloon(
                    ToolWindowId.DEBUG,
                    MessageType.INFO,
                    "Press and hold Ctrl/⌘ to enter into the Run to Cursor mode."
            )
        }

        if (firstLine != null) {
            val firstLineHighlighter = markupModel.addLineHighlighter(firstLine.sourceLine - 1, EXECUTION_LINE_HIGHLIGHTERLAYER, safeLineAttribute)
            jumpHighlighters.add(firstLineHighlighter)
        }

        return jumpHighlighters
    }

    private fun updateHighlightersForGoTo(currentJumpInfo: JumpLinesInfo,
                                          markupModel: MarkupModel,
                                          lineCount: Int): List<RangeHighlighter>? {
        return currentJumpInfo.linesToGoto.mapNotNull { line ->
            val lineToSet = line.sourceLine - 1
            if (lineToSet <= lineCount && lineToSet != currentLine) {
                markupModel.addLineHighlighter(lineToSet, EXECUTION_LINE_HIGHLIGHTERLAYER, safeLineAttribute)
            } else null
        }
    }

    private fun updateHighlighters(highlightGoTo: Boolean) {
        if (highlighters != null && highlightGoTo == highlightersIsForGoto) return
        
        highlightersIsForGoto = highlightGoTo
        resetHighlighters()

        val lineCount = document?.lineCount ?: return
        val markupModel = markupModel ?: return

        val currentJumpInfo = jumpService.tryGetJumpInfo() ?: return

        synchronized(session) {
            if (highlighters != null) return
            highlighters = if (highlightersIsForGoto) updateHighlightersForGoTo(currentJumpInfo, markupModel, lineCount)
            else updateHighlightersForJump(currentJumpInfo, markupModel, lineCount)

            if (highlighters != null) {
                reSetterByMouseLeave.install()
            }
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
            installedGutterComponent?.removeMouseListener(this)
            installedGutterComponent = null
        }

        override fun mouseReleased(e: MouseEvent?) {}
        override fun mouseEntered(e: MouseEvent?) {}

        override fun mouseExited(e: MouseEvent?) {
            resetHighlighters()
            remove()
        }

        override fun mouseClicked(e: MouseEvent?) {}
        override fun mousePressed(e: MouseEvent?) {}
    }

    override fun getCursor(line: Int, actionId: Int): Cursor {
        if (actionId != 1 && actionId != 2) return DefaultCursor
        updateHighlighters(actionId == 1)
        return if (highlighters == null) WaitCursor else DefaultCursor
    }

    fun reset() {
        resetHighlighters()
        jumpService.reset()
        markupModelCached = null
        documentCached = null
        reSetterByMouseLeave.remove()
    }

}