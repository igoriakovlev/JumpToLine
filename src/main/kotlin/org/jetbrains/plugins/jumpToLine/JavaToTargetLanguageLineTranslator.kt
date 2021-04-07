/*
 * Copyright 2020-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.plugins.jumpToLine

internal interface LineTranslator {
    fun translate(line: Int): Int?
    companion object {
        val DEFAULT: LineTranslator = object : LineTranslator {
            override fun translate(line: Int): Int? = line
        }
    }
}
