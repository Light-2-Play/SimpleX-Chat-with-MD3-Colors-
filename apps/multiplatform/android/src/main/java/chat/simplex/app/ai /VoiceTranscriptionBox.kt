package chat.simplex.app.ai

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

/**
 * Анимация плавающих эквалайзерных столбиков во время распознавания речи
 */
@Composable
fun TranscribingWaveAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "transcription_wave")

    val h1 by infiniteTransition.animateFloat(
        initialValue = 4f, targetValue = 14f,
        animationSpec = infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "h1"
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 13f, targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(320, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "h2"
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 6f, targetValue = 15f,
        animationSpec = infiniteRepeatable(tween(480, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "h3"
    )
    val h4 by infiniteTransition.animateFloat(
        initialValue = 11f, targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(360, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "h4"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        modifier = modifier.height(16.dp)
    ) {
        listOf(h1, h2, h3, h4).forEach { heightValue ->
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(heightValue.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(1.5.dp)
                    )
            )
        }
    }
}

@Composable
fun VoiceTranscriptionBox(
    audioFile: File?,
    isSent: Boolean = false,
    modifier: Modifier = Modifier
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
    var currentModel by remember { mutableStateOf(VoiceTranscriptionManager.getPreferredModel(context)) }

    if (showDownloadDialog) {
        WhisperDownloadDialog(
            downloader = downloader,
            initialModelType = currentModel,
            onDismiss = { showDownloadDialog = false },
            onModelReady = { model ->
                showDownloadDialog = false
                currentModel = model
                VoiceTranscriptionManager.setPreferredModel(context, model)
                scope.launch {
                    VoiceTranscriptionManager.transcribeAudio(context, audioFile, model)
                }
            }
        )
    }

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
                    // Плашка с живой анимацией эквалайзера
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        tonalElevation = 2.dp,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            TranscribingWaveAnimation()
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = "Transcribing...",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                } else {
                    // Активная кнопка: прямой onClick гарантирует реакцию на нажатие
                    Surface(
                        onClick = {
                            currentModel = VoiceTranscriptionManager.getPreferredModel(context)
                            if (!transcriber.isModelAvailable(currentModel)) {
                                showDownloadDialog = true
                            } else {
                                scope.launch {
                                    VoiceTranscriptionManager.transcribeAudio(context, audioFile, currentModel)
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
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Text",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )

                            // Разделитель и бейдж переключения модели
                            Spacer(modifier = Modifier.width(5.dp))
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(10.dp)
                                    .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.25f))
                            )
                            Spacer(modifier = Modifier.width(5.dp))

                            Text(
                                text = "${currentModel.id.uppercase()} ▾",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    showDownloadDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }

        // Показ готового текста (или ошибки)
        AnimatedVisibility(
            visible = transcribedText != null,
            enter = fadeIn() + expandVertically()
        ) {
            transcribedText?.let { text ->
                val isError = text.startsWith("Error:")
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isError) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    },
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .wrapContentSize()
                        .clickable {
                            if (isError) {
                                // При клике по ошибке даем возможность повторить
                                VoiceTranscriptionManager.transcriptions.remove(filePath)
                            } else {
                                clipboardManager.setText(AnnotatedString(text))
                                Toast.makeText(context, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        }
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = if (isError) "$text (tap to retry)" else text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}
