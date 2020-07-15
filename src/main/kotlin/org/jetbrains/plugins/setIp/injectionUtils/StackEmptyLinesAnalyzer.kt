package org.jetbrains.plugins.setIp.injectionUtils

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Label
import org.objectweb.asm.commons.AnalyzerAdapter
import org.jetbrains.plugins.setIp.LineTranslator

internal class StackEmptyLocatorAnalyzer private constructor(private val lineTranslator: LineTranslator?, private val addFirstLine: Boolean) : SingleMethodAnalyzer() {

    private val linesFound = mutableSetOf<Int>()
    private val linesVisited = mutableSetOf<Int>()

    private var firstLineVisited = false

    private val linesExpectedFromFrame = mutableSetOf<Int>()

    override var analyzerAdapter: AnalyzerAdapter? = null

    override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any?>?, numStack: Int, stack: Array<out Any?>?) {

        super.visitFrame(type, numLocal, local, numStack, stack)

        if (linesExpectedFromFrame.isEmpty()) return

        if (stack === null || numStack == 0) {
            linesFound.addAll(linesExpectedFromFrame)
        }
        linesExpectedFromFrame.clear()
    }

    override fun visitLineNumber(line: Int, start: Label?) {

        super.visitLineNumber(line, start)

        if (!linesVisited.add(line)) return
        if (linesExpectedFromFrame.contains(line)) return

        if (!firstLineVisited) {
            if (addFirstLine) linesFound.add(line)
            firstLineVisited = true
            return
        }

        if (lineTranslator !== null && lineTranslator.translate(line) === null) return

        if (analyzerAdapter!!.stack !== null) {
            if (analyzerAdapter!!.stack.isEmpty()) {
                linesFound.add(line)
            }
        } else {
            linesExpectedFromFrame.add(line)
        }
    }

    companion object {
        fun analyze(
                classReader: ClassReader,
                methodName: MethodName,
                ownerTypeName: String,
                lineTranslator: LineTranslator?,
                addFirstLine: Boolean
        ): Set<Int>? {

            val methodVisitor = StackEmptyLocatorAnalyzer(lineTranslator, addFirstLine)

            val classVisitor = SingleMethodVisitorForClass(methodName, ownerTypeName, methodVisitor)

            classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES)

            if (!classVisitor.visitSuccess) return null

            return methodVisitor.linesFound
        }
    }
}
