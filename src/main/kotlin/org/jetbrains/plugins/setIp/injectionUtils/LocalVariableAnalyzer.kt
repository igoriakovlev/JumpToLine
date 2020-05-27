package org.jetbrains.plugins.setIp.injectionUtils

import org.objectweb.asm.commons.AnalyzerAdapter
import org.objectweb.asm.*
import org.jetbrains.plugins.setIp.LineTranslator

internal typealias LocalsList = List<Pair<Type, Int>>
internal typealias LocalsFrame = List<Any>

internal data class LocalVariableAnalyzeResult(
        val javaLine: Int,
        val sourceLine: Int,
        val locals: LocalsList,
        val localsFrame: LocalsFrame,
        val fistLocalsFrame: LocalsFrame,
        val isSafeLine: Boolean,
        val isFirstLine: Boolean,
        val methodLocalsCount: Int,
        val instantFrame: Boolean
)

internal class LocalVariableAnalyzer private constructor(
        private val ownerTypeName: String,
        private val lineTranslator: LineTranslator?,
        private val lineFilterSet: Set<Int>,
        private val instructionCounter: InstructionCounteringVisitor
) : SingleMethodAnalyzer(instructionCounter) {

    private data class SemiResult(
            val locals: LocalsList,
            val localsFrame: LocalsFrame,
            val label: Label,
            val isFirstLine: Boolean,
            val instructionCount: Long
    )

    private var isFirstLine = true

    private val instructionCountOnFrames = mutableSetOf<Long>()

    private val linesExpectedFromFrame = mutableSetOf<Int>()
    private val semiResult = mutableMapOf<Int, SemiResult>()
    private val visitedLines = mutableSetOf<Int>()
    private val duplicatedLines = mutableSetOf<Int>()
    private val firstLocalsFrame = mutableListOf<Any>()

    private val labels = mutableListOf<Label>()
    private val localVariablesWithRanges = mutableListOf<Pair<Label, Label>>()

    private fun getVariablesCountForLabel(targetLabel: Label) : Int {

        if (labels.isEmpty()) return 0
        if (localVariablesWithRanges.isEmpty()) return 0

        var afterMark = false

        val copiedRanges = mutableListOf<Pair<Label, Label>>()
                .also { it.addAll(localVariablesWithRanges) }

        for (currentLabel in labels) {
            if (currentLabel == targetLabel) {
                afterMark = true
                continue
            }

            if (afterMark) {
                copiedRanges.removeIf { it.first == currentLabel }
            } else {
                copiedRanges.removeIf { it.second == currentLabel }
            }
        }

        return copiedRanges.count()
    }

    private val analyzeResult: List<LocalVariableAnalyzeResult>? get() {

        val methodLocalsCount = semiResult.maxBy { it.value.locals.size }?.value?.locals?.size ?: 0

        //We have to filter out targets with duplicated lines because we have not idea where exactly we are going to jump
        return semiResult.map {
            val visiblesCount = getVariablesCountForLabel(it.value.label)
            val isSafe = visiblesCount == it.value.locals.size //&& !duplicatedLines.contains(it.key)

            //Translate line and skip it if translation is failed
            val sourceLine = if (lineTranslator !== null) lineTranslator.translate(it.key) else it.key
            sourceLine ?: throw AssertionError("Translated line should not be zero on result building")

            val isInstantFrame = instructionCountOnFrames.contains(it.value.instructionCount)

            LocalVariableAnalyzeResult(
                    javaLine = it.key,
                    sourceLine = sourceLine,
                    locals = it.value.locals,
                    fistLocalsFrame = firstLocalsFrame,
                    localsFrame = it.value.localsFrame,
                    isSafeLine = isSafe,
                    isFirstLine = it.value.isFirstLine,
                    methodLocalsCount = methodLocalsCount,
                    instantFrame = isInstantFrame
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
        firstLocalsFrame.addAll(analyzerAdapter!!.locals)
        super.visitCode()
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

        instructionCountOnFrames.add(instructionCounter.instructionVisited)

        if (local === null) return

        if (linesExpectedFromFrame.isEmpty()) return

        val resultBuilder = mutableListOf<Pair<Type, Int>>()
        val localsFrame = mutableListOf<Any>()

        var currentLocalIndex = 0
        for (index in 0 until numLocal) {
            val variable = local[index]
            requireNotNull(variable) { "Unexpected local variable null" }
            localsFrame.add(variable)
            val convertedType = variable.convertToType()
            if (convertedType != null) {
                resultBuilder.add(convertedType to currentLocalIndex)
                currentLocalIndex += convertedType.indexShiftSize
            } else {
                currentLocalIndex++
            }
        }

        linesExpectedFromFrame.forEach {
            semiResult[it] = SemiResult(resultBuilder, localsFrame, labels.last(), isFirstLine, instructionCounter.instructionVisited)
            isFirstLine = false
        }

        linesExpectedFromFrame.clear()
    }

    override fun visitLineNumber(line: Int, start: Label) {

        super.visitLineNumber(line, start)
        val actuallyFirstLine = isFirstLine
        isFirstLine = false

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
                type.convertToType()?.let { it to index }
            }
            semiResult[line] = SemiResult(result, localsFrame, labels.last(), actuallyFirstLine, instructionCounter.instructionVisited)
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
                lineFilterSet: Set<Int>
        ): List<LocalVariableAnalyzeResult>? {

            val methodVisitor = LocalVariableAnalyzer(ownerTypeName, lineTranslator, lineFilterSet, InstructionCounteringVisitor())

            val classVisitor = SingleMethodVisitorForClass(methodName, ownerTypeName, methodVisitor)

            classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES)

            if (!classVisitor.visitSuccess) return null

            return methodVisitor.analyzeResult
        }
    }
}

