package com.github.ronah123.vanderbilttestplugin.coverage

import java.io.IOException

data class RecommendationGenerationResult(
    val draft: String,
    val verificationPrompt: String,
    val correctionPrompt: String?,
    val finalPrompt: String,
    val recommendations: String
)

/** Generates a draft, then independently reviews and corrects it before display. */
class RecommendationGenerator(private val client: ChatClient) {

    fun generate(
        contextPrompt: String,
        beforeVerification: () -> Unit = {},
        beforeCorrection: () -> Unit = {}
    ): RecommendationGenerationResult {
        val draft = client.chatOnce(contextPrompt)
        if (RecommendationTextFormatter.readableContent(draft).isBlank()) {
            return RecommendationGenerationResult(draft, contextPrompt, null, contextPrompt,
                RecommendationTextFormatter.NO_OUTPUT_MESSAGE)
        }
        beforeVerification()
        val verificationPrompt = CodeExtraction.buildVerificationPrompt(contextPrompt, draft)
        val reviewed = try {
            client.chatOnce(verificationPrompt)
        } catch (_: IOException) {
            return result(draft, verificationPrompt, null, draftGuidance(draft))
        }
        if (RecommendationTextFormatter.readableContent(reviewed).isBlank()) {
            return result(draft, verificationPrompt, null, draftGuidance(draft))
        }
        val firstCheck = RecommendationQualityGate.validateAndRender(reviewed, contextPrompt)
        if (firstCheck.isFullyValid) {
            return RecommendationGenerationResult(
                draft, verificationPrompt, null, verificationPrompt, firstCheck.rendered
            )
        }

        beforeCorrection()
        val correctionPrompt = CodeExtraction.buildCorrectionPrompt(contextPrompt, reviewed, firstCheck.errors)
        val corrected = try {
            client.chatOnce(correctionPrompt)
        } catch (_: IOException) {
            return result(draft, verificationPrompt, correctionPrompt, checkedOrDraft(firstCheck, draft))
        }
        if (RecommendationTextFormatter.readableContent(corrected).isBlank()) {
            return result(draft, verificationPrompt, correctionPrompt, checkedOrDraft(firstCheck, draft))
        }
        val finalCheck = RecommendationQualityGate.validateAndRender(corrected, contextPrompt)
        if (!finalCheck.isFullyValid) {
            val bestCheck = if (finalCheck.validCount >= firstCheck.validCount) finalCheck else firstCheck
            return result(draft, verificationPrompt, correctionPrompt, checkedOrDraft(bestCheck, draft))
        }
        return RecommendationGenerationResult(
            draft, verificationPrompt, correctionPrompt, correctionPrompt, finalCheck.rendered
        )
    }

    private fun checkedOrDraft(check: RecommendationQualityResult, draft: String): String =
        if (check.validCount > 0) check.rendered else draftGuidance(draft)

    private fun draftGuidance(draft: String): String =
        RecommendationTextFormatter.readableContent(draft).ifBlank { RecommendationTextFormatter.NO_OUTPUT_MESSAGE }

    private fun result(
        draft: String,
        verificationPrompt: String,
        correctionPrompt: String?,
        recommendations: String
    ): RecommendationGenerationResult {
        return RecommendationGenerationResult(
            draft, verificationPrompt, correctionPrompt, correctionPrompt ?: verificationPrompt,
            recommendations
        )
    }
}
