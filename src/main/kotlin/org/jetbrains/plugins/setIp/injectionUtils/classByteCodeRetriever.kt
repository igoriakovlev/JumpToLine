package org.jetbrains.plugins.setIp.injectionUtils

import com.sun.jdi.*

private fun ThreadReference.invokeInstance(
        obj: ObjectReference,
        klass: ClassType,
        name: String,
        signature: String,
        parameters: List<Value>
): Value? {
    val method = klass.concreteMethodByName(name, signature) ?: return null
    return obj.invokeMethod(this, method, parameters, 0)
}

private fun ThreadReference.invokeStatic(
        klass: ClassType,
        name: String,
        signature: String,
        parameters: List<Value>
) = klass.invokeMethod(this, klass.concreteMethodByName(name, signature), parameters, 0)

fun ThreadReference.tryGetTypeByteCode(targetType: ClassType): ByteArray? = try {
    tryGetTypeByteCodeImpl(targetType)
} catch (e: Exception) { null }

private fun ThreadReference.tryGetTypeByteCodeImpl(targetType: ClassType): ByteArray? {

    val virtualMachine = virtualMachine()

    // java.lang.Class
    val classType = virtualMachine.classesByName("java.lang.Class")?.let {
        if (it.size != 0) it[0] as? ClassType else null
    } ?: return null

    fun getClassForName(className: String): ClassType? {
        val forNameMethod = classType.concreteMethodByName("forName", "(Ljava/lang/String;)Ljava/lang/Class;")
        val loadedClass = classType.invokeMethod(this, forNameMethod, listOf(virtualMachine.mirrorOf(className)), 0) as ClassObjectReference
        return loadedClass.reflectedType() as? ClassType
    }

    // java.lang.reflect.Array
    val arrayType = getClassForName("java.lang.reflect.Array") ?: return null
    //java.lang.InputStream
    val inputStreamType = getClassForName("java.io.InputStream") ?: return null
    // byte
    val byteClass = getClassForName("java.lang.Byte") ?: return null
    val byteType = run {
        val typeField = byteClass.fieldByName("TYPE")
        val typeFieldValue = byteClass.getValue(typeField) as? ClassObjectReference
        typeFieldValue?.reflectedType() as? ClassType
    } ?: return null

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
    ) as? ObjectReference ?: return null

    val available = invokeInstance(
            obj = inputStream,
            klass = inputStreamType,
            name = "available",
            signature = "()I",
            parameters = emptyList()
    ) as? IntegerValue ?: return null

    val arraySize = available.value()

    val array = createArray(byteType, arraySize) ?: return null

    val read = invokeInstance(
            obj = inputStream,
            klass = inputStreamType,
            name = "read",
            signature = "([BII)I",
            parameters = listOf(array, virtualMachine.mirrorOf(0), available)
    ) as? IntegerValue ?: return null

    if (arraySize != read.value()) return null

    //maybe we should call available one again to check it is empty

    return array.values.map { (it as ByteValue).value() }.toByteArray()
}
