package org.jetbrains.plugins.setIp

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.ui.breakpoints.SyntheticLineBreakpoint
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.sun.jdi.Location
import com.sun.jdi.event.LocatableEvent

internal class InstrumentationMethodBreakpoint(project: Project, private val location: Location, private val action: () -> Boolean) : SyntheticLineBreakpoint(project) {
    override fun createRequest(debugProcess: DebugProcessImpl) {
        debugProcess.requestsManager.run {
            enableRequest(createBreakpointRequest(this@InstrumentationMethodBreakpoint, location))
        }
    }

    override fun processLocatableEvent(action: SuspendContextCommandImpl, event: LocatableEvent): Boolean {
        with(event) {
            virtualMachine().eventRequestManager().deleteEventRequest(request())
        }
        return action()
    }

    override fun getLineIndex(): Int = - 1

    override fun getFileName(): String = ""
    override fun getDocument(): Document? = null

}