package chat.simplex.app.ai

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class WhisperModelType(val id: String) {
    TINY("tiny"),
    BASE("base");

    fun getDir(context: Context): File = File(context.filesDir, "models/whisper_$id")
    fun getTokensFile(context: Context): File = File(getDir(context), "$id-tokens.txt")
    fun getEncoderFile(context: Context): File = File(getDir(context), "$id-encoder.int8.onnx")
    fun getDecoderFile(context: Context): File = File(getDir(context), "$id-decoder.int8.onnx")

    fun getDownloadUrl(fileName: String): String =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$id/resolve/main/$fileName"

    fun getRequiredFileNames(): List<String> = listOf(
        "$id-tokens.txt",
        "$id-encoder.int8.onnx",
        "$id-decoder.int8.onnx"
    )

    fun isAvailable(context: Context): Boolean {
        val tokens = getTokensFile(context)
        val encoder = getEncoderFile(context)
        val decoder = getDecoderFile(context)
        return tokens.exists() && tokens.length() > 0 &&
               encoder.exists() && encoder.length() > 0 &&
               decoder.exists() && decoder.length() > 0
    }
}

class WhisperTranscriber(private val context: Context) {

    // Автоматически определяет, какая модель уже скачана на устройство
    fun getInstalledModel(): WhisperModelType? {
        return when {
            WhisperModelType.BASE.isAvailable(context) -> WhisperModelType.BASE
            WhisperModelType.TINY.isAvailable(context) -> WhisperModelType.TINY
            else -> null
        }
    }

    fun isModelAvailable(modelType: WhisperModelType): Boolean {
        return modelType.isAvailable(context)
    }

    suspend fun transcribe(
        fileSource: chat.simplex.common.model.CryptoFile,
        modelType: WhisperModelType? = null
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            val targetModel = modelType ?: getInstalledModel()
                ?: return@withContext Result.failure(
                    IllegalStateException("No Whisper model found. Please download Tiny or Base first.")
                )

            val encoder = targetModel.getEncoderFile(context)
            val decoder = targetModel.getDecoderFile(context)
            val tokens = targetModel.getTokensFile(context)

            // Передаем fileSource в обновленный AudioDecoder
            val samples = AudioDecoder.decodeTo16kMonoSamples(fileSource)
            if (samples.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("Failed to decode audio file or audio is empty"))
            }

            val modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    language = "",
                    task = "transcribe",
                    tailPaddings = 0
                ),
                modelType = "whisper",
                tokens = tokens.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )

            val config = OfflineRecognizerConfig(modelConfig = modelConfig)
            val recognizer = OfflineRecognizer(null, config)
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)

            stream.release()
            recognizer.release()

            val text = result.text.trim()
            if (text.isEmpty()) {
                Result.success("[No speech detected]")
            } else {
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
