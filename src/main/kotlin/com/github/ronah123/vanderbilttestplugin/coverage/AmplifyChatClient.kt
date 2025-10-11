package com.github.ronah123.vanderbilttestplugin.coverage

import com.intellij.util.concurrency.AppExecutorUtil
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val res = execute(req)

        if (res.status in 200..299) {
            extractJsonDataField(res.body)?.let { return it }
            return res.body
        }

        return errorDump("Chat API request failed", res, payload)
    }

    // Matches the doc you pasted: top-level { "data": { ... } } and model/prompt inside options.
    private fun payloadWrappedExact(prompt: String): String = """
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

    private fun json(s: String): String {
        val sb = StringBuilder(s.length + 32)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"'  -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f") // form feed
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028") // line sep (breaks some parsers)
                '\u2029' -> sb.append("\\u2029") // paragraph sep
                else -> {
                    val code = ch.toInt()
                    if (code < 0x20) {
                        // Any other control char → \u00XX
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
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        return HttpResult(resp.statusCode(), resp.headers(), resp.body())
    }

    /** Minimal JSON scrape for top-level "data": "<string>" */
    private fun extractJsonDataField(body: String): String? {
        val key = "\"data\""
        val i = body.indexOf(key)
        if (i < 0) return null
        val colon = body.indexOf(':', i + key.length)
        if (colon < 0) return null
        var j = colon + 1
        while (j < body.length && body[j].isWhitespace()) j++
        if (j >= body.length || body[j] != '"') return null
        j++
        val sb = StringBuilder()
        var esc = false
        while (j < body.length) {
            val c = body[j++]
            if (esc) {
                sb.append(when (c) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> c })
                esc = false
            } else {
                when (c) {
                    '\\' -> esc = true
                    '"'  -> return sb.toString()
                    else -> sb.append(c)
                }
            }
        }
        return null
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
