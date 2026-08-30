package com.github.ronah123.vanderbilttestplugin.coverage

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
        beforeVerification()
        val verificationPrompt = CodeExtraction.buildVerificationPrompt(contextPrompt, draft)
        val reviewed = client.chatOnce(verificationPrompt)
        val firstCheck = RecommendationQualityGate.validateAndRender(reviewed, contextPrompt)
        if (firstCheck.isFullyValid) {
            return RecommendationGenerationResult(
                draft, verificationPrompt, null, verificationPrompt, firstCheck.rendered
            )
        }

        beforeCorrection()
        val correctionPrompt = CodeExtraction.buildCorrectionPrompt(contextPrompt, reviewed, firstCheck.errors)
        val corrected = client.chatOnce(correctionPrompt)
        val finalCheck = RecommendationQualityGate.validateAndRender(corrected, contextPrompt)
        return RecommendationGenerationResult(
            draft, verificationPrompt, correctionPrompt, correctionPrompt, finalCheck.rendered
        )
    }
}
