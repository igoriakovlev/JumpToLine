package org.jetbrains.plugins.setIp.injectionUtils

import org.jetbrains.plugins.setIp.LineTranslator
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Label
import org.objectweb.asm.commons.AnalyzerAdapter

internal data class LineInfo(val javaLine: Int, val sourceLine: Int)

internal data class JumpTargetInfo(
        val instructionIndex: Long,
        val lines: Set<LineInfo>,
        val instantFrame: Boolean
)

internal data class LinesAnalyzerResult(
        val jumpTargetInfos: List<JumpTargetInfo>,
        val jumpFromJavaLineIndexes: Set<Long>,
        val instantFrameOnFirstInstruction: Boolean
)

internal class LinesAnalyzer private constructor(
        private val lineTranslator: LineTranslator?,
        private val addFirstLine: Boolean,
        private val jumpFromJavaLine: Int) : SingleMethodAnalyzer() {

    private data class SemiJumpTargetInfo(
            val lines: MutableSet<LineInfo>
    )

    private val linesFound = mutableMapOf<Long, SemiJumpTargetInfo>()
    private val linesVisited = mutableSetOf<Int>()
    private var jumpFromJavaLineIndexes: MutableSet<Long>? = null
    private val frameIndexes = mutableSetOf<Long>()
    private var firstLineVisited = false

    override var analyzerAdapter: AnalyzerAdapter? = null

    override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any>?, numStack: Int, stack: Array<out Any>?) {
        super.visitFrame(type, numLocal, local, numStack, stack)
        frameIndexes.add(instructionIndex)
    }

    override fun visitLineNumber(line: Int, start: Label?) {

        super.visitLineNumber(line, start)

        if (jumpFromJavaLine == line) {
            jumpFromJavaLineIndexes = (jumpFromJavaLineIndexes ?: mutableSetOf()).also {
                it.add(instructionIndex)
            }
        }

        if (!linesVisited.add(line)) return
        val sourceLine = (if (lineTranslator != null) lineTranslator.translate(line) else line) ?: return
        val lineInfo = LineInfo(line, sourceLine)

        linesFound[instructionIndex]?.run {
            lines.add(lineInfo)
            return
        }

        if (!firstLineVisited) {
            if (addFirstLine) linesFound[instructionIndex] = SemiJumpTargetInfo(lines = mutableSetOf(lineInfo))
            firstLineVisited = true
            return
        }

        linesFound[instructionIndex] = SemiJumpTargetInfo(lines = mutableSetOf(lineInfo))
    }

    companion object {
        fun analyze(
                classReader: ClassReader,
                methodName: MethodName,
                ownerTypeName: String,
                lineTranslator: LineTranslator?,
                jumpFromJavaLine: Int,
                addFirstLine: Boolean
        ): LinesAnalyzerResult? {

            val methodVisitor = LinesAnalyzer(lineTranslator, addFirstLine, jumpFromJavaLine)

            val classVisitor = SingleMethodVisitorForClass(methodName, ownerTypeName, methodVisitor)

            classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES)

            if (!classVisitor.visitSuccess) return null

            val jumpFromLineIndexes = methodVisitor.jumpFromJavaLineIndexes ?: return null

            val linesFound = methodVisitor.linesFound.map {
                JumpTargetInfo(
                        instructionIndex = it.key,
                        lines = it.value.lines,
                        instantFrame = methodVisitor.frameIndexes.contains(it.key)
                )
            }

            return LinesAnalyzerResult(
                    jumpTargetInfos = linesFound,
                    jumpFromJavaLineIndexes = jumpFromLineIndexes,
                    instantFrameOnFirstInstruction = methodVisitor.frameIndexes.contains(0)
            )
        }
    }
}
