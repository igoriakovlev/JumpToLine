/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.setIp

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebuggerManagerThreadImpl
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.engine.events.DebuggerContextCommandImpl
import com.intellij.debugger.impl.DebuggerContextImpl
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.debugger.ui.breakpoints.StackCapturingLineBreakpoint
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.sun.jdi.ClassType
import com.sun.jdi.Location
import com.sun.jdi.Method
import com.sun.jdi.request.EventRequest
import org.jetbrains.plugins.setIp.injectionUtils.*

private fun <T> runInDebuggerThread(session: DebuggerSession, body: () -> T): T {
    var result: T? = null
    var exception: Exception? = null
    session.process.invokeInManagerThread {
        try {
            result = body()
        }catch(e : Exception) {
            exception = e
        }
    }

    return exception?.let {
        it.logException()
        throw it
    } ?: result!!
}

internal val Method.methodName get() = MethodName(name(), signature(), genericSignature())

internal fun checkCanJump(session: DebuggerSession, xsession: XDebugSessionImpl) = runInDebuggerThread(session) {
    checkCanJumpImpl(session, xsession)
}

private const val NOT_SUSPENDED = "Debugger session is not suspended"
private const val SOME_ERROR = "Not available in current for some reason :("
private const val MAIN_FUNCTION_CALL = "SetIP is not available for main function call"
private const val TOP_FRAME_NOT_SELECTED = "SetIP is not available for non top frames"
private const val COROUTINE_SUSPECTED = "SetIP for Kotlin coroutines is not supported"
private const val AVAILABLE = "Grab to change execution position"
private const val NOT_ALL_THREADS_ARE_SUSPENDED = "Available only when all threads are suspended"

private val coroutineRegex = "\\(Lkotlin/coroutines/Continuation;.*?\\)Ljava/lang/Object;".toRegex()

private fun checkCanJumpImpl(session: DebuggerSession, xsession: XDebugSessionImpl): Pair<Boolean, String> {
    val process = session.process

    if (!xsession.isSuspended) return false to NOT_SUSPENDED

    if (!xsession.isTopFrameSelected) return false to TOP_FRAME_NOT_SELECTED

    if (process.suspendManager.pausedContext.suspendPolicy != EventRequest.SUSPEND_ALL)
        return false to NOT_ALL_THREADS_ARE_SUSPENDED

    if (!process.virtualMachineProxy.isSuspended) return false to NOT_SUSPENDED

    val context = process.debuggerContext
    val threadProxy = context.threadProxy ?: return false to SOME_ERROR

    if (!threadProxy.isSuspended) return false to NOT_SUSPENDED

    if (threadProxy.frameCount() < 2) return false to MAIN_FUNCTION_CALL

    val method = threadProxy.frame(0)?.location()?.method() ?: return false to SOME_ERROR

    if (method.signature().matches(coroutineRegex)) return false to COROUTINE_SUSPECTED

    if (method.name() == "invokeSuspend" && method.signature() == "(Ljava/lang/Object;)Ljava/lang/Object;")
        return false to COROUTINE_SUSPECTED

    return true to AVAILABLE
}


private fun checkIsTopMethodRecursive(location: Location, threadProxy: ThreadReferenceProxyImpl): Boolean {

    fun Location.stringifyMethod(): String {
        val type = declaringType()
        val method = method()
        return "${type.name()}.${method.name()}.${method.signature()}.${method.genericSignature()}"
    }

    val topMethodName = location.stringifyMethod()

    return threadProxy.threadGroupProxy().threads().sumBy { thread ->
        thread.frames().count { frame -> topMethodName == frame.location().stringifyMethod()  }
    } > 1
}

internal sealed class GetLinesToJumpResult
internal class JumpLinesInfo(val linesToJump: List<LocalVariableAnalyzeResult>, val classFile: ByteArray?) : GetLinesToJumpResult()
internal object ClassNotFoundErrorResult : GetLinesToJumpResult()
internal object UnknownErrorResult : GetLinesToJumpResult()

internal fun tryGetLinesToJump(
        session: DebuggerSession
): GetLinesToJumpResult = runInDebuggerThread(session) {
    tryGetLinesToJumpImpl(session) ?: UnknownErrorResult
}

private class StrataViaLocationTranslator(
        currentLocation: Location,
        private val actualStratum: String
): LineTranslator {

    private val sourceNameJava = currentLocation.sourceName("Java")
    private val method = currentLocation.method()

    override fun translate(line: Int): Int? {
        return method
                .locationsOfLine("Java", sourceNameJava, line)
                .firstOrNull()
                ?.lineNumber(actualStratum)
    }
}

private fun tryGetLinesToJumpImpl(session: DebuggerSession): GetLinesToJumpResult? {

    val process = session.process

    if (process.suspendManager.pausedContext.suspendPolicy != EventRequest.SUSPEND_ALL)
        return nullWithLog("Available only when all threads are suspended")

    if (!process.virtualMachineProxy.isSuspended) return nullWithLog("Process is not suspended")

    val context = process.debuggerContext
    val threadProxy = context.threadProxy ?: return nullWithLog("Cannot get threadProxy")
    if (threadProxy.frameCount() < 2) return nullWithLog("frameCount < 2")

    val frame = context.frameProxy ?: return nullWithLog("Cannot get frameProxy")

    val location = frame.location()
    val method = location.method()
    val classType = location.declaringType() as? ClassType ?: return nullWithLog("Invalid type to jump")

    //create line translator for Kotlin if able
    val lineTranslator = classType.availableStrata()?.let {
        if (it.size == 2 && it.contains("Kotlin")) StrataViaLocationTranslator(location, "Kotlin") else null
    }

    val isCtorOrRecursive = method.isConstructor || checkIsTopMethodRecursive(location, threadProxy)
    if (isCtorOrRecursive) {
        val javaLine = method.allLineLocations()
                .map { it.lineNumber("Java") }
                .min()
                ?: return UnknownErrorResult

        val translatedLine = lineTranslator?.translate(javaLine) ?: javaLine

        val firstLineAnalyze = LocalVariableAnalyzeResult(
                javaLine = javaLine,
                sourceLine = translatedLine,
                locals = emptyList(),
                isSafeLine = true,
                isFirstLine = true,
                methodLocalsCount = 0
        )
        return JumpLinesInfo(listOf(firstLineAnalyze), classFile = null)
    }

    val classFile = threadProxy.threadReference.tryGetTypeByteCode(classType)
    classFile ?: run {
        unitWithLog("Cannot get class file for ${classType.name()}")
        return ClassNotFoundErrorResult
    }

    val availableLines = getAvailableGotoLines(
            ownerTypeName = classType.name(),
            targetMethod = method.methodName,
            lineTranslator = lineTranslator,
            klass = classFile
    ) ?: return nullWithLog("Cannot get available goto lines")

    return JumpLinesInfo(availableLines, classFile)
}

internal fun tryJumpToSelectedLine(
    session: DebuggerSession,
    targetLineInfo: LocalVariableAnalyzeResult,
    classFile: ByteArray?,
    commonTypeResolver: CommonTypeResolver
) {
    val command = object : DebuggerContextCommandImpl(session.contextManager.context) {
        override fun threadAction(suspendContext: SuspendContextImpl) {
            tryJumpToSelectedLineImpl(
                    session = session,
                    classFile = classFile,
                    targetLineInfo = targetLineInfo,
                    commonTypeResolver = commonTypeResolver,
                    suspendContext = suspendContext
            )
        }
    }
    session.process.managerThread.schedule(command)
}

private fun tryJumpToSelectedLineImpl(
    session: DebuggerSession,
    targetLineInfo: LocalVariableAnalyzeResult,
    classFile: ByteArray?,
    commonTypeResolver: CommonTypeResolver,
    suspendContext: SuspendContextImpl
) {
    val process = session.process

    val context = process.debuggerContext
    val threadProxy = context.threadProxy ?: return unitWithLog("Cannot get threadProxy")

    if (!threadProxy.isSuspended) return unitWithLog("Calling jump on unsuspended thread")

    if (threadProxy.frameCount() < 2) return unitWithLog("frameCount < 2")

    val frame = context.frameProxy ?: return unitWithLog("Cannot get frameProxy")

    val location = frame.location()

    if (location.lineNumber("Java") == targetLineInfo.javaLine) return unitWithLog("Current line selected")

    val classType = location.declaringType() as? ClassType ?: return unitWithLog("Invalid location type")

    if (targetLineInfo.isFirstLine) {
        jumpByFrameDrop(
                process = process,
                suspendContext = suspendContext
        )
    } else {
        val checkedClassFile = classFile ?: return unitWithLog("Cannot jump to not-first-line without class file")
        debuggerJump(
                targetLineInfo = targetLineInfo,
                declaredType = classType,
                originalClassFile = checkedClassFile,
                threadProxy = threadProxy,
                commonTypeResolver = commonTypeResolver,
                process = process,
                suspendContext = suspendContext
        )
    }
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
