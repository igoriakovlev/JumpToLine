/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.setIp.injectionUtils

import org.jetbrains.org.objectweb.asm.*
import org.jetbrains.org.objectweb.asm.ClassReader.EXPAND_FRAMES
import org.jetbrains.org.objectweb.asm.ClassReader.SKIP_FRAMES
import org.jetbrains.plugins.setIp.CommonTypeResolver
import org.jetbrains.plugins.setIp.LineTranslator
import org.jetbrains.plugins.setIp.TypeResolveError

internal abstract class MethodVisitor6 : MethodVisitor(Opcodes.ASM6)
internal abstract class ClassVisitor6 : ClassVisitor(Opcodes.ASM6)
internal val EMPTY_METHOD_VISITOR = object : MethodVisitor6() {}

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

internal fun getAvailableGotoLines(
        ownerTypeName: String,
        targetMethod: MethodName,
        lineTranslator: LineTranslator?,
        klass: ByteArray
): List<LocalVariableAnalyzeResult>? {
    //sss()
    val classReader = ClassReader(klass)

    val stackAnalyzerResult =
            StackEmptyLocatorAnalyzer.analyze(classReader, targetMethod, ownerTypeName, lineTranslator)
                    ?: return null

    return LocalVariableAnalyzer.analyze(
            classReader = classReader,
            methodName = targetMethod,
            ownerTypeName = ownerTypeName,
            lineTranslator = lineTranslator,
            lineFilterSet = stackAnalyzerResult
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

internal fun updateClassWithGotoLinePrefix(
        targetLineInfo: LocalVariableAnalyzeResult,
        targetMethod: MethodName,
        argumentsCount: Int,
        klass: ByteArray,
        commonTypeResolver: CommonTypeResolver
): ByteArray? {

    val classReaderToWrite = ClassReader(klass)
    val writer = ClassWriterWithTypeResolver(
            commonTypeResolver = commonTypeResolver,
            classReader = classReaderToWrite,
            flags = ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS
    )

    val transformer = Transformer(
            methodName = targetMethod,
            line = targetLineInfo.javaLine,
            locals = targetLineInfo.locals,
            argumentsCount = argumentsCount,
            visitor = writer
    )

    try {
        classReaderToWrite.accept(transformer, SKIP_FRAMES)
    } catch(e: TypeResolveError) {
        return null
    }


    return if (transformer.transformationSuccess) writer.toByteArray() else null
}