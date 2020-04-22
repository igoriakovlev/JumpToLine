package org.jetbrains.plugins.setIp.injectionUtils

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.FileOutputStream

private val logger = Logger.getInstance("SetIP Plugin")

internal fun <T> nullWithLog(message: String): T?
        = null.apply { logger.warn(message) }

internal fun unitWithLog(message: String): Unit
        = logger.warn(message)

internal fun Throwable.logException(): Unit
        = logger.error(this)

internal fun dumpClass(originalClass: ByteArray, pathedClass: ByteArray) {
    val path = System.getProperty("set.ip.dump.class.path")
    if (path.isNullOrBlank() || !File(path).isDirectory) return

    FileOutputStream("$path\\Original.class").use {
        it.write(originalClass)
    }
    FileOutputStream("$path\\Patched.class").use {
        it.write(pathedClass)
    }
}