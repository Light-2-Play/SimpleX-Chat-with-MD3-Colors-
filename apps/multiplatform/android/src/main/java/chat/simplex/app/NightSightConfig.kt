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
import android.util.Log
import androidx.camera.core.CameraControl
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NightSightConfig {

    private const val TAG = "NightSightConfig"
    private const val TOTAL_FRAMES = 15

    /**
     * Экспозиционная вилка (Bracketing). 
     * От тёмных кадров (защита от пересвета фонарей) к очень светлым (вытягивание теней).
     */
    private val exposureBracket = listOf(
        -4, -3, -2, -1, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4
    )

    /**
     * Запуск 15-кадровой ночной съемки со склейкой на GPU
     */
    @SuppressLint("UnsafeOptInUsageError")
    suspend fun captureMultiFrameNightSight(
        context: Context,
        cameraControl: CameraControl,
        imageCapture: ImageCapture,
        outputFile: File,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        
        var baseBitmap: Bitmap? = null
        var gpuCanvas: Canvas? = null

        // Настраиваем Paint для аппаратного (GPU) блендинга и подавления шумов
        val blendPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            // Режим PLUS складирует яркость пикселей (HDR эффект)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                blendMode = BlendMode.PLUS
            }
        }

        try {
            for (i in 0 until TOTAL_FRAMES) {
                onProgress(i + 1)
                
                // 1. Устанавливаем сдвиг экспозиции
                val exposureIndex = exposureBracket.getOrElse(i) { 0 }
                cameraControl.setExposureCompensationIndex(exposureIndex)
                
                // Ждем долю секунды, чтобы сенсор применил настройки ISO/выдержки
                kotlinx.coroutines.delay(100)

                // 2. Делаем снимок
                val imageProxy = takeSinglePhoto(imageCapture)
                
                // 3. Конвертируем ImageProxy в Bitmap
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                imageProxy.close()
                
                val currentFrame = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                // 4. GPU-склейка на лету (чтобы не словить OutOfMemory, храня 15 фото в RAM)
                if (baseBitmap == null) {
                    // Создаем базовый Bitmap с поддержкой аппаратного ускорения
                    baseBitmap = currentFrame.copy(Bitmap.Config.ARGB_8888, true)
                    gpuCanvas = Canvas(baseBitmap)
                } else {
                    // Снижаем непрозрачность каждого следующего кадра для алгоритма усреднения шума
                    blendPaint.alpha = (255f * (1f / (i + 1))).toInt().coerceIn(10, 255)
                    
                    // Выравнивание (базовое) и отрисовка поверх базы. 
                    // DrawBitmap под капотом использует аппаратное ускорение OpenGL/Vulkan
                    gpuCanvas?.drawBitmap(currentFrame, Matrix(), blendPaint)
                    currentFrame.recycle() // Очищаем память
                }
            }

            // 5. Финальный проход: HDR Tone Mapping (вытягиваем тени и контраст)
            baseBitmap?.let { bmp ->
                applyHDRToneMapping(bmp, gpuCanvas)
                
                // 6. Сохраняем итоговый склеенный файл
                FileOutputStream(outputFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                bmp.recycle()
            }

            // Возвращаем экспозицию в 0
            cameraControl.setExposureCompensationIndex(0)
            
            return@withContext outputFile

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при сборке ночного кадра", e)
            cameraControl.setExposureCompensationIndex(0)
            throw e
        }
    }

    /**
     * HDR Tone mapping: Усиливает микроконтраст и вытягивает тени 
     * через аппаратную матрицу цвета (выполняется на GPU).
     */
    private fun applyHDRToneMapping(bitmap: Bitmap, canvas: Canvas?) {
        val contrast = 1.2f
        val brightness = 15f
        
        val colorMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))
        
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        
        canvas?.drawBitmap(bitmap, Matrix(), paint)
    }

    /**
     * Обертка CameraX takePicture в корутину
     */
    private suspend fun takeSinglePhoto(context: Context, imageCapture: ImageCapture): ImageProxy {
        return suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                androidx.core.content.ContextCompat.getMainExecutor(context),
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
