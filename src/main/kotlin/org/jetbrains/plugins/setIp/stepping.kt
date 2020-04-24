package org.jetbrains.plugins.setIp

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.engine.events.DebuggerContextCommandImpl
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.sun.jdi.ClassType
import com.sun.jdi.StackFrame
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value
import com.sun.jdi.request.EventRequestManager
import com.sun.jdi.request.StepRequest
import org.jetbrains.plugins.setIp.injectionUtils.*

/**
 * JRE Stepping bug patch after class redefinition
 * SHOULD be called on location with different method other from redefinition method
 */
private fun jreSteppingBugPatch(eventRequestManager: EventRequestManager, threadReference: ThreadReference) {
    val lineTablePatchStepRequest = eventRequestManager.createStepRequest(threadReference, StepRequest.STEP_LINE, StepRequest.STEP_OVER)
    lineTablePatchStepRequest.enable()
    lineTablePatchStepRequest.disable()
}

internal fun debuggerJump(
        targetLineInfo: LocalVariableAnalyzeResult,
        declaredType: ClassType,
        originalClassFile: ByteArray,
        threadProxy: ThreadReferenceProxyImpl,
        commonTypeResolver: CommonTypeResolver,
        process: DebugProcessImpl,
        suspendContext: SuspendContextImpl
) {
    fun currentFrame() = threadProxy.frame(0)

    val method = currentFrame().location().method()

    val methodName = method.methodName
    val arguments = method.arguments()

    val argumentsCount = arguments.size + if (!method.isStatic) 1 else 0

    val classToRedefine = updateClassWithGotoLinePrefix(
            targetLineInfo = targetLineInfo,
            targetMethod = methodName,
            argumentsCount = argumentsCount,
            klass = originalClassFile,
            commonTypeResolver = commonTypeResolver
    ) ?: return unitWithLog("Failed to redefine class")

    dumpClass(originalClassFile, classToRedefine)

    val machine = threadProxy.virtualMachineProxy

    val localVariables =
            currentFrame().visibleVariables()
                    .filterNot { arguments.contains(it.variable) }
                    .map { it.name() to currentFrame().getValue(it) }
                    .filter { it.second !== null }

    process.suspendBreakpoints()

    val popFrameCommand = process.createPopFrameCommand(process.debuggerContext, suspendContext.frameProxy) as DebuggerContextCommandImpl
    popFrameCommand.threadAction(suspendContext)

    jreSteppingBugPatch(machine.eventRequestManager(), threadProxy.threadReference)

    machine.redefineClasses(mapOf(declaredType to classToRedefine))
    process.onHotSwapFinished()

    val newMethod = declaredType.concreteMethodByName(methodName.name, methodName.signature)
            ?: return unitWithLog("Cannot find refurbished method with given name and signature")

    val stopPreloadLocation = newMethod.locationOfCodeIndex(firstStopCodeIndex)
            ?: return unitWithLog("Cannot find first stop location")

    val targetLocation = newMethod.locationsOfLine(targetLineInfo.javaLine)
            .minBy { it.codeIndex() }
            ?: return unitWithLog("Cannot find target location")

    fun StackFrame.trySetValue(name: String, value: Value) =
            visibleVariableByName(name)?.let {
                setValue(it, value)
                true
            } ?: false

    InstrumentationMethodBreakpoint(process, threadProxy, stopPreloadLocation, stopAfterAction = false) {

        fun resumeBreakpointsAndThrow(message: String): Nothing {
            process.resumeBreakpoints()
            throw IllegalStateException(message)
        }

        fun ThreadReferenceProxyImpl.forceFramesAndGetFirst() = forceFrames()
                .firstOrNull()?.stackFrame?: nullWithLog<StackFrame>("Failed to get refreshed stack frame")

        val stackSwitchFrame = threadProxy.forceFramesAndGetFirst()
                ?: resumeBreakpointsAndThrow("Failed to get frame on stack")

        if (!stackSwitchFrame.trySetValue(jumpSwitchVariableName, machine.mirrorOf(1))) {
            resumeBreakpointsAndThrow("Failed to set SETIP variable")
        }

        InstrumentationMethodBreakpoint(process, threadProxy, targetLocation, stopAfterAction = true) {

            val stackTargetFrame = threadProxy.forceFramesAndGetFirst()

            if (stackTargetFrame != null) {
                localVariables.forEach {
                    stackTargetFrame.trySetValue(it.first, it.second)
                }
            }
            process.resumeBreakpoints()
        }
    }

    process.suspendBreakpoints()
    process.suspendManager.resume(process.suspendManager.pausedContext)
    // We should not resume it by command because Command will recreate user breakpoints that we have to avoid
//    val resumeThread = process.createResumeCommand(process.suspendManager.pausedContext)
//    resumeThread.run()
}

internal fun jumpByFrameDrop(
        process: DebugProcessImpl,
        suspendContext: SuspendContextImpl)
{
    val popFrameCommand = process.createPopFrameCommand(process.debuggerContext, suspendContext.frameProxy) as DebuggerContextCommandImpl
    popFrameCommand.threadAction(suspendContext)

    val stepInCommand = process.createStepIntoCommand(process.suspendManager.pausedContext, true, null)
    stepInCommand.action()
}