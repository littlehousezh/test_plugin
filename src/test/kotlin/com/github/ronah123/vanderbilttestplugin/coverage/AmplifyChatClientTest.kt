package com.github.ronah123.vanderbilttestplugin.coverage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.http.HttpClient

class AmplifyChatClientTest {
    private val availableModels = """{"data":{"models":[{"id":"test-model"}],"default":{"id":"test-model"}}}"""

    @Test
    fun `empty default response retries an available alternative and keeps it for review`() {
        val models = """{"data":{"models":[{"id":"default-model"},{"id":"us.openai.gpt-5.6-luna"}],"default":{"id":"default-model"}}}"""
        val review = """{"recommendations":[{"name":"Add positive numbers","covers":"Addition","action":"Use 2 and 3","expected":"The result is 5","targetLines":[],"reachableLines":[]}]}"""
        val sentModels = mutableListOf<String>()
        withServer(200, models, 200, """{"success":true,"data":""}""",
            chatBodiesByModel = mapOf("us.openai.gpt-5.6-luna" to JSONObject().put("success", true).put("data", review).toString()),
            sentModels = sentModels
        ) { client, requests ->
            val result = RecommendationGenerator(client).generate("Synthetic addition example")
            assertTrue(result.recommendations.contains("The result is 5"))
            assertEquals(listOf("default-model", "us.openai.gpt-5.6-luna", "us.openai.gpt-5.6-luna"), sentModels)
            assertEquals("us.openai.gpt-5.6-luna", client.resolvedModelId)
            assertEquals(4, requests.size)
        }
    }

    @Test
    fun `empty responses from both models stop after one fallback`() {
        val models = """{"data":{"models":[{"id":"default-model"},{"id":"alternative"},{"id":"third-model"}],"default":{"id":"default-model"}}}"""
        val sentModels = mutableListOf<String>()
        withServer(200, models, 200, """{"success":true,"data":"  "}""", sentModels = sentModels) { client, _ ->
            val failure = runCatching { client.chatOnce("Synthetic addition example") }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(listOf("default-model", "alternative"), sentModels)
            assertTrue(failure!!.message!!.contains("empty"))
        }
    }

    @Test
    fun `HTTP failures never trigger a model fallback`() {
        val models = """{"data":{"models":[{"id":"default-model"},{"id":"alternative"}],"default":{"id":"default-model"}}}"""
        for (status in listOf(401, 403, 429, 500)) {
            val sentModels = mutableListOf<String>()
            withServer(200, models, status, "private-server-content", sentModels = sentModels) { client, _ ->
                val failure = runCatching { client.chatOnce("Synthetic addition example") }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals(listOf("default-model"), sentModels)
                assertFalse(failure!!.message!!.contains("private-server-content"))
            }
        }
    }

    @Test
    fun `empty accuracy review recovers without discarding the draft`() {
        val models = """{"data":{"models":[{"id":"default-model"},{"id":"alternative"}],"default":{"id":"default-model"}}}"""
        val review = """{"recommendations":[{"name":"Addition","covers":"Positive inputs","action":"Use 2 and 3","expected":"The result is 5","targetLines":[],"reachableLines":[]}]}"""
        val sentModels = mutableListOf<String>()
        withServer(200, models, 200, "unused", sentModels = sentModels,
            chatBodiesInOrder = listOf(
                """{"success":true,"data":"Add 2 and 3 to get 5."}""",
                """{"success":true,"data":""}""",
                JSONObject().put("success", true).put("data", review).toString()
            )
        ) { client, _ ->
            val result = RecommendationGenerator(client).generate("Synthetic addition example")
            assertEquals("Add 2 and 3 to get 5.", result.draft)
            assertTrue(result.recommendations.contains("The result is 5"))
            assertEquals(listOf("default-model", "default-model", "alternative"), sentModels)
        }
    }

    @Test
    fun `API failures and malformed JSON are not retried as empty output`() {
        val models = """{"data":{"models":[{"id":"default-model"},{"id":"alternative"}],"default":{"id":"default-model"}}}"""
        for (body in listOf("""{"success":false,"data":""}""", """{"error":"private-server-content"}""", "not JSON")) {
            val sentModels = mutableListOf<String>()
            withServer(200, models, 200, body, sentModels = sentModels) { client, _ ->
                val failure = runCatching { client.chatOnce("Synthetic addition example") }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals(listOf("default-model"), sentModels)
                assertFalse(failure!!.message!!.contains("private-server-content"))
            }
        }
    }

    @Test
    fun `decodes the response envelope once and preserves review JSON escapes`() {
        val review = """{"recommendations":[{"name":"Check input","covers":"Escaped input","action":"Use a newline: \n and path C:\\tmp","expected":"Keep \\u0041 literal","targetLines":[],"reachableLines":[]}]}"""
        val body = JSONObject().put("success", true).put("data", review).toString()

        val actual = AmplifyChatClient.extractContentSmart(body)

        assertEquals(review, actual)
        assertEquals(1, JSONObject(actual!!).getJSONArray("recommendations").length())
    }

    @Test
    fun `model authentication failure is surfaced without sending chat requests`() {
        withServer(401, "unauthorized", 200, "unused") { client, requests ->
            val failure = runCatching { RecommendationGenerator(client).generate("source context") }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure!!.message!!.contains("HTTP 401"))
            assertEquals(listOf("/available_models"), requests)
        }
    }

    @Test
    fun `chat HTTP failures stop before review and do not expose server response`() {
        for (status in listOf(401, 403, 429, 500)) {
            withServer(200, availableModels, status, "echoed-private-content") { client, requests ->
                val failure = runCatching { RecommendationGenerator(client).generate("source context") }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertTrue(failure!!.message!!.contains("HTTP $status"))
                assertFalse(failure.message!!.contains("echoed-private-content"))
                assertEquals(listOf("/available_models", "/chat"), requests)
            }
        }
    }

    @Test
    fun `successful HTTP with an API failure empty content or invalid body is not model output`() {
        for (body in listOf(
            """{"success":false,"message":"failed","data":"not a recommendation"}""",
            """{"error":"failed"}""",
            """{"success":true,"data":"  "}""",
            """{"success":true,"data":{}}""",
            "not JSON"
        )) {
            withServer(200, availableModels, 200, body) { client, requests ->
                val failure = runCatching { RecommendationGenerator(client).generate("source context") }.exceptionOrNull()
                assertTrue("Expected request failure for $body", failure is IOException)
                assertTrue(failure!!.message!!.isNotBlank())
                assertEquals(listOf("/available_models", "/chat"), requests)
            }
        }
    }

    @Test
    fun `valid fenced review survives the HTTP client and full generation flow`() {
        val review = """{"recommendations":[{"name":"Check input","covers":"Escaped input","action":"Use a newline: \n and path C:\\tmp","expected":"Keep \\u0041 literal","targetLines":[],"reachableLines":[]}]}"""
        val body = JSONObject().put("success", true).put("data", "```json\n$review\n```").toString()
        withServer(200, availableModels, 200, body) { client, requests ->
            val result = RecommendationGenerator(client).generate("source context")
            assertTrue(result.recommendations.contains("Check input"))
            assertTrue(result.recommendations.contains("C:\\tmp"))
            assertEquals(null, result.correctionPrompt)
            assertEquals(listOf("/available_models", "/chat", "/chat"), requests)
            assertEquals("test-model", client.resolvedModelId)
        }
    }

    private fun withServer(
        modelStatus: Int,
        modelBody: String,
        chatStatus: Int,
        chatBody: String,
        chatBodiesByModel: Map<String, String> = emptyMap(),
        sentModels: MutableList<String> = mutableListOf(),
        chatBodiesInOrder: List<String> = emptyList(),
        test: (AmplifyChatClient, List<String>) -> Unit
    ) {
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests.add(path)
            val requestBody = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val model = if (path == "/chat") {
                JSONObject(requestBody).getJSONObject("data").getJSONObject("options").getJSONObject("model").getString("id")
                    .also { sentModels.add(it) }
            } else null
            val status = if (path == "/available_models") modelStatus else chatStatus
            val body = if (path == "/available_models") modelBody else
                chatBodiesInOrder.getOrNull(sentModels.size - 1) ?: chatBodiesByModel[model] ?: chatBody
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val httpClient = HttpClient.newHttpClient()
        try {
            val client = AmplifyChatClient("http://127.0.0.1:${server.address.port}", "test-token", "", httpClient)
            test(client, requests)
        } finally {
            httpClient.close()
            server.stop(0)
        }
    }
}
