package chat.simplex.common.views.helpers

import android.content.res.Resources
import android.graphics.*
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.os.*
import android.provider.OpenableColumns
import android.text.Spanned
import android.text.SpannedString
import android.text.style.*
import android.util.Base64
import android.view.WindowManager
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import chat.simplex.common.helpers.*
import chat.simplex.common.model.*
import chat.simplex.common.platform.*
import chat.simplex.res.MR
import dev.icerock.moko.resources.StringResource
import java.io.*
import java.net.URI

fun Spanned.toHtmlWithoutParagraphs(): String {
  return HtmlCompat.toHtml(this, HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
    .substringAfter("<p dir=\"ltr\">").substringBeforeLast("</p>")
}

fun Resources.getText(id: StringResource, vararg args: Any): CharSequence {
  val escapedArgs = args.map {
    if (it is Spanned) it.toHtmlWithoutParagraphs() else it
  }.toTypedArray()
  val resource = SpannedString(getText(id))
  val htmlResource = resource.toHtmlWithoutParagraphs()
  val formattedHtml = String.format(htmlResource, *escapedArgs)
  return HtmlCompat.fromHtml(formattedHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
}

fun keepScreenOn(on: Boolean) {
  val window = mainActivity.get()?.window ?: return
  Handler(Looper.getMainLooper()).post {
    if (on) {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
      window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }
}

actual fun saveImageFromUriPlatform(uri: URI): CryptoFile? {
  return try {
    val quality = MediaQualityManager.photoQualityState.value
    val encrypted = chatController.appPrefs.privacyEncryptLocalFiles.get()
    val inputStream = uri.inputStream() ?: return null
    val ext = getFileName(uri)?.substringAfterLast(".")?.lowercase() ?: "jpg"
    val destFileName = generateNewFileName("IMG", if (ext == "png") "png" else "jpg", File(getAppFilePath("")))
    val destFile = File(getAppFilePath(destFileName))

    createTmpFileAndDelete { tmpFile ->
      copyInputStreamToFile(inputStream, tmpFile, Long.MAX_VALUE)

      // Если выбран HD — уменьшаем до 4MP. Если UHD — берем исходник без сжатия
      val processedFile = if (quality == PhotoQuality.HD) {
        val outFile = File(tmpFile.parentFile, "hd_${tmpFile.name}")
        val result = MediaQualityConverter.processPhoto(
          androidAppContext,
          tmpFile,
          PhotoQualityMode.HD,
          outFile
        )
        ChatModel.filesToDelete.add(outFile)
        result
      } else {
        tmpFile // UHD: отправляем оригинальный файл байт-в-байт
      }

      if (encrypted) {
        try {
          val args = encryptCryptoFile(processedFile.absolutePath, destFile.absolutePath)
          CryptoFile(destFileName, args)
        } catch (e: Exception) {
          Log.e(TAG, "Unable to encrypt image: ${e.stackTraceToString()}")
          AlertManager.shared.showAlertMsg(title = generalGetString(MR.strings.error), text = e.stackTraceToString())
          null
        }
      } else {
        processedFile.copyTo(destFile, overwrite = true)
        CryptoFile.plain(destFileName)
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "saveImageFromUriPlatform error: ${e.stackTraceToString()}")
    null
  }
}

actual fun escapedHtmlToAnnotatedString(text: String, density: Density): AnnotatedString {
  return spannableStringToAnnotatedString(HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY), density)
}

actual fun processVideoIfNeeded(file: File): File {
  val qualityMode = MediaQualityManager.videoQualityState.value

  // 1. В режиме FullRes видео отправляется оригинальным
  if (qualityMode == VideoQuality.FULL_RES) {
    return file
  }

  // 2. В режиме FHD проверяем видимое разрешение готового ролика
  val retriever = MediaMetadataRetriever()
  val (width, height, rotation) = try {
    retriever.setDataSource(file.absolutePath)
    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
    val r = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    Triple(w, h, r)
  } catch (e: Exception) {
    Log.e(TAG, "retriever error: ${e.message}")
    Triple(0, 0, 0)
  } finally {
    retriever.release()
  }

  if (width == 0 || height == 0) return file

  val visualWidth = if (rotation == 90 || rotation == 270) height else width
  val visualHeight = if (rotation == 90 || rotation == 270) width else height

  val isLandscape = visualWidth >= visualHeight
  val maxLimit = if (isLandscape) 1920 else 1080
  val minLimit = if (isLandscape) 1080 else 1920

  // Если видео уже <= 1080p (вертикальное 1080x1920 или горизонтальное 1920x1080), сжимать не нужно
  if (visualWidth <= maxLimit && visualHeight <= minLimit) {
    return file
  }

  // 3. Если больше (например, 4K) — уменьшаем до 1080p
  return try {
    val fhdFile = File(file.parentFile, "fhd_${file.name}")
    if (fhdFile.exists()) fhdFile.delete()
    val success = VideoTranscoder.transcodeTo1080p(file, fhdFile)
    if (success && fhdFile.exists() && fhdFile.length() > 0) {
      ChatModel.filesToDelete.add(fhdFile)
      fhdFile
    } else {
      file
    }
  } catch (e: Exception) {
    Log.e(TAG, "Video transcode failed: ${e.message}")
    file
  }
}

private fun spannableStringToAnnotatedString(
  text: CharSequence,
  density: Density,
): AnnotatedString {
  return if (text is Spanned) {
    with(density) {
      buildAnnotatedString {
        append((text.toString()))
        text.getSpans(0, text.length, Any::class.java).forEach {
          val start = text.getSpanStart(it)
          val end = text.getSpanEnd(it)
          when (it) {
            is StyleSpan -> when (it.style) {
              Typeface.NORMAL -> addStyle(
                SpanStyle(
                  fontWeight = FontWeight.Normal,
                  fontStyle = FontStyle.Normal,
                ),
                start,
                end
              )
              Typeface.BOLD -> addStyle(
                SpanStyle(
                  fontWeight = FontWeight.Bold,
                  fontStyle = FontStyle.Normal
                ),
                start,
                end
              )
              Typeface.ITALIC -> addStyle(
                SpanStyle(
                  fontWeight = FontWeight.Normal,
                  fontStyle = FontStyle.Italic
                ),
                start,
                end
              )
              Typeface.BOLD_ITALIC -> addStyle(
                SpanStyle(
                  fontWeight = FontWeight.Bold,
                  fontStyle = FontStyle.Italic
                ),
                start,
                end
              )
            }
            is TypefaceSpan -> addStyle(
              SpanStyle(
                fontFamily = when (it.family) {
                  FontFamily.SansSerif.name -> FontFamily.SansSerif
                  FontFamily.Serif.name -> FontFamily.Serif
                  FontFamily.Monospace.name -> FontFamily.Monospace
                  FontFamily.Cursive.name -> FontFamily.Cursive
                  else -> FontFamily.Default
                }
              ),
              start,
              end
            )
            is AbsoluteSizeSpan -> addStyle(
              SpanStyle(fontSize = if (it.dip) it.size.dp.toSp() else it.size.toSp()),
              start,
              end
            )
            is RelativeSizeSpan -> addStyle(
              SpanStyle(fontSize = it.sizeChange.em),
              start,
              end
            )
            is StrikethroughSpan -> addStyle(
              SpanStyle(textDecoration = TextDecoration.LineThrough),
              start,
              end
            )
            is UnderlineSpan -> addStyle(
              SpanStyle(textDecoration = TextDecoration.Underline),
              start,
              end
            )
            is SuperscriptSpan -> addStyle(
              SpanStyle(baselineShift = BaselineShift.Superscript),
              start,
              end
            )
            is SubscriptSpan -> addStyle(
              SpanStyle(baselineShift = BaselineShift.Subscript),
              start,
              end
            )
            is ForegroundColorSpan -> addStyle(
              SpanStyle(color = Color(it.foregroundColor)),
              start,
              end
            )
            else -> addStyle(SpanStyle(color = Color.White), start, end)
          }
        }
      }
    }
  } else {
    AnnotatedString(text.toString())
  }
}

actual fun getAppFileUri(fileName: String): URI =
  FileProvider.getUriForFile(androidAppContext, "$APPLICATION_ID.provider", if (File(fileName).isAbsolute) File(fileName) else File(getAppFilePath(fileName))).toURI()

actual fun clearImageCaches() {}

// https://developer.android.com/training/data-storage/shared/documents-files#bitmap
actual suspend fun getLoadedImage(file: CIFile?): Pair<ImageBitmap, ByteArray>? {
  val filePath = getLoadedFilePath(file)
  return if (filePath != null && file != null) {
    try {
      val data = if (file.fileSource?.cryptoArgs != null) {
        try {
          readCryptoFile(getAppFilePath(file.fileSource.filePath), file.fileSource.cryptoArgs)
        } catch (e: Exception) {
          Log.e(TAG, "Unable to read crypto file: " + e.stackTraceToString())
          return null
        }
      } else {
        File(getAppFilePath(file.fileName)).readBytes()
      }
      decodeSampledBitmapFromByteArray(data, 1000, 1000).asImageBitmap() to data
    } catch (e: Exception) {
      Log.e(TAG, e.stackTraceToString())
      null
    }
  } else {
    null
  }
}

// https://developer.android.com/topic/performance/graphics/load-bitmap#load-bitmap
private fun decodeSampledBitmapFromByteArray(data: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap {
  // First decode with inJustDecodeBounds=true to check dimensions
  return BitmapFactory.Options().run {
    inJustDecodeBounds = true
    BitmapFactory.decodeByteArray(data, 0, data.size, this)
    // Calculate inSampleSize
    inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)
    // Decode bitmap with inSampleSize set
    inJustDecodeBounds = false

    BitmapFactory.decodeByteArray(data, 0, data.size, this)
      ?: throw IOException("Unable to decode image")
  }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
  // Raw height and width of image
  val (height: Int, width: Int) = options.run { outHeight to outWidth }
  var inSampleSize = 1

  if (height > reqHeight || width > reqWidth) {
    val halfHeight: Int = height / 2
    val halfWidth: Int = width / 2
    // Calculate the largest inSampleSize value that is a power of 2 and keeps both
    // height and width larger than the requested height and width.
    while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
      inSampleSize *= 2
    }
  }

  return inSampleSize
}

actual fun getFileName(uri: URI): String? {
  return try {
    androidAppContext.contentResolver.query(uri.toUri(), null, null, null, null)?.use { cursor ->
      val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      cursor.moveToFirst()
      // Can make an exception
      cursor.getString(nameIndex)
    }
  } catch (e: Exception) {
    null
  }
}

actual fun getAppFilePath(uri: URI): String? {
  return androidAppContext.contentResolver.query(uri.toUri(), null, null, null, null)?.use { cursor ->
    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    cursor.moveToFirst()
    getAppFilePath(cursor.getString(nameIndex))
  }
}

actual fun getFileSize(uri: URI): Long? {
  return androidAppContext.contentResolver.query(uri.toUri(), null, null, null, null)?.use { cursor ->
    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
    cursor.moveToFirst()
    cursor.getLong(sizeIndex)
  }
}

actual fun getBitmapFromUri(uri: URI, withAlertOnException: Boolean): ImageBitmap? {
  val androidUri = uri.toUri()
  val contentResolver = androidAppContext.contentResolver
  val mimeType = contentResolver.getType(androidUri) ?: ""

  // 1. Если это видео (.mp4) — сразу извлекаем первый кадр
  if (mimeType.startsWith("video/") || androidUri.toString().endsWith(".mp4", ignoreCase = true)) {
    return try {
      val retriever = android.media.MediaMetadataRetriever()
      retriever.setDataSource(androidAppContext, androidUri)
      val frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
      retriever.release()
      frame?.asImageBitmap()
    } catch (e: Exception) {
      Log.e(TAG, "Unable to extract video frame: ${e.stackTraceToString()}")
      null
    }
  }

  // 2. Пайплайн для изображений (все ветки строго возвращают android.graphics.Bitmap?)
  val bitmap: android.graphics.Bitmap? = if (Build.VERSION.SDK_INT >= 28) {
    try {
      val source = ImageDecoder.createSource(contentResolver, androidUri)
      ImageDecoder.decodeBitmap(source)
    } catch (e: Exception) {
      // Страховка: если видеофайл пришел без MIME-типа, достаем кадр как Bitmap
      try {
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(androidAppContext, androidUri)
        val frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        retriever.release()
        frame
      } catch (videoEx: Exception) {
        Log.e(TAG, "Unable to decode the image: ${e.stackTraceToString()}")
        if (withAlertOnException) showImageDecodingException()
        null
      }
    }
  } else {
    BitmapFactory.decodeFile(getAppFilePath(uri))
  }

  return bitmap?.asImageBitmap()
}

actual fun getBitmapFromByteArray(data: ByteArray, withAlertOnException: Boolean): ImageBitmap? {
  return if (Build.VERSION.SDK_INT >= 31) {
    try {
      val source = ImageDecoder.createSource(data)
      ImageDecoder.decodeBitmap(source)
    } catch (e: android.graphics.ImageDecoder.DecodeException) {
      Log.e(TAG, "Unable to decode the image: ${e.stackTraceToString()}")
      if (withAlertOnException) showImageDecodingException()

      null
    }
  } else {
    BitmapFactory.decodeByteArray(data, 0, data.size)
  }?.asImageBitmap()
}

actual fun getDrawableFromUri(uri: URI, withAlertOnException: Boolean): Any? {
  return if (Build.VERSION.SDK_INT >= 28) {
    try {
      val source = ImageDecoder.createSource(androidAppContext.contentResolver, uri.toUri())
      ImageDecoder.decodeDrawable(source)
    } catch (e: Exception) {
      Log.e(TAG, "Error while decoding drawable: ${e.stackTraceToString()}")
      if (withAlertOnException) showImageDecodingException()

      null
    }
  } else {
    Drawable.createFromPath(getAppFilePath(uri))
  }
}

actual suspend fun saveTempImageUncompressed(image: ImageBitmap, asPng: Boolean): File? {
  return try {
    val ext = if (asPng) "png" else "jpg"
    tmpDir.mkdir()

    val originalBitmap = image.asAndroidBitmap()
    val photoMode = MediaQualityManager.photoQualityState.value

    // Алгоритм HD (4MP) vs UHD (Оригинал)
    val finalBitmap = if (!asPng && photoMode == PhotoQuality.HD) {
      val srcWidth = originalBitmap.width
      val srcHeight = originalBitmap.height
      val totalPixels = srcWidth.toLong() * srcHeight.toLong()

      if (totalPixels > PhotoQuality.HD.maxPixels) {
        val scale = kotlin.math.sqrt(PhotoQuality.HD.maxPixels.toDouble() / totalPixels.toDouble())
        val targetWidth = (srcWidth * scale).toInt()
        val targetHeight = (srcHeight * scale).toInt()
        Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true)
      } else {
        originalBitmap
      }
    } else {
      originalBitmap
    }

    // UHD сохраняем с максимальным качеством 95%, HD — 88%
    val quality = if (photoMode == PhotoQuality.UHD) 95 else 88

    return File(tmpDir.absolutePath + File.separator + generateNewFileName("IMG", ext, tmpDir)).apply {
      outputStream().use { out ->
        finalBitmap.compress(if (asPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, out)
        out.flush()
      }
      if (finalBitmap != originalBitmap && !finalBitmap.isRecycled) {
        finalBitmap.recycle()
      }
      deleteOnExit()
      ChatModel.filesToDelete.add(this)
    }
  } catch (e: Exception) {
    Log.e(TAG, "Utils.android saveTempImageUncompressed error: ${e.message}")
    null
  }
}

actual suspend fun getBitmapFromVideo(uri: URI, timestamp: Long?, random: Boolean, withAlertOnException: Boolean): VideoPlayerInterface.PreviewAndDuration =
  try {
    val mmr = MediaMetadataRetriever()
    mmr.setDataSource(androidAppContext, uri.toUri())
    val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
    val image = when {
      timestamp != null -> mmr.getFrameAtTime(timestamp * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
      random -> mmr.frameAtTime
      else -> mmr.getFrameAtTime(0)
    }
    mmr.release()
    VideoPlayerInterface.PreviewAndDuration(image?.asImageBitmap(), durationMs, timestamp ?: 0)
  } catch (e: Exception) {
    Log.e(TAG, "Utils.android getBitmapFromVideo error: ${e.message}")
    if (withAlertOnException) showVideoDecodingException()

    VideoPlayerInterface.PreviewAndDuration(null, 0, 0)
  }

actual suspend fun hasVideoTrack(uri: URI): Boolean {
  val mmr = MediaMetadataRetriever()
  return try {
    mmr.setDataSource(androidAppContext, uri.toUri())
    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
  } catch (e: Exception) {
    Log.e(TAG, "Utils.android hasVideoTrack error: ${e.message}")
    false
  } finally {
    mmr.release()
  }
}

actual fun ByteArray.toBase64StringForPassphrase(): String = Base64.encodeToString(this, Base64.DEFAULT)

actual fun String.toByteArrayFromBase64ForPassphrase(): ByteArray = Base64.decode(this, Base64.DEFAULT)
