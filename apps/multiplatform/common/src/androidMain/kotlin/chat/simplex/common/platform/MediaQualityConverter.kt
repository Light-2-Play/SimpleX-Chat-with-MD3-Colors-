package chat.simplex.common.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

object MediaQualityConverter {

  private const val FOUR_MEGAPIXELS = 4_000_000L

  fun processPhoto(
    inputFile: File,
    qualityMode: PhotoQuality,
    outputFile: File
  ): File {
    // 1. В режиме UHD или если режим не HD — отдаем оригинальный файл без изменений
    if (qualityMode == PhotoQuality.UHD) {
      return inputFile
    }

    // 2. Читаем размеры без загрузки пикселей в память
    val boundsOptions = BitmapFactory.Options().apply {
      inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(inputFile.absolutePath, boundsOptions)

    val srcWidth = boundsOptions.outWidth
    val srcHeight = boundsOptions.outHeight
    val totalPixels = srcWidth.toLong() * srcHeight.toLong()

    // Если фото уже <= 4MP, пережатие не требуется
    if (totalPixels <= 0 || totalPixels <= FOUR_MEGAPIXELS) {
      return inputFile
    }

    // 3. Рассчитываем пропорциональный масштаб под лимит 4MP
    val scaleFactor = sqrt(FOUR_MEGAPIXELS.toDouble() / totalPixels.toDouble())
    val targetWidth = (srcWidth * scaleFactor).roundToInt()
    val targetHeight = (srcHeight * scaleFactor).roundToInt()

    // Считываем поворот EXIF до декодирования
    val exifRotation = getExifRotation(inputFile)

    // 4. Безопасное декодирование с inSampleSize против OOM
    val decodeOptions = BitmapFactory.Options().apply {
      inSampleSize = calculateInSampleSize(srcWidth, srcHeight, targetWidth, targetHeight)
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    val decodedBitmap = BitmapFactory.decodeFile(inputFile.absolutePath, decodeOptions) 
      ?: return inputFile

    // 5. Масштабируем до точных габаритов 4MP
    val scaledBitmap = Bitmap.createScaledBitmap(decodedBitmap, targetWidth, targetHeight, true)

    // 6. Запекаем ориентацию, если исходник был повернут камерой
    val finalBitmap = if (exifRotation != 0f) {
      val matrix = Matrix().apply { postRotate(exifRotation) }
      Bitmap.createBitmap(scaledBitmap, 0, 0, scaledBitmap.width, scaledBitmap.height, matrix, true)
    } else {
      scaledBitmap
    }

    // Сохраняем в целевой файл
    FileOutputStream(outputFile).use { out ->
      finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }

    // Освобождаем память
    if (decodedBitmap != scaledBitmap && decodedBitmap != finalBitmap && !decodedBitmap.isRecycled) {
      decodedBitmap.recycle()
    }
    if (scaledBitmap != finalBitmap && !scaledBitmap.isRecycled) {
      scaledBitmap.recycle()
    }
    if (!finalBitmap.isRecycled) {
      finalBitmap.recycle()
    }

    return outputFile
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
