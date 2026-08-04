/*
 * Copyright 2021-2025, Darchest and contributors.
 * Licensed under the Apache License, Version 2.0
 */

package org.darchest.kastapi.processor

object PathParamAliases {

    private val aliasSegment = Regex("""\{([^}:]+):([^}]+)\}""")
    private val anySegment = Regex("""\{([^}:]+)(?::([^}]+))?\}""")

    fun rewriteForKtor(path: String): String =
        aliasSegment.replace(path) { "{${it.groupValues[2]}}" }

    fun rewriteForOpenApi(path: String): String =
        aliasSegment.replace(path) { "{${it.groupValues[1]}}" }

    fun displayNameFor(variableName: String, vararg paths: String): String {
        for (path in paths) {
            for (match in anySegment.findAll(path)) {
                val display = match.groupValues[1]
                val variable = match.groupValues[2].ifEmpty { display }
                if (variable == variableName)
                    return display
            }
        }
        return variableName
    }
}
