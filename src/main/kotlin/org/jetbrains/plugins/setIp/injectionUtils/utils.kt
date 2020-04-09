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
    FileOutputStream("C:\\AAA\\Patched.class").use {
        it.write(pathedClass)
    }
}