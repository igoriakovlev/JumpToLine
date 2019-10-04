package org.jetbrains.plugins.setIp.injectionUtils

import org.jetbrains.org.objectweb.asm.*

internal class Transformer(
        private val methodName: MethodName,
        private val line: Int,
        private val locals: List<Pair<Type, Int>>,
        private val argumentsCount: Int,
        visitor: ClassVisitor
) : ClassVisitor(Opcodes.ASM6, visitor) {

    private var methodVisited = false
    private var methodVisitedTwice = false
    private var lineVisited = false

    val transformationSuccess get() =
        methodVisited && lineVisited && !methodVisitedTwice

    var stopLineNumber = 0
        private set

    private inner class MethodTransformer(private val targetLine: Int, visitor: MethodVisitor) : MethodVisitor(Opcodes.ASM6, visitor) {

        private val labelToMark = Label()
        private val stopLabel = Label()

        private val Type.defaultValue : Any?
            get() = when (this) {
                Type.BOOLEAN_TYPE -> false
                Type.BYTE_TYPE -> 0.toByte()
                Type.CHAR_TYPE -> 0.toChar()
                Type.DOUBLE_TYPE -> 0.0
                Type.FLOAT_TYPE -> 0.0.toFloat()
                Type.INT_TYPE -> 0
                Type.LONG_TYPE -> 0.toLong()
                Type.SHORT_TYPE -> 0.toShort()
                else -> null
            }

        private val Type.storeInstruction
            get() = when (this) {
                Type.BOOLEAN_TYPE -> Opcodes.ISTORE
                Type.BYTE_TYPE -> Opcodes.ISTORE
                Type.CHAR_TYPE -> Opcodes.ISTORE
                Type.FLOAT_TYPE -> Opcodes.FSTORE
                Type.INT_TYPE -> Opcodes.ISTORE
                Type.SHORT_TYPE -> Opcodes.ISTORE

                Type.LONG_TYPE -> Opcodes.LSTORE
                Type.DOUBLE_TYPE -> Opcodes.DSTORE

                else -> Opcodes.ASTORE
            }


        private fun emitNullifyLocals() {
            for ((type, index) in locals) {
                if (index < argumentsCount) continue

                if (type.defaultValue === null) {
                    super.visitInsn(Opcodes.ACONST_NULL)

                } else {
                    super.visitLdcInsn(type.defaultValue)
                }
                super.visitVarInsn(type.storeInstruction, index)
            }
        }

        override fun visitCode() {

            val extraVariable = locals.count()

            super.visitLdcInsn(0)
            super.visitVarInsn(Opcodes.ISTORE, extraVariable)

            super.visitLabel(stopLabel)

            super.visitCode()
        }

        private var isStartLabel = true

        override fun visitLineNumber(line: Int, start: Label?) {

            if (isStartLabel) {
                isStartLabel = false

                stopLineNumber = line

                super.visitLabel(start)
                super.visitLineNumber(line, start) //<---

                super.visitVarInsn(Opcodes.ILOAD, locals.count())
                super.visitLdcInsn(0)
                super.visitVarInsn(Opcodes.ISTORE, locals.count())

                val labelToJump = Label()
                super.visitJumpInsn(Opcodes.IFEQ, labelToJump)

                emitNullifyLocals()
                super.visitJumpInsn(Opcodes.GOTO, labelToMark)

                super.visitLabel(labelToJump)
                return
            }

            if (targetLine == line) {
                if (!lineVisited) {
                    lineVisited = true
                    super.visitLabel(labelToMark)
                    super.visitLocalVariable("$$", "I", null, stopLabel, labelToMark, locals.count())
                }
            }
            super.visitLineNumber(line, start)
        }
    }

    override fun visitMethod(access: Int, name: String?, desc: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor {
        return if (methodName.matches(name, desc, signature)) {
            if (methodVisited) {
                methodVisitedTwice = true
                super.visitMethod(access, name, desc, signature, exceptions)
            } else {
                methodVisited = true
                MethodTransformer(
                        line,
                        super.visitMethod(access, name, desc, signature, exceptions)
                )
            }
        } else {
            super.visitMethod(access, name, desc, signature, exceptions)
        }
    }
}