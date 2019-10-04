package org.jetbrains.plugins.setIp

import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.GutterDraggableObject
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.messages.MessageDialog
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.setIp.injectionUtils.LocalVariableAnalyzeResult
import java.awt.Cursor
import java.awt.dnd.DragSource
import java.io.File

internal class SetIPArrowGutter(
        private val project: Project,
        private val commonTypeResolver: CommonTypeResolver,
        private val session: DebuggerSession
): GutterDraggableObject {

    private val fileChooser =
            FileChooserFactory.getInstance().createFileChooser(
                    FileChooserDescriptorFactory
                            .createSingleFileDescriptor("class")
                            .withDescription("Select .class for type with method you trying to SetIP"),
                    project,
                    null)

    private fun selectClassFile(file: VirtualFile): ByteArray? {

        val outputFilePath = tryGetOutputFilePaths(project, file)
                ?.mapNotNull (file.fileSystem::findFileByPath)
                ?: emptyList()

        val selectedFiles = fileChooser.choose(project, *outputFilePath.toTypedArray())
        if (selectedFiles.isEmpty()) return null

        selectedFiles[0].path.let {
            val selectedFile = File(it)
            if (selectedFile.isFile && selectedFile.canRead()) {
                return selectedFile.readBytes()
            }
        }

        return null
    }

    override fun copy(line: Int, file: VirtualFile?, actionId: Int): Boolean {
        val extension = file?.extension ?: return false

        if (extension != "java" && extension != "kt") return false

        val preferableStratum = when(extension) {
            "kt" -> "Kotlin"
            else -> "Java"
        }

        val jumpInfo = when(val result = tryGetLinesToJump(session, project, file, preferableStratum)) {
            is JumpLinesInfo -> result
            is ClassNotFoundErrorResult ->
                selectClassFile(file)?.let { tryGetLinesToJump(session, project, file, preferableStratum, it) as? JumpLinesInfo }
            else -> null
        }

        currentJumpInfo = jumpInfo
        jumpInfo ?: return false

        val selected = localAnalysisByRenderLine(line) ?: return false

        if (!selected.isSafeLine) {
            val dialog = MessageDialog(project, "This jump is not safe! Continue?", "SetIP", arrayOf("Yes", "No way!"), 1, null, true)
            dialog.show()
            if (dialog.exitCode == 1) return false
        }

        return tryJumpToSelectedLine(selected, jumpInfo.classFile)
    }

    override fun getCursor(line: Int, actionId: Int): Cursor {
        val selected =
                localAnalysisByRenderLine(line) ?: return DragSource.DefaultMoveNoDrop

        return if (selected.isSafeLine) DragSource.DefaultMoveDrop else DragSource.DefaultLinkDrop
    }

    private fun localAnalysisByRenderLine(line: Int) =
            currentJumpInfo?.linesToJump?.firstOrNull { it.sourceLine == line + 1 }

    private var currentJumpInfo: JumpLinesInfo? = null

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
                    //rebuildViews()
                    showExecutionPoint()
                }
            }
        }

        return result
    }
}