package com.github.ronah123.vanderbilttestplugin.coverage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationQualityGateTest {

    @Test
    fun `rejects backward title paired with forward rover command`() {
        val raw = marsRecommendation(
            name = "Wrap backward from the west",
            covers = "Backward movement and line 56.",
            commands = "lf",
            movement = "forward",
            expected = "The rover returns (2,0,W)."
        )

        val result = RecommendationQualityGate.validateAndRender(raw, "class MarsRover {}")

        assertFalse(result.isFullyValid)
        assertTrue(result.errors.any { it.contains("says backward") })
    }

    @Test
    fun `simulates rover commands and accepts a consistent recommendation`() {
        val raw = marsRecommendation(
            name = "Move forward while facing west",
            covers = "Forward west movement and wrapping.",
            commands = "lf",
            movement = "forward",
            expected = "The rover returns (2,0,W)."
        )

        val result = RecommendationQualityGate.validateAndRender(raw, "class MarsRover {}")

        assertTrue(result.isFullyValid)
        assertTrue(result.rendered.contains("The rover returns (2,0,W)."))
    }

    @Test
    fun `calculates bowling score and rejects an incorrect total`() {
        val result = RecommendationQualityGate.validateAndRender(bowlingRecommendation(52), "class BowlingGame {}")

        assertFalse(result.isFullyValid)
        assertTrue(result.errors.any { it.contains("calculated total 62") })
    }

    @Test
    fun `accepts bowling total calculated from exact frames and bonus throws`() {
        val result = RecommendationQualityGate.validateAndRender(bowlingRecommendation(62), "class BowlingGame {}")

        assertTrue(result.isFullyValid)
        assertEquals(1, result.validCount)
    }

    private fun marsRecommendation(
        name: String,
        covers: String,
        commands: String,
        movement: String,
        expected: String
    ): String = """
        {"recommendations":[{
          "name":"$name",
          "covers":"$covers",
          "action":"Use a 3 by 3 grid and execute $commands.",
          "expected":"$expected",
          "targetLines":[75],
          "reachableLines":[75],
          "commandSequence":"$commands",
          "movement":"$movement",
          "gridWidth":3,
          "gridHeight":3
        }],"alreadyCovered":""}
    """.trimIndent()

    private fun bowlingRecommendation(total: Int): String {
        val frames = (List(8) { "{\"firstThrow\":1,\"secondThrow\":1}" } +
            listOf("{\"firstThrow\":10,\"secondThrow\":0}", "{\"firstThrow\":10,\"secondThrow\":0}"))
            .joinToString(",")
        return """
            {"recommendations":[{
              "name":"Ninth and tenth frame strikes",
              "covers":"Strike bonuses at the tenth-frame boundary.",
              "action":"Create 8 open frames with throws 1 and 1, then two strikes, with bonus throws 7 and 2.",
              "expected":"The total score is $total.",
              "targetLines":[63,64,65],
              "reachableLines":[63,64,65],
              "bowlingFrames":[$frames],
              "bowlingBonus":{"firstThrow":7,"secondThrow":2},
              "expectedNumericTotal":$total
            }],"alreadyCovered":""}
        """.trimIndent()
    }
}
