package com.github.ronah123.vanderbilttestplugin.coverage

import com.github.ronah123.vanderbilttestplugin.actions.MethodHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeExtractionPromptTest {

    @Test
    fun `includes complete production context once and keeps hotspot method`() {
        val gameSource = ProductionSourceBundle("BowlingGame", "/src/BowlingGame.java", "class BowlingGame { void score() {} }")
        val frameSource = ProductionSourceBundle("Frame", "/src/Frame.java", "class Frame { int getScore() { return 0; } }")
        val method = MethodBundle(
            classFqn = "BowlingGame",
            methodName = "calculateStrikeBonus",
            methodText = "private int calculateStrikeBonus(int index) { return 0; }",
            sourceFilePath = "/src/BowlingGame.java",
            startLine = 10,
            endLine = 12,
            productionSources = listOf(gameSource, frameSource)
        )
        val hit = MethodHit("BowlingGame", "calculateStrikeBonus(I)I", 3, 2, 1, 2.0 / 3.0, listOf(11))

        val prompt = CodeExtraction.buildPrompt(
            bundles = listOf(MethodCoverageBundle(hit, method), MethodCoverageBundle(hit, method)),
            testFiles = emptyList()
        )

        assertTrue(prompt.contains("class BowlingGame { void score() {} }"))
        assertTrue(prompt.contains("class Frame { int getScore() { return 0; } }"))
        assertEquals(1, Regex("Production source 1: /src/BowlingGame.java").findAll(prompt).count())
        assertEquals(1, Regex("Production source 2: /src/Frame.java").findAll(prompt).count())
        assertTrue(prompt.contains("private int calculateStrikeBonus(int index)"))
    }

    @Test
    fun `requires reachability and exact expected values in prose`() {
        val method = MethodBundle(
            classFqn = "Example",
            methodName = "run",
            methodText = "int run() { return 1; }",
            sourceFilePath = "/src/Example.java",
            startLine = 1,
            endLine = 1,
            productionSources = listOf(ProductionSourceBundle("Example", "/src/Example.java", "class Example {}"))
        )
        val hit = MethodHit("Example", "run()I", 1, 0, 1, 0.0, listOf(1))

        val prompt = CodeExtraction.buildPrompt(listOf(MethodCoverageBundle(hit, method)), emptyList())
        val instructions = prompt.substringBefore("===== No relevant test files found in project content =====")

        assertTrue(prompt.contains("proposed setup actually reaches that line"))
        assertTrue(prompt.contains("zero-based indexes"))
        assertTrue(prompt.contains("The total score is 46"))
        assertTrue(prompt.contains("precise plain-language outcome"))
        assertTrue(prompt.contains("without writing the assertion statement"))
        assertFalse(instructions.contains("assertEquals("))
        assertTrue(prompt.contains("Covers, Action, and Expected lines agree"))
        assertTrue(prompt.contains("Write for a novice programmer"))
        assertTrue(prompt.contains("exact grid dimensions and command sequences"))
        assertTrue(prompt.contains("both throw values for every repeated frame type"))
        assertTrue(prompt.contains("Never use vague setup phrases"))
    }

    @Test
    fun `verification prompt independently checks arithmetic reachability and novice wording`() {
        val verificationPrompt = CodeExtraction.buildVerificationPrompt(
            contextPrompt = "production source and current tests",
            draft = "Expected: The total score is 52."
        )

        assertTrue(verificationPrompt.contains("Do not trust the draft's arithmetic"))
        assertTrue(verificationPrompt.contains("Recalculate every Expected result from scratch"))
        assertTrue(verificationPrompt.contains("first and second throw values"))
        assertTrue(verificationPrompt.contains("wording a novice can follow"))
        assertTrue(verificationPrompt.contains("Expected: The total score is 52."))
        assertTrue(verificationPrompt.contains("production source and current tests"))
    }
}
