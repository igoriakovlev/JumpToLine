/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.setIp

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.impl.XDebugSessionImpl
import org.jetbrains.plugins.setIp.injectionUtils.MethodName
import org.jetbrains.plugins.setIp.injectionUtils.dumpClass
import org.jetbrains.plugins.setIp.injectionUtils.getAvailableGotoLines
import org.jetbrains.plugins.setIp.injectionUtils.updateClassWithGotoLinePrefix
import java.io.File
import java.nio.file.Files

class SetIPPluginProjectComponent(private val project: Project) : ProjectComponent {

    private fun instrument() {
        val file = Files.readAllBytes(File("C:\\AAA\\KtUltraLightClass.class").toPath())
        val methodName = MethodName("ownMethods", "()Ljava/util/List;", null)

        val result = getAvailableGotoLines(
                ownerTypeName = "org/jetbrains/kotlin/asJava/classes/KtUltraLightClass",
                targetMethod = methodName,
                lineTranslator = null,
                klass = file
        )

        val line = result?.firstOrNull { it.sourceLine == 270 } ?: return
        val argumentsCount = 1

        val classToRedefine = updateClassWithGotoLinePrefix(
                targetLineInfo = line,
                targetMethod = methodName,
                argumentsCount = argumentsCount,
                klass = file,
                commonTypeResolver = CommonTypeResolver(project)
        )

        if (classToRedefine !== null) {
            dumpClass(file, classToRedefine)
        }
    }

    override fun projectOpened() {

        //instrument()

        val debuggerListener = object : DebuggerManagerListener {
            override fun sessionAttached(session: DebuggerSession?) {
                val xSession = session?.xDebugSession as? XDebugSessionImpl ?: return
                val typeResolver = CommonTypeResolver(project)
                val sessionHandler = SetIPSessionEvenHandler(session, xSession, project, typeResolver)
                xSession.addSessionListener(sessionHandler)
            }
        }

        (DebuggerManagerEx.getInstance(project) as? DebuggerManagerEx)
                ?.addDebuggerManagerListener(debuggerListener)
    }
}