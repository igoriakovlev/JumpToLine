package org.jetbrains.plugins.setIp

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope

private val parserAndMapper by lazy  {

    val loader = PluginManager.getPlugins()
            .firstOrNull { it.pluginId.idString == "org.jetbrains.kotlin" }
            ?.pluginClassLoader
            ?: return@lazy null

    val smapData = loader
            .loadClass("org.jetbrains.kotlin.idea.debugger.SmapData")
            ?: return@lazy null


    val smapDataCtor = smapData.declaredConstructors.firstOrNull {
        it.parameterCount == 1 &&
                it.parameterTypes[0].name == "java.lang.String"
    } ?: return@lazy null

    val smapUtilKt = loader
            .loadClass("org.jetbrains.kotlin.idea.debugger.SmapUtilKt")
            ?: return@lazy null

    val mapStacktraceLineToSource = smapUtilKt
            .declaredMethods
            .firstOrNull { it.name == "mapStacktraceLineToSource" && it.parameterCount == 5 }
            ?: return@lazy null


    val sourceLineKind = loader
            .loadClass("org.jetbrains.kotlin.idea.debugger.SourceLineKind")

    val valueOf = sourceLineKind
            .declaredMethods
            .firstOrNull {
                it.name == "valueOf" &&
                        it.parameterCount == 1 &&
                        it.parameterTypes[0].name == "java.lang.String"
            }?: return@lazy null

    val excludedLine = valueOf.invoke(sourceLineKind, "EXECUTED_LINE")

    return@lazy { mappingInfo: String, project: Project, line: Int ->
        val smapDataInstance = smapDataCtor.newInstance(mappingInfo)

        val result = mapStacktraceLineToSource.invoke(
                smapUtilKt,
                smapDataInstance,
                line,
                project,
                excludedLine,
                GlobalSearchScope.allScope(project)
        )

        (result as? Pair<*,*>)?.let {
            val first = it.first as? PsiFile ?: return@let null
            val second = it.second as? Int ?: return@let null
            first to second
        }
    }
}

fun parseKotlinSMAP(mappingInfo: String, lines: Set<Int>, project: Project) =
    lines.mapNotNull { it to parserAndMapper?.invoke(mappingInfo, project, it) }