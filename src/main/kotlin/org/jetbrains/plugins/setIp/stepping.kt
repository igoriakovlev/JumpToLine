package org.jetbrains.plugins.setIp

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.events.DebuggerContextCommandImpl
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.sun.jdi.ClassType
import com.sun.jdi.Value
import com.sun.jdi.request.StepRequest
import org.jetbrains.plugins.setIp.injectionUtils.*

internal fun debuggerJump(
        targetLineInfo: LocalVariableAnalyzeResult,
        declaredType: ClassType,
        originalClassFile: ByteArray,
        threadProxy: ThreadReferenceProxyImpl,
        commonTypeResolver: CommonTypeResolver,
        process: DebugProcessImpl
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

//    threadProxy.popFrames(currentFrame())
    val popFrameCommand = process.createPopFrameCommand(process.debuggerContext, process.debuggerContext.frameProxy) as DebuggerContextCommandImpl
    popFrameCommand.threadAction(process.debuggerContext.suspendContext!!)

    //JRE STEPPING BUG PATCH
    val lineTablePatchStepRequest = machine.eventRequestManager().createStepRequest(threadProxy.threadReference, StepRequest.STEP_LINE, StepRequest.STEP_OVER)
    lineTablePatchStepRequest.enable()
    lineTablePatchStepRequest.disable()
    //JRE STEPPING BUG PATCH END

    machine.redefineClasses(mapOf(declaredType to classToRedefine))
    process.onHotSwapFinished()

    val newMethod = declaredType.concreteMethodByName(methodName.name, methodName.signature)
            ?: return unitWithLog("Cannot find refurbished method with given name and signature")

    val stopPreloadLocation = newMethod.locationOfCodeIndex(firstStopCodeIndex)
            ?: return unitWithLog("Cannot find first stop location")

    val targetLocation = newMethod.locationsOfLine(targetLineInfo.javaLine)
            .minBy { it.codeIndex() }
            ?: return unitWithLog("Cannot find target location")

    fun StackFrameProxyImpl.trySetValue(name: String, value: Value) =
            visibleVariableByName(name)?.let {
                setValue(it, value)
                true
            } ?: false


    val breakpoint2 = InstrumentationMethodBreakpoint(process.project, stopPreloadLocation) {
        with(threadProxy.frame(0)) {
            if (!trySetValue(jumpSwitchVariableName, machine.mirrorOf(1))) {
                unitWithLog("Failed to set SETIP variable")
                return@InstrumentationMethodBreakpoint false
            }
        }
        false //Means not stop!
    }
    breakpoint2.createRequest(process)

    val breakpoint = InstrumentationMethodBreakpoint(process.project, targetLocation) {
        localVariables.forEach {
            with(threadProxy.frame(0)) {
                trySetValue(it.first, it.second)
            }
        }
        true //Means stop!
    }
    breakpoint.createRequest(process)

    process.suspendManager.run {
        resume(pausedContext)
    }
}

internal fun jumpByFrameDrop(
        threadProxy: ThreadReferenceProxyImpl,
        process: DebugProcessImpl)
{
    val suspendContext = process.debuggerContext.suspendContext!!

    val popFrameCommand = process.createPopFrameCommand(process.debuggerContext, process.debuggerContext.frameProxy) as DebuggerContextCommandImpl
    popFrameCommand.threadAction(suspendContext)

    val lineTablePatchStepRequest = threadProxy.virtualMachine.eventRequestManager().createStepRequest(threadProxy.threadReference, StepRequest.STEP_LINE, StepRequest.STEP_INTO)
    lineTablePatchStepRequest.enable()

    process.suspendManager.run {
        resume(pausedContext)
    }
}