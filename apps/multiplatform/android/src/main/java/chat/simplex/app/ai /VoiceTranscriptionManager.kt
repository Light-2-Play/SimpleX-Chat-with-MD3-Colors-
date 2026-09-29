package chat.simplex.app.ai

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object VoiceTranscriptionManager {
    private const val PREFS_NAME = "simplex_whisper_prefs"
    private const val KEY_PREFERRED_MODEL = "preferred_model"

    private var transcriber: WhisperTranscriber? = null
    private var downloader: WhisperDownloader? = null

    val transcriptions = mutableStateMapOf<String, String>()
    val loadingStates = mutableStateMapOf<String, Boolean>()

    fun getPreferredModel(context: Context): WhisperModelType {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_PREFERRED_MODEL, null)
        if (saved != null) {
            try {
                return WhisperModelType.valueOf(saved)
            } catch (_: Exception) {}
        }
        return when {
            WhisperModelType.BASE.isAvailable(context) -> WhisperModelType.BASE
            WhisperModelType.TINY.isAvailable(context) -> WhisperModelType.TINY
            else -> WhisperModelType.TINY
        }
    }

    fun setPreferredModel(context: Context, modelType: WhisperModelType) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PREFERRED_MODEL, modelType.name).apply()
    }

    fun getTranscriber(context: Context): WhisperTranscriber {
        return transcriber ?: WhisperTranscriber(context.applicationContext).also { transcriber = it }
    }

    fun getDownloader(context: Context): WhisperDownloader {
        return downloader ?: WhisperDownloader(context.applicationContext).also { downloader = it }
    }

    suspend fun transcribeAudio(
        context: Context,
        audioFile: File,
        modelType: WhisperModelType? = null
    ): String = withContext(Dispatchers.IO) {
        val path = audioFile.absolutePath
        val activeModel = modelType ?: getPreferredModel(context)

        // Если уже расшифровано успешно, возвращаем из кэша
        transcriptions[path]?.let { return@withContext it }

        loadingStates[path] = true
        try {
            val t = getTranscriber(context)
            val result = t.transcribe(audioFile, activeModel)
            val text = result.getOrElse { "Failed to transcribe speech: ${it.message}" }
            
            // Кэшируем только успешный результат, чтобы ошибки не блокировали повторные попытки
            if (result.isSuccess) {
                transcriptions[path] = text
            }
            text
        } finally {
            loadingStates[path] = false
        }
    }
}
