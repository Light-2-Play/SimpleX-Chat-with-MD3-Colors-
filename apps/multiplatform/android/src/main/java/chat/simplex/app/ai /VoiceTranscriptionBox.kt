package chat.simplex.app.ai

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceTranscriptionBox(
    audioFile: File?,
    isSent: Boolean = false,
    modifier: Modifier = Modifier,
    preferredModel: WhisperModelType = WhisperModelType.TINY
) {
    if (audioFile == null || !audioFile.exists()) return

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val transcriber = remember { VoiceTranscriptionManager.getTranscriber(context) }
    val downloader = remember { VoiceTranscriptionManager.getDownloader(context) }

    val filePath = audioFile.absolutePath
    val transcribedText = VoiceTranscriptionManager.transcriptions[filePath]
    val isLoading = VoiceTranscriptionManager.loadingStates[filePath] ?: false

    var showDownloadDialog by remember { mutableStateOf(false) }

    if (showDownloadDialog) {
        WhisperDownloadDialog(
            downloader = downloader,
            initialModelType = preferredModel,
            onDismiss = { showDownloadDialog = false },
            onModelReady = {
                showDownloadDialog = false
                scope.launch {
                    VoiceTranscriptionManager.transcribeAudio(context, audioFile, preferredModel)
                }
            }
        )
    }

    // Без fillMaxWidth(), чтобы баббл голосового не распирало на весь экран
    Column(
        modifier = modifier.wrapContentSize(),
        horizontalAlignment = if (isSent) Alignment.End else Alignment.Start
    ) {
        if (transcribedText == null) {
            Row(
                modifier = Modifier.wrapContentSize(),
                horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Transcribing...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Surface(
                        onClick = {
                            if (!transcriber.isModelAvailable(preferredModel)) {
                                showDownloadDialog = true
                            } else {
                                scope.launch {
                                    VoiceTranscriptionManager.transcribeAudio(context, audioFile, preferredModel)
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        tonalElevation = 1.dp,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "A →",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Text",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = transcribedText != null,
            enter = fadeIn() + expandVertically()
        ) {
            transcribedText?.let { text ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .wrapContentSize()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                clipboardManager.setText(AnnotatedString(text))
                                Toast.makeText(context, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}
