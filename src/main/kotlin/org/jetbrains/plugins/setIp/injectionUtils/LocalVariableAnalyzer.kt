package org.jetbrains.plugins.setIp.injectionUtils

import com.jetbrains.rd.util.getOrCreate
import org.objectweb.asm.commons.AnalyzerAdapter
import org.objectweb.asm.*
import org.jetbrains.plugins.setIp.LineTranslator

internal typealias LocalsFrame = List<Any>

internal open class LocalSemiDescriptor(val index: Int, val asmType: Type)
internal class LocalDescriptor(index: Int, asmType: Type, val canRestore: Boolean) : LocalSemiDescriptor(index, asmType)

internal data class LocalVariableAnalyzeResult(
        val javaLine: Int,
        val sourceLine: Int,
        val locals: List<LocalDescriptor>,
        val localsFrame: LocalsFrame,
        val fistLocalsFrame: LocalsFrame,
        val isSafeLine: Boolean,
        val isFirstLine: Boolean,
        val methodLocalsCount: Int,
        val instantFrame: Boolean,
        val frameOnFirstInstruction: Boolean
)

internal class LocalVariableAnalyzer private constructor(
        private val ownerTypeName: String,
        private val lineTranslator: LineTranslator?,
        private val lineFilterSet: Set<Int>,
        private val jumpFromLine: Int
) : SingleMethodAnalyzerWithCounter() {

    private data class SemiResult(
            val locals: List<LocalSemiDescriptor>,
            val localsFrame: LocalsFrame,
            val isFirstLine: Boolean,
            val instructionIndex: Long
    )

    private var isFirstLine = true

    private val instructionCountOnFrames = mutableSetOf<Long>()

    private val linesExpectedFromFrame = mutableSetOf<Int>()

    private val semiResult = mutableMapOf<Int, SemiResult>()
    private val visitedLines = mutableSetOf<Int>()
    private val duplicatedLines = mutableSetOf<Int>()
    private val firstLocalsFrame = mutableListOf<Any>()

    private data class UserVisibleLocal(val start: Label, val end: Label)
    private val localVariablesWithRanges: MutableMap<Int, MutableList<UserVisibleLocal>> = mutableMapOf()

    private val localVariablesAccessRegions: MutableMap<Int, MutableList<Pair<Long, Long>>> = mutableMapOf()
    private val localVariablesStoreIndexes: MutableMap<Int, Long> = mutableMapOf()

    private val labelToIndex: MutableMap<Label, Long> = mutableMapOf()

    private var sourceLineIndex: Long? = null

    private fun canBeRestored(local: Int, onIndex: Long): Boolean {

        val ranges = localVariablesWithRanges[local] ?: return false

        return ranges.any {
            val firstIndex = labelToIndex[it.start] ?: return@any false
            val lastIndex = labelToIndex[it.end] ?: return@any false
            onIndex in firstIndex..lastIndex && sourceLineIndex in firstIndex..lastIndex
        }
    }

    private fun isLocalAccessible(local: Int, onIndex: Long): Boolean {
        val ranges = localVariablesAccessRegions[local] ?: return false

        return ranges.any {
            onIndex in it.first..it.second
        }
    }

    private val analyzeResult: List<LocalVariableAnalyzeResult>? get() {

        val methodLocalsCount = semiResult.maxBy { it.value.locals.size }?.value?.locals?.size ?: 0

        //We have to filter out targets with duplicated lines because we have not idea where exactly we are going to jump
        return semiResult.map {

            val localsDescriptors = it.value.locals.map { semiDescriptor ->
                val canRestore = canBeRestored(semiDescriptor.index, it.value.instructionIndex)
                LocalDescriptor(semiDescriptor.index, semiDescriptor.asmType, canRestore)
            }

            val allVariablesIsSafe = localsDescriptors.all { local ->
                local.canRestore || !isLocalAccessible(local.index, it.value.instructionIndex)
            }

            val isSafe = allVariablesIsSafe //&& !duplicatedLines.contains(it.key)

            //Translate line and skip it if translation is failed
            val sourceLine = if (lineTranslator !== null) lineTranslator.translate(it.key) else it.key
            sourceLine ?: throw AssertionError("Translated line should not be zero on result building")

            val isInstantFrame = instructionCountOnFrames.contains(it.value.instructionIndex)

            LocalVariableAnalyzeResult(
                    javaLine = it.key,
                    sourceLine = sourceLine,
                    locals = localsDescriptors,
                    fistLocalsFrame = firstLocalsFrame,
                    localsFrame = it.value.localsFrame,
                    isSafeLine = isSafe,
                    isFirstLine = it.value.isFirstLine,
                    methodLocalsCount = methodLocalsCount,
                    instantFrame = isInstantFrame,
                    frameOnFirstInstruction = instructionCountOnFrames.contains(0)
            )
        }
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
                localVariablesAccessRegions.getOrCreate(index) { mutableListOf() }

        regionsList.add(Pair(storeIndex, instructionIndex))
    }

    override fun visitIincInsn(`var`: Int, increment: Int) {
        processLocalRead(`var`)
        processLocalWrite(`var`)
        super.visitIincInsn(`var`, increment)
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

    override fun visitLocalVariable(name: String, descriptor: String?, signature: String?, start: Label, end: Label, index: Int) {
        super.visitLocalVariable(name, descriptor, signature, start, end, index)
        localVariablesWithRanges.getOrCreate(index) { mutableListOf() }.add(UserVisibleLocal(start, end))
    }

    override fun visitLabel(label: Label) {
        super.visitLabel(label)
        labelToIndex[label] = instructionIndex
    }

    private fun createIndexesForCurrentPosition(localsCount: Int) {
        localVariablesStoreIndexes.clear()
        for (local in localsCount until localsCount) {
            localVariablesStoreIndexes[local] = instructionIndex
        }
    }

    override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any?>?, numStack: Int, stack: Array<out Any?>?) {

        super.visitFrame(type, numLocal, local, numStack, stack)

        createIndexesForCurrentPosition(numLocal)

        instructionCountOnFrames.add(instructionIndex)

        if (local === null) return

        if (linesExpectedFromFrame.isEmpty()) return

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

        linesExpectedFromFrame.forEach {
            semiResult[it] = SemiResult(resultBuilder, localsFrame, isFirstLine, instructionIndex)
            isFirstLine = false
        }

        linesExpectedFromFrame.clear()
    }

    override fun visitLineNumber(line: Int, start: Label) {

        super.visitLineNumber(line, start)
        val actuallyFirstLine = isFirstLine
        isFirstLine = false

        if (sourceLineIndex == null && line == jumpFromLine) {
            sourceLineIndex = instructionIndex
        }

        if (!lineFilterSet.contains(line)) return

        if (!visitedLines.add(line)) {
            duplicatedLines.add(line)
        }

        if (lineTranslator !== null && lineTranslator.translate(line) === null) return

        if (semiResult.containsKey(line)) return
        if (linesExpectedFromFrame.contains(line)) return

        val locals = analyzerAdapter!!.locals

        if (locals !== null) {
            val localsFrame = locals.toList()
            val result = locals.mapIndexedNotNull { index, type ->
                type.convertToType()?.let { LocalSemiDescriptor(index, it) }
            }
            semiResult[line] = SemiResult(result, localsFrame, actuallyFirstLine, instructionIndex)
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
                lineFilterSet: Set<Int>,
                jumpFromLine: Int
        ): List<LocalVariableAnalyzeResult>? {

            val methodVisitor = LocalVariableAnalyzer(ownerTypeName, lineTranslator, lineFilterSet, jumpFromLine)

            val classVisitor = SingleMethodVisitorForClass(methodName, ownerTypeName, methodVisitor)

            classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES)

            if (!classVisitor.visitSuccess) return null

            return methodVisitor.analyzeResult
        }
    }
}

