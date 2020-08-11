package org.jetbrains.plugins.jumpToLine

internal interface LineTranslator {
    fun translate(line: Int): Int?
}
