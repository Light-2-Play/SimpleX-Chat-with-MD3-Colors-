package chat.simplex.common.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class PhotoQualityMode {
  HD,   // 4 Megapixels (Downscale with aspect ratio preserved)
  UHD   // Original Full Resolution
}

enum class VideoQualityMode {
  FHD,     // 1080p max constraint
  FULL_RES // Full original resolution
}

object MediaQualityConverter {

  private const val FOUR_MEGAPIXELS = 4_000_000L
  private const val FHD_MAX_SIDE = 1920

  fun processPhoto(
    context: Context,
    inputFile: File,
    qualityMode: PhotoQualityMode,
    outputFile: File
  ): File {
    if (qualityMode == PhotoQualityMode.UHD) {
      return inputFile
    }

    val boundsOptions = BitmapFactory.Options().apply {
      inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(inputFile.absolutePath, boundsOptions)

    val srcWidth = boundsOptions.outWidth
    val srcHeight = boundsOptions.outHeight
    val totalPixels = srcWidth.toLong() * srcHeight.toLong()

    if (totalPixels <= FOUR_MEGAPIXELS) {
      return inputFile
    }

    val scaleFactor = sqrt(FOUR_MEGAPIXELS.toDouble() / totalPixels.toDouble())
    val targetWidth = (srcWidth * scaleFactor).roundToInt()
    val targetHeight = (srcHeight * scaleFactor).roundToInt()

    val decodeOptions = BitmapFactory.Options().apply {
      inSampleSize = calculateInSampleSize(srcWidth, srcHeight, targetWidth, targetHeight)
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    val decodedBitmap = BitmapFactory.decodeFile(inputFile.absolutePath, decodeOptions) 
      ?: return inputFile

    val exifRotation = getExifRotation(inputFile)
    val matrix = Matrix().apply {
      if (exifRotation != 0f) postRotate(exifRotation)
    }

    val scaledBitmap = Bitmap.createScaledBitmap(decodedBitmap, targetWidth, targetHeight, true)
    val finalBitmap = if (exifRotation != 0f) {
      Bitmap.createBitmap(scaledBitmap, 0, 0, scaledBitmap.width, scaledBitmap.height, matrix, true)
    } else {
      scaledBitmap
    }

    FileOutputStream(outputFile).use { out ->
      finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }

    if (decodedBitmap != finalBitmap && !decodedBitmap.isRecycled) decodedBitmap.recycle()
    if (scaledBitmap != finalBitmap && !scaledBitmap.isRecycled) scaledBitmap.recycle()
    if (!finalBitmap.isRecycled) finalBitmap.recycle()

    return outputFile
  }

  fun shouldDownscaleVideo(videoFile: File, mode: VideoQualityMode): Boolean {
    if (mode == VideoQualityMode.FULL_RES) return false

    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(videoFile.absolutePath)
      val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
      val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
      val maxSide = maxOf(width, height)
      maxSide > FHD_MAX_SIDE
    } catch (_: Exception) {
      false
    } finally {
      retriever.release()
    }
  }

  private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
    var inSampleSize = 1
    if (srcHeight > reqHeight || srcWidth > reqWidth) {
      val halfHeight = srcHeight / 2
      val halfWidth = srcWidth / 2
      while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
        inSampleSize *= 2
      }
    }
    return inSampleSize
  }

  private fun getExifRotation(file: File): Float {
    return try {
      val exif = ExifInterface(file.absolutePath)
      when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
      }
    } catch (_: Exception) {
      0f
    }
  }
}
