package chat.simplex.app.ai

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import chat.simplex.common.model.CryptoFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object VoiceTranscriptionManager {
    private var transcriber: WhisperTranscriber? = null
    private var downloader: WhisperDownloader? = null

    // Независимый скоуп приложения: никогда не отменится при скролле чата или пересоздании UI
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val transcriptions = mutableStateMapOf<String, String>()
    val loadingStates = mutableStateMapOf<String, Boolean>()

    fun getTranscriber(context: Context): WhisperTranscriber {
        return transcriber ?: WhisperTranscriber(context.applicationContext).also { transcriber = it }
    }

    fun getDownloader(context: Context): WhisperDownloader {
        return downloader ?: WhisperDownloader(context.applicationContext).also { downloader = it }
    }

    fun getPreferredModel(context: Context): WhisperModelType {
        val prefs = context.getSharedPreferences("simplex_whisper", Context.MODE_PRIVATE)
        val savedId = prefs.getString("preferred_model", null)
        val model = if (savedId == WhisperModelType.BASE.id) WhisperModelType.BASE else WhisperModelType.TINY
        val t = getTranscriber(context)
        return if (t.isModelAvailable(model)) model else (t.getInstalledModel() ?: WhisperModelType.TINY)
    }

    fun setPreferredModel(context: Context, model: WhisperModelType) {
        val prefs = context.getSharedPreferences("simplex_whisper", Context.MODE_PRIVATE)
        prefs.edit().putString("preferred_model", model.id).apply()
    }

    /**
     * Запускает распознавание в глобальном пуле потоков.
     */
    fun startTranscription(
        context: Context,
        fileSource: CryptoFile,
        modelType: WhisperModelType? = null
    ) {
        val path = fileSource.filePath
        if (loadingStates[path] == true) return

        loadingStates[path] = true
        appScope.launch {
            try {
                val t = getTranscriber(context)
                val result = t.transcribe(fileSource, modelType)
                result.fold(
                    onSuccess = { text ->
                        transcriptions[path] = text
                    },
                    onFailure = { error ->
                        transcriptions[path] = "Error: ${error.message ?: "Recognition failed"}"
                    }
                )
            } catch (e: Throwable) {
                if (e !is CancellationException) {
                    transcriptions[path] = "Error: ${e.message ?: "Failed"}"
                }
            } finally {
                loadingStates[path] = false
            }
        }
    }

    // Оставляем для обратной совместимости
    suspend fun transcribeAudio(
        context: Context,
        fileSource: CryptoFile,
        modelType: WhisperModelType? = null
    ): String {
        startTranscription(context, fileSource, modelType)
        return transcriptions[fileSource.filePath] ?: ""
    }
}
