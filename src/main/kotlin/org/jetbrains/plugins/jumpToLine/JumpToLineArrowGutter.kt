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
import com.intellij.openapi.ui.messages.MessageDialog
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.xdebugger.ui.DebuggerColors
import com.intellij.xdebugger.ui.DebuggerColors.EXECUTION_LINE_HIGHLIGHTERLAYER
import org.jetbrains.plugins.jumpToLine.fus.FUSLogger
import org.jetbrains.plugins.jumpToLine.fus.FUSLogger.JumpToLineStatus
import org.jetbrains.plugins.jumpToLine.injectionUtils.*
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.awt.event.MouseListener

internal class JumpToLineArrowGutter(
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

        val jumpInfo = currentJumpResult as? JumpLinesInfo ?: return false

        val goingToSelectedLine = "Going to selected line..."

        if (actionId == 1) {
            val gotoLine = jumpInfo.linesToGoto.firstOrNull { it.sourceLine - 1 == line } ?: return false
            project.runSynchronouslyWithProgress(goingToSelectedLine) { onFinish ->
                tryJumpToByGoto(session, gotoLine.javaLine, onFinish)
            }
            return false
        }

        val firstLine = jumpInfo.firstLine
        if (firstLine != null && line == firstLine.sourceLine - 1) {
            project.runSynchronouslyWithProgress(goingToSelectedLine) { onFinish ->
                tryJumpToByGoto(session, firstLine.javaLine, onFinish)
            }
            return false
        }

        val selected = localAnalysisByRenderLine(line) ?: return false

        when (selected.first.safeStatus) {
            LineSafetyStatus.NotSafe -> {
                val dialog = MessageDialog(project, "This jump could be potentially unsafe. Please, consider the risks. Do you want to continue?", "JumpToLine", arrayOf("Yes", "No"), 1, null, true)
                dialog.show()
                if (dialog.exitCode == 1) return false
            }
            LineSafetyStatus.UninitializedExist -> {
                ToolWindowManager.getInstance(project).notifyByBalloon(
                        ToolWindowId.DEBUG,
                        MessageType.WARNING,
                        "Some local variables were initialized to default values."
                )
            }
            else -> {}
        }

        project.runSynchronouslyWithProgress("Jumping to the selected line...") { onFinish ->
            tryJumpToSelectedLine(
                    session = session,
                    jumpAnalyzeTarget = selected.first,
                    jumpAnalyzeAdditionalInfo = selected.second,
                    classFile = jumpInfo.classFile,
                    commonTypeResolver = commonTypeResolver,
                    onFinish = onFinish
            )
        }

        return true
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
        jumpAnalyzeInfo?.jumpAnalyzedTargets?.forEach { info ->
            info.jumpTargetInfo.lines.forEach { line ->
                val lineToSet = line.sourceLine - 1
                (lineToSet <= lineCount && lineToSet != currentLine).onTrue {
                    val attributes = if (info.safeStatus != LineSafetyStatus.NotSafe) safeLineAttribute else unsafeLineAttribute
                    yellowLineAdded = yellowLineAdded || info.safeStatus == LineSafetyStatus.NotSafe

                    val highlighter = markupModel.addLineHighlighter(lineToSet, EXECUTION_LINE_HIGHLIGHTERLAYER, attributes)
                    jumpHighlighters.add(highlighter)
                }
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
        if (inProgress) return

        highlightersIsForGoto = highlightGoTo
        resetHighlighters()

        val lineCount = document?.lineCount ?: return
        val markupModel = markupModel ?: return

        val currentJumpResult = currentJumpResult
        if (currentJumpResult == null) {
            loadJumpResult()
            return
        }

        val currentJumpInfo = currentJumpResult as? JumpLinesInfo ?: return

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

    private fun localAnalysisByRenderLine(requestedLine: Int): Pair<JumpAnalyzeTarget, JumpAnalyzeAdditionalInfo>? {
        val info = currentJumpResult as? JumpLinesInfo ?: return null
        val analyzeResult = info.jumpAnalyzeResult ?: return null

        val targetByLine = analyzeResult
                .jumpAnalyzedTargets
                .firstOrNull { target ->
                    target.jumpTargetInfo.lines.any {
                        line -> line.sourceLine == requestedLine + 1
                    }
                }
                ?: return null

        return targetByLine to analyzeResult.jumpAnalyzeAdditionalInfo
    }

    fun reset() {
        resetHighlighters()
        currentJumpResult = null
        markupModelCached = null
        documentCached = null
        reSetterByMouseLeave.remove()
    }

    private var inProgress = false

    private var currentJumpResult: GetLinesToJumpResult? = null

    private fun loadJumpResult() {
        if (inProgress) return
        if (currentJumpResult != null) return

        synchronized(session) {
            if (inProgress) return
            if (currentJumpResult != null) return
            inProgress = true
        }

        project.runSynchronouslyWithProgress("Analyzing the lines...") { onFinish ->
            val myOnFinish: (GetLinesToJumpResult?) -> Unit = { result ->
                synchronized(session) {
                    currentJumpResult = result ?: UnknownErrorResult
                    inProgress = false
                    FUSLogger.log(
                        event = FUSLogger.JumpToLineEvent.GetLinesToJump,
                        status = if (result != null) JumpToLineStatus.Success else JumpToLineStatus.Success
                    )
                }
                onFinish()
            }
            tryGetLinesToJump(session, myOnFinish)
        }
    }
}