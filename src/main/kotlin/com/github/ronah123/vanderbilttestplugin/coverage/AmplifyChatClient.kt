package com.github.ronah123.vanderbilttestplugin.coverage

import com.intellij.util.concurrency.AppExecutorUtil
import org.apache.commons.text.StringEscapeUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.Duration

class AmplifyChatClient(
    private val baseUrl: String,
    private val bearerToken: String,
    private val preferredModelId: String,
    private val debug: Boolean = true
) : ChatClient {

    @Volatile
    var resolvedModelId: String? = null
        private set

    private val client: HttpClient = HttpClient.newBuilder()
        .executor(AppExecutorUtil.getAppExecutorService())
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    override fun chatOnce(prompt: String): String {
        val modelId = try {
            resolveModelId()
        } catch (e: AmplifyRequestException) {
            return e.message ?: "Unable to select an Amplify model."
        }
        val payload = payloadWrappedExact(prompt, modelId)

        val req = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}/chat"))
            .header("Authorization", "Bearer $bearerToken")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build()

        val res = execute(req)

        if (res.status in 200..299) {
            extractContentSmart(res.body)?.let { return it }
            // Fallback (should be rare): return raw body
            return res.body
        }

        if (res.status == 401) {
            return """
                Amplify authentication failed (HTTP 401).

                Check TestCompass settings and make sure the Amplify token is current and pasted as the raw token only, without quotes or an extra "Bearer " prefix.
            """.trimIndent()
        }

        return errorDump("Chat API request failed", res, payload)
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

        if (res.status == 401) {
            throw AmplifyRequestException(
                "Amplify authentication failed (HTTP 401). Check that the saved token is current."
            )
        }
        if (res.status !in 200..299) {
            throw AmplifyRequestException(
                "Could not retrieve models available to this Amplify token (HTTP ${res.status})."
            )
        }

        val selected = runCatching {
            selectAvailableModel(res.body, preferredModelId)
        }.getOrElse { cause ->
            throw AmplifyRequestException("Amplify returned an invalid available-models response.", cause)
        } ?: throw AmplifyRequestException(
            "Amplify did not return any models available to this token. Ask the token administrator to grant chat model access."
        )

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
        val headers: HttpHeaders,
        val body: String
    )

    private fun execute(req: HttpRequest): HttpResult {
        // Decode as UTF-8 explicitly to avoid platform charset issues
        val resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        val bodyUtf8 = String(resp.body(), StandardCharsets.UTF_8)
        return HttpResult(resp.statusCode(), resp.headers(), bodyUtf8)
    }

    private fun errorDump(title: String, res: HttpResult, requestBody: String): String {
        val sb = StringBuilder()
        sb.append(title).append('\n')
        sb.append("HTTP ").append(res.status).append('\n')
        sb.append("Headers:\n")
        res.headers.map().forEach { (k, v) ->
            sb.append("  ").append(k).append(": ").append(v.joinToString(", ")).append('\n')
        }
        sb.append('\n')
        val bodyPreview = res.body.let { it.take(16_384) + if (it.length > 16_384) "\n…(truncated)…" else "" }
        sb.append("Response body:\n").append(bodyPreview).append('\n')

        if (debug) {
            val reqPreview = requestBody.take(4096) + if (requestBody.length > 4096) "\n…(truncated)…" else ""
            sb.append("\n--- Request payload preview ---\n").append(reqPreview).append('\n')
        }
        return sb.toString()
    }

    private class AmplifyRequestException(message: String, cause: Throwable? = null) : IOException(message, cause)

    companion object {
        /** Select only IDs that Amplify says are available to this token. */
        internal fun selectAvailableModel(body: String, preferredModelId: String): String? {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            val models = data.optJSONArray("models") ?: JSONArray()
            val availableIds = buildList {
                for (index in 0 until models.length()) {
                    models.optJSONObject(index)?.optString("id")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }

            preferredModelId.trim().takeIf { it in availableIds }?.let { return it }

            val defaultId = data.optJSONObject("default")?.optString("id")
                ?.takeIf { it.isNotBlank() }
            if (defaultId != null && defaultId in availableIds) return defaultId

            return availableIds.firstOrNull()
        }

        /**
         * Extract text from the current Amplify response and common OpenAI-like
         * response shapes used by compatible deployments.
         */
        internal fun extractContentSmart(body: String): String? {
            val trimmed = body.trim()
            if (trimmed.isEmpty() || trimmed.first() != '{') return null

            val root = JSONObject(trimmed)
            extractText(root.opt("data"))?.let { return sanitizeModelText(it) }
            extractText(root)?.let { return sanitizeModelText(it) }
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

        private fun sanitizeModelText(s: String): String {
            val needsUnescape = s.contains("\\u") || s.contains("\\n") || s.contains("\\t") || s.contains("\\r")
            val unescapedOnce = if (needsUnescape) StringEscapeUtils.unescapeJava(s) else s
            return Normalizer.normalize(unescapedOnce, Normalizer.Form.NFC)
        }
    }
}
