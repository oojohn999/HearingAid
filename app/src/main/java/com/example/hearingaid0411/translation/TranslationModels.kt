package com.example.hearingaid0411.translation

data class TranslationRequest(
    val q: List<String>,
    val target: String,
    val source: String
)

data class TranslationResponse(
    val data: TranslationData
)

data class TranslationData(
    val translations: List<Translation>
)

data class Translation(
    val translatedText: String
)
