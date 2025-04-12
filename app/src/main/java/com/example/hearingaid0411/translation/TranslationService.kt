package com.example.hearingaid0411.translation

import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface TranslationApi {
    @POST("language/translate/v2")
    fun translate(
        @Header("x-goog-api-key") apiKey: String,
        @Body request: TranslationRequest
    ): Call<TranslationResponse>
}

class TranslationService {
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://translation.googleapis.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(TranslationApi::class.java)

    // 將這裡替換為您的 Google Cloud API 密鑰
    private val apiKey = "YOUR_API_KEY"

    fun translate(text: String, sourceLanguage: String, targetLanguage: String, callback: (String?, Exception?) -> Unit) {
        val request = TranslationRequest(
            q = listOf(text),
            source = sourceLanguage,
            target = targetLanguage
        )

        api.translate(apiKey, request).enqueue(object : retrofit2.Callback<TranslationResponse> {
            override fun onResponse(call: Call<TranslationResponse>, response: retrofit2.Response<TranslationResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val translatedText = response.body()!!.data.translations[0].translatedText
                    callback(translatedText, null)
                } else {
                    callback(null, Exception("翻譯失敗: ${response.code()} ${response.message()}"))
                }
            }

            override fun onFailure(call: Call<TranslationResponse>, t: Throwable) {
                callback(null, Exception("翻譯請求失敗: ${t.message}"))
            }
        })
    }
}
