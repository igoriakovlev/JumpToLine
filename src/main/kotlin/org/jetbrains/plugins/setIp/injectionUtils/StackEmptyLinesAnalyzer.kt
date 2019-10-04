package org.jetbrains.plugins.setIp.injectionUtils

import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.commons.AnalyzerAdapter
import org.jetbrains.plugins.setIp.LineTranslator

internal class StackEmptyLinesAnalyzer(
        private val ownerTypeName: String,
        private val methodName: MethodName,
        private val lineTranslator: LineTranslator?
) : ClassVisitor6() {

    private val linesFound = mutableSetOf<Int>()
    private val linesVisited = mutableSetOf<Int>()

    var sourceDebugLine: String? = null
        private set

    private var methodVisited = false
    private var methodVisitedTwice = false
    private var frameExpected = false
    private var lineOfExpectedFrame: Int = 0
    private var firstLineVisited = false

    val validLines get() : Set<Int>? =
        (if (!methodVisited || methodVisitedTwice) null else linesFound)
                ?: nullWithLog("Cannot get validLines because methodVisited=$methodVisited and methodVisitedTwice=$methodVisitedTwice for $methodName")

    private inner class StackEmptyLocator : MethodVisitor6() {

        var analyzer: AnalyzerAdapter? = null


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

            if (analyzer!!.stack !== null) {
                if (analyzer!!.stack.isEmpty()) {
                    linesFound.add(line)
                }
            } else {
                frameExpected = true
                lineOfExpectedFrame = line
            }
        }
    }

    override fun visitSource(source: String?, debug: String?) {
        sourceDebugLine = debug
        super.visitSource(source, debug)
    }

    override fun visitMethod(
            access: Int,
            name: String?,
            desc: String?,
            signature: String?,
            exceptions: Array<out String>?
    ): MethodVisitor {
        return if (methodName.matches(name, desc, signature)) {
            if (methodVisited) {
                methodVisitedTwice = true
                EMPTY_METHOD_VISITOR
            } else {
                methodVisited = true
                StackEmptyLocator().let {
                    AnalyzerAdapter(ownerTypeName, access, name, desc, it).apply {
                        it.analyzer = this
                    }
                }
            }
        } else EMPTY_METHOD_VISITOR
    }
}