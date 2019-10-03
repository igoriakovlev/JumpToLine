package org.jetbrains.plugins.setIp

/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import com.intellij.openapi.compiler.ex.CompilerPathsEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

internal fun tryGetOutputFilePaths(project: Project, file: VirtualFile): List<String>? {
    val module = ProjectFileIndex.SERVICE.getInstance(project).getModuleForFile(file) ?: return null
    return CompilerPathsEx.getOutputPaths(arrayOf(module)).toList()
}

internal fun tryLocateClassFile(project: Project, typeName: String, file: VirtualFile): ByteArray? {

    val className = typeName.replace('.', '/') + ".class"

    val outputPaths = tryGetOutputFilePaths(project, file)

    val foundFilePath = outputPaths?.mapNotNull { path ->
        val directory = File(path)
        if (!directory.exists() || !directory.isDirectory) return@mapNotNull null

        val file = File(directory, className)

        if (file.isFile && file.canRead()) file else null
    }?.firstOrNull()

    return foundFilePath?.readBytes()
}