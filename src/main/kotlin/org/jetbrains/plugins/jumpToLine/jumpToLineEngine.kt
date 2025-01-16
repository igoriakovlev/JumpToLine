/*
 * Copyright 2020-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.jumpToLine

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebuggerManagerThreadImpl
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.engine.events.DebuggerContextCommandImpl
import com.intellij.debugger.impl.DebuggerContextImpl
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.sun.jdi.ClassType
import com.sun.jdi.Location
import com.sun.jdi.Method
import org.jetbrains.plugins.jumpToLine.fus.FUSLogger
import org.jetbrains.plugins.jumpToLine.fus.FUSLogger.JumpToLineEvent.*
import org.jetbrains.plugins.jumpToLine.injectionUtils.*

private fun <T> runInDebuggerThread(session: DebuggerSession, body: () -> T?): T? {
    var result: T? = null
    session.process.invokeInManagerThread {
        try {
            result = body()
        }
        catch(e : Exception) {
            e.logException();
        }
    }
    return result
}


internal val Method.methodName get() = MethodName(name(), signature(), genericSignature())

internal fun checkCanJump(session: DebuggerSession) =
        runInDebuggerThread(session) { checkCanJumpImpl(session) } ?: false to UNKNOWN_ERROR0

private const val NOT_SUSPENDED = "Process is not suspended"
private const val MAIN_FUNCTION_CALL = "Jump to line is not available for the main function"
private const val TOP_FRAME_NOT_SELECTED = "Jump to line is available for top frame only"
private const val COROUTINE_SUSPECTED = "Jump to line is not available for Kotlin coroutine"
private const val METHOD_IS_HAVE_NOT_DEBUG_INFO_OR_NATIVE = "Jump to line is not available for methods without debug information"
private const val AVAILABLE = "Drag and drop the arrow to set an execution point"
private const val NOT_ALL_THREADS_ARE_SUSPENDED = "Jump to line is available only when all the threads are suspended"
private const val UNKNOWN_ERROR0 = "Unexpected jump error (#0)"
private const val UNKNOWN_ERROR1 = "Unexpected jump error (#1)"
private const val UNKNOWN_ERROR2 = "Unexpected jump error (#2)"
private const val UNKNOWN_ERROR3 = "Unexpected jump error (#3)"

private val coroutineRegex = "\\(Lkotlin/coroutines/Continuation;.*?\\)Ljava/lang/Object;".toRegex()

private fun checkCanJumpImpl(session: DebuggerSession): Pair<Boolean, String> {
    val process = session.process
    val xSession = session.xDebugSession as? XDebugSessionImpl ?: return false to UNKNOWN_ERROR3

    if (!xSession.isSuspended)
        return false to NOT_SUSPENDED

    if (!xSession.isTopFrameSelected) return false to TOP_FRAME_NOT_SELECTED

    if (!process.virtualMachineProxy.isSuspended)
        return false to NOT_SUSPENDED

    val context = process.debuggerContext

    if (context.suspendContext?.suspendPolicy != 2) return false to NOT_ALL_THREADS_ARE_SUSPENDED

    val threadProxy = context.threadProxy ?: return false to UNKNOWN_ERROR1

    if (!threadProxy.isSuspended)
        return false to NOT_SUSPENDED

    if (threadProxy.frameCount() < 2) return false to MAIN_FUNCTION_CALL

    val method = threadProxy.frame(0)?.location()?.method() ?: return false to UNKNOWN_ERROR2

    if (method.signature().matches(coroutineRegex)) return false to COROUTINE_SUSPECTED

    if (method.name() == "invokeSuspend" && method.signature() == "(Ljava/lang/Object;)Ljava/lang/Object;")
        return false to COROUTINE_SUSPECTED

    if (method.location().lineNumber("Java") == -1) return false to METHOD_IS_HAVE_NOT_DEBUG_INFO_OR_NATIVE

    return true to AVAILABLE
}


private fun checkIsTopMethodRecursive(location: Location, threadProxy: ThreadReferenceProxyImpl): Boolean {

    fun Location.stringifyMethod(): String {
        val type = declaringType()
        val method = method()
        return "${type.name()}.${method.name()}.${method.signature()}.${method.genericSignature()}"
    }

    val topMethodName = location.stringifyMethod()

    return threadProxy.threadGroupProxy().threads().sumOf { thread ->
        thread.frames().count { frame -> topMethodName == frame.location().stringifyMethod()  }
    } > 1
}

internal sealed class GetLinesToJumpResult
internal class JumpLinesInfo(val jumpAnalyzeResult: JumpAnalyzeResult?, val classFile: ByteArray?, val linesToGoto: List<LineInfo>, val firstLine: LineInfo?) : GetLinesToJumpResult()
internal object UnknownErrorResult : GetLinesToJumpResult()

internal fun tryGetLinesToJump(session: DebuggerSession, onFinish: (GetLinesToJumpResult?) -> Unit) =
        runInDebuggerThread(session) { tryGetLinesToJumpImpl(session, onFinish) }

private class StrataViaLocationTranslator(
        currentLocation: Location,
        private val actualStratum: String
): LineTranslator {

    private val sourceNameJava = currentLocation.sourceName("Java")
    private val sourceNameStratum = currentLocation.sourceName(actualStratum)
    private val method = currentLocation.method()

    override fun translate(line: Int): Int? {
        return method
                .locationsOfLine("Java", sourceNameJava, line)
                .firstOrNull()
                ?.takeIf { it.sourceName(actualStratum) == sourceNameStratum }
                ?.lineNumber(actualStratum)
    }
}

private fun tryGetLinesToJumpImpl(session: DebuggerSession, onFinish: (GetLinesToJumpResult?) -> Unit) {
    val process = session.process

    if (!process.virtualMachineProxy.isSuspended) return onFinish(nullWithLog("Process is not suspended"))

    val context = process.debuggerContext

    val threadProxy = context.threadProxy ?: return onFinish(nullWithLog("Cannot get threadProxy"))
    if (threadProxy.frameCount() < 2) return onFinish(nullWithLog("frameCount < 2"))

    val frame = context.frameProxy ?: return onFinish(nullWithLog("Cannot get frameProxy"))

    val location = frame.location()
    val method = location.method()
    val classType = location.declaringType() as? ClassType ?: return onFinish(nullWithLog("Invalid type to jump"))

    val jumpFromLine = location.lineNumber("Java")
    if (jumpFromLine == -1) return onFinish(nullWithLog("Invalid line to jump -1"))

    //create line translator for Kotlin if able
    val lineTranslator = classType.availableStrata()?.let {
        when {
            it.contains("Kotlin") -> {
                StrataViaLocationTranslator(location, "Kotlin")
            }
            it.contains("KotlinDebug") -> {
                StrataViaLocationTranslator(location, "KotlinDebug")
            }
            else -> null
        }
    } ?: LineTranslator.DEFAULT

    val javaLines = method.allLineLocations()
            .map { it.lineNumber("Java") }
            .distinct()

    val linesToGoto = javaLines.mapNotNull { javaLine ->
                lineTranslator.translate(javaLine)?.let {
                    LineInfo(javaLine, it)
                }
            }

    val firstLine = javaLines.minOrNull()?.let { firstJavaLine ->
        linesToGoto.firstOrNull { it.javaLine == firstJavaLine }
    }

    val jumpOnLineAvailable = !method.isConstructor &&
            !checkIsTopMethodRecursive(location, threadProxy) &&
            process.isEvaluationPossible

    if (!jumpOnLineAvailable) {
        return onFinish(JumpLinesInfo(null, null, linesToGoto, firstLine))
    }

    tryGetTypeByteCodeByEvaluate(process, classType) { klass ->
    //tryGetTypeByteCode(process, threadProxy.threadReference, classType) { klass ->
        if (klass == null) {
            unitWithLog("Cannot get class file for ${classType.name()}")
            onFinish(JumpLinesInfo(null, null, linesToGoto, firstLine))
        } else {
            val analyzeResult = getAvailableJumpLines(
                    ownerTypeName = classType.name(),
                    targetMethod = method.methodName,
                    lineTranslator = lineTranslator,
                    klass = klass,
                    jumpFromJavaLine = jumpFromLine,
                    analyzeFirstLine = firstLine == null //When first line is not known for fast jump maybe it can be available for binary jump
            ) ?: nullWithLog<JumpAnalyzeResult>("Cannot get available goto lines")
            onFinish(JumpLinesInfo(analyzeResult, klass, linesToGoto, firstLine))
        }
    }
}

private fun loggedOnFinish(
        unLoggedFinish: () -> Unit,
        event: FUSLogger.JumpToLineEvent
): (Boolean) -> Unit = { success: Boolean ->
    FUSLogger.log(
        event = event,
        status = if (success) FUSLogger.JumpToLineStatus.Success else FUSLogger.JumpToLineStatus.Failed
    )
    unLoggedFinish()
}

internal fun tryJumpToSelectedLine(
        session: DebuggerSession,
        jumpAnalyzeTarget: JumpAnalyzeTarget,
        jumpAnalyzeAdditionalInfo: JumpAnalyzeAdditionalInfo,
        classFile: ByteArray?,
        commonTypeResolver: CommonTypeResolver,
        onFinish: () -> Unit
) {
    val command = object : DebuggerContextCommandImpl(session.contextManager.context) {
        override fun threadAction(suspendContext: SuspendContextImpl) {

            val loggedFinish = loggedOnFinish(
                    unLoggedFinish = onFinish,
                    event = if (jumpAnalyzeTarget.safeStatus != LineSafetyStatus.NotSafe) JumpToGreenLine else JumpToYellowLine
            )

            finishOnException(loggedFinish) {
                tryJumpToSelectedLineImpl(
                        session = session,
                        classFile = classFile,
                        jumpAnalyzeTarget = jumpAnalyzeTarget,
                        jumpAnalyzeAdditionalInfo = jumpAnalyzeAdditionalInfo,
                        commonTypeResolver = commonTypeResolver,
                        onFinish = loggedFinish
                )
            }
        }
    }
    session.process.managerThread.schedule(command)
}

internal fun tryJumpToByGoto(
    session: DebuggerSession,
    line: Int,
    onFinish: () -> Unit
) {
    val command = object : DebuggerContextCommandImpl(session.contextManager.context) {
        override fun threadAction(suspendContext: SuspendContextImpl) {
            val loggedFinish = loggedOnFinish(
                    unLoggedFinish = onFinish,
                    event = GoToLine
            )

            finishOnException(loggedFinish) {
                jumpByRunToLineImpl(
                        session = session,
                        suspendContext = suspendContext,
                        line = line,
                        onFinish = loggedFinish
                )
            }
        }
    }
    session.process.managerThread.schedule(command)
}

private fun jumpByRunToLineImpl(
        session: DebuggerSession,
        suspendContext: SuspendContextImpl,
        line: Int,
        onFinish: (Boolean) -> Unit
) {

    val process = session.process

    val context = process.debuggerContext
    val threadProxy = context.threadProxy ?: returnByExceptionWithLog("Cannot get threadProxy")

    if (!threadProxy.isSuspended) returnByExceptionWithLog("Calling jump on unsuspended thread")

    if (threadProxy.frameCount() < 2) returnByExceptionWithLog("frameCount < 2")

    jumpByRunToLine(
            process = process,
            suspendContext = suspendContext,
            threadProxy = threadProxy,
            line = line,
            onFinish = onFinish
    )

}

private fun tryJumpToSelectedLineImpl(
        session: DebuggerSession,
        jumpAnalyzeTarget: JumpAnalyzeTarget,
        jumpAnalyzeAdditionalInfo: JumpAnalyzeAdditionalInfo,
        classFile: ByteArray?,
        commonTypeResolver: CommonTypeResolver,
        onFinish: (Boolean) -> Unit
) {
    val process = session.process

    val context = process.debuggerContext
    val threadProxy = context.threadProxy ?: returnByExceptionWithLog("Cannot get threadProxy")

    if (!threadProxy.isSuspended) returnByExceptionWithLog("Calling jump on unsuspended thread")

    if (threadProxy.frameCount() < 2) returnByExceptionWithLog("frameCount < 2")

    val frame = context.frameProxy ?: returnByExceptionWithLog("Cannot get frameProxy")

    val location = frame.location()

    val classType = location.declaringType() as? ClassType ?: returnByExceptionWithLog("Invalid location type")

    val checkedClassFile = classFile ?: returnByExceptionWithLog("Cannot jump to not-first-line without class file")

    debuggerJump(
            jumpAnalyzeTarget = jumpAnalyzeTarget,
            jumpAnalyzeAdditionalInfo = jumpAnalyzeAdditionalInfo,
            declaredType = classType,
            originalClassFile = checkedClassFile,
            threadProxy = threadProxy,
            commonTypeResolver = commonTypeResolver,
            process = process,
            onFinish = onFinish
    )
}

private fun <T : Any> DebugProcessImpl.invokeInManagerThread(f: (DebuggerContextImpl) -> T?): T? {
    var result: T? = null
    val command: DebuggerCommandImpl = object : DebuggerCommandImpl() {
        override fun action() {
            result = f(debuggerContext)
        }
    }

    when {
        DebuggerManagerThreadImpl.isManagerThread() ->
            managerThread.invoke(command)
        else ->
            managerThread.invokeAndWait(command)
    }

    return result
}
