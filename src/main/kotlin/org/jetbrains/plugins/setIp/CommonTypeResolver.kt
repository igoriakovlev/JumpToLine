package org.jetbrains.plugins.setIp

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope

internal class TypeResolveError: Exception() {
    companion object {
        val INSTANCE = TypeResolveError()
    }
}

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

    fun tryGetCommonType(fqName1: String, fqName2: String): String? {

        if (fqName1 == fqName2) return fqName1
        if (fqName1 == "java.lang.Object") return fqName2
        if (fqName2 == "java.lang.Object") return fqName1

        val class1 = PsiType.getTypeByName(fqName1, project, allScope).resolve() ?: throw TypeResolveError.INSTANCE
        val class2 = PsiType.getTypeByName(fqName2, project, allScope).resolve() ?: throw TypeResolveError.INSTANCE

        val firstTry = findCommonSuperClass(class1, class2)?.qualifiedName
        return if (firstTry == "java.lang.Object") findCommonSuperClass(class2, class1)?.qualifiedName else firstTry
    }
}