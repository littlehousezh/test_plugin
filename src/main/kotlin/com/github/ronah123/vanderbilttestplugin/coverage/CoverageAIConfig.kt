package com.github.ronah123.vanderbilttestplugin.coverage

import java.io.File
import java.util.Properties

object CoverageAIConfig {
    const val MAX_METHODS_TO_REVIEW = 5
    const val MAX_METHOD_CHARS = 3500
    const val MAX_PROMPT_CHARS = 60000

    private const val DEFAULT_AMPLIFY_BASE = "https://prod-api.vanderbilt.ai"
    private const val DEFAULT_MODEL_ID = "gpt-5"

    const val DEBUG_SIMPLE_PROMPT = false
    const val DEBUG_SIMPLE_PROMPT_TEXT = "What is the capital of France?"

    fun getAmplifyBase(): String {
        System.getenv("AMPLIFY_BASE")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        loadPluginEnv().getProperty("AMPLIFY_BASE")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return DEFAULT_AMPLIFY_BASE
    }

    fun getModelId(): String {
        System.getenv("AMPLIFY_MODEL_ID")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        loadPluginEnv().getProperty("AMPLIFY_MODEL_ID")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return DEFAULT_MODEL_ID
    }

    fun getAmplifyBearer(): String {
        // 1) Prefer user-provided token from IDE Settings UI
        runCatching {
            val settings = com.intellij.openapi.application.ApplicationManager
                .getApplication()
                .getService(CoverageSettings::class.java)
            val saved = AmplifyToken.normalize(settings?.getBearerToken().orEmpty())
            if (saved.isNotBlank()) return saved
        }

        // 2) environment variable takes precedence over file
        System.getenv("AMPLIFY_BEARER")?.let { return AmplifyToken.normalize(it) }

        // 3) otherwise read from local .env file (ignored by git)
        loadPluginEnv().getProperty("AMPLIFY_BEARER")?.let { return AmplifyToken.normalize(it) }

        // 4) fallback for safety
        return "MISSING_TOKEN"
    }

    private fun loadPluginEnv(): Properties {
        val props = Properties()
        val envFile = File("plugin.env")
        if (!envFile.exists()) return props
        envFile.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                val (k, v) = trimmed.split("=", limit = 2)
                props[k.trim()] = v.trim()
            }
        }
        return props
    }
}
