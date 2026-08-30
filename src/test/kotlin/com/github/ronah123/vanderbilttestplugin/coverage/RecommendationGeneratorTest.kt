package com.github.ronah123.vanderbilttestplugin.coverage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationGeneratorTest {

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
        assertTrue(result.recommendations.contains("Expected: The result is 62."))
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
}
