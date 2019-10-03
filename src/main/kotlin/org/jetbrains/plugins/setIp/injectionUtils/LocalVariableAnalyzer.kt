package org.jetbrains.plugins.setIp.injectionUtils

import org.jetbrains.org.objectweb.asm.commons.AnalyzerAdapter
import org.jetbrains.org.objectweb.asm.*

internal class LocalVariableAnalyzer(
        private val ownerTypeName: String,
        private val methodName: MethodName,
        private val targetLine: Int
) : ClassVisitor6() {

    private var methodVisited = false
    private var methodVisitedTwice = false
    private var lineVisited = false
    private var frameExpected = false

    private var result : List<Pair<Type, Int>>? = null

    private val labels = mutableListOf<Label>()
    private val localVariablesWithRanges = mutableListOf<Pair<Label, Label>>()
    private var targetLabel: Label? = null

    val visibleVariables by lazy {

        targetLabel ?: return@lazy null
        if (labels.isEmpty()) return@lazy null
        if (localVariablesWithRanges.isEmpty()) return@lazy 0

        var afterMark = false

        for (currentLabel in labels) {
            if (currentLabel == targetLabel) {
                afterMark = true
                continue
            }

            if (afterMark) {
                localVariablesWithRanges.removeIf { it.first == currentLabel }
            } else {
                localVariablesWithRanges.removeIf { it.second == currentLabel }
            }
        }

        localVariablesWithRanges.count()
    }

    val defaultValueAndName get() : List<Pair<Type, Int>>? =
        (if (!methodVisited || methodVisitedTwice || !lineVisited) null else result)
                ?: nullWithLog("Cannot get locals because methodVisited=$methodVisited and methodVisitedTwice=$methodVisitedTwice and lineVisited=$lineVisited for $methodName : $targetLine")

    private inner class LocalsOnLineRetrieverVisitor : MethodVisitor6() {

        var analyzer: AnalyzerAdapter? = null
        private fun Any.convertToType() = when(this) {
            is String -> Type.getObjectType(this)
            Opcodes.INTEGER -> Type.INT_TYPE
            Opcodes.FLOAT -> Type.FLOAT_TYPE
            Opcodes.DOUBLE -> Type.DOUBLE_TYPE
            Opcodes.LONG -> Type.LONG_TYPE
            Opcodes.UNINITIALIZED_THIS -> Type.getObjectType(ownerTypeName)
            Opcodes.TOP -> null
            Opcodes.NULL -> error { "Opcode does not supported NULL" }
            else -> error { "Opcode does not supported $this" }
        }

        override fun visitLocalVariable(name: String, descriptor: String?, signature: String?, start: Label, end: Label, index: Int) {
            super.visitLocalVariable(name, descriptor, signature, start, end, index)
            localVariablesWithRanges.add(start to end)
        }

        override fun visitLabel(label: Label) {

            super.visitLabel(label)

            labels.add(label)


        }

        override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any?>?, numStack: Int, stack: Array<out Any?>?) {

            super.visitFrame(type, numLocal, local, numStack, stack)

            if (local === null) return

            if (!frameExpected) return
            frameExpected = false

            val resultBuilder = mutableListOf<Pair<Type, Int>>()

            for (index in 0 until numLocal) {
                val convertedType = local[index]?.convertToType() ?: continue
                resultBuilder.add(convertedType to index)
            }
            targetLabel = labels.last()
            result = resultBuilder
        }

        override fun visitLineNumber(line: Int, start: Label) {

            super.visitLineNumber(line, start)

            if (targetLine != line) return
            if (lineVisited) return

            lineVisited = true

            val locals = analyzer!!.locals

            if (locals !== null) {
                result = locals.mapIndexedNotNull { index, type ->
                    type.convertToType()?.let { it to index }
                }
                targetLabel = labels.last()
            } else {
                frameExpected = true
            }
        }
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
                LocalsOnLineRetrieverVisitor().let {
                    AnalyzerAdapter(ownerTypeName, access, name, desc, it).apply {
                        it.analyzer = this
                    }
                }
            }
        } else EMPTY_METHOD_VISITOR
    }
}