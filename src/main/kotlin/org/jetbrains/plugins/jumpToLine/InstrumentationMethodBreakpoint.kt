/*
 * Copyright 2020-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.jumpToLine

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.debugger.settings.DebuggerSettings
import com.intellij.debugger.ui.breakpoints.SyntheticLineBreakpoint
import com.intellij.openapi.editor.Document
import com.sun.jdi.Location
import com.sun.jdi.event.LocatableEvent
import org.jetbrains.plugins.jumpToLine.injectionUtils.finishOnException

internal class InstrumentationMethodBreakpoint(
        private val process: DebugProcessImpl,
        private val thread: ThreadReferenceProxyImpl,
        private val location: Location,
        private val stopAfterAction: Boolean,
        private val onFinish: () -> Unit,
        private val action: () -> Unit
) : SyntheticLineBreakpoint(process.project) {

    init {
        suspendPolicy = DebuggerSettings.SUSPEND_ALL
        createRequest(process)
    }

    override fun createRequest(debugProcess: DebugProcessImpl) {
        debugProcess.requestsManager.run {
            enableRequest(createBreakpointRequest(this@InstrumentationMethodBreakpoint, location).also {
                filterThread = thread.threadReference
            })
        }
    }

    override fun processLocatableEvent(action: SuspendContextCommandImpl, event: LocatableEvent): Boolean {
        finishOnException(onFinish) {
            process.requestsManager.deleteRequest(this)
            action()
        }
        return stopAfterAction
    }

    override fun getLineIndex(): Int = - 1
    override fun getFileName(): String = ""
    override fun getDocument(): Document? = null

}