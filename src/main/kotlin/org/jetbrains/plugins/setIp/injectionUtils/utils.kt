package org.jetbrains.plugins.setIp.injectionUtils

import org.jetbrains.org.objectweb.asm.ClassReader
import java.io.*
import java.nio.file.Files
import com.intellij.openapi.diagnostic.Logger

private val logger = Logger.getInstance("SetIP Plugin")

internal fun falseWithLog( message: String): Boolean
        = false.apply { logger.warn(message) }

internal fun <T> nullWithLog(message: String): T?
        = null.apply { logger.warn(message) }

internal fun unitWithLog(message: String): Unit
        = logger.warn(message)

internal fun Throwable.logException(): Unit
        = logger.error(this)


@Suppress("UNREACHABLE_CODE")
internal fun dumpClass(originalClass: ByteArray, pathedClass: ByteArray) {
    //return
    FileOutputStream("C:\\AAA\\Original.class").use {
        it.write(originalClass)
    }
    FileOutputStream("C:\\AAA\\Pathed.class").use {
        it.write(pathedClass)
    }
}

fun sss() {
    val klass = Files.readAllBytes(File("C:\\AAA\\LockBasedStorageManager\$MapBasedMemoizedFunctionToNotNull.class").toPath())
    val classReaderToWrite = ClassReader(klass)
    val name = MethodName("invoke", "(Ljava/lang/Object;)Ljava/lang/Object;", "(TK;)TV;")
    val type = "org/jetbrains/kotlin/storage/LockBasedStorageManager\$MapBasedMemoizedFunction"
    val stackCalculator = StackEmptyLinesAnalyzer(type, name, null)
    classReaderToWrite.accept(stackCalculator, ClassReader.EXPAND_FRAMES)

    val stackLines = stackCalculator.validLines ?: return

    val localCalculator = LocalVariableAnalyzer(type, name, null, stackLines)
    classReaderToWrite.accept(localCalculator, ClassReader.EXPAND_FRAMES)

}