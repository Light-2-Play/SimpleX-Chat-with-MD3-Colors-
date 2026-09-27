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
import kotlin.math.max

object NightSightConfig {

    private const val TAG = "NightSightConfig"
    
    // 1. 15 КАДРОВ И ПОДНЯТИЕ ЭКПОЗИЦИИ
    // Начинаем с нейтральных/темных для деталей света, 
    // затем делаем агрессивный упор в +1, +2, +3, +4 для вытягивания теней.
    private val exposureBracket = listOf(
        -2, -1, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4
    )
    val TOTAL_FRAMES = exposureBracket.size

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

        // Настраиваем кисть на Осветление (SCREEN) - идеально для вытягивания света ночью
        val blendPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }

        try {
            for (i in 0 until TOTAL_FRAMES) {
                onProgress(i + 1)

                // Устанавливаем целевую экспозицию в рамках возможностей сенсора смартфона
                val targetExposure = exposureBracket[i].coerceIn(range.lower, range.upper)
                if (exposureState.isExposureCompensationSupported) {
                    cameraControl.setExposureCompensationIndex(targetExposure)
                }

                // Даем сенсору время применить экспозицию
                delay(90)

                // Делаем снимок
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

                // Поворачиваем кадр, если нужно
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
                    // Первый кадр становится базой
                    baseBitmap = currentFrame.copy(Bitmap.Config.ARGB_8888, true)
                    blendCanvas = Canvas(baseBitmap!!)
                    currentFrame.recycle()
                } else {
                    // 2. КОМПЕНСАЦИЯ ДВИЖЕНИЯ (ANTI-SHAKE)
                    // Находим сдвиг текущего кадра относительно базы
                    val offset = calculateMotionOffset(baseBitmap!!, currentFrame)
                    val alignMatrix = Matrix().apply {
                        postTranslate(offset.x, offset.y)
                    }

                    // Чем светлее оригинальный кадр (к концу массива), тем больше его вес при осветлении
                    val alphaWeight = if (i > 7) 0.6f else 0.4f
                    blendPaint.alpha = (255f * alphaWeight).toInt()

                    // Отрисовываем кадр со сдвигом, компенсируя дрожание рук
                    blendCanvas?.drawBitmap(currentFrame, alignMatrix, blendPaint)
                    currentFrame.recycle()
                }
            }

            // 3. ФИНАЛЬНЫЙ TONE MAPPING (Поднятие теней и контраста)
            baseBitmap?.let { bmp ->
                applyHDRToneMapping(bmp, blendCanvas)

                FileOutputStream(outputFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                bmp.recycle()
            }

            // Сбрасываем экспозицию в 0
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

    /**
     * Алгоритм быстрого поиска сдвига (Fast Block Matching).
     * Сжимает изображения до 64x64 пикселей и ищет минимальную разницу, 
     * чтобы вычислить дрожание рук (dx, dy).
     */
    private fun calculateMotionOffset(base: Bitmap, current: Bitmap): PointF {
        val targetSize = 64f
        val scale = targetSize / max(base.width, base.height).toFloat()
        
        val w = (base.width * scale).toInt()
        val h = (base.height * scale).toInt()

        // Создаем миниатюры для сверхбыстрого анализа
        val baseThumb = Bitmap.createScaledBitmap(base, w, h, true)
        val currentThumb = Bitmap.createScaledBitmap(current, w, h, true)

        val basePixels = IntArray(w * h)
        val currentPixels = IntArray(w * h)
        baseThumb.getPixels(basePixels, 0, w, 0, 0, w, h)
        currentThumb.getPixels(currentPixels, 0, w, 0, 0, w, h)

        val searchRadius = 6 // Радиус поиска в миниатюре (эквивалентно большому сдвигу в оригинале)
        var bestDx = 0
        var bestDy = 0
        var minError = Long.MAX_VALUE

        // Проходим по возможным сдвигам
        for (dy in -searchRadius..searchRadius) {
            for (dx in -searchRadius..searchRadius) {
                var error = 0L
                // Сравниваем центральную область с шагом 2 (для максимальной скорости)
                for (y in searchRadius until h - searchRadius step 2) {
                    for (x in searchRadius until w - searchRadius step 2) {
                        val basePixel = basePixels[y * w + x]
                        val curPixel = currentPixels[(y + dy) * w + (x + dx)]

                        // Извлекаем яркость пикселей
                        val baseLum = ((basePixel shr 16 and 0xFF) + (basePixel shr 8 and 0xFF) + (basePixel and 0xFF))
                        val curLum = ((curPixel shr 16 and 0xFF) + (curPixel shr 8 and 0xFF) + (curPixel and 0xFF))

                        val diff = baseLum - curLum
                        error += diff * diff // Сумма квадратов разностей
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

        // Масштабируем найденный сдвиг обратно до размеров оригинального фото
        val fullDx = bestDx / scale
        val fullDy = bestDy / scale

        return PointF(fullDx, fullDy)
    }

    /**
     * Постобработка: поднятие микроконтраста и финальной яркости.
     */
    private fun applyHDRToneMapping(bitmap: Bitmap, canvas: Canvas?) {
        val contrast = 1.18f
        val brightness = 25f // Существенное увеличение яркости

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
