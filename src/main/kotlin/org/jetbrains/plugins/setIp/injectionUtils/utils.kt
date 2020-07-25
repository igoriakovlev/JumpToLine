package org.jetbrains.plugins.setIp.injectionUtils

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.setIp.CommonTypeResolver
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val logger = Logger.getInstance("SetIP Plugin")

internal class ReturnLikeException : Exception()

inline fun finishOnException(onFinish: () -> Unit, body: () -> Unit) {
    try {
        body()
    } catch (e: Exception) {
        onFinish()
        if (e !is ReturnLikeException) throw e
    }
}

internal fun <T> nullWithLog(message: String): T?
        = null.apply { logger.warn(message) }

internal fun unitWithLog(message: String): Unit
        = logger.warn(message)

internal fun returnByExceptionWithLog(message: String): Nothing {
    logger.warn(message)
    throw ReturnLikeException()
}

internal fun returnByExceptionNoLog(): Nothing {
    throw ReturnLikeException()
}

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
        dumpClass(file, classToRedefine.klass)
    }
}

internal inline fun <T> Boolean.onTrue(body: () -> T): T? = if (this) body() else null

internal fun Project.runSynchronouslyWithProgress(progressTitle: String, action: (() -> Unit) -> Unit) {
    ProgressManager.getInstance().run(object: Task.Modal(this, progressTitle, false) {
        override fun run(indicator: ProgressIndicator) {
            val semaphore = Semaphore(0)
            val release = { semaphore.release() }
            action(release)
            if (!semaphore.tryAcquire(15, TimeUnit.SECONDS)) {
                throw TimeoutException("SetIP timeout on $progressTitle")
            }
        }
    })
}

internal fun <T : Any> Project.runSynchronouslyWithProgress(progressTitle: String, canBeCanceled: Boolean, action: () -> T): T? {
    var result: T? = null
    ProgressManager.getInstance().run(object: Task.Modal(this, progressTitle, canBeCanceled) {
        override fun run(indicator: ProgressIndicator) {
            result = action()
        }
    })
    return result
}
