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
    
    // 5 кадров — оптимальный баланс между качеством и скоростью (съемка занимает ~2.5 секунды)
    private val exposureBracket = listOf(-2, -1, 0, 1, 2)
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

                val targetExposure = exposureBracket[i].coerceIn(range.lower, range.upper)
                if (exposureState.isExposureCompensationSupported) {
                    cameraControl.setExposureCompensationIndex(targetExposure)
                }

                delay(80)

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
                    blendCanvas = Canvas(baseBitmap)
                    currentFrame.recycle()
                } else {
                    blendPaint.alpha = (255f * (1f / (i + 1))).toInt().coerceIn(20, 255)
                    blendCanvas?.drawBitmap(currentFrame, 0f, 0f, blendPaint)
                    currentFrame.recycle()
                }
            }

            baseBitmap?.let { bmp ->
                applyHDRToneMapping(bmp, blendCanvas)

                FileOutputStream(outputFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
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

    private fun applyHDRToneMapping(bitmap: Bitmap, canvas: Canvas?) {
        val contrast = 1.12f
        val brightness = 12f

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
