package chat.simplex.app.ai

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import chat.simplex.app.MonetPalette
import chat.simplex.common.model.CryptoFile
import kotlinx.coroutines.launch

@Composable
fun TranscribingWaveAnimation(
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
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
                        color = color,
                        shape = RoundedCornerShape(1.5.dp)
                    )
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceTranscriptionBox(
    fileSource: CryptoFile?,
    isSent: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (fileSource == null) return

    // Оборачиваем весь компонент в системную палитру Monet
    MonetPalette {
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current
        val scope = rememberCoroutineScope()

        val transcriber = remember { VoiceTranscriptionManager.getTranscriber(context) }
        val downloader = remember { VoiceTranscriptionManager.getDownloader(context) }

        val filePath = fileSource.filePath
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
                        VoiceTranscriptionManager.transcribeAudio(context, fileSource, model)
                    }
                }
            )
        }

        // Динамические цвета Material You:
        // Исходящие получают основной контейнер (PrimaryContainer), входящие — гармоничный Secondary/Tertiary
        val btnContainerColor = if (isSent) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }

        val btnContentColor = if (isSent) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
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
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = btnContainerColor,
                            tonalElevation = 2.dp,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                TranscribingWaveAnimation(color = btnContentColor)
                                Spacer(modifier = Modifier.width(7.dp))
                                Text(
                                    text = "Transcribing...",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = btnContentColor
                                )
                            }
                        }
                    } else {
                        Surface(
                            onClick = {
                                currentModel = VoiceTranscriptionManager.getPreferredModel(context)
                                if (!transcriber.isModelAvailable(currentModel)) {
                                    showDownloadDialog = true
                                } else {
                                    scope.launch {
                                        VoiceTranscriptionManager.transcribeAudio(context, fileSource, currentModel)
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = btnContainerColor,
                            tonalElevation = 2.dp,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "A →",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = btnContentColor
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Text",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = btnContentColor
                                )

                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(10.dp)
                                        .background(btnContentColor.copy(alpha = 0.35f))
                                )
                                Spacer(modifier = Modifier.width(6.dp))

                                Text(
                                    text = "${currentModel.id.uppercase()} ▾",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = btnContentColor,
                                    modifier = Modifier.clickable {
                                        showDownloadDialog = true
                                    }
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
                    val isError = text.startsWith("Error:") || text.startsWith("Failed")
                    val resultContainerColor = if (isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                    val resultContentColor = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = resultContainerColor,
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .wrapContentSize()
                            .combinedClickable(
                                onClick = {
                                    // Одиночный тап: скрывает результат и возвращает кнопку
                                    VoiceTranscriptionManager.transcriptions.remove(filePath)
                                },
                                onLongClick = {
                                    // Долгий тап: копирует в буфер
                                    if (!isError) {
                                        clipboardManager.setText(AnnotatedString(text))
                                        Toast.makeText(context, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = if (isError) "$text (tap to dismiss)" else text,
                                style = MaterialTheme.typography.bodySmall,
                                color = resultContentColor,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
