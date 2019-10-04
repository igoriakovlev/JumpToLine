package org.jetbrains.plugins.setIp

internal interface LineTranslator {
    fun translate(line: Int): Int?
}
