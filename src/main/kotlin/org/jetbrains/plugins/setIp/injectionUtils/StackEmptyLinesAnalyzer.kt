package org.jetbrains.plugins.setIp.injectionUtils

import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.commons.AnalyzerAdapter
import org.jetbrains.plugins.setIp.LineTranslator

internal class StackEmptyLocatorAnalyzer private constructor(private val lineTranslator: LineTranslator?) : SingleMethodAnalyzer() {

    private val linesFound = mutableSetOf<Int>()
    private val linesVisited = mutableSetOf<Int>()

    private var frameExpected = false
    private var lineOfExpectedFrame: Int = 0
    private var firstLineVisited = false

    override var analyzerAdapter: AnalyzerAdapter? = null

    override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any?>?, numStack: Int, stack: Array<out Any?>?) {

        super.visitFrame(type, numLocal, local, numStack, stack)

        if (stack === null) return

        if (!frameExpected) return
        frameExpected = false

        if (stack.all { it === null }) {
            linesFound.add(lineOfExpectedFrame)
        }
    }

    override fun visitLineNumber(line: Int, start: Label?) {

        super.visitLineNumber(line, start)

        if (!linesVisited.add(line)) return

        if (!firstLineVisited) {
            linesFound.add(line)
            firstLineVisited = true
            return
        }

        if (lineTranslator !== null && lineTranslator.translate(line) === null) return

        if (analyzerAdapter!!.stack !== null) {
            if (analyzerAdapter!!.stack.isEmpty()) {
                linesFound.add(line)
            }
        } else {
            frameExpected = true
            lineOfExpectedFrame = line
        }
    }

    companion object {
        fun analyze(
                classReader: ClassReader,
                methodName: MethodName,
                ownerTypeName: String,
                lineTranslator: LineTranslator?
        ): Set<Int>? {

            val methodVisitor = StackEmptyLocatorAnalyzer(lineTranslator)

            val classVisitor = SingleMethodVisitorForClass(methodName, ownerTypeName, methodVisitor)

            classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES)

            if (!classVisitor.visitSuccess) return null

            return methodVisitor.linesFound
        }
    }
}
