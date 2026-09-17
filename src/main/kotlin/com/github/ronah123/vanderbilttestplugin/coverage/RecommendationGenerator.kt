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
            return unverifiedResult(draft, verificationPrompt, null,
                "The accuracy review could not be completed. The earlier model answer is shown below.", draft)
        }
        if (RecommendationTextFormatter.readableContent(reviewed).isBlank()) {
            return unverifiedResult(draft, verificationPrompt, null,
                "Amplify returned no readable text during the accuracy review. The earlier model answer is shown below.", draft)
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
            return unverifiedResult(draft, verificationPrompt, correctionPrompt,
                "The model's answer could not be verified, and the correction request failed.", reviewed, draft)
        }
        if (RecommendationTextFormatter.readableContent(corrected).isBlank()) {
            return unverifiedResult(draft, verificationPrompt, correctionPrompt,
                "Amplify returned no readable text during correction. The earlier model answer is shown below.", reviewed, draft)
        }
        val finalCheck = RecommendationQualityGate.validateAndRender(corrected, contextPrompt)
        if (!finalCheck.isFullyValid) {
            val reason = if (finalCheck.validCount > 0) {
                finalCheck.rendered + "\n\nSome model output did not pass the accuracy checks."
            } else "The model's answer could not be verified. Its available response is shown below."
            return unverifiedResult(draft, verificationPrompt, correctionPrompt, reason, corrected, reviewed, draft)
        }
        return RecommendationGenerationResult(
            draft, verificationPrompt, correctionPrompt, correctionPrompt, finalCheck.rendered
        )
    }

    private fun unverifiedResult(
        draft: String,
        verificationPrompt: String,
        correctionPrompt: String?,
        reason: String,
        vararg responses: String
    ): RecommendationGenerationResult {
        val response = responses.asSequence().map(RecommendationTextFormatter::readableContent)
            .firstOrNull { it.isNotBlank() } ?: RecommendationTextFormatter.NO_OUTPUT_MESSAGE
        return RecommendationGenerationResult(
            draft, verificationPrompt, correctionPrompt, correctionPrompt ?: verificationPrompt,
            "$reason\n\nModel response (unverified)\nCheck the expected results against your code before using this advice.\n\n$response"
        )
    }
}
