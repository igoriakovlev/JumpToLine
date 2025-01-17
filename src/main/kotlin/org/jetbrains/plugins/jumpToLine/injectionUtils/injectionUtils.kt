/*
 * Copyright 2020-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.jumpToLine.injectionUtils

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.ui.breakpoints.StackCapturingLineBreakpoint
import org.jetbrains.plugins.jumpToLine.CommonTypeResolver
import org.jetbrains.plugins.jumpToLine.LineTranslator
import org.jetbrains.plugins.jumpToLine.TypeResolveError
import org.objectweb.asm.*
import org.objectweb.asm.ClassReader.EXPAND_FRAMES


internal abstract class MethodVisitor9(visitor: MethodVisitor? = null) : MethodVisitor(Opcodes.ASM9, visitor)
internal abstract class ClassVisitor9(visitor: ClassVisitor? = null) : ClassVisitor(Opcodes.ASM9, visitor)
internal val EMPTY_METHOD_VISITOR = object : MethodVisitor9() {}

internal val String.dotSpacedName get() = replace('/', '.')
internal val String.slashSpacedName get() = replace('.', '/')

internal data class MethodName(val name: String, val signature: String, val genericSignature: String?)
internal fun MethodName.matches(name: String?, desc: String?, signature: String?): Boolean {
    if (this.name != name) return false
    if (this.signature == desc) return true
    if (this.signature == signature) return true
    if (this.genericSignature !== null) {
        if (this.genericSignature == desc) return true
        if (this.genericSignature == signature) return true
    }
    return false
}

internal fun DebugProcessImpl.suspendBreakpoints() {
    val breakpointManager = DebuggerManagerEx.getInstanceEx(project).breakpointManager
    breakpointManager.disableBreakpoints(this)
    StackCapturingLineBreakpoint.deleteAll(this)
}

internal fun DebugProcessImpl.resumeBreakpoints() {
    val breakpointManager = DebuggerManagerEx.getInstanceEx(project).breakpointManager
    breakpointManager.enableBreakpoints(this)
    StackCapturingLineBreakpoint.createAll(this)
}

internal fun getAvailableJumpLines(
        ownerTypeName: String,
        targetMethod: MethodName,
        lineTranslator: LineTranslator,
        klass: ByteArray,
        jumpFromJavaLine: Int,
        analyzeFirstLine: Boolean
): JumpAnalyzeResult? {

    val classReader = ClassReader(klass)

    val linesAnalyzeResult = LinesAnalyzer.analyze(
            classReader = classReader,
            methodName = targetMethod,
            ownerTypeName = ownerTypeName,
            lineTranslator = lineTranslator,
            jumpFromJavaLine = jumpFromJavaLine,
            addFirstLine = analyzeFirstLine
    ) ?: return null

//    val stackAnalyzerResult =
//            StackEmptyLocatorAnalyzer.analyze(classReader, targetMethod, ownerTypeName, lineTranslator, analyzeFirstLine)
//                    ?: return null

    return LocalVariableAnalyzer.analyze(
            classReader = classReader,
            methodName = targetMethod,
            ownerTypeName = ownerTypeName,
            linesAnalyzerResult = linesAnalyzeResult
    )
}

internal class ClassWriterWithTypeResolver(
        private val commonTypeResolver: CommonTypeResolver,
        classReader: ClassReader?,
        flags: Int
) : ClassWriter(classReader, flags) {
    override fun getCommonSuperClass(type1: String, type2: String): String {

        if (type1 == type2) return type1
        if (type1 == "java/lang/Object") return type2
        if (type2 == "java/lang/Object") return type1

        return commonTypeResolver
                .tryGetCommonType(type1.dotSpacedName, type2.dotSpacedName)
                .slashSpacedName
    }
}

internal class UpdateClassWithGotoLinePrefixResult(
        val klass: ByteArray,
        val jumpSwitchBytecodeOffset: Long,
        val jumpTargetBytecodeOffset: Long
)

internal fun updateClassWithGotoLinePrefix(
        jumpAnalyzeTarget: JumpAnalyzeTarget,
        jumpAnalyzeAdditionalInfo: JumpAnalyzeAdditionalInfo,
        targetMethod: MethodName,
        argumentsCount: Int,
        klass: ByteArray,
        commonTypeResolver: CommonTypeResolver
): UpdateClassWithGotoLinePrefixResult? {

    val classReaderToWrite = ClassReader(klass)
    val writer = ClassWriterWithTypeResolver(
            commonTypeResolver = commonTypeResolver,
            classReader = classReaderToWrite,
            flags = ClassWriter.COMPUTE_MAXS
    )

    val transformer = Transformer(
            jumpAnalyzeTarget = jumpAnalyzeTarget,
            jumpAnalyzeAdditionalInfo = jumpAnalyzeAdditionalInfo,
            methodName = targetMethod,
            argumentsCount = argumentsCount,
            visitor = writer
    )

    try {
        classReaderToWrite.accept(transformer, EXPAND_FRAMES)
    } catch(_: TypeResolveError) {
        return null
    }

    return transformer.transformationSuccess.onTrue {
        UpdateClassWithGotoLinePrefixResult(
            klass = writer.toByteArray(),
            jumpSwitchBytecodeOffset = transformer.jumpSwitchBytecodeOffset,
            jumpTargetBytecodeOffset = transformer.jumpTargetBytecodeOffset
        )
    }
}