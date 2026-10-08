package chat.simplex.common.platform

import android.content.Context
import androidx.compose.runtime.mutableStateOf

enum class PhotoQuality(val label: String, val maxPixels: Long) {
  HD("HD", 4_000_000L), // 4 Мегапикселя (~2309x1732 для 4:3)
  UHD("UHD", Long.MAX_VALUE) // Исходное разрешение без даунскейла
}

enum class VideoQuality(val label: String, val maxDimension: Int) {
  FHD("FHD", 1080), // Ограничение по меньшей стороне до 1080p
  FULL_RES("FullRes", Int.MAX_VALUE) // Без пережатия (как стандартно в SimpleX)
}

object MediaQualityManager {
  private const val PREFS_NAME = "media_quality_prefs"
  private const val KEY_PHOTO_QUALITY = "photo_quality"
  private const val KEY_VIDEO_QUALITY = "video_quality"

  val photoQualityState = mutableStateOf(PhotoQuality.HD)
  val videoQualityState = mutableStateOf(VideoQuality.FHD)

  fun init(context: Context) {
    val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val savedPhoto = sp.getString(KEY_PHOTO_QUALITY, PhotoQuality.HD.name) ?: PhotoQuality.HD.name
    val savedVideo = sp.getString(KEY_VIDEO_QUALITY, VideoQuality.FHD.name) ?: VideoQuality.FHD.name

    photoQualityState.value = runCatching { PhotoQuality.valueOf(savedPhoto) }.getOrDefault(PhotoQuality.HD)
    videoQualityState.value = runCatching { VideoQuality.valueOf(savedVideo) }.getOrDefault(VideoQuality.FHD)
  }

  fun setPhotoQuality(context: Context, quality: PhotoQuality) {
    photoQualityState.value = quality
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_PHOTO_QUALITY, quality.name)
      .apply()
  }

  fun setVideoQuality(context: Context, quality: VideoQuality) {
    videoQualityState.value = quality
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_VIDEO_QUALITY, quality.name)
      .apply()
  }
}
