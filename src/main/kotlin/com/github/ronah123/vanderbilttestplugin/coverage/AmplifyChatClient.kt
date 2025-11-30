package com.github.ronah123.vanderbilttestplugin.coverage

import com.intellij.util.concurrency.AppExecutorUtil
import org.apache.commons.text.StringEscapeUtils
import org.json.JSONObject
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
    private val modelId: String,
    private val debug: Boolean = true
) : ChatClient {

    private val client: HttpClient = HttpClient.newBuilder()
        .executor(AppExecutorUtil.getAppExecutorService())
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    override fun chatOnce(prompt: String): String {
        val payload = payloadWrappedExact(prompt)

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

        return errorDump("Chat API request failed", res, payload)
    }

    // Matches your server doc: top-level { "data": { ... } } and model/prompt inside options.
    private fun payloadWrappedExact(prompt: String): String {
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
                    val code = ch.toInt()
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

    /**
     * Robustly extract assistant text from various response shapes, then
     * unescape once (if double-escaped) and normalize to NFC.
     *
     * Supported shapes:
     * 1) {"choices":[{"message":{"content":"…"}}]}
     * 2) {"data":{"content":"…"}}
     * 3) {"data":"…"}
     * 4) {"content":"…"}
     */
    private fun extractContentSmart(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || (trimmed.first() != '{' && trimmed.first() != '[')) return null

        val root = JSONObject(body)

        runCatching {
            val content = root.getString("data")
            return sanitizeModelText(content)
        }

        return null
    }

    /** Unescape once if needed (handles \\uXXXX) and normalize to NFC. */
    private fun sanitizeModelText(s: String): String {
        val needsUnescape = s.contains("\\u") || s.contains("\\n") || s.contains("\\t") || s.contains("\\r")
        val unescapedOnce = if (needsUnescape) StringEscapeUtils.unescapeJava(s) else s
        return Normalizer.normalize(unescapedOnce, Normalizer.Form.NFC)
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
}
