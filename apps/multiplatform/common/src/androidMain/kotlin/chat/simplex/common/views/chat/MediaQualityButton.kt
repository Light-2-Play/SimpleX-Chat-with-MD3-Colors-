package chat.simplex.common.views.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.platform.MediaQualityManager
import chat.simplex.common.platform.PhotoQuality
import chat.simplex.common.platform.VideoQuality

@OptIn(ExperimentalAnimationApi::class)
@Composable
actual fun MediaQualityButton(
  isVideo: Boolean,
  modifier: Modifier
) {
  val context = LocalContext.current
  val photoQuality by MediaQualityManager.photoQualityState
  val videoQuality by MediaQualityManager.videoQualityState

  val isHighQuality = if (isVideo) {
    videoQuality == VideoQuality.FULL_RES
  } else {
    photoQuality == PhotoQuality.UHD
  }

  val label = if (isVideo) {
    if (isHighQuality) "FullRes" else "FHD"
  } else {
    if (isHighQuality) "UHD" else "HD"
  }

  // Динамические цвета Material 3 (Monet)
  val containerColor by animateColorAsState(
    targetValue = if (isHighQuality) {
      MaterialTheme.colorScheme.primaryContainer
    } else {
      MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
    },
    animationSpec = tween(durationMillis = 250)
  )

  val contentColor by animateColorAsState(
    targetValue = if (isHighQuality) {
      MaterialTheme.colorScheme.onPrimaryContainer
    } else {
      MaterialTheme.colorScheme.onSecondaryContainer
    },
    animationSpec = tween(durationMillis = 250)
  )

  // Анимация нажатия (MD3 bounce feedback)
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (isPressed) 0.92f else 1.0f,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioMediumBouncy,
      stiffness = Spring.StiffnessLow
    )
  )

  Box(
    modifier = modifier
      .scale(scale)
      .clip(RoundedCornerShape(8.dp))
      .background(containerColor)
      .clickable(
        interactionSource = interactionSource,
        indication = rememberRipple(bounded = true),
        onClick = {
          if (isVideo) {
            val next = if (videoQuality == VideoQuality.FHD) VideoQuality.FULL_RES else VideoQuality.FHD
            MediaQualityManager.setVideoQuality(context, next)
          } else {
            val next = if (photoQuality == PhotoQuality.HD) PhotoQuality.UHD else PhotoQuality.HD
            MediaQualityManager.setPhotoQuality(context, next)
          }
        }
      )
      .padding(horizontal = 10.dp, vertical = 5.dp),
    contentAlignment = Alignment.Center
  ) {
    AnimatedContent(
      targetState = label,
      transitionSpec = {
        (slideInVertically { height -> height } + fadeIn()) with
            (slideOutVertically { height -> -height } + fadeOut())
      }
    ) { targetText ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        Text(
          text = targetText,
          color = contentColor,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 0.5.sp
        )
      }
    }
  }
}
