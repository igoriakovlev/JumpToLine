/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.setIp

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebuggerManagerThreadImpl
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.impl.DebuggerContextImpl
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.debugger.ui.breakpoints.StackCapturingLineBreakpoint
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.sun.jdi.Location
import com.sun.jdi.Method
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
private const val CANT_LOCATE_CLASS_FILE = "Could't locate compiled .class file"
private const val MAIN_FUNCTION_CALL = "SetIP is not available for main function call"
private const val TOP_FRAME_NOT_SELECTED = "SetIP is not available for non top frames"
private const val COROUTINE_SUSPECTED = "SetIP for Kotlin coroutines is not supported"
private const val CONSTRUCTORS_NOT_SUPPORTED = "SetIP does not supports for init"
private const val AVAILABLE = "Grab to change execution position"

private val coroutineRegex = "\\(Lkotlin/coroutines/Continuation;.*?\\)Ljava/lang/Object;".toRegex()

private fun checkCanJumpImpl(session: DebuggerSession, xsession: XDebugSessionImpl): Pair<Boolean, String> {
    val process = session.process

    if (!process.virtualMachineProxy.isSuspended) return false to NOT_SUSPENDED

    if (!xsession.isSuspended) return false to NOT_SUSPENDED

    if (!xsession.isTopFrameSelected) return false to TOP_FRAME_NOT_SELECTED

    val context = process.debuggerContext
    val threadProxy = context.threadProxy ?: return false to SOME_ERROR

    if (threadProxy.frameCount() < 2) return false to MAIN_FUNCTION_CALL

    val method = threadProxy.frame(0)?.location()?.method() ?: return false to SOME_ERROR

    if (method.isConstructor) return false to CONSTRUCTORS_NOT_SUPPORTED

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
internal class JumpLinesInfo(val linesToJump: List<LocalVariableAnalyzeResult>, val sourceDebugLine: String?, val classFile: ByteArray) : GetLinesToJumpResult()
internal object ClassNotFoundErrorResult : GetLinesToJumpResult()
internal object UnknownErrorResult : GetLinesToJumpResult()

internal fun tryGetLinesToJump(
        session: DebuggerSession,
        project: Project,
        file: VirtualFile,
        overridedFileContent: ByteArray? = null
): GetLinesToJumpResult = runInDebuggerThread(session) {
    tryGetLinesToJumpImpl(session, project, file, overridedFileContent) ?: UnknownErrorResult
}

private fun tryGetLinesToJumpImpl(
        session: DebuggerSession,
        project: Project,
        virtualFile: VirtualFile,
        overrideFileContent: ByteArray? = null
): GetLinesToJumpResult? {

    val process = session.process

    if (!process.virtualMachineProxy.isSuspended) return nullWithLog("Process is not suspended")

    val context = process.debuggerContext
    val threadProxy = context.threadProxy ?: return nullWithLog("Cannot get threadProxy")
    if (threadProxy.frameCount() < 2) return nullWithLog("frameCount < 2")

    val frame = context.frameProxy ?: return nullWithLog("Cannot get frameProxy")

    val location = frame.location()

    val classType = location.declaringType()

    val classFile = overrideFileContent ?: tryLocateClassFile(project, classType.name(), virtualFile)
    classFile ?: run {
        unitWithLog("Cannot get class file for ${classType.name()}")
        return ClassNotFoundErrorResult
    }

    val result = getAvailableGotoLines(
            ownerTypeName = classType.name(),
            targetMethod = location.method().methodName,
            klass = classFile
    ) ?: return nullWithLog("Cannot get available goto lines")

    val isRecursive = checkIsTopMethodRecursive(frame.location(), threadProxy)

    val availableLines =
            if (isRecursive) result.first.firstOrNull { it.isFirstLine }?.let { listOf(it) } else result.first

    availableLines?: return null

    return JumpLinesInfo(availableLines, result.second, classFile)
}

internal fun tryJumpToSelectedLine(
    session: DebuggerSession,
    project: Project,
    targetLineInfo: LocalVariableAnalyzeResult,
    classFile: ByteArray,
    commonTypeResolver: CommonTypeResolver
) = runInDebuggerThread(session) {
    session.process.let {

        val breakpointManager = DebuggerManagerEx.getInstanceEx(project).breakpointManager
        breakpointManager.disableBreakpoints(it)
        StackCapturingLineBreakpoint.deleteAll(it)

        val result = tryJumpToSelectedLineImpl(
                session = session,
                classFile = classFile,
                targetLineInfo = targetLineInfo,
                commonTypeResolver = commonTypeResolver
        )

        breakpointManager.enableBreakpoints(it)
        StackCapturingLineBreakpoint.createAll(it)

        result
    }
}

private fun tryJumpToSelectedLineImpl(
    session: DebuggerSession,
    targetLineInfo: LocalVariableAnalyzeResult,
    classFile: ByteArray,
    commonTypeResolver: CommonTypeResolver
): Boolean {

    val process = session.process

    val context = process.debuggerContext
    val threadProxy = context.threadProxy ?: return falseWithLog("Cannot get threadProxy")
    if (threadProxy.frameCount() < 2) return falseWithLog("frameCount < 2")

    val frame = context.frameProxy ?: return falseWithLog("Cannot get frameProxy")

    val location = frame.location()

    if (location.lineNumber() == targetLineInfo.line) return falseWithLog("Current line selected")

    val classType = location.declaringType()

    if (targetLineInfo.isFirstLine) {
        threadProxy.jumpByFrameDrop()
    } else {
        debuggerJump(
                targetLineInfo = targetLineInfo,
                declaredType = classType,
                originalClassFile = classFile,
                threadProxy = threadProxy,
                commonTypeResolver = commonTypeResolver
        )
    }

    session.process.onHotSwapFinished()

    return true
}

private fun <T : Any> DebugProcessImpl.invokeInManagerThread(f: (DebuggerContextImpl) -> T?): T? {
    var result: T? = null
    val command: DebuggerCommandImpl = object : DebuggerCommandImpl() {
        override fun action() {
            result = ApplicationManager.getApplication().runReadAction(Computable {
                f(debuggerContext)
            })
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
