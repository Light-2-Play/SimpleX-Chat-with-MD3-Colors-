package chat.simplex.common.views.chat

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
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
import androidx.core.content.ContextCompat
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

  val isDark = !MaterialTheme.colors.isLight

  // Привязка к системной палитре Monet (Android 12+) с фоллбэком на палитру темы
  val monetPrimaryContainer = remember(isDark) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val resId = if (isDark) android.R.color.system_accent1_700 else android.R.color.system_accent1_100
      Color(ContextCompat.getColor(context, resId))
    } else {
      MaterialTheme.colors.primary.copy(alpha = if (isDark) 0.35f else 0.22f)
    }
  }

  val monetOnPrimaryContainer = remember(isDark) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val resId = if (isDark) android.R.color.system_accent1_100 else android.R.color.system_accent1_900
      Color(ContextCompat.getColor(context, resId))
    } else {
      MaterialTheme.colors.primary
    }
  }

  val monetSecondaryContainer = remember(isDark) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val resId = if (isDark) android.R.color.system_accent2_800 else android.R.color.system_accent2_100
      Color(ContextCompat.getColor(context, resId)).copy(alpha = 0.8f)
    } else {
      MaterialTheme.colors.onSurface.copy(alpha = if (isDark) 0.15f else 0.08f)
    }
  }

  val monetOnSecondaryContainer = remember(isDark) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val resId = if (isDark) android.R.color.system_accent2_100 else android.R.color.system_accent2_900
      Color(ContextCompat.getColor(context, resId))
    } else {
      MaterialTheme.colors.onSurface.copy(alpha = 0.85f)
    }
  }

  val containerColor by animateColorAsState(
    targetValue = if (isHighQuality) monetPrimaryContainer else monetSecondaryContainer,
    animationSpec = tween(durationMillis = 200)
  )

  val contentColor by animateColorAsState(
    targetValue = if (isHighQuality) monetOnPrimaryContainer else monetOnSecondaryContainer,
    animationSpec = tween(durationMillis = 200)
  )

  // MD3 Bounce эффект при нажатии
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
        indication = null,
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
