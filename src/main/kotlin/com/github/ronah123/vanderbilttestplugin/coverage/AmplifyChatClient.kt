package com.github.ronah123.vanderbilttestplugin.coverage

import com.intellij.util.concurrency.AppExecutorUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class AmplifyChatClient(
    private val baseUrl: String,
    private val bearerToken: String,
    private val preferredModelId: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .executor(AppExecutorUtil.getAppExecutorService())
        .connectTimeout(Duration.ofSeconds(15))
        .build()
) : ChatClient {

    @Volatile
    var resolvedModelId: String? = null
        private set

    private var recoveryModelId: String? = null

    override fun chatOnce(prompt: String): String {
        // Failures must reach the UI's error handler, never become review prompts.
        val modelId = resolveModelId()
        requestChat(prompt, modelId)?.let { return it }

        // Amplify can return HTTP 200 / success=true with an empty data string.
        // Retry only empty output, once per client, using another authorized model.
        // Authentication, quota, API errors, and malformed JSON still fail immediately.
        val alternative = recoveryModelId
        recoveryModelId = null
        if (alternative != null) {
            requestChat(prompt, alternative)?.let {
                resolvedModelId = alternative
                return it
            }
        }
        val attempted = if (alternative == null) modelId else "$modelId and $alternative"
        throw AmplifyRequestException(
            "Amplify accepted the request but returned empty recommendation text from $attempted. " +
                "Check these models in Amplify or choose another available model with AMPLIFY_MODEL_ID."
        )
    }

    private fun requestChat(prompt: String, modelId: String): String? {
        val payload = payloadWrappedExact(prompt, modelId)

        val req = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}/chat"))
            .header("Authorization", "Bearer $bearerToken")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build()

        val res = execute(req)

        checkStatus(res.status)
        val root = parseResponse(res.body)
        checkSuccess(root)
        return extractContentSmart(res.body)?.takeIf { it.isNotBlank() }
    }

    /**
     * Amplify model access is account-specific. Resolve the configured preference
     * against /available_models and otherwise use the server-provided default.
     */
    private fun resolveModelId(): String {
        resolvedModelId?.let { return it }

        val req = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}/available_models"))
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .GET()
            .build()
        val res = execute(req)

        checkStatus(res.status)
        checkSuccess(parseResponse(res.body))

        val selected = runCatching {
            selectAvailableModel(res.body, preferredModelId)
        }.getOrElse { cause ->
            throw AmplifyRequestException("Amplify returned an invalid available-models response.", cause)
        } ?: throw AmplifyRequestException(
            "Amplify did not return any models available to this token. Ask the token administrator to grant chat model access."
        )

        val availableIds = availableModelIds(parseResponse(res.body).optJSONObject("data"))
        // Prefer the model verified in successful recommendation runs, but never
        // send a model ID that this account's /available_models did not advertise.
        recoveryModelId = RECOVERY_MODEL_ID.takeIf { it != selected && it in availableIds }
            ?: availableIds.firstOrNull { it != selected }
        resolvedModelId = selected
        return selected
    }

    // Matches Amplify's API: top-level { "data": { ... } } and model/prompt inside options.
    private fun payloadWrappedExact(prompt: String, modelId: String): String {
        return """
            {
              "data": {
                "temperature": 0.2,
                "max_tokens": 2000,
                "dataSources": [],
                "messages": [
                  { "role": "user", "content": ${json(prompt)} }
                ],
                "options": {
                  "ragOnly": false,
                  "skipRag": true,
                  "model": { "id": ${json(modelId)} },
                  "prompt": ${json(prompt)}
                }
              }
            }
        """.trimIndent()
    }

    /**
     * Minimal JSON string escaper for request bodies.
     */
    private fun json(s: String): String {
        val sb = StringBuilder(s.length + 32)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> {
                    val code = ch.code
                    if (code < 0x20) {
                        sb.append("\\u")
                        sb.append("0123456789abcdef"[(code shr 12) and 0xF])
                        sb.append("0123456789abcdef"[(code shr 8) and 0xF])
                        sb.append("0123456789abcdef"[(code shr 4) and 0xF])
                        sb.append("0123456789abcdef"[code and 0xF])
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private data class HttpResult(
        val status: Int,
        val body: String
    )

    private fun execute(req: HttpRequest): HttpResult {
        // Decode as UTF-8 explicitly to avoid platform charset issues
        val resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        val bodyUtf8 = String(resp.body(), StandardCharsets.UTF_8)
        return HttpResult(resp.statusCode(), bodyUtf8)
    }

    private fun checkStatus(status: Int) {
        if (status in 200..299) return
        val message = when (status) {
            401 -> "Amplify authentication failed (HTTP 401). Check that the saved token is current in TestCompass settings."
            403 -> "Amplify denied access (HTTP 403). Ask the token administrator to check chat model access."
            429 -> "Amplify request limit reached (HTTP 429). Please try again later."
            else -> "Amplify request failed (HTTP $status). Please try again or contact the token administrator."
        }
        throw AmplifyRequestException(message)
    }

    private fun parseResponse(body: String): JSONObject = runCatching { JSONObject(body) }.getOrElse {
        throw AmplifyRequestException("Amplify returned an invalid API response. Please try again.")
    }

    private fun checkSuccess(root: JSONObject) {
        if (root.has("success") && !root.optBoolean("success", true) ||
            root.has("error") && !root.isNull("error")) {
            // Do not include raw server responses: they may echo credentials or source code.
            throw AmplifyRequestException("Amplify reported a request failure. Please try again or ask the token administrator to check access and quota.")
        }
    }

    private class AmplifyRequestException(message: String, cause: Throwable? = null) : IOException(message, cause)

    companion object {
        private const val RECOVERY_MODEL_ID = "us.openai.gpt-5.6-luna"

        /** Select only IDs that Amplify says are available to this token. */
        internal fun selectAvailableModel(body: String, preferredModelId: String): String? {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            val availableIds = availableModelIds(data)

            preferredModelId.trim().takeIf { it in availableIds }?.let { return it }

            val defaultId = data.optJSONObject("default")?.optString("id")
                ?.takeIf { it.isNotBlank() }
            if (defaultId != null && defaultId in availableIds) return defaultId

            return availableIds.firstOrNull()
        }

        private fun availableModelIds(data: JSONObject?): List<String> {
            val models = data?.optJSONArray("models") ?: JSONArray()
            return buildList {
                for (index in 0 until models.length()) {
                    models.optJSONObject(index)?.optString("id")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }.distinct()
        }

        /**
         * Extract text from the current Amplify response and common OpenAI-like
         * response shapes used by compatible deployments.
         */
        internal fun extractContentSmart(body: String): String? {
            val trimmed = body.trim()
            if (trimmed.isEmpty() || trimmed.first() != '{') return null

            val root = JSONObject(trimmed)
            // JSONObject already decoded the envelope. A second unescape corrupts
            // escapes inside the model's JSON (for example, an embedded \\n).
            extractText(root.opt("data"))?.let { return it }
            extractText(root)?.let { return it }
            return null
        }

        private fun extractText(value: Any?): String? {
            when (value) {
                is String -> return value
                is JSONObject -> {
                    value.optString("content").takeIf { it.isNotBlank() }?.let { return it }
                    val choices = value.optJSONArray("choices")
                    val messageContent = choices?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                    if (!messageContent.isNullOrBlank()) return messageContent
                }
            }
            return null
        }

    }
}
