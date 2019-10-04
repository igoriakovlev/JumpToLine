package org.jetbrains.plugins.setIp

import com.intellij.debugger.engine.DebugProcessEvents
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import com.sun.jdi.Value
import com.sun.jdi.request.EventRequestManager
import org.jetbrains.plugins.setIp.injectionUtils.LocalVariableAnalyzeResult
import org.jetbrains.plugins.setIp.injectionUtils.dumpClass
import org.jetbrains.plugins.setIp.injectionUtils.unitWithLog
import org.jetbrains.plugins.setIp.injectionUtils.updateClassWithGotoLinePrefix

internal fun debuggerJump(
        targetLineInfo: LocalVariableAnalyzeResult,
        declaredType: ReferenceType,
        originalClassFile: ByteArray,
        threadProxy: ThreadReferenceProxyImpl,
        commonTypeResolver: CommonTypeResolver
) {
    val currentFrame = threadProxy.frame(0)

    val method = currentFrame.location().method()

    val arguments = method.arguments()

    val argumentsCount = arguments.size + if (!method.isStatic) 1 else 0

    //val sourceName = currentFrame.location().sourceName("Java")

    val (classToRedefine, stopLine) = updateClassWithGotoLinePrefix(
            targetLineInfo = targetLineInfo,
            targetMethod = method.methodName,
            argumentsCount = argumentsCount,
            klass = originalClassFile,
            commonTypeResolver = commonTypeResolver
    ) ?: return unitWithLog("Failed to redefine class")

    dumpClass(classToRedefine)

    val machine = threadProxy.virtualMachineProxy

    val localVariables =
            currentFrame.visibleVariables()
                    .filterNot { arguments.contains(it.variable) }
                    .map { it.name() to currentFrame.getValue(it) }
                    .filter { it.second !== null }


    threadProxy.popFrames(currentFrame)

    machine.redefineClasses(mapOf(declaredType to classToRedefine))

//
//    val stopPreloadLocation = method
//            .locationsOfLine(stopLine)
//            .firstOrNull()
//            ?: return unitWithLog("Cannot find first stop location")
//
//    val targetLocation = method
//            .locationsOfLine(targetLineInfo.line)
//            .firstOrNull()
//            ?: return unitWithLog("Cannot find target location")

    val stopPreloadLocation = declaredType.allLineLocations().firstOrNull {
        it.lineNumber() == stopLine
    }?: return unitWithLog("Cannot find first stop location")

    val targetLocation = declaredType.allLineLocations().firstOrNull {
        it.lineNumber() == targetLineInfo.javaLine
    } ?: return unitWithLog("Cannot find target location")

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
