/*
 * Copyright 2020-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.jumpToLine

import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.ui.DebuggerColors
import com.intellij.xdebugger.ui.DebuggerColors.EXECUTION_LINE_HIGHLIGHTERLAYER
import org.jetbrains.plugins.jumpToLine.injectionUtils.LineInfo
import org.jetbrains.plugins.jumpToLine.injectionUtils.LineSafetyStatus
import org.jetbrains.plugins.jumpToLine.injectionUtils.onTrue
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.awt.event.MouseListener

internal class JumpToLineArrowGutter(private val project: Project): GutterDraggableObject {

    private val sync = Any()
    private var highlighters: List<Pair<MarkupModel, RangeHighlighter>>? = null
    private var highlightersIsForGoto: Boolean = false

    private val markupModel: MarkupModel? get() =
        currentPosition
            ?.let { PsiManager.getInstance(project).findFile(it.file) }
            ?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
            ?.let { DocumentMarkupModel.forDocument(it, project, true) }

    private val currentPosition get() =
        XDebuggerManager.getInstance(project).currentSession?.currentPosition

    override fun copy(line: Int, file: VirtualFile?, actionId: Int): Boolean {
        reSetterByMouseLeave.remove()
        resetHighlighters()

        val currentSession = XDebuggerManager.getInstance(project).currentSession ?: return false
        val currentLine = currentSession.currentPosition?.line ?: return false
        if (line == currentLine) return false

        val sourceLine = line + 1

        val debugSession = (currentSession.debugProcess as? JavaDebugProcess)?.debuggerSession ?: return false
        val jumpService = JumpService.getJumpService(project)

        return if (actionId == 1) !jumpService.tryGotoLine(sourceLine, debugSession)
        else !jumpService.tryJumpToLine(sourceLine, debugSession)
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
        val currentHighlighters = synchronized(sync) {
            highlighters?.also {
                highlighters = null
            }
        } ?: return

        ApplicationManager.getApplication().invokeLater {
            currentHighlighters.forEach {
                it.first.removeHighlighter(it.second)
            }
        }
    }

    private fun MarkupModel.isValidLine(line: LineInfo) =
        line.documentLine.let { it >= 0 && it < document.lineCount && it != currentPosition?.line }

    private val LineInfo.documentLine get() = sourceLine - 1

    private fun updateHighlightersForJump(
        currentJumpInfo: JumpLinesInfo,
        markupModel: MarkupModel
    ): List<Pair<MarkupModel, RangeHighlighter>>? {

        val jumpAnalyzeInfo = currentJumpInfo.jumpAnalyzeResult
        val firstLine = currentJumpInfo.firstLine
        if (jumpAnalyzeInfo == null && firstLine == null) return null

        val jumpHighlighters = mutableListOf<Pair<MarkupModel, RangeHighlighter>>()
        var yellowLineAdded = false

        if (jumpAnalyzeInfo != null) {
            val lineToBestStatus = mutableMapOf<Int, LineSafetyStatus>()

            for (currentInfo in jumpAnalyzeInfo.jumpAnalyzedTargets) {
                for (currentSourceLine in currentInfo.jumpTargetInfo.lines) {

                    if (firstLine != null && firstLine.sourceLine == currentSourceLine.sourceLine) continue
                    if (!markupModel.isValidLine(currentSourceLine)) continue

                    val currentBestStatus = lineToBestStatus[currentSourceLine.documentLine]
                    if (currentBestStatus == null || currentBestStatus > currentInfo.safeStatus) {
                        lineToBestStatus[currentSourceLine.documentLine] = currentInfo.safeStatus
                    }
                }
            }

            for (currentLineToStatus in lineToBestStatus) {
                val (lineToSet, currentSafeStatus) = currentLineToStatus

                val attributes = if (currentSafeStatus != LineSafetyStatus.NotSafe) safeLineAttribute else unsafeLineAttribute
                yellowLineAdded = yellowLineAdded || currentSafeStatus == LineSafetyStatus.NotSafe

                val highlighter = markupModel.addLineHighlighter(lineToSet, EXECUTION_LINE_HIGHLIGHTERLAYER, attributes)
                jumpHighlighters.add(markupModel to highlighter)
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
            val firstLineHighlighter = markupModel.addLineHighlighter(firstLine.documentLine, EXECUTION_LINE_HIGHLIGHTERLAYER, safeLineAttribute)
            jumpHighlighters.add(markupModel to firstLineHighlighter)
        }

        return jumpHighlighters
    }

    private fun updateHighlightersForGoTo(
        currentJumpInfo: JumpLinesInfo,
        markupModel: MarkupModel
    ): List<Pair<MarkupModel, RangeHighlighter>> = currentJumpInfo.linesToGoto.mapNotNull { line ->
            markupModel.isValidLine(line).onTrue {
                markupModel to markupModel.addLineHighlighter(line.documentLine, EXECUTION_LINE_HIGHLIGHTERLAYER, safeLineAttribute)
            }
        }

    private fun updateHighlighters(highlightGoTo: Boolean) {
        if (highlighters != null && highlightGoTo == highlightersIsForGoto) return
        
        highlightersIsForGoto = highlightGoTo
        resetHighlighters()

        val markupModel = markupModel ?: return

        val session = (XDebuggerManager.getInstance(project).currentSession?.debugProcess as? JavaDebugProcess)
            ?.debuggerSession ?: return

        val currentJumpInfo = JumpService.getJumpService(project).tryGetJumpInfo(session) ?: return

        synchronized(sync) {
            if (highlighters != null) return
            highlighters = if (highlightersIsForGoto) updateHighlightersForGoTo(currentJumpInfo, markupModel)
            else updateHighlightersForJump(currentJumpInfo, markupModel)

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


            val editor = XDebuggerManager.getInstance(project)
                .currentSession
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

    fun reset(session: DebuggerSession) {
        synchronized(sync) {
            resetHighlighters()
            JumpService.getJumpService(project).reset(session)
            reSetterByMouseLeave.remove()
        }
    }
}