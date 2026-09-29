package chat.simplex.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

class WhisperDownloader(private val context: Context) {

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private data class FileItem(val url: String, val targetName: String)

    private fun getModelFiles(type: WhisperModelType): List<FileItem> {
        val baseUrl = when (type) {
            WhisperModelType.TINY -> "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main"
            WhisperModelType.BASE -> "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main"
        }
        val prefix = if (type == WhisperModelType.TINY) "tiny" else "base"

        return listOf(
            FileItem("$baseUrl/$prefix-tokens.txt", "tokens.txt"),
            FileItem("$baseUrl/$prefix-encoder.int8.onnx", "encoder.int8.onnx"),
            FileItem("$baseUrl/$prefix-decoder.int8.onnx", "decoder.int8.onnx")
        )
    }

    suspend fun downloadModel(type: WhisperModelType) = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, "models/${type.dirName}").apply { mkdirs() }
        val items = getModelFiles(type)

        try {
            items.forEachIndexed { index, item ->
                val finalFile = File(targetDir, item.targetName)
                if (finalFile.exists() && finalFile.length() > 0) {
                    // Файл уже скачан — пропускаем
                    return@forEachIndexed
                }

                val tempFile = File(targetDir, "${item.targetName}.tmp")
                downloadFileWithRedirects(
                    urlString = item.url,
                    outputFile = tempFile,
                    onProgress = { filePercent ->
                        // Рассчитываем общий процент с учетом индекса текущего файла
                        val totalPercent = ((index * 100 + filePercent) / items.size).coerceIn(0, 100)
                        _downloadState.value = DownloadState.Progress(totalPercent, item.targetName)
                    }
                )

                // Атомарно переименовываем скачанный временный файл в чистовой
                if (tempFile.exists()) {
                    tempFile.renameTo(finalFile)
                }
            }

            _downloadState.value = DownloadState.Completed
        } catch (e: Exception) {
            _downloadState.value = DownloadState.Error(e.localizedMessage ?: "Ошибка скачивания")
        }
    }

    private fun downloadFileWithRedirects(
        urlString: String,
        outputFile: File,
        onProgress: (Int) -> Unit
    ) {
        var currentUrl = urlString
        var connection: HttpURLConnection
        var redirects = 0

        while (true) {
            val url = URL(currentUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
            }

            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == 307 || status == 308
            ) {
                currentUrl = connection.getHeaderField("Location")
                connection.disconnect()
                redirects++
                if (redirects > 5) error("Слишком много перенаправлений")
                continue
            }
            break
        }

        val totalLength = connection.contentLengthLong
        connection.inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(16 * 1024)
                var bytesCopied = 0L
                var read: Int

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesCopied += read
                    if (totalLength > 0) {
                        val progress = ((bytesCopied * 100) / totalLength).toInt()
                        onProgress(progress)
                    }
                }
            }
        }
        connection.disconnect()
    }

    fun deleteModel(type: WhisperModelType) {
        val dir = File(context.filesDir, "models/${type.dirName}")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        _downloadState.value = DownloadState.Idle
    }
}
