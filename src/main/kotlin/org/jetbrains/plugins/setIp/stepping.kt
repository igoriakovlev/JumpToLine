package org.jetbrains.plugins.setIp

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.engine.events.DebuggerContextCommandImpl
import com.intellij.debugger.jdi.LocalVariablesUtil
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.jetbrains.jdi.SlotLocalVariable
import com.jetbrains.jdi.StackFrameImpl
import com.sun.jdi.*
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
        targetLineInfo: JumpLineAnalyzeResult,
        declaredType: ClassType,
        originalClassFile: ByteArray,
        threadProxy: ThreadReferenceProxyImpl,
        commonTypeResolver: CommonTypeResolver,
        process: DebugProcessImpl,
        suspendContext: SuspendContextImpl,
        onFinish: () -> Unit
) {
    fun currentFrame() = threadProxy.frame(0)

    val method = currentFrame().location().method()

    val methodName = method.methodName
    val arguments = method.arguments()

    val argumentsCount = arguments.size + if (!method.isStatic) 1 else 0

    val prefixUpdateResult = updateClassWithGotoLinePrefix(
            targetLineInfo = targetLineInfo,
            targetMethod = methodName,
            argumentsCount = argumentsCount,
            klass = originalClassFile,
            commonTypeResolver = commonTypeResolver
    ) ?: returnByExceptionWithLog("Failed to redefine class")

    dumpClass(originalClassFile, prefixUpdateResult.klass)

    val machine = threadProxy.virtualMachineProxy

    data class VariableDescription(val name: String, val signature: String?, val genericSignature: String?) {
        fun isTheSameWith(variable: LocalVariable) =
                variable.name() == name && variable.signature() == signature && variable.genericSignature() == genericSignature
    }
    fun LocalVariable.toDescription() = VariableDescription(name(), signature(), genericSignature())

    var localVariablesByIJ: List<Pair<SlotLocalVariable, Value>>? = null
    var localVariablesByJDI: List<Pair<VariableDescription, Value>>? = null

    val jbJDIStack = currentFrame().stackFrame as? StackFrameImpl
    if (LocalVariablesUtil.canSetValues() && jbJDIStack != null) {

        fun LocalDescriptor.toSlotLocalVariable() = object : SlotLocalVariable {
            override fun slot(): Int = index
            override fun signature(): String = asmType.toString()
        }

        val localsToSafeAndRestore = targetLineInfo.locals
                .filter { it.saveRestoreStatus == LocalVariableAnalyzer.SaveRestoreStatus.CanBeSavedAndRestored }
                .map { it.toSlotLocalVariable() }

        try {
            val slotValues = jbJDIStack.getSlotsValues(localsToSafeAndRestore)
            localVariablesByIJ = localsToSafeAndRestore.zip(slotValues)
        } catch (e: Exception) {
            returnByExceptionWithLog("Exception getting values by slots: $e")
        }

    } else {
        localVariablesByJDI = currentFrame().visibleVariables()
                .filterNot { it.variable.isArgument }
                .map { it.variable.toDescription() to currentFrame().getValue(it) }
                .filter { it.second !== null }
    }

    val popFrameCommand = process.createPopFrameCommand(process.debuggerContext, suspendContext.frameProxy) as DebuggerContextCommandImpl
    popFrameCommand.threadAction(suspendContext)

    jreSteppingBugPatch(machine.eventRequestManager(), threadProxy.threadReference)

    machine.redefineClasses(mapOf(declaredType to prefixUpdateResult.klass))
    process.onHotSwapFinished()

    val newMethod = declaredType.concreteMethodByName(methodName.name, methodName.signature)
            ?: returnByExceptionWithLog("Cannot find refurbished method with given name and signature")

    val stopPreloadLocation = newMethod.locationOfCodeIndex(prefixUpdateResult.jumpSwitchBytecodeOffset)
            ?: returnByExceptionWithLog("Cannot find first stop location")

    val targetLocation = newMethod.locationOfCodeIndex(prefixUpdateResult.jumpTargetBytecodeOffset)
            ?: returnByExceptionWithLog("Cannot find target location")

    fun StackFrame.trySetValue(description: VariableDescription, value: Value) {
        val variable = visibleVariableByName(description.name) ?: return
        if (!description.isTheSameWith(variable)) return
        setValue(variable, value)
        return
    }

    InstrumentationMethodBreakpoint(process, threadProxy, stopPreloadLocation, stopAfterAction = false, onFinish = onFinish) {

        fun resumeBreakpointsAndThrow(message: String): Nothing {
            process.resumeBreakpoints()
            throw IllegalStateException(message)
        }

        fun ThreadReferenceProxyImpl.forceFramesAndGetFirst() = forceFrames()
                .firstOrNull()?.stackFrame?: nullWithLog<StackFrame>("Failed to get refreshed stack frame")

        val stackSwitchFrame = threadProxy.forceFramesAndGetFirst()
                ?: resumeBreakpointsAndThrow("Failed to get frame on stack")

        val jumpVariable = stackSwitchFrame.visibleVariableByName(jumpSwitchVariableName)
        stackSwitchFrame.setValue(jumpVariable, machine.mirrorOf(1))
        //resumeBreakpointsAndThrow("Failed to set SETIP variable")

        InstrumentationMethodBreakpoint(process, threadProxy, targetLocation, stopAfterAction = true, onFinish = onFinish) {

            val stackTargetFrame = threadProxy.forceFramesAndGetFirst()

            if (stackTargetFrame != null) {
                val jbJDITargetStack = stackTargetFrame as? StackFrameImpl
                if (jbJDITargetStack != null && LocalVariablesUtil.canSetValues() && localVariablesByIJ != null) {
                    localVariablesByIJ.forEach {
                        try {
                            jbJDITargetStack.setSlotValue(it.first, it.second)
                        } catch (e: Exception) {
                            unitWithLog("Exception setting values to slots: $e")
                        }
                    }
                } else localVariablesByJDI?.forEach {
                    stackTargetFrame.trySetValue(it.first, it.second)
                }
            }

            process.resumeBreakpoints()
            onFinish()
        }
    }

    process.suspendBreakpoints()
    process.suspendManager.resume(process.suspendManager.pausedContext)
    // We should not resume it by command because Command will recreate user breakpoints that we have to avoid
//    val resumeThread = process.createResumeCommand(process.suspendManager.pausedContext)
//    resumeThread.run()
}

internal fun jumpByRunToLine(
        process: DebugProcessImpl,
        suspendContext: SuspendContextImpl,
        threadProxy: ThreadReferenceProxyImpl,
        line: Int,
        onFinish: () -> Unit)
{
    fun currentFrame() = threadProxy.frame(0)
    val method = currentFrame().location().method()

    val runToLocation = method.locationsOfLine(line)
            ?.minBy { it.codeIndex() }
            ?: returnByExceptionWithLog("Cannot find line location for RunTo")

    val codeLocation = currentFrame().location()

    if (codeLocation.codeIndex() > runToLocation.codeIndex()) {
        val popFrameCommand = process.createPopFrameCommand(process.debuggerContext, suspendContext.frameProxy) as DebuggerContextCommandImpl
        popFrameCommand.threadAction(suspendContext)
    }

    val steppingBreakpoint = RunToCursorBreakpoint(project = process.project)
    process.setSteppingBreakpoint(steppingBreakpoint)
    process.requestsManager.run {
        enableRequest(createBreakpointRequest(steppingBreakpoint, runToLocation).also {
            filterThread = threadProxy.threadReference
        })
    }
    process.suspendManager.resume(process.suspendManager.pausedContext)
    onFinish()
}