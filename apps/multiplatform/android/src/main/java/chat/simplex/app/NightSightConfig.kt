package chat.simplex.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object NightSightConfig {

    private const val TAG = "NightSightConfig"
    
    // Снизили до 8 кадров (до 2-3 сек). Это золотой стандарт для съемки с рук.
    // Больше = неизбежные вращения кисти, которые нельзя исправить простым X/Y сдвигом.
    private val exposureBracket = listOf(-1, 0, 1, 2, 2, 3, 3, 4)
    val MAX_FRAMES = exposureBracket.size
    private const val MIN_FRAMES = 3

    data class MotionResult(val dx: Float, val dy: Float, val error: Long)

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

        // Обычное наложение. Яркость будем доставать в конце.
        val blendPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        var maxShiftLeft = 0f
        var maxShiftRight = 0f
        var maxShiftTop = 0f
        var maxShiftBottom = 0f
        
        var framesProcessed = 0

        try {
            for (i in 0 until MAX_FRAMES) {
                onProgress(framesProcessed + 1) // Показываем реальный прогресс удачных кадров

                val targetExposure = exposureBracket[i].coerceIn(range.lower, range.upper)
                if (exposureState.isExposureCompensationSupported) {
                    cameraControl.setExposureCompensationIndex(targetExposure)
                }

                delay(100)

                val imageProxy = takeSinglePhoto(context, imageCapture)
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                imageProxy.close()

                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                val currentFrame = if (rotationDegrees != 0 && rawBitmap != null) {
                    val rotMatrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, rotMatrix, true)
                    if (rotated != rawBitmap) rawBitmap.recycle()
                    rotated
                } else {
                    rawBitmap
                }

                if (currentFrame == null) continue

                if (baseBitmap == null) {
                    baseBitmap = currentFrame.copy(Bitmap.Config.ARGB_8888, true)
                    blendCanvas = Canvas(baseBitmap!!)
                    currentFrame.recycle()
                    framesProcessed = 1
                } else {
                    val motion = calculateMotionOffset(baseBitmap!!, currentFrame)
                    val shiftDist = sqrt(motion.dx * motion.dx + motion.dy * motion.dy)
                    
                    // ЖЕСТКАЯ ОТБРАКОВКА (FRAME REJECTION)
                    // Если кадр слишком сильно уехал или не совпадает по текстуре (сильный error)
                    // значит рука дрогнула или повернулась. Мы просто выкидываем этот кадр!
                    if (shiftDist > 65f || motion.error > 800_000L) {
                        Log.w(TAG, "Кадр отбракован. Сдвиг: $shiftDist, Ошибка: ${motion.error}")
                        currentFrame.recycle()
                        
                        // Если уже есть минимальное число хороших кадров — завершаем съемку досрочно
                        if (framesProcessed >= MIN_FRAMES) break else continue
                    }

                    if (motion.dx > 0) maxShiftLeft = max(maxShiftLeft, motion.dx)
                    else maxShiftRight = max(maxShiftRight, abs(motion.dx))
                    
                    if (motion.dy > 0) maxShiftTop = max(maxShiftTop, motion.dy)
                    else maxShiftBottom = max(maxShiftBottom, abs(motion.dy))

                    val alignMatrix = Matrix().apply {
                        postTranslate(motion.dx, motion.dy)
                    }

                    // МАТЕМАТИЧЕСКОЕ УСРЕДНЕНИЕ ШУМА
                    // 2-й кадр: alpha = 127 (50%), 3-й: alpha = 85 (33%), 4-й: alpha = 63 (25%)
                    // Это не делает яркие пиксели еще ярче, а именно сглаживает шум.
                    blendPaint.alpha = (255f / (framesProcessed + 1)).toInt().coerceIn(10, 255)

                    blendCanvas?.drawBitmap(currentFrame, alignMatrix, blendPaint)
                    currentFrame.recycle()
                    framesProcessed++
                }
            }

            baseBitmap?.let { bmp ->
                val cropX = maxShiftLeft.toInt()
                val cropY = maxShiftTop.toInt()
                val cropWidth = bmp.width - (maxShiftLeft + maxShiftRight).toInt()
                val cropHeight = bmp.height - (maxShiftTop + maxShiftBottom).toInt()
                
                // Безопасный кроп черных краев
                val croppedBmp = if (cropWidth > 0 && cropHeight > 0 && 
                                    cropWidth <= bmp.width && cropHeight <= bmp.height &&
                                    (cropWidth != bmp.width || cropHeight != bmp.height)) {
                    try {
                        Bitmap.createBitmap(bmp, cropX, cropY, cropWidth, cropHeight)
                    } catch (e: Exception) {
                        bmp
                    }
                } else {
                    bmp
                }
                
                // Агрессивный Tone Mapping для вытягивания общей яркости фото
                applyHDRToneMapping(croppedBmp, Canvas(croppedBmp))

                FileOutputStream(outputFile).use { out ->
                    croppedBmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                
                if (croppedBmp != bmp) {
                    croppedBmp.recycle()
                }
                bmp.recycle()
            }

            if (exposureState.isExposureCompensationSupported) {
                cameraControl.setExposureCompensationIndex(0)
            }

            return@withContext outputFile

        } catch (t: Throwable) {
            Log.e(TAG, "Ошибка многокадровой ночной съёмки", t)
            if (exposureState.isExposureCompensationSupported) {
                cameraControl.setExposureCompensationIndex(0)
            }
            throw Exception(t.message)
        }
    }

    private fun calculateMotionOffset(base: Bitmap, current: Bitmap): MotionResult {
        val targetSize = 128f 
        val scale = targetSize / max(base.width, base.height).toFloat()
        
        val w = (base.width * scale).toInt()
        val h = (base.height * scale).toInt()

        val baseThumb = Bitmap.createScaledBitmap(base, w, h, true)
        val currentThumb = Bitmap.createScaledBitmap(current, w, h, true)

        val basePixels = IntArray(w * h)
        val currentPixels = IntArray(w * h)
        baseThumb.getPixels(basePixels, 0, w, 0, 0, w, h)
        currentThumb.getPixels(currentPixels, 0, w, 0, 0, w, h)

        val searchRadius = 7 
        var bestDx = 0
        var bestDy = 0
        var minError = Long.MAX_VALUE

        for (dy in -searchRadius..searchRadius) {
            for (dx in -searchRadius..searchRadius) {
                var error = 0L
                for (y in searchRadius until h - searchRadius step 2) {
                    for (x in searchRadius until w - searchRadius step 2) {
                        val basePixel = basePixels[y * w + x]
                        val curPixel = currentPixels[(y + dy) * w + (x + dx)]

                        val baseLum = ((basePixel shr 16 and 0xFF) + (basePixel shr 8 and 0xFF) + (basePixel and 0xFF))
                        val curLum = ((curPixel shr 16 and 0xFF) + (curPixel shr 8 and 0xFF) + (curPixel and 0xFF))

                        val diff = baseLum - curLum
                        error += diff * diff
                    }
                }
                if (error < minError) {
                    minError = error
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        baseThumb.recycle()
        currentThumb.recycle()

        val fullDx = bestDx / scale
        val fullDy = bestDy / scale

        return MotionResult(fullDx, fullDy, minError)
    }

    private fun applyHDRToneMapping(bitmap: Bitmap, canvas: Canvas?) {
        val contrast = 1.15f
        // Радикально повышаем базовую яркость, чтобы скомпенсировать отказ от режима SCREEN
        val brightness = 40f 

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
