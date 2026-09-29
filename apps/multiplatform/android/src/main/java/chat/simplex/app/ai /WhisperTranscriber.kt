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

    // Единая папка: /files/models/whisper_tiny или /files/models/whisper_base
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

    fun isModelAvailable(modelType: WhisperModelType): Boolean {
        return modelType.isAvailable(context)
    }

    suspend fun transcribe(
        audioFile: File,
        modelType: WhisperModelType = WhisperModelType.TINY
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            if (!modelType.isAvailable(context)) {
                val dir = modelType.getDir(context)
                return@withContext Result.failure(
                    IllegalStateException("Whisper ${modelType.id} model files not found in ${dir.absolutePath}")
                )
            }

            val encoder = modelType.getEncoderFile(context)
            val decoder = modelType.getDecoderFile(context)
            val tokens = modelType.getTokensFile(context)

            // 1. Декодирование аудио
            // Если в AudioDecoder функция называется decode(), вызовите AudioDecoder.decode(audioFile)
            // 1. Вызываем функцию с ее настоящим именем decodeTo16kMonoSamples
            val samples = AudioDecoder.decodeTo16kMonoSamples(audioFile)
            if (samples.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("Failed to decode audio file or audio is empty"))
            }

            // 2. Конфигурация модели
            val modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    language = "", // автоопределение языка
                    task = "transcribe",
                    tailPaddings = 0
                ),
                modelType = "whisper",
                tokens = tokens.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )

            // 3. Параметр называется modelConfig (не offlineModelConfig)
            val config = OfflineRecognizerConfig(
                modelConfig = modelConfig
            )

            // 4. Первым аргументом передаем null (AssetManager не нужен, файлы читаются из filesDir)
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
