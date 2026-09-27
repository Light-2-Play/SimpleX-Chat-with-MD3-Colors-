package chat.simplex.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NightSightConfig {

    private const val TAG = "NightSightConfig"
    private const val TOTAL_FRAMES = 15

    // Экспозиционная вилка (от темных к светлым кадрам)
    private val exposureBracket = listOf(
        -4, -3, -2, -1, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4
    )

    @SuppressLint("UnsafeOptInUsageError")
    suspend fun captureMultiFrameNightSight(
        context: Context,
        camera: Camera,
        imageCapture: ImageCapture,
        outputFile: File,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {

        val cameraControl = camera.cameraControl
        val exposureState = camera.cameraInfo.exposureState
        val range = exposureState.exposureCompensationRange

        var baseBitmap: Bitmap? = null
        var blendCanvas: Canvas? = null

        val blendPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                blendMode = BlendMode.PLUS
            }
        }

        try {
            for (i in 0 until TOTAL_FRAMES) {
                onProgress(i + 1)

                // 1. Безопасно выставляем экспозицию в границах текущего сенсора
                val requestedIndex = exposureBracket.getOrElse(i) { 0 }
                val safeIndex = requestedIndex.coerceIn(range.lower, range.upper)
                if (exposureState.isExposureCompensationSupported) {
                    cameraControl.setExposureCompensationIndex(safeIndex)
                }

                // Задержка на отработку AE сенсора
                delay(90)

                // 2. Получаем кадр
                val imageProxy = takeSinglePhoto(context, imageCapture)
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                // 3. Декодируем байты кадра
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                imageProxy.close()

                val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                
                // Корректируем ориентацию кадра в соответствии с положением смартфона
                val currentFrame = if (rotationDegrees != 0 && rawBitmap != null) {
                    val rotMatrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, rotMatrix, true)
                    if (rotated != rawBitmap) rawBitmap.recycle()
                    rotated
                } else {
                    rawBitmap
                }

                if (currentFrame == null) continue

                // 4. Наложение кадров и усреднение шума
                if (baseBitmap == null) {
                    baseBitmap = currentFrame.copy(Bitmap.Config.ARGB_8888, true)
                    blendCanvas = Canvas(baseBitmap)
                    currentFrame.recycle()
                } else {
                    // Уменьшаем вес каждого последующего кадра для алгоритмического подавления шумов
                    blendPaint.alpha = (255f * (1f / (i + 1))).toInt().coerceIn(12, 255)
                    blendCanvas?.drawBitmap(currentFrame, 0f, 0f, blendPaint)
                    currentFrame.recycle()
                }
            }

            // 5. Tone mapping (выравнивание динамического диапазона)
            baseBitmap?.let { bmp ->
                applyHDRToneMapping(bmp, blendCanvas)

                FileOutputStream(outputFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                bmp.recycle()
            }

            // Сбрасываем экспозицию обратно в нейтраль
            if (exposureState.isExposureCompensationSupported) {
                cameraControl.setExposureCompensationIndex(0)
            }

            return@withContext outputFile

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка многокадровой ночной съёмки", e)
            if (exposureState.isExposureCompensationSupported) {
                cameraControl.setExposureCompensationIndex(0)
            }
            throw e
        }
    }

    private fun applyHDRToneMapping(bitmap: Bitmap, canvas: Canvas?) {
        val contrast = 1.15f
        val brightness = 14f

        val colorMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))

        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }

        canvas?.drawBitmap(bitmap, 0f, 0f, paint)
    }

    private suspend fun takeSinglePhoto(context: Context, imageCapture: ImageCapture): ImageProxy {
        return suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        continuation.resume(image)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                }
            )
        }
    }
}
