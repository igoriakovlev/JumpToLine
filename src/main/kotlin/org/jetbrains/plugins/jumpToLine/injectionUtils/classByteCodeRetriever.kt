package org.jetbrains.plugins.jumpToLine.injectionUtils

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.JavaStackFrame
import com.intellij.debugger.engine.JavaValue
import com.intellij.lang.Language
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import com.jetbrains.jdi.ArrayReferenceImpl
import com.sun.jdi.*
import com.sun.jdi.ObjectReference.INVOKE_SINGLE_THREADED
import java.util.*

private fun ThreadReference.invokeInstance(
        obj: ObjectReference,
        klass: ClassType,
        name: String,
        signature: String,
        parameters: List<Value>
): Value? {
    val method = klass.concreteMethodByName(name, signature) ?: return null
    return obj.invokeMethod(this, method, parameters, INVOKE_SINGLE_THREADED)
}

private fun ThreadReference.invokeStatic(
        klass: ClassType,
        name: String,
        signature: String,
        parameters: List<Value>
) = klass.invokeMethod(this, klass.concreteMethodByName(name, signature), parameters, INVOKE_SINGLE_THREADED)

internal fun tryGetTypeByteCode(
        process: DebugProcessImpl,
        threadReference: ThreadReference,
        targetType: ClassType,
        onFinish: (ByteArray?) -> Unit
) {
    val result = methodByteCodeCache.getOrPut(targetType) {
        process.suspendBreakpoints()
        try {
            threadReference.tryGetTypeByteCodeImpl(targetType)
        } catch (e: Exception) {
            null
        } finally {
            process.resumeBreakpoints()
        }
    }

    onFinish(result)
}


private val classTypeCache = WeakHashMap<VirtualMachine, MutableMap<String, ClassType?>>()
private val methodByteCodeCache = WeakHashMap<ClassType, ByteArray?>()

private fun ThreadReference.tryGetTypeByteCodeImpl(targetType: ClassType): ByteArray? {

    val virtualMachine = virtualMachine()
    val classTypeCacheValue = classTypeCache.getOrPut(virtualMachine) { mutableMapOf() }
    
    fun getClassForNameVMNoCache(className: String) =
        virtualMachine.classesByName(className)?.let {
            if (it.size != 0) it[0] as? ClassType else null
        }

    // java.lang.Class
    val classType = getClassForNameVMNoCache("java.lang.Class") ?:
        return nullWithLog("java.lang.Class!")

    fun getClassForName(className: String): ClassType? = classTypeCacheValue.getOrPut(className) {
        getClassForNameVMNoCache(className)?.let { return@getOrPut it }
        val forNameMethod = classType.concreteMethodByName("forName", "(Ljava/lang/String;)Ljava/lang/Class;")
        val loadedClass = classType.invokeMethod(this, forNameMethod, listOf(virtualMachine.mirrorOf(className)), INVOKE_SINGLE_THREADED) as ClassObjectReference
        loadedClass.reflectedType() as? ClassType
    }

    // java.lang.reflect.Array
    val arrayType = getClassForName("java.lang.reflect.Array") ?: return nullWithLog("getClassForName(java.lang.reflect.Array)!")
    //java.lang.InputStream
    val inputStreamType = getClassForName("java.io.InputStream") ?: return nullWithLog("getClassForName(java.io.InputStream)!")
    // byte
    val byteClass = getClassForName("java.lang.Byte") ?: return nullWithLog("getClassForName(java.lang.Byte)!")
    val byteType = run {
        val typeField = byteClass.fieldByName("TYPE")
        val typeFieldValue = byteClass.getValue(typeField) as? ClassObjectReference
        typeFieldValue?.reflectedType() as? ClassType
    } ?: return nullWithLog("byteType!")

    fun createArray(baseType: ClassType, length: Int)= invokeStatic(
                klass = arrayType,
                name = "newInstance",
                signature = "(Ljava/lang/Class;I)Ljava/lang/Object;",
                parameters = listOf(baseType.classObject(), virtualMachine.mirrorOf(length))
        ) as? ArrayReference

    //XXX.class.getResourceAsStream("XXX.class").read(new byte[SIZE], 0, SIZE)
    val className = targetType.name().takeLastWhile { it != '.' } + ".class"
    val inputStream = invokeInstance(
            obj = targetType.classObject(),
            klass = classType,
            name = "getResourceAsStream",
            signature = "(Ljava/lang/String;)Ljava/io/InputStream;",
            parameters = listOf(virtualMachine.mirrorOf(className))
    ) as? ObjectReference ?: return nullWithLog("getResourceAsStream!")

    val available = invokeInstance(
            obj = inputStream,
            klass = inputStreamType,
            name = "available",
            signature = "()I",
            parameters = emptyList()
    ) as? IntegerValue ?: return nullWithLog("available!")

    val arraySize = available.value()

    val array = createArray(byteType, arraySize) ?: return nullWithLog("createArray(byteType, arraySize)!")

    var readed = 0
    while(readed < arraySize) {

        val currentReaded = invokeInstance(
                obj = inputStream,
                klass = inputStreamType,
                name = "read",
                signature = "([BII)I",
                parameters = listOf(array, virtualMachine.mirrorOf(readed), virtualMachine.mirrorOf(arraySize - readed))
        ) as? IntegerValue ?: return nullWithLog("read returned invalid result type")

        val currentReadedValue = currentReaded.value()

        if (currentReadedValue < 1) break

        readed += currentReadedValue
    }

    if (arraySize != readed) return nullWithLog("arraySize != read.value()! with arraySize=$arraySize, read.value()=$readed")

    //maybe we should call available one again to check it is empty
    return array.values.map { (it as ByteValue).value() }.toByteArray()
}

internal fun tryGetTypeByteCodeByEvaluate(
        process: DebugProcessImpl,
        targetType: ClassType,
        onFinish: (ByteArray?) -> Unit) {

    methodByteCodeCache[targetType]?.run {
        return onFinish(this)
    }

    val language = Language.findLanguageByID("JAVA")
            ?: return onFinish(null)

    val evaluator = (process.session.xDebugSession?.currentStackFrame as? JavaStackFrame)
            ?.evaluator
            ?: return onFinish(null)

    val className = (targetType.name().takeLastWhile { it != '.' } + ".class")
    val typeName = targetType.name()

    val fragmentText = """
        java.io.InputStream stream = java.lang.Class.forName("$typeName").getResourceAsStream("$className");
        int size  = stream.available();
        byte[] result = new byte[size];
        int readed = 0;
        int newReaded = 1;
        while(readed < size && newReaded > 0) {
            newReaded = stream.read(result, readed, size - readed);
            readed += newReaded;
        }
        byte[] out = newReaded == readed ? result : null;
    """

    val expression = XExpressionImpl(fragmentText, language, null, EvaluationMode.CODE_FRAGMENT)

    evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
        override fun errorOccurred(errorMessage: String) {
            methodByteCodeCache[targetType] = nullWithLog("JumpToLine evaluator error $errorMessage")
            return onFinish(null)
        }
        override fun evaluated(result: XValue) {
            val array = (result as? JavaValue)?.descriptor?.value as? ArrayReferenceImpl
            if (array == null) {
                methodByteCodeCache[targetType] = nullWithLog("JumpToLine evaluator unexpected evaluation result")
                return onFinish(null)
            }

            val byteArray = array.values.map { (it as ByteValue).value() }.toByteArray()
            methodByteCodeCache[targetType] = byteArray
            return onFinish(byteArray)
        }
    }, null)
}