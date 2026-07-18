package com.example.hearingaid0411.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * 離線辨識器的程序級快取：模型載入（67MB encoder）要數秒，
 * 只做一次、跨服務重啟重複使用——之後每次「開始聆聽」都是秒開。
 *
 * App 啟動時呼叫 [preloadAsync] 背景預載，使用者按下開始時通常已就緒。
 */
object SherpaRuntime {

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var loadedEncoderPath: String? = null

    /** 取得（必要時建立）辨識器；模型未載入時會阻塞數秒，請在背景執行緒呼叫 */
    @Synchronized
    fun acquire(model: SherpaModel): OnlineRecognizer {
        val existing = recognizer
        if (existing != null && loadedEncoderPath == model.encoder) return existing
        // 模型檔換了（重新下載等）→ 釋放舊的重建
        existing?.let { try { it.release() } catch (_: Throwable) {} }
        val created = OnlineRecognizer(null, buildConfig(model))
        recognizer = created
        loadedEncoderPath = model.encoder
        return created
    }

    /** App 啟動時背景預載（模型不存在時安靜跳過） */
    fun preloadAsync(context: Context) {
        val appContext = context.applicationContext
        Thread({
            try {
                val model = SherpaModelLocator.find(appContext) ?: return@Thread
                acquire(model)
                SherpaCaptionEngine.prewarmOpenCc()
            } catch (_: Throwable) {
            }
        }, "SherpaPreload").start()
    }

    private fun buildConfig(model: SherpaModel) = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = AsrConstants.SAMPLE_RATE, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = model.encoder,
                decoder = model.decoder,
                joiner = model.joiner,
            ),
            tokens = model.tokens,
            numThreads = 2,
            provider = "cpu",
        ),
        endpointConfig = EndpointConfig(),
        enableEndpoint = true,
        decodingMethod = "greedy_search",
    )
}
