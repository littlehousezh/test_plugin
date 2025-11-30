package com.github.ronah123.vanderbilttestplugin.coverage

import java.io.File
import java.util.Properties

object CoverageAIConfig {
    const val MAX_METHODS_TO_REVIEW = 5
    const val MAX_METHOD_CHARS = 3500
    const val MAX_PROMPT_CHARS = 60000

    const val AMPLIFY_BASE = "https://prod-api.vanderbilt.ai"
    val AMPLIFY_BEARER: String by lazy { loadToken() }    // ✅ now loaded dynamically
    const val MODEL_ID = "gpt-5"

    const val DEBUG_SIMPLE_PROMPT = false
    const val DEBUG_SIMPLE_PROMPT_TEXT = "What is the capital of France?"

    private fun loadToken(): String {
        // 1) environment variable takes precedence
        System.getenv("AMPLIFY_BEARER")?.let { return it }

        // 2) otherwise read from local .env file (ignored by git)
        val envFile = File("plugin.env")
        if (envFile.exists()) {
            val props = Properties()
            envFile.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val (k, v) = trimmed.split("=", limit = 2)
                    props[k.trim()] = v.trim()
                }
            }
            props.getProperty("AMPLIFY_BEARER")?.let { return it }
        }

        // 3) fallback for safety
        return "MISSING_TOKEN"
    }
}
