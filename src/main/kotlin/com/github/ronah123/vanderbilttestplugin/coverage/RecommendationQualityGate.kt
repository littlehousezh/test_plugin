package com.github.ronah123.vanderbilttestplugin.coverage

import org.json.JSONArray
import org.json.JSONObject

data class RecommendationQualityResult(
    val rendered: String,
    val errors: List<String>,
    val validCount: Int,
    val totalCount: Int
) {
    val isFullyValid: Boolean get() = errors.isEmpty() && validCount == totalCount && totalCount > 0
}

/** Deterministic checks for the two assignment domains before advice reaches students. */
object RecommendationQualityGate {

    fun validateAndRender(raw: String, context: String): RecommendationQualityResult {
        val root = runCatching { JSONObject(raw.trim()) }.getOrElse {
            return RecommendationQualityResult("", listOf("The review response was not valid JSON."), 0, 0)
        }
        val items = root.optJSONArray("recommendations") ?: JSONArray()
        val errors = mutableListOf<String>()
        val valid = mutableListOf<JSONObject>()

        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index)
            if (item == null) {
                errors += "Recommendation ${index + 1} is not an object."
                continue
            }
            val itemErrors = validateItem(item, context).map { "Recommendation ${index + 1}: $it" }
            if (itemErrors.isEmpty()) valid += item else errors += itemErrors
        }
        if (items.length() == 0) errors += "No recommendations were returned."

        return RecommendationQualityResult(
            rendered = render(valid, root.optString("alreadyCovered")),
            errors = errors,
            validCount = valid.size,
            totalCount = items.length()
        )
    }

    private fun validateItem(item: JSONObject, context: String): List<String> {
        val errors = mutableListOf<String>()
        val name = item.optString("name").trim()
        val covers = item.optString("covers").trim()
        val action = item.optString("action").trim()
        val expected = item.optString("expected").trim()
        if (name.isEmpty()) errors += "name is missing."
        if (covers.isEmpty()) errors += "Covers is missing."
        if (action.isEmpty()) errors += "Action is missing."
        if (expected.isEmpty()) errors += "Expected is missing."
        if (listOf(name, covers, action, expected).any(::containsCode)) errors += "student-facing text contains test code."

        val targetLines = item.optJSONArray("targetLines").intSet()
        val reachableLines = item.optJSONArray("reachableLines").intSet()
        if (!reachableLines.containsAll(targetLines)) errors += "claimed target lines are not all listed as reachable."

        when {
            context.contains("class MarsRover") -> errors += validateMars(item, name, covers, action, expected)
            context.contains("class BowlingGame") -> errors += validateBowling(item, action, expected)
        }
        return errors
    }

    private fun validateMars(
        item: JSONObject,
        name: String,
        covers: String,
        action: String,
        expected: String
    ): List<String> {
        val errors = mutableListOf<String>()
        val width = item.optInt("gridWidth", 0)
        val height = item.optInt("gridHeight", 0)
        val commands = item.optString("commandSequence").lowercase()
        val movement = item.optString("movement").lowercase()
        if (width <= 0 || height <= 0) errors += "grid width and height must be specified."
        if (commands.isEmpty() || commands.any { it !in "lrfb" }) errors += "an exact valid command sequence is required."
        if (commands.isNotEmpty() && !action.lowercase().replace(" ", "").contains(commands)) {
            errors += "Action must state the exact command sequence $commands."
        }

        val description = "$name $covers".lowercase()
        if ("backward" in description && (movement != "backward" || 'b' !in commands)) {
            errors += "the title or Covers says backward, but the movement facts or commands do not."
        }
        if ("forward" in description && (movement != "forward" || 'f' !in commands)) {
            errors += "the title or Covers says forward, but the movement facts or commands do not."
        }

        if (width > 0 && height > 0 && commands.isNotEmpty() && commands.all { it in "lrfb" }) {
            val simulated = simulateRover(width, height, commands)
            val match = Regex("""\((-?\d+),(-?\d+),([NSEW])\)""").find(expected)
            if (match == null) {
                errors += "Expected must contain an exact rover result such as (0,2,N)."
            } else {
                val stated = Triple(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3][0])
                if (stated != simulated) errors += "Expected $stated does not match simulated result $simulated."
            }
        }
        return errors
    }

    private fun simulateRover(width: Int, height: Int, commands: String): Triple<Int, Int, Char> {
        var x = 0
        var y = 0
        var facing = 'N'
        for (command in commands) {
            if (command == 'r') facing = mapOf('N' to 'E', 'E' to 'S', 'S' to 'W', 'W' to 'N').getValue(facing)
            if (command == 'l') facing = mapOf('N' to 'W', 'W' to 'S', 'S' to 'E', 'E' to 'N').getValue(facing)
            if (command == 'f' || command == 'b') {
                val step = if (command == 'f') 1 else -1
                when (facing) {
                    'N' -> y += step
                    'S' -> y -= step
                    'E' -> x += step
                    'W' -> x -= step
                }
                x = ((x % width) + width) % width
                y = ((y % height) + height) % height
            }
        }
        return Triple(x, y, facing)
    }

    private fun validateBowling(item: JSONObject, action: String, expected: String): List<String> {
        val errors = mutableListOf<String>()
        val framesJson = item.optJSONArray("bowlingFrames") ?: JSONArray()
        val frames = mutableListOf<Pair<Int, Int>>()
        for (index in 0 until framesJson.length()) {
            val frame = framesJson.optJSONObject(index) ?: continue
            frames += frame.optInt("firstThrow", -1) to frame.optInt("secondThrow", -1)
        }
        if (frames.isEmpty() || frames.size > 10 || frames.any { it.first !in 0..10 || it.second !in 0..10 }) {
            errors += "bowlingFrames must list every frame with valid throw values."
            return errors
        }

        val repeated = frames.groupingBy { it }.eachCount().maxByOrNull { it.value }
        if (repeated != null && repeated.value > 1) {
            val (throws, count) = repeated
            val numbers = Regex("""\d+""").findAll(action).map { it.value.toInt() }.toList()
            if (count !in numbers || throws.first !in numbers || throws.second !in numbers) {
                errors += "Action must state the repeated frame count and both throw values using numerals."
            }
        }

        val bonus = item.optJSONObject("bowlingBonus")
        val bonusFirst = bonus?.optInt("firstThrow", 0) ?: 0
        val bonusSecond = bonus?.optInt("secondThrow", 0) ?: 0
        val calculated = bowlingScore(frames, bonusFirst, bonusSecond, bonus != null)
        val statedTotal = item.optInt("expectedNumericTotal", Int.MIN_VALUE)
        if (statedTotal == Int.MIN_VALUE) errors += "expectedNumericTotal is required."
        else if (statedTotal != calculated) errors += "expected total $statedTotal does not match calculated total $calculated."
        if (statedTotal != Int.MIN_VALUE && !Regex("""\b$statedTotal\b""").containsMatchIn(expected)) {
            errors += "Expected text must contain the exact total $statedTotal."
        }
        return errors
    }

    private fun bowlingScore(frames: List<Pair<Int, Int>>, bonusFirst: Int, bonusSecond: Int, hasBonus: Boolean): Int {
        var total = 0
        for (index in frames.indices) {
            val current = frames[index]
            total += current.first + current.second
            val strike = current.first == 10
            val spare = !strike && current.first + current.second == 10
            if (strike) {
                if (index == 9) {
                    if (hasBonus) total += bonusFirst + bonusSecond
                } else if (index + 1 < frames.size) {
                    val next = frames[index + 1]
                    total += next.first
                    if (next.first == 10) {
                        if (index + 1 == 9) {
                            if (hasBonus) total += bonusFirst
                        } else if (index + 2 < frames.size) total += frames[index + 2].first
                    } else total += next.second
                }
            } else if (spare) {
                if (index == 9) {
                    if (hasBonus) total += bonusFirst
                } else if (index + 1 < frames.size) total += frames[index + 1].first
            }
        }
        return total
    }

    private fun render(items: List<JSONObject>, alreadyCovered: String): String {
        if (items.isEmpty()) return "No recommendations passed the accuracy checks."
        return buildString {
            appendLine("Recommended tests")
            items.forEachIndexed { index, item ->
                appendLine()
                appendLine("${index + 1}. ${item.getString("name")}")
                appendLine("   Covers: ${item.getString("covers")}")
                appendLine("   Action: ${item.getString("action")}")
                appendLine("   Expected: ${item.getString("expected")}")
            }
            if (alreadyCovered.isNotBlank()) {
                appendLine()
                appendLine("Already covered")
                appendLine()
                append(alreadyCovered.trim())
            }
        }.trim()
    }

    private fun containsCode(text: String): Boolean =
        listOf("assertEquals(", "assertTrue(", "assertFalse(", "assertThrows(", "@Test", "new MarsRover(", "new BowlingGame(")
            .any { it in text }

    private fun JSONArray?.intSet(): Set<Int> = if (this == null) emptySet() else buildSet {
        for (index in 0 until length()) add(optInt(index))
    }
}
