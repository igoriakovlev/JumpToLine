/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.setIp.injectionUtils

import org.jetbrains.org.objectweb.asm.*
import org.jetbrains.org.objectweb.asm.ClassReader.EXPAND_FRAMES
import org.jetbrains.org.objectweb.asm.ClassReader.SKIP_FRAMES
import org.jetbrains.plugins.setIp.CommonTypeResolver
import org.jetbrains.plugins.setIp.parseKotlinSMAP

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

internal fun getAvailableGotoLines(ownerTypeName: String, targetMethod: MethodName, klass: ByteArray): Pair<Set<Int>, String?>? {
    //sss()
    val classReader = ClassReader(klass)
    val analyzer = StackEmptyLinesAnalyzer(ownerTypeName.slashSpacedName, targetMethod)
    classReader.accept(analyzer, EXPAND_FRAMES)
    return analyzer.validLines?.let { it to analyzer.sourceDebugLine }
}

data class ClassAndFirstLine(val klass: ByteArray, val stopLineNumber: Int, val localsFound: Int)

internal class ClassWriterWithTypeResolver(
        private val commonTypeResolver: CommonTypeResolver,
        classReader: ClassReader?,
        flags: Int
) : ClassWriter(classReader, flags) {
    override fun getCommonSuperClass(type1: String, type2: String): String {

        if (type1 == type2) return type1
        if (type1 == "java/lang/Object") return type2
        if (type2 == "java/lang/Object") return type1

        val type1FqName = type1.dotSpacedName
        val type2FqName = type2.dotSpacedName

        return commonTypeResolver.tryGetCommonType(type1FqName, type2FqName)
                ?.slashSpacedName
                ?: "java/lang/Object"
    }
}

internal fun updateClassWithGotoLinePrefix(
        ownerTypeName: String,
        targetMethod: MethodName,
        isInstanceMethod: Boolean,
        klass: ByteArray,
        line: Int,
        commonTypeResolver: CommonTypeResolver
): ClassAndFirstLine? {

    val classReaderToWrite = ClassReader(klass)
    val writer = ClassWriterWithTypeResolver(
            commonTypeResolver = commonTypeResolver,
            classReader = classReaderToWrite,
            flags = ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS
    )

    val localCalculator = LocalVariableAnalyzer(ownerTypeName.slashSpacedName, targetMethod, line)
    classReaderToWrite.accept(localCalculator, EXPAND_FRAMES)

    val localsFound =
            localCalculator.defaultValueAndName ?: return nullWithLog("Cannot get locals")

    val transformer = Transformer(
            methodName = targetMethod,
            line = line,
            locals = localsFound,
            isInstanceMethod = isInstanceMethod,
            visitor = writer
    )

    classReaderToWrite.accept(transformer, SKIP_FRAMES)

    return if (transformer.transformationSuccess) ClassAndFirstLine(writer.toByteArray(), transformer.stopLineNumber, localsFound.size) else null
}