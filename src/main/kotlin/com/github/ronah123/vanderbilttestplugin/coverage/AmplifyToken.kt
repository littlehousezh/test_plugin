package com.github.ronah123.vanderbilttestplugin.coverage

object AmplifyToken {
    fun normalize(raw: String): String {
        var token = raw.trim().trim('"', '\'')
        if (token.startsWith("Bearer ", ignoreCase = true)) {
            token = token.substringAfter(' ').trim()
        }
        return token.trim().trim('"', '\'')
    }
}
