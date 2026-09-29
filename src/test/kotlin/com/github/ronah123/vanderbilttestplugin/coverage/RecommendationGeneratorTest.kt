package com.github.ronah123.vanderbilttestplugin.coverage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationGeneratorTest {

    @Test
    fun `malformed review and correction show only the readable draft`() {
        var calls = 0
        val client = object : ChatClient {
            override fun chatOnce(prompt: String): String {
                calls++
                return "The service returned an unexpected response."
            }
        }

        val result = RecommendationGenerator(client).generate("source context")

        assertEquals(3, calls)
        assertTrue(result.recommendations.isNotBlank())
        assertEquals("The service returned an unexpected response.", result.recommendations)
    }

    @Test
    fun `review failure preserves only the draft`() {
        var calls = 0
        val client = object : ChatClient {
            override fun chatOnce(prompt: String): String {
                if (++calls == 1) return "Try inputs 2 and 3. Expected: 5."
                throw java.io.IOException("private-server-details")
            }
        }
        val result = RecommendationGenerator(client).generate("synthetic context")
        assertEquals(2, calls)
        assertEquals("Try inputs 2 and 3. Expected: 5.", result.recommendations)
        assertTrue(!result.recommendations.contains("private-server-details"))
    }

    @Test
    fun `empty correction falls back to the concise draft when no item passed`() {
        val responses = ArrayDeque(listOf("Original draft", "Try a boundary input.", ""))
        val client = object : ChatClient {
            override fun chatOnce(prompt: String) = responses.removeFirst()
        }
        val result = RecommendationGenerator(client).generate("synthetic context")
        assertEquals("Original draft", result.recommendations)
    }

    @Test
    fun `correction request failure does not expose unchecked structured fields`() {
        var calls = 0
        val client = object : ChatClient {
            override fun chatOnce(prompt: String): String = when (++calls) {
                1 -> "Original draft"
                2 -> """{"recommendations":[{"name":"Boundary input","action":"Try zero"}]}"""
                else -> throw java.io.IOException("private-server-details")
            }
        }
        val result = RecommendationGenerator(client).generate("synthetic context")
        assertEquals(3, calls)
        assertEquals("Original draft", result.recommendations)
        assertTrue(!result.recommendations.contains("private-server-details"))
    }

    @Test
    fun `presentation-only review preserves the draft without a correction request`() {
        var calls = 0
        val client = object : ChatClient {
            override fun chatOnce(prompt: String): String = if (++calls == 1) "Try zero as the input." else "```\n```"
        }
        val result = RecommendationGenerator(client).generate("synthetic context")
        assertEquals(2, calls)
        assertEquals("Try zero as the input.", result.recommendations)
    }

    @Test
    fun `empty draft produces an explanation without requesting a review`() {
        var calls = 0
        val client = object : ChatClient {
            override fun chatOnce(prompt: String): String { calls++; return "" }
        }
        val result = RecommendationGenerator(client).generate("synthetic context")
        assertEquals(1, calls)
        assertTrue(result.recommendations.contains("no readable text"))
    }

    @Test
    fun `request failure stops generation before verification`() {
        var calls = 0
        val failure = java.io.IOException("Amplify authentication failed (HTTP 401).")
        val client = object : ChatClient {
            override fun chatOnce(prompt: String): String {
                calls++
                throw failure
            }
        }

        val actual = runCatching { RecommendationGenerator(client).generate("source context") }.exceptionOrNull()

        assertEquals(failure, actual)
        assertEquals(1, calls)
    }

    @Test
    fun `returns reviewed recommendations after two model calls`() {
        val prompts = mutableListOf<String>()
        val responses = ArrayDeque(
            listOf(
                "Expected: The total score is 52.",
                """{"recommendations":[{"name":"Useful case","covers":"A missed behavior.","action":"Use exact values.","expected":"The result is 62.","targetLines":[],"reachableLines":[]}],"alreadyCovered":""}"""
            )
        )
        val client = object : ChatClient {
            override fun chatOnce(prompt: String): String {
                prompts += prompt
                return responses.removeFirst()
            }
        }
        var reviewStarted = false

        val result = RecommendationGenerator(client).generate(
            contextPrompt = "complete source context",
            beforeVerification = { reviewStarted = true }
        )

        assertEquals(2, prompts.size)
        assertEquals("Expected: The total score is 52.", result.draft)
        assertTrue(result.recommendations.contains("Check: The result is 62."))
        assertTrue(reviewStarted)
        assertTrue(prompts[1].contains(result.draft))
        assertTrue(prompts[1].contains("complete source context"))
        assertEquals(null, result.correctionPrompt)
    }

    @Test
    fun `makes one correction call when deterministic validation fails`() {
        val prompts = mutableListOf<String>()
        val invalid = """{"recommendations":[{"name":"Move backward","covers":"Backward movement.","action":"On a 3 by 3 grid execute lf.","expected":"The rover returns (2,0,W).","targetLines":[56],"reachableLines":[56],"commandSequence":"lf","movement":"forward","gridWidth":3,"gridHeight":3}],"alreadyCovered":""}"""
        val corrected = """{"recommendations":[{"name":"Move forward west","covers":"Forward movement and horizontal wrapping.","action":"On a 3 by 3 grid execute lf.","expected":"The rover returns (2,0,W).","targetLines":[75],"reachableLines":[75],"commandSequence":"lf","movement":"forward","gridWidth":3,"gridHeight":3}],"alreadyCovered":""}"""
        val responses = ArrayDeque(listOf("draft", invalid, corrected))
        val client = object : ChatClient {
            override fun chatOnce(prompt: String): String {
                prompts += prompt
                return responses.removeFirst()
            }
        }
        var correctionStarted = false

        val result = RecommendationGenerator(client).generate(
            contextPrompt = "class MarsRover {}",
            beforeCorrection = { correctionStarted = true }
        )

        assertEquals(3, prompts.size)
        assertTrue(correctionStarted)
        assertTrue(result.recommendations.contains("Move forward west"))
        assertTrue(result.correctionPrompt!!.contains("says backward"))
    }

    @Test
    fun `partial rover review shows only checked student guidance`() {
        val partial = """
            {"recommendations":[
              {"name":"completes every right and left turn","covers":"Remaining turn directions.",
               "action":"On a 5 by 5 grid execute rrrrllll.","expected":"The rover returns (0,0,N).",
               "targetLines":[40],"reachableLines":[40],"commandSequence":"rrrrllll",
               "movement":"rotations only","gridWidth":5,"gridHeight":5},
              {"name":"parses an obstacle","covers":"Obstacle parsing.","action":"Try an obstacle.",
               "expected":"The rover returns (0,0,N).","targetLines":[86],"reachableLines":[86]}
            ],"alreadyCovered":"Existing tests cover forward movement. The suggested tests share setup that can be extracted into @BeforeEach."}
        """.trimIndent()
        val responses = ArrayDeque(listOf("Recommended tests\n1. Draft suggestion", partial, partial))
        val client = object : ChatClient {
            override fun chatOnce(prompt: String) = responses.removeFirst()
        }

        val shown = RecommendationGenerator(client).generate("class MarsRover {}").recommendations

        assertTrue(shown.startsWith("Still needs testing"))
        assertTrue(shown.contains("completes every right and left turn"))
        assertTrue(shown.contains("Do: On a 5 by 5 grid execute rrrrllll."))
        assertTrue(shown.contains("Already covered\n\nExisting tests cover forward movement."))
        assertTrue(!shown.contains("parses an obstacle"))
        assertTrue(!shown.contains("Target lines"))
        assertTrue(!shown.contains("Model guidance"))
        assertTrue(!shown.contains("Some suggestions passed"))
        assertTrue(!shown.contains("@BeforeEach"))
    }
}
