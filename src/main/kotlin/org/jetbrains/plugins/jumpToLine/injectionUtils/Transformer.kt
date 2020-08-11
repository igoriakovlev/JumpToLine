/*
 * Copyright 2020-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.jumpToLine.injectionUtils

import org.objectweb.asm.*

internal const val jumpSwitchVariableName = "\$SETIP\$"

internal class Transformer(
        private val jumpAnalyzeTarget: JumpAnalyzeTarget,
        private val jumpAnalyzeAdditionalInfo: JumpAnalyzeAdditionalInfo,
        private val methodName: MethodName,
        private val argumentsCount: Int,
        visitor: ClassVisitor
) : ClassVisitor7(visitor) {

    private var methodVisited = false
    private var methodVisitedTwice = false
    private var alreadyInstrumented = false

    var jumpTargetBytecodeOffset: Long = -1
        private set
    var jumpSwitchBytecodeOffset: Long = -1
        private set

    val transformationSuccess get() =
        methodVisited && alreadyInstrumented && !methodVisitedTwice && jumpTargetBytecodeOffset >= 0 && jumpSwitchBytecodeOffset >= 0

    private inner class MethodTransformer(visitor: MethodVisitor) : MethodVisitorWithCounter(visitor) {

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
            for (localDescriptor in jumpAnalyzeTarget.locals) {
                if (localDescriptor.index < argumentsCount) continue

                if (localDescriptor.asmType.defaultValue === null) {
                    super.visitInsn(Opcodes.ACONST_NULL)

                } else {
                    super.visitLdcInsn(localDescriptor.asmType.defaultValue)
                }
                super.visitVarInsn(localDescriptor.asmType.storeInstruction, localDescriptor.index)
            }
        }

        private fun LocalsFrame.withSlashSpacedNames() = map {
            if (it is String) it.slashSpacedName else it
        }

        val labelOnZeroIndex = Label()
        var codeStartIndex: Long = 0L
        val codeLabelsOnCodeStart: MutableSet<Label> = mutableSetOf()

        override fun visitCode() {

            val extraVariable = jumpAnalyzeAdditionalInfo.methodLocalsCount

            val labelOnStart = Label()
            val labelOnFinish = Label()

            super.visitLabel(labelOnZeroIndex)

            super.visitLdcInsn(0)
            super.visitVarInsn(Opcodes.ISTORE, extraVariable)

            super.visitLabel(labelOnStart)
            jumpSwitchBytecodeOffset = labelOnStart.offset.toLong()
            super.visitVarInsn(Opcodes.ILOAD, extraVariable)

            super.visitJumpInsn(Opcodes.IFEQ, labelOnFinish)

            emitNullifyLocals()

            super.visitJumpInsn(Opcodes.GOTO, labelToMark)
            if (!jumpAnalyzeAdditionalInfo.frameOnFirstInstruction) {
                val localsFrame = jumpAnalyzeAdditionalInfo.fistLocalsFrame.withSlashSpacedNames().toTypedArray()
                super.visitFrame(Opcodes.F_NEW, localsFrame.size, localsFrame, 0, null)
            }

            super.visitLabel(labelOnFinish)

            super.visitLocalVariable(jumpSwitchVariableName, "I", null, labelOnStart, labelOnFinish, extraVariable)

            codeStartIndex = instructionIndex
            super.visitCode()
        }

        override fun visitLabel(label: Label) {
            super.visitLabel(label)
            if (codeStartIndex == instructionIndex) {
                codeLabelsOnCodeStart.add(label)
            }
        }

        override fun visitLocalVariable(name: String?, descriptor: String?, signature: String?, start: Label?, end: Label?, index: Int) {
            val shiftedStart = if (codeLabelsOnCodeStart.contains(start)) labelOnZeroIndex else start
            super.visitLocalVariable(name, descriptor, signature, shiftedStart, end, index)
        }

        override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any>?, numStack: Int, stack: Array<out Any>?) {
            super.visitFrame(Opcodes.F_NEW, numLocal, local, numStack, stack)
        }

        override fun visitLineNumber(line: Int, start: Label?) {
            val isTargetIndex = jumpAnalyzeTarget.jumpTargetInfo.instructionIndex == instructionIndex - codeStartIndex
            if (!alreadyInstrumented && isTargetIndex) {
                alreadyInstrumented = true
                super.visitLabel(labelToMark)
                jumpTargetBytecodeOffset = labelToMark.offset.toLong()
                if (!jumpAnalyzeTarget.jumpTargetInfo.instantFrame) {
                    val localsFrame = jumpAnalyzeTarget
                            .localsFrame
                            .withSlashSpacedNames()
                            .toTypedArray()
                    super.visitFrame(Opcodes.F_NEW, localsFrame.size, localsFrame, 0, null)
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
                MethodTransformer(super.visitMethod(access, name, desc, signature, exceptions))
            }
        } else {
            super.visitMethod(access, name, desc, signature, exceptions)
        }
    }
}