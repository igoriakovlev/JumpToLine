package org.jetbrains.plugins.setIp.injectionUtils

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.AnalyzerAdapter

internal abstract class SingleMethodAnalyzer : MethodVisitor6() {
    abstract var analyzerAdapter: AnalyzerAdapter?
}

internal class SingleMethodVisitorForClass(
        private val methodName: MethodName,
        private val ownerTypeName: String,
        private val methodVisitor: SingleMethodAnalyzer)
    : ClassVisitor6() {

    private var methodVisited = false
    private var methodVisitedTwice = false

    val visitSuccess get() = methodVisited && !methodVisitedTwice

    override fun visitMethod(
            access: Int,
            name: String?,
            desc: String?,
            signature: String?,
            exceptions: Array<out String>?
    ): MethodVisitor {

        return if (methodName.matches(name, desc, signature)) {
            if (methodVisited) {
                methodVisitedTwice = true
                EMPTY_METHOD_VISITOR
            } else {
                methodVisited = true
                methodVisitor.let {
                    AnalyzerAdapter(ownerTypeName, access, name, desc, it).apply {
                        it.analyzerAdapter = this
                    }
                }
            }
        } else EMPTY_METHOD_VISITOR
    }
}