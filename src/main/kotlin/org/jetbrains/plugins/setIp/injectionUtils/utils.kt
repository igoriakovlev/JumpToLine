package org.jetbrains.plugins.setIp.injectionUtils

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.setIp.CommonTypeResolver
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

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

private fun instrument(project: Project) {

    val file = Files.readAllBytes(File("C:\\AAA\\KtUltraLightClass.class").toPath())
    val methodName = MethodName("ownMethods", "()Ljava/util/List;", null)

    val result = getAvailableJumpLines(
            ownerTypeName = "org/jetbrains/kotlin/asJava/classes/KtUltraLightClass",
            targetMethod = methodName,
            lineTranslator = null,
            klass = file,
            jumpFromLine = 0,
            analyzeFirstLine = false
    )

    val line = result?.firstOrNull { it.sourceLine == 270 } ?: return
    val argumentsCount = 1

    val classToRedefine = updateClassWithGotoLinePrefix(
            targetLineInfo = line,
            targetMethod = methodName,
            argumentsCount = argumentsCount,
            klass = file,
            commonTypeResolver = CommonTypeResolver(project)
    )

    if (classToRedefine !== null) {
        dumpClass(file, classToRedefine)
    }
}

internal inline fun <T> Boolean.onTrue(body: () -> T): T? = if (this) body() else null
