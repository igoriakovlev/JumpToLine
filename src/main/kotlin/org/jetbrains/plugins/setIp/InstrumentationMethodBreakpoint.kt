package org.jetbrains.plugins.setIp

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.ui.breakpoints.SyntheticLineBreakpoint
import com.intellij.openapi.editor.Document
import com.sun.jdi.Location
import com.sun.jdi.event.LocatableEvent

internal class InstrumentationMethodBreakpoint(
        private val process: DebugProcessImpl,
        private val location: Location,
        private val stopAfterAction: Boolean,
        private val action: () -> Unit
) : SyntheticLineBreakpoint(process.project) {

    init {
        createRequest(process)
    }

    override fun createRequest(debugProcess: DebugProcessImpl) {
        debugProcess.requestsManager.run {
            enableRequest(createBreakpointRequest(this@InstrumentationMethodBreakpoint, location))
        }
    }

    override fun processLocatableEvent(action: SuspendContextCommandImpl, event: LocatableEvent): Boolean {
        process.requestsManager.deleteRequest(this)
        action()
        return stopAfterAction
    }

    override fun getLineIndex(): Int = - 1
    override fun getFileName(): String = ""
    override fun getDocument(): Document? = null

}