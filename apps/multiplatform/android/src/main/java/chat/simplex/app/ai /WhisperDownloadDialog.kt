package chat.simplex.app.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

@Composable
fun WhisperDownloadDialog(
    downloader: WhisperDownloader,
    initialModelType: WhisperModelType = WhisperModelType.TINY,
    onDismiss: () -> Unit,
    onModelReady: (WhisperModelType) -> Unit
) {
    val context = LocalContext.current
    val downloadState by downloader.downloadState.collectAsState()
    var selectedType by remember { mutableStateOf(initialModelType) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = {
        if (downloadState !is DownloadState.Progress) onDismiss()
    }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
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
                    text = "Speech Recognition Model",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                when (val state = downloadState) {
                    is DownloadState.Idle, is DownloadState.Completed -> {
                        Text(
                            text = "Choose an AI model for offline speech-to-text:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        val tinyAvailable = remember(state) { WhisperModelType.TINY.isAvailable(context) }
                        val baseAvailable = remember(state) { WhisperModelType.BASE.isAvailable(context) }

                        // Модель Tiny
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedType = WhisperModelType.TINY }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedType == WhisperModelType.TINY,
                                onClick = { selectedType = WhisperModelType.TINY }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Tiny (~135 MB)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (tinyAvailable) "Downloaded • Fast & lightweight" else "Not downloaded • ~135 MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (tinyAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Модель Base
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedType = WhisperModelType.BASE }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedType == WhisperModelType.BASE,
                                onClick = { selectedType = WhisperModelType.BASE }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Base (~230 MB)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (baseAvailable) "Downloaded • Higher accuracy" else "Not downloaded • ~230 MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (baseAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        val isSelectedModelAvailable = if (selectedType == WhisperModelType.TINY) tinyAvailable else baseAvailable

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text("Cancel")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                if (isSelectedModelAvailable) {
                                    VoiceTranscriptionManager.setPreferredModel(context, selectedType)
                                    onModelReady(selectedType)
                                    onDismiss()
                                } else {
                                    scope.launch {
                                        downloader.downloadModel(selectedType)
                                    }
                                }
                            }) {
                                Text(if (isSelectedModelAvailable) "Use Model" else "Download")
                            }
                        }
                    }

                    is DownloadState.Progress -> {
                        Text(
                            text = "Downloading ${selectedType.id.replaceFirstChar { it.uppercase() }}: ${state.currentFile}",
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

                    is DownloadState.Error -> {
                        Text(
                            text = "Download error: ${state.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            scope.launch { downloader.downloadModel(selectedType) }
                        }) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}
