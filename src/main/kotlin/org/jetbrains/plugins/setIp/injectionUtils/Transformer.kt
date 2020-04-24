package org.jetbrains.plugins.setIp.injectionUtils

import org.objectweb.asm.*

internal const val firstStopCodeIndex = 5L
internal const val jumpSwitchVariableName = "\$SETIP\$"

internal class Transformer(
        private val methodName: MethodName,
        private val line: Int,
        private val locals: List<Pair<Type, Int>>,
        private val methodLocalsCount: Int,
        private val argumentsCount: Int,
        visitor: ClassVisitor
) : ClassVisitor(Opcodes.ASM7, visitor) {

    private var methodVisited = false
    private var methodVisitedTwice = false
    private var lineVisited = false

    val transformationSuccess get() =
        methodVisited && lineVisited && !methodVisitedTwice

    private inner class MethodTransformer(private val targetLine: Int, visitor: MethodVisitor) : MethodVisitor(Opcodes.ASM7, visitor) {

        private val labelToMark = Label()

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

            val extraVariable = methodLocalsCount

            val labelOnStart = Label()
            val labelOnFinish = Label()


            super.visitLdcInsn(0)
            super.visitVarInsn(Opcodes.ISTORE, extraVariable)
            super.visitLabel(labelOnStart)

            // THIS MAGIC NOP HAVE TO BE HERE
            // There is four variants are possible: ldc and ldc_w either as istore_N or istore indexes
            // We need to stop AFTER istore (index could be #2 or #3 or #4) BUT before/on iload (index could be #5 or #6 or #7)
            // But ASM not giving us what index we should choose so the workaround is to choose index #5 with extra nop's.
            // With this workaround we assume that index #5 [firstStopCodeIndex] points either on one of nop's or iload command, as required.
            super.visitInsn(Opcodes.NOP)
            super.visitInsn(Opcodes.NOP)
            super.visitVarInsn(Opcodes.ILOAD, extraVariable) //STOP PLACE INDEX firstStopCodeIndex

            super.visitLdcInsn(0)
            super.visitVarInsn(Opcodes.ISTORE, extraVariable)

            super.visitJumpInsn(Opcodes.IFEQ, labelOnFinish)

            emitNullifyLocals()

            super.visitJumpInsn(Opcodes.GOTO, labelToMark)

            super.visitLabel(labelOnFinish)

            super.visitLocalVariable(jumpSwitchVariableName, "I", null, labelOnStart, labelOnFinish, extraVariable)

            super.visitCode()
        }

        override fun visitLineNumber(line: Int, start: Label?) {
            if (targetLine == line) {
                if (!lineVisited) {
                    lineVisited = true
                    super.visitLabel(labelToMark)
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