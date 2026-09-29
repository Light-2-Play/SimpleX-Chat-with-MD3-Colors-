package chat.simplex.app.ai

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class WhisperModelType(val dirName: String) {
    TINY("whisper_tiny"),
    BASE("whisper_base")
}

class WhisperTranscriber(private val context: Context) {

    /**
     * Проверяет, лежат ли нужные файлы модели в хранилище приложения
     */
    fun isModelAvailable(modelType: WhisperModelType): Boolean {
        val dir = File(context.filesDir, "models/${modelType.dirName}")
        val encoder = File(dir, "encoder.int8.onnx")
        val decoder = File(dir, "decoder.int8.onnx")
        val tokens = File(dir, "tokens.txt")
        return encoder.exists() && decoder.exists() && tokens.exists()
    }

    /**
     * Выполняет распознавание речи
     */
    suspend fun transcribe(
        audioFile: File,
        modelType: WhisperModelType = WhisperModelType.TINY,
        language: String = "ru"
    ): Result<String> = withContext(Dispatchers.Default) {
        runCatching {
            val dir = File(context.filesDir, "models/${modelType.dirName}")
            if (!isModelAvailable(modelType)) {
                error("Файлы модели ${modelType.dirName} не найдены в ${dir.absolutePath}")
            }

            val encoderPath = File(dir, "encoder.int8.onnx").absolutePath
            val decoderPath = File(dir, "decoder.int8.onnx").absolutePath
            val tokensPath = File(dir, "tokens.txt").absolutePath

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = encoderPath,
                        decoder = decoderPath,
                        language = language,
                        task = "transcribe",
                        tailPaddings = 1000
                    ),
                    numThreads = 4,
                    debug = 0,
                    provider = "cpu"
                )
            )

            val recognizer = OfflineRecognizer(config = config)

            try {
                val samples = AudioDecoder.decodeTo16kMonoSamples(audioFile)
                val stream = recognizer.createStream()
                stream.acceptWaveform(samples, 16000)
                recognizer.decode(stream)

                val result = recognizer.getResult(stream)
                stream.release()

                result.text.trim()
            } finally {
                recognizer.release()
            }
        }
    }
}
