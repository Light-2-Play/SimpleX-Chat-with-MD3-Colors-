package chat.simplex.app.ai

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object VoiceTranscriptionManager {
    private var transcriber: WhisperTranscriber? = null
    private var downloader: WhisperDownloader? = null

    val transcriptions = mutableStateMapOf<String, String>()
    val loadingStates = mutableStateMapOf<String, Boolean>()

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
        transcriptions[path]?.let { return@withContext it }

        loadingStates[path] = true
        try {
            val t = getTranscriber(context)
            val result = t.transcribe(audioFile, modelType)
            val text = result.getOrElse { "Failed to transcribe speech: ${it.message}" }
            transcriptions[path] = text
            text
        } finally {
            loadingStates[path] = false
        }
    }
}
