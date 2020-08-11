/*
 * Copyright 2020-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.jumpToLine

import com.intellij.debugger.engine.RequestHint
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.settings.DebuggerSettings
import com.intellij.debugger.ui.breakpoints.SteppingBreakpoint
import com.intellij.debugger.ui.breakpoints.SyntheticLineBreakpoint
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.sun.jdi.event.LocatableEvent

internal class RunToCursorBreakpoint(project: Project) : SyntheticLineBreakpoint(project), SteppingBreakpoint {
    override fun isRestoreBreakpoints(): Boolean = false
    override fun setRequestHint(hint: RequestHint?) {}
    override fun track(): Boolean = false
    override fun getLineIndex(): Int = - 1
    override fun getFileName(): String = ""
    override fun getDocument(): Document? = null
    override fun getSuspendPolicy(): String = DebuggerSettings.SUSPEND_ALL
    override fun processLocatableEvent(action: SuspendContextCommandImpl, event: LocatableEvent?): Boolean = true
}