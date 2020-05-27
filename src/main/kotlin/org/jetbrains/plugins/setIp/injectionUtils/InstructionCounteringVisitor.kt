package org.jetbrains.plugins.setIp.injectionUtils

import org.objectweb.asm.Handle
import org.objectweb.asm.Label

internal class InstructionCounteringVisitor : MethodVisitor7() {

    var instructionVisited: Long = 0
        private set

    override fun visitInsn(opcode: Int) {
        super.visitInsn(opcode)
        instructionVisited++
    }

    override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) {
        super.visitFieldInsn(opcode, owner, name, descriptor)
        instructionVisited++
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        super.visitIntInsn(opcode, operand)
        instructionVisited++
    }

    override fun visitIincInsn(`var`: Int, increment: Int) {
        super.visitIincInsn(`var`, increment)
        instructionVisited++
    }

    override fun visitJumpInsn(opcode: Int, label: Label?) {
        super.visitJumpInsn(opcode, label)
        instructionVisited++
    }

    override fun visitLdcInsn(value: Any?) {
        super.visitLdcInsn(value)
        instructionVisited++
    }

    override fun visitMethodInsn(opcode: Int, owner: String?, name: String?, descriptor: String?, isInterface: Boolean) {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        instructionVisited++
    }

    override fun visitTypeInsn(opcode: Int, type: String?) {
        super.visitTypeInsn(opcode, type)
        instructionVisited++
    }

    override fun visitVarInsn(opcode: Int, `var`: Int) {
        super.visitVarInsn(opcode, `var`)
        instructionVisited++
    }

    override fun visitInvokeDynamicInsn(name: String?, descriptor: String?, bootstrapMethodHandle: Handle?, vararg bootstrapMethodArguments: Any?) {
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        instructionVisited++
    }

    override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<out Label>?) {
        super.visitLookupSwitchInsn(dflt, keys, labels)
        instructionVisited++
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, vararg labels: Label?) {
        super.visitTableSwitchInsn(min, max, dflt, *labels)
        instructionVisited++
    }

    override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) {
        super.visitMultiANewArrayInsn(descriptor, numDimensions)
        instructionVisited++
    }
}