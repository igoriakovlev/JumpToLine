package org.jetbrains.plugins.setIp

import com.intellij.debugger.engine.DebugProcessEvents
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.sun.jdi.ClassType
import com.sun.jdi.Location
import com.sun.jdi.Value
import com.sun.jdi.request.EventRequestManager
import org.jetbrains.plugins.setIp.injectionUtils.*

internal fun debuggerJump(
        targetLineInfo: LocalVariableAnalyzeResult,
        declaredType: ClassType,
        originalClassFile: ByteArray,
        threadProxy: ThreadReferenceProxyImpl,
        commonTypeResolver: CommonTypeResolver
) {
    val currentFrame = threadProxy.frame(0)

    val method = currentFrame.location().method()

    val arguments = method.arguments()

    val argumentsCount = arguments.size + if (!method.isStatic) 1 else 0

    val classToRedefine = updateClassWithGotoLinePrefix(
            targetLineInfo = targetLineInfo,
            targetMethod = method.methodName,
            argumentsCount = argumentsCount,
            klass = originalClassFile,
            commonTypeResolver = commonTypeResolver
    ) ?: return unitWithLog("Failed to redefine class")

    dumpClass(originalClassFile, classToRedefine)

    val machine = threadProxy.virtualMachineProxy

    val localVariables =
            currentFrame.visibleVariables()
                    .filterNot { arguments.contains(it.variable) }
                    .map { it.name() to currentFrame.getValue(it) }
                    .filter { it.second !== null }


    threadProxy.popFrames(currentFrame)

    val methodName = method.name()
    val signature = method.signature()

    machine.redefineClasses(mapOf(declaredType to classToRedefine))

    val newMethod = declaredType.concreteMethodByName(methodName, signature)
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


    with(machine.eventRequestManager()) {
        jumpToLocationAndRun(stopPreloadLocation) {
            with(threadProxy.frame(0)) {

                if (!trySetValue("$$", machine.mirrorOf(1))) {
                    return@jumpToLocationAndRun
                }

                jumpToLocationAndRun(targetLocation) {
                    machine.suspend()

                    localVariables.forEach {
                        trySetValue(it.first, it.second)
                    }
                }
            }
        }
    }

    machine.resume()
}

private fun EventRequestManager.jumpToLocationAndRun(location: Location, action: () -> Unit) {
    with(createBreakpointRequest(location)) {
        enable()
        DebugProcessEvents.enableRequestWithHandler(this) {
            disable()
            action()
        }
    }
}

internal fun ThreadReferenceProxyImpl.jumpByFrameDrop() {

    with(frame(0)) {
        val minLocation = location().method()
                .allLineLocations()
                .minBy { it.lineNumber() }
                ?: return unitWithLog("Could not find min method location")

        popFrames(this)

        with(virtualMachineProxy) {
            eventRequestManager().jumpToLocationAndRun(minLocation) {
                suspend()
            }
            resume()
        }

    }
}
