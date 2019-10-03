package org.jetbrains.plugins.setIp

import com.intellij.debugger.engine.DebugProcessEvents
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.openapi.ui.messages.MessageDialog
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import com.sun.jdi.Value
import com.sun.jdi.request.EventRequestManager
import org.jetbrains.plugins.setIp.injectionUtils.dumpClass
import org.jetbrains.plugins.setIp.injectionUtils.getTargetLineInfo
import org.jetbrains.plugins.setIp.injectionUtils.unitWithLog
import org.jetbrains.plugins.setIp.injectionUtils.updateClassWithGotoLinePrefix


internal fun debuggerJump(
        selectedLine: Int,
        declaredType: ReferenceType,
        originalClassFile: ByteArray,
        threadProxy: ThreadReferenceProxyImpl,
        commonTypeResolver: CommonTypeResolver
) {
    val currentFrame = threadProxy.frame(0)

    val method = currentFrame.location().method()

    val targetLineInfo = getTargetLineInfo(
        ownerTypeName = declaredType.name(),
        targetMethod = method.methodName,
        klass = originalClassFile,
        line = selectedLine
    )?: return unitWithLog("Failed to get target line info")

    if (!targetLineInfo.isSafeTarget) {
        return
//        val dialog = MessageDialog(null, "This jump is not safe! Continue?", "SetIP", arrayOf("Yes", "No way!"), 0, null, true)
//        dialog.show()
//        if (dialog.exitCode == 1) return
    }

    val (classToRedefine, stopLine) = updateClassWithGotoLinePrefix(
            targetLineInfo = targetLineInfo,
            targetMethod = method.methodName,
            isInstanceMethod = !method.isStatic,
            klass = originalClassFile,
            line = selectedLine,
            commonTypeResolver = commonTypeResolver
    ) ?: return unitWithLog("Failed to redefine class")

    dumpClass(classToRedefine)

    val machine = threadProxy.virtualMachineProxy

    val localVariables =
            currentFrame.visibleVariables()
                    .map { it.name() to currentFrame.getValue(it) }
                    .filter { it.second !== null }


    threadProxy.popFrames(currentFrame)

    machine.redefineClasses(mapOf(declaredType to classToRedefine))

    val stopPreloadLocation = declaredType.allLineLocations().firstOrNull {
        it.lineNumber() == stopLine
    }?: return unitWithLog("Cannot find first stop location")

    val targetLocation = declaredType.allLineLocations().firstOrNull {
        it.lineNumber() == selectedLine
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
