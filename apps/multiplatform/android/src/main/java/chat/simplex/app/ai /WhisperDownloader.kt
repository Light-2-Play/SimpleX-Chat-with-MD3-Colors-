package chat.simplex.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class DownloadState {
    object Idle : DownloadState()
    data class Progress(val percent: Int, val currentFile: String) : DownloadState()
    object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}

// Добавьте это свойство перед class WhisperDownloader:
val WhisperModelType.folderName: String
    get() = when (this) {
        WhisperModelType.TINY -> "tiny"
        WhisperModelType.BASE -> "base"
    }

class WhisperDownloader(private val context: Context) {
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    suspend fun downloadModel(modelType: WhisperModelType) = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, "models/${modelType.folderName}")
        if (!targetDir.exists()) targetDir.mkdirs()

        try {
            val files = listOf(
                "${modelType.folderName}-tokens.txt",
                "${modelType.folderName}-encoder.int8.onnx",
                "${modelType.folderName}-decoder.int8.onnx"
            )

            files.forEachIndexed { index, fileName ->
                val destination = File(targetDir, fileName)
                if (destination.exists() && destination.length() > 0) return@forEachIndexed

                val fileUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-${modelType.folderName}/resolve/main/$fileName"
                downloadFileWithRedirects(fileUrl, destination) { bytesDownloaded, totalBytes ->
                    val overallProgress = (((index + (bytesDownloaded.toFloat() / totalBytes)) / files.size) * 100).toInt()
                    _downloadState.value = DownloadState.Progress(overallProgress.coerceIn(0, 100), fileName)
                }
            }

            _downloadState.value = DownloadState.Completed
        } catch (e: Exception) {
            _downloadState.value = DownloadState.Error(e.localizedMessage ?: "Unknown download error")
        }
    }

    private fun downloadFileWithRedirects(
        initialUrl: String,
        destination: File,
        onProgress: (Long, Long) -> Unit
    ) {
        var currentUrl = URL(initialUrl)
        var connection: HttpURLConnection
        var redirects = 0

        while (true) {
            connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false // Обрабатываем редиректы вручную
            }

            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirect without Location header")
                // Разрешаем как абсолютные, так и относительные URL от Hugging Face
                currentUrl = URL(currentUrl, location)
                connection.disconnect()
                redirects++
                if (redirects > 8) throw IllegalStateException("Too many redirects")
            } else if (status in 200..299) {
                break
            } else {
                throw IllegalStateException("HTTP server returned code $status")
            }
        }

        val totalBytes = connection.contentLengthLong
        val tempFile = File(destination.parentFile, "${destination.name}.tmp")

        connection.inputStream.use { input ->
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalBytes > 0) {
                        onProgress(totalRead, totalBytes)
                    }
                }
            }
        }

        if (destination.exists()) destination.delete()
        tempFile.renameTo(destination)
    }
}
