package chat.simplex.app.ai

import android.content.Context
import chat.simplex.common.model.CryptoFile
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
        fileSource: CryptoFile,
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

            // Передаем CryptoFile напрямую в AudioDecoder
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
                numThreads = 4,
                debug = false,
                provider = "cpu"
            )

            val config = OfflineRecognizerConfig(modelConfig = modelConfig)
            val recognizer = OfflineRecognizer(null, config)

            val chunks = splitIntoChunks(samples, sampleRate = 16000)
            val fullTextBuilder = java.lang.StringBuilder()

            for (chunk in chunks) {
                val stream = recognizer.createStream()
                stream.acceptWaveform(chunk, 16000)
                recognizer.decode(stream)
                val chunkResult = recognizer.getResult(stream)

                val chunkText = chunkResult.text
                    .replace("[BLANK_AUDIO]", "")
                    .trim()

                if (chunkText.isNotEmpty()) {
                    if (fullTextBuilder.isNotEmpty()) {
                        fullTextBuilder.append(" ")
                    }
                    fullTextBuilder.append(chunkText)
                }

                stream.release()
            }

            recognizer.release()

            val finalText = fullTextBuilder.toString().trim()
            if (finalText.isEmpty()) {
                Result.success("[No speech detected]")
            } else {
                Result.success(finalText)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun splitIntoChunks(samples: FloatArray, sampleRate: Int): List<FloatArray> {
        val maxChunkSamples = 29 * sampleRate
        if (samples.size <= maxChunkSamples) {
            return listOf(samples)
        }

        val chunks = mutableListOf<FloatArray>()
        var startIndex = 0

        while (startIndex < samples.size) {
            val remaining = samples.size - startIndex
            if (remaining <= maxChunkSamples) {
                chunks.add(samples.copyOfRange(startIndex, samples.size))
                break
            }

            val searchStart = startIndex + (22 * sampleRate)
            val searchEnd = startIndex + maxChunkSamples
            val windowSize = sampleRate / 10

            var minEnergy = Float.MAX_VALUE
            var bestSplitIndex = startIndex + maxChunkSamples

            var i = searchStart
            while (i + windowSize <= searchEnd) {
                var energy = 0f
                for (j in 0 until windowSize) {
                    val s = samples[i + j]
                    energy += s * s
                }
                if (energy < minEnergy) {
                    minEnergy = energy
                    bestSplitIndex = i + (windowSize / 2)
                }
                i += windowSize
            }

            chunks.add(samples.copyOfRange(startIndex, bestSplitIndex))
            startIndex = bestSplitIndex
        }

        return chunks
    }
}
