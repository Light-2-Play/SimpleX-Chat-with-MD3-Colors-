package chat.simplex.app.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

@Composable
fun WhisperDownloadDialog(
    downloader: WhisperDownloader,
    initialModelType: WhisperModelType = WhisperModelType.TINY,
    onDismiss: () -> Unit,
    onModelReady: () -> Unit
) {
    val downloadState by downloader.downloadState.collectAsState()
    var selectedType by remember { mutableStateOf(initialModelType) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = {
        if (downloadState !is DownloadState.Progress) onDismiss()
    }) {
        Surface(
            shape = RoundedCornerShape(24.dp), // Фирменное закругление MD3
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Офлайн-распознавание речи",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                when (val state = downloadState) {
                    is DownloadState.Idle -> {
                        Text(
                            text = "Выберите модель Whisper для загрузки на устройство:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Выбор модели Tiny / Base
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedType == WhisperModelType.TINY,
                                onClick = { selectedType = WhisperModelType.TINY }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Tiny (~135 МБ) — быстро и легко",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedType == WhisperModelType.BASE,
                                onClick = { selectedType = WhisperModelType.BASE }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Base (~230 МБ) — выше точность",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text("Отмена")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                scope.launch {
                                    downloader.downloadModel(selectedType)
                                }
                            }) {
                                Text("Скачать")
                            }
                        }
                    }

                    is DownloadState.Progress -> {
                        Text(
                            text = "Загрузка: ${state.currentFile}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${state.percent}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    is DownloadState.Completed -> {
                        Text(
                            text = "Модель успешно загружена!",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            onModelReady()
                            onDismiss()
                        }) {
                            Text("Готово")
                        }
                    }

                    is DownloadState.Error -> {
                        Text(
                            text = "Ошибка загрузки: ${state.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            scope.launch { downloader.downloadModel(selectedType) }
                        }) {
                            Text("Повторить")
                        }
                    }
                }
            }
        }
    }
}
