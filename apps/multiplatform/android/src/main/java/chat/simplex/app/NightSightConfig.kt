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
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
    
    // Снизили до 4 кадров. Агрессивный шаг экспозиции, чтобы захватить максимум света
    private val exposureBracket = listOf(0, 1, 2, 3)
    val MAX_FRAMES = exposureBracket.size
    private const val MIN_FRAMES = 2

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

        // Режим SCREEN накапливает свет. 
        // Временное шумоподавление (Temporal NR) происходит за счет того, 
        // что мы берем только часть непрозрачности (alpha) от каждого кадра.
        val blendPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }

        var maxShiftLeft = 0f
        var maxShiftRight = 0f
        var maxShiftTop = 0f
        var maxShiftBottom = 0f
        
        var framesProcessed = 0

        try {
            for (i in 0 until MAX_FRAMES) {
                onProgress(framesProcessed + 1)

                val targetExposure = exposureBracket[i].coerceIn(range.lower, range.upper)
                if (exposureState.isExposureCompensationSupported) {
                    cameraControl.setExposureCompensationIndex(targetExposure)
                }

                delay(120) // Чуть увеличили задержку, так как выдержка стала длиннее (EV +3)

                val imageProxy = takeSinglePhoto(context, imageCapture)
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                imageProxy.close()

                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    // Запрашиваем максимально сырой несжатый Bitmap (Dither)
                    inDither = true 
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
                    
                    // Жесткий допуск, так как кадров всего 4. Ошибка выравнивания критична.
                    if (shiftDist > 70f || motion.error > 850_000L) {
                        currentFrame.recycle()
                        if (framesProcessed >= MIN_FRAMES) break else continue
                    }

                    if (motion.dx > 0) maxShiftLeft = max(maxShiftLeft, motion.dx)
                    else maxShiftRight = max(maxShiftRight, abs(motion.dx))
                    
                    if (motion.dy > 0) maxShiftTop = max(maxShiftTop, motion.dy)
                    else maxShiftBottom = max(maxShiftBottom, abs(motion.dy))

                    val alignMatrix = Matrix().apply {
                        postTranslate(motion.dx, motion.dy)
                    }

                    // Балансировка веса кадра для снижения теплового шума
                    blendPaint.alpha = (255f * 0.40f).toInt() 

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
                
                // Финальный проход: программный Denoise + Буст экспозиции
                val finalBmp = applyDenoiseAndExposure(croppedBmp)

                FileOutputStream(outputFile).use { out ->
                    finalBmp.compress(Bitmap.CompressFormat.JPEG, 97, out) // Качество 97 для сохранения деталей
                }
                
                if (croppedBmp != bmp) croppedBmp.recycle()
                if (finalBmp != croppedBmp) finalBmp.recycle()
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

    // Программный фильтр постобработки (заменяет аппаратный ISP RAW Denoise)
    private fun applyDenoiseAndExposure(source: Bitmap): Bitmap {
        val config = source.config ?: Bitmap.Config.ARGB_8888
        val result = Bitmap.createBitmap(source.width, source.height, config)
        val canvas = Canvas(result)

        // 1. Агрессивное поднятие экспозиции (базовая яркость и контраст)
        val contrast = 1.18f
        val brightness = 35f 

        // 2. Chroma Denoise: слегка гасим насыщенность в темных участках, 
        // чтобы скрыть цветовой шум (синие/красные пиксели), характерный для съемки в темноте
        val saturation = 0.85f 
        
        val invSat = 1 - saturation
        val rWeight = 0.213f * invSat
        val gWeight = 0.715f * invSat
        val bWeight = 0.072f * invSat

        val colorMatrix = ColorMatrix(floatArrayOf(
            (rWeight + saturation) * contrast, gWeight * contrast, bWeight * contrast, 0f, brightness,
            rWeight * contrast, (gWeight + saturation) * contrast, bWeight * contrast, 0f, brightness,
            rWeight * contrast, gWeight * contrast, (bWeight + saturation) * contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))

        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }

        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
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
