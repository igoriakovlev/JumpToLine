package org.jetbrains.plugins.setIp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope

internal abstract class TypeResolveError(message: String): Exception(message)

internal class TypeResolveFqNameError(fqName: String): TypeResolveError("Cannot resolve type: $fqName")
internal class TypeResolveForCommonTypeError(fqName1: String, fqName2: String): Exception("Cannot resolve common type for: $fqName1 and $fqName2")

internal class CommonTypeResolver(private val project: Project) {
    private val allScope = GlobalSearchScope.allScope(project)

    private fun findCommonSuperClass(firstClass: PsiClass, secondClass: PsiClass): PsiClass? {
        if (firstClass == secondClass) return firstClass

        var currentClass: PsiClass? = secondClass
        while(currentClass !== null) {
            if (firstClass.isInheritor(currentClass, true)) {
                return currentClass
            }
            currentClass = currentClass.superClass
        }
        return null
    }

    fun tryGetCommonType(fqName1: String, fqName2: String): String {

        if (fqName1 == fqName2) return fqName1
        if (fqName1 == "java.lang.Object") return fqName2
        if (fqName2 == "java.lang.Object") return fqName1

        var result: String? = null
        ApplicationManager.getApplication().runReadAction {
            val class1 = PsiType.getTypeByName(fqName1, project, allScope).resolve() ?: throw TypeResolveFqNameError(fqName1)
            val class2 = PsiType.getTypeByName(fqName2, project, allScope).resolve() ?: throw TypeResolveFqNameError(fqName2)

            val firstTry = findCommonSuperClass(class1, class2)?.qualifiedName
            result = if (firstTry == "java.lang.Object") findCommonSuperClass(class2, class1)?.qualifiedName else firstTry
        }
        return result ?: throw TypeResolveForCommonTypeError(fqName1, fqName2)
    }
}