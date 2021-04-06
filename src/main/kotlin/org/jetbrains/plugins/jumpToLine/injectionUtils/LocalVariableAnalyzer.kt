/*
 * Copyright 2020-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.jumpToLine.injectionUtils

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AnalyzerAdapter

internal typealias LocalsFrame = List<Any>

internal open class LocalSemiDescriptor(val index: Int, val asmType: Type)
internal class LocalDescriptor(index: Int, asmType: Type, val saveRestoreStatus: LocalVariableAnalyzer.SaveRestoreStatus) : LocalSemiDescriptor(index, asmType)
internal data class UserVisibleLocal(val name: String, val descriptor: String, val signature: String?, val start: Label, val end: Label, val index: Int)

internal enum class LineSafetyStatus {
    Safe,
    NotSafe,
    UninitializedExist
}

internal data class JumpAnalyzeTarget(
        val jumpTargetInfo: JumpTargetInfo,
        val locals: List<LocalDescriptor>,
        val localsFrame: LocalsFrame,
        val safeStatus: LineSafetyStatus
)



internal data class JumpAnalyzeAdditionalInfo(
        val fistLocalsFrame: LocalsFrame,
        val methodLocalsCount: Int,
        val frameOnFirstInstruction: Boolean
)

internal data class JumpAnalyzeResult(
        val jumpAnalyzedTargets: List<JumpAnalyzeTarget>,
        val jumpAnalyzeAdditionalInfo: JumpAnalyzeAdditionalInfo
)

internal class LocalVariableAnalyzer private constructor(
        private val ownerTypeName: String,
        private val linesAnalyzerResult: LinesAnalyzerResult
) : SingleMethodAnalyzer() {

    private data class SemiResult(
            val locals: List<LocalSemiDescriptor>,
            val localsFrame: LocalsFrame,
            val instructionIndex: Long,
            val jumpTargetInfo: JumpTargetInfo
    )

    private val semiResult = mutableMapOf<Long, SemiResult>()
    private val firstLocalsFrame = mutableListOf<Any>()

    private val localVariablesWithRanges: MutableMap<Int, MutableList<UserVisibleLocal>> = mutableMapOf()

    private data class AccessRange(val start: Long, val end: Long)
    private data class AccessJumpRange(val start: Long, val end: Long, val label: Label)
    private val localVariablesAccessRegions: MutableMap<Int, MutableList<AccessRange>> = mutableMapOf()
    private val localVariablesJumpAccessRegions: MutableMap<Int, MutableList<AccessJumpRange>> = mutableMapOf()
    private val localVariablesStoreIndexes: MutableMap<Int, Long> = mutableMapOf()

    private val labelToIndex: MutableMap<Label, Long> = mutableMapOf()

    enum class SaveRestoreStatus {
        CanBeRestored,
        CanBeSavedAndRestored,
        IsParameter,
        None
    }

    private fun getSaveRestoreStatus(local: Int, onIndex: Long): SaveRestoreStatus {

        if (local < firstLocalsFrame.size) return SaveRestoreStatus.IsParameter

        val ranges = localVariablesWithRanges[local] ?: return SaveRestoreStatus.None

        var resultStatus = SaveRestoreStatus.None
        ranges.forEach {
            val firstIndex = labelToIndex[it.start]
            val lastIndex = labelToIndex[it.end]

            if (firstIndex != null && lastIndex != null) {
                val canBeSaved = linesAnalyzerResult
                        .jumpFromJavaLineIndexes.all { index -> index in firstIndex..lastIndex }

                val canBeRestored = onIndex in firstIndex..lastIndex

                if (canBeSaved && canBeRestored) return SaveRestoreStatus.CanBeSavedAndRestored
                if (canBeRestored) resultStatus = SaveRestoreStatus.CanBeRestored
            }
        }

        return resultStatus
    }

    private class LazyMutableSet {
        val mutableSet: MutableSet<Long> by lazy { mutableSetOf<Long>() }
    }

    private fun isLocalAccessible(local: Int, onIndex: Long): Boolean =
            isLocalAccessible(local, onIndex, LazyMutableSet())

    private fun isLocalAccessible(local: Int, onIndex: Long, visitedJumps: LazyMutableSet): Boolean {
        val readAccess = localVariablesAccessRegions[local]?.let { ranges ->
            ranges.any {
                onIndex in it.start..it.end
            }
        } ?: false

        if (readAccess) return true

        val jumpRanges = localVariablesJumpAccessRegions[local] ?: return false

        for (jumpRange in jumpRanges) {
            if (onIndex in jumpRange.start..jumpRange.end) {
                val targetLabelIndex = labelToIndex[jumpRange.label] ?: continue
                if (visitedJumps.mutableSet.contains(targetLabelIndex)) continue

                visitedJumps.mutableSet.add(targetLabelIndex)
                if (isLocalAccessible(local, targetLabelIndex, visitedJumps)) return true
            }
        }

        return false
    }

    private val analyzeResult: JumpAnalyzeResult get() {

        val methodLocalsCount = semiResult.maxBy { it.value.locals.size }?.value?.locals?.size ?: 0

        val analyzeTargets = mutableListOf<JumpAnalyzeTarget>()

        for (currentResult in semiResult.values) {

            val localsDescriptors = currentResult.locals.map { semiDescriptor ->
                val status = getSaveRestoreStatus(semiDescriptor.index, currentResult.instructionIndex)
                LocalDescriptor(semiDescriptor.index, semiDescriptor.asmType, status)
            }

            val allVariablesIsSafe = localsDescriptors.all { local ->
                local.saveRestoreStatus != SaveRestoreStatus.None || !isLocalAccessible(local.index, currentResult.instructionIndex)
            }

            val allVariablesSavedAndRestored = allVariablesIsSafe && localsDescriptors.all {
                local -> local.saveRestoreStatus == SaveRestoreStatus.CanBeSavedAndRestored || local.saveRestoreStatus == SaveRestoreStatus.IsParameter
            }

            val safeStatus = if (allVariablesIsSafe) {
                if (allVariablesSavedAndRestored) LineSafetyStatus.Safe else LineSafetyStatus.UninitializedExist
            } else LineSafetyStatus.NotSafe

            val jumpAnalyzeResult = JumpAnalyzeTarget(
                    jumpTargetInfo = currentResult.jumpTargetInfo,
                    locals = localsDescriptors,
                    localsFrame = currentResult.localsFrame,
                    safeStatus = safeStatus
           )

            analyzeTargets.add(jumpAnalyzeResult)
        }

        return JumpAnalyzeResult(
                jumpAnalyzedTargets = analyzeTargets,
                jumpAnalyzeAdditionalInfo = JumpAnalyzeAdditionalInfo(
                    fistLocalsFrame = firstLocalsFrame,
                    methodLocalsCount = methodLocalsCount,
                    frameOnFirstInstruction = linesAnalyzerResult.instantFrameOnFirstInstruction
                )
        )
    }


    override var analyzerAdapter: AnalyzerAdapter? = null

    private fun Any.convertToType() = when(this) {
        is String -> Type.getObjectType(this)
        Opcodes.INTEGER -> Type.INT_TYPE
        Opcodes.FLOAT -> Type.FLOAT_TYPE
        Opcodes.DOUBLE -> Type.DOUBLE_TYPE
        Opcodes.LONG -> Type.LONG_TYPE
        Opcodes.UNINITIALIZED_THIS -> Type.getObjectType(ownerTypeName)
        Opcodes.TOP -> null
        Opcodes.NULL -> Type.getObjectType("java.lang.Object")
        else -> error { "Opcode does not supported $this" }
    }

    private val Type.indexShiftSize get() =
        when(this) {
            Type.LONG_TYPE, Type.DOUBLE_TYPE -> 2
            else -> 1
        }

    override fun visitCode() {
        val locals = analyzerAdapter!!.locals
        firstLocalsFrame.addAll(locals)
        createIndexesForCurrentPosition(locals.size)
        super.visitCode()
    }

    private fun processLocalWrite(index: Int) {
        localVariablesStoreIndexes[index] = instructionIndex
    }

    private fun processLocalRead(index: Int) {
        val storeIndex = localVariablesStoreIndexes[index] ?: 0
        val regionsList =
                localVariablesAccessRegions.getOrPut(index) { mutableListOf() }

        regionsList.add(AccessRange(storeIndex, instructionIndex))
    }

    private fun processLocalJump(index: Int, label: Label) {
        val storeIndex = localVariablesStoreIndexes[index] ?: 0
        val regionsList =
                localVariablesJumpAccessRegions.getOrPut(index) { mutableListOf() }

        regionsList.add(AccessJumpRange(storeIndex, instructionIndex, label))
    }

    override fun visitIincInsn(`var`: Int, increment: Int) {
        processLocalRead(`var`)
        processLocalWrite(`var`)
        super.visitIincInsn(`var`, increment)
    }

    override fun visitJumpInsn(opcode: Int, label: Label?) {
        if (label != null) {
            analyzerAdapter!!.locals.forEachIndexed { index, type ->
                if (type != null) {
                    processLocalJump(index, label)
                }
            }
        } else {
            analyzerAdapter!!.locals.forEachIndexed { index, type ->
                if (type != null) {
                    processLocalRead(index)
                }
            }
        }
        super.visitJumpInsn(opcode, label)
    }

    override fun visitVarInsn(opcode: Int, `var`: Int) {
        when (opcode) {
            Opcodes.ILOAD,
            Opcodes.LLOAD,
            Opcodes.FLOAD,
            Opcodes.DLOAD,
            Opcodes.ALOAD,
            Opcodes.RET
                -> processLocalRead(`var`)
            Opcodes.ISTORE,
            Opcodes.LSTORE,
            Opcodes.FSTORE,
            Opcodes.DSTORE,
            Opcodes.ASTORE
                -> processLocalWrite(`var`)
        }
        super.visitVarInsn(opcode, `var`)
    }

    override fun visitLocalVariable(name: String, descriptor: String, signature: String?, start: Label, end: Label, index: Int) {
        super.visitLocalVariable(name, descriptor, signature, start, end, index)
        localVariablesWithRanges.getOrPut(index) { mutableListOf() }.add(UserVisibleLocal(name, descriptor, signature, start, end, index))
    }

    override fun visitLabel(label: Label) {
        super.visitLabel(label)
        labelToIndex[label] = instructionIndex
    }

    private fun createIndexesForCurrentPosition(localsCount: Int) {
        localVariablesStoreIndexes.clear()
        for (local in 0 until localsCount) {
            localVariablesStoreIndexes[local] = instructionIndex
        }
    }

    override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any?>?, numStack: Int, stack: Array<out Any?>?) {

        super.visitFrame(type, numLocal, local, numStack, stack)

        if (numStack != 0) return
        if (local === null) return

        val jumpTargetInfo = linesAnalyzerResult
                .jumpTargetInfos
                .firstOrNull { it.instructionIndex == instructionIndex }
                ?: return

        if (!jumpTargetInfo.instantFrame) return

        val resultBuilder = mutableListOf<LocalSemiDescriptor>()
        val localsFrame = mutableListOf<Any>()

        var currentLocalIndex = 0
        for (index in 0 until numLocal) {
            val variable = local[index]
            requireNotNull(variable) { "Unexpected local variable null" }
            localsFrame.add(variable)
            val convertedType = variable.convertToType()
            if (convertedType != null) {
                resultBuilder.add(LocalSemiDescriptor(currentLocalIndex, convertedType))
                currentLocalIndex += convertedType.indexShiftSize
            } else {
                currentLocalIndex++
            }
        }

        semiResult[instructionIndex] = SemiResult(
                locals = resultBuilder,
                localsFrame = localsFrame,
                instructionIndex = instructionIndex,
                jumpTargetInfo = jumpTargetInfo
        )
    }

    override fun visitLineNumber(line: Int, start: Label) {

        super.visitLineNumber(line, start)

        val jumpTargetInfo = linesAnalyzerResult
                .jumpTargetInfos
                .firstOrNull { it.instructionIndex == instructionIndex }
                ?: return

        if (jumpTargetInfo.instantFrame) return

        if (semiResult.containsKey(instructionIndex)) return

        val analyzer = requireNotNull(analyzerAdapter)

        val stack = requireNotNull(analyzer.stack)
        if (stack.isNotEmpty()) return
        val locals = requireNotNull(analyzer.locals)

        val localsFrame = locals.toList()
        val result = locals.mapIndexedNotNull { index, type ->
            type.convertToType()?.let { LocalSemiDescriptor(index, it) }
        }
        semiResult[instructionIndex] = SemiResult(
                locals = result,
                localsFrame = localsFrame,
                instructionIndex = instructionIndex,
                jumpTargetInfo = jumpTargetInfo
        )


    }

    companion object {
        fun analyze(
                classReader: ClassReader,
                methodName: MethodName,
                ownerTypeName: String,
                linesAnalyzerResult: LinesAnalyzerResult
        ): JumpAnalyzeResult? {

            val methodVisitor = LocalVariableAnalyzer(ownerTypeName, linesAnalyzerResult)

            val classVisitor = SingleMethodVisitorForClass(methodName, ownerTypeName, methodVisitor)

            classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES)

            if (!classVisitor.visitSuccess) return null

            return methodVisitor.analyzeResult
        }
    }
}

