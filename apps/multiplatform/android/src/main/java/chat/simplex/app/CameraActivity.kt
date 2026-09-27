@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)

package chat.simplex.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Rational
import android.view.Surface
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : ComponentActivity() { // или AppCompatActivity

    // Объявляем на уровне класса:
    private var activeRecording: Recording? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    
    private var outputUri: Uri? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCameraUI()
        } else {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Получаем целевой URI от SimpleX Chat
        outputUri = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
            ?: intent.data

        val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        val hasCameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission) {
            startCameraUI()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onDestroy() {
    super.onDestroy()
    activeRecording?.stop()
    cameraExecutor.shutdown()
}

   private fun startCameraUI() {
        setContent {
            CameraScreen(
                onImageCaptured = {
                    // Результат с файлом уже выставлен в takePhoto, просто закрываем экран
                    finish()
                },
                onError = {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                },
                onClose = {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
            )
        }
    }

    @Composable
    private fun CameraScreen(
        onImageCaptured: () -> Unit,
        onError: (Exception) -> Unit, // <--- ИСПРАВЛЕННАЯ СТРОЧКА
        onClose: () -> Unit
    ) {
        var cachedPreviewView by remember { mutableStateOf<PreviewView?>(null) }
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val coroutineScope = rememberCoroutineScope()

        var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
        var isNightSightActive by remember { mutableStateOf(false) }
        var selectedAspectRatio by remember { mutableStateOf("4:3") } // "4:3" или "1:1"

        var currentCamera by remember { mutableStateOf<Camera?>(null) }
        var currentImageCapture by remember { mutableStateOf<ImageCapture?>(null) }
        var currentVideoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
        var isRecordingVideo by remember { mutableStateOf(false) }

       var minZoomRatio by remember { mutableStateOf(1.0f) }
        var maxZoomRatio by remember { mutableStateOf(1.0f) }
        var currentZoomRatio by remember { mutableStateOf(1.0f) }

        // ВОТ СЮДА ВСТАВЛЯЕМ ЭТИ ДВЕ СТРОЧКИ:
        var nightSightProgress by remember { mutableStateOf(0) }
        var isProcessingNightSight by remember { mutableStateOf(false) }

        // Токены темы Monet
        val monetAccent = remember(context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Color(ContextCompat.getColor(context, android.R.color.system_accent1_200))
            } else {
                Color.White
            }
        }

        val monetAccentSoft = remember(context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Color(ContextCompat.getColor(context, android.R.color.system_accent1_100))
            } else {
                Color.White
            }
        }

        val monetButtonBg = remember(context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Color(ContextCompat.getColor(context, android.R.color.system_neutral1_900)).copy(alpha = 0.65f)
            } else {
                Color.Black.copy(alpha = 0.65f)
            }
        }

        // --- Переменные для видео и таймера ---
      // Переменные для таймера записи
        var recordingTimeSeconds by remember { mutableStateOf(0) }

// --- ВОТ СЮДА ВСТАВЛЯЕМ НАШ КОД (ШАГ 1 и ШАГ 2) ---
        var isTorchOn by remember { mutableStateOf(false) }
        val hasFlashUnit = remember(currentCamera) {
            currentCamera?.cameraInfo?.hasFlashUnit() == true
        }

        val toggleTorch: () -> Unit = {
            currentCamera?.let { cam ->
                if (cam.cameraInfo.hasFlashUnit()) {
                    val nextState = !isTorchOn
                    cam.cameraControl.enableTorch(nextState)
                    isTorchOn = nextState
                }
            }
        }
        
       // 1. Сначала объявляем остановку:
        fun stopVideoRecording() {
            activeRecording?.stop()
            activeRecording = null
        }

        // 2. Теперь объявляем старт (компилятор уже видит stopVideoRecording):
        fun startVideoRecording(videoCapture: VideoCapture<Recorder>) {
            val videoFile = File(context.cacheDir, "VID_${System.currentTimeMillis()}.mp4")
            val outputOptions = FileOutputOptions.Builder(videoFile).build()

            var pending = videoCapture.output.prepareRecording(context, outputOptions)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pending = pending.withAudioEnabled()
            }

            activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecordingVideo = true
                    }
                    is VideoRecordEvent.Status -> {
                        val durationSec = (event.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()
                        recordingTimeSeconds = durationSec
                        if (durationSec >= 120) {
                            stopVideoRecording()
                        }
                    }
                   is VideoRecordEvent.Finalize -> {
                        isRecordingVideo = false
                        recordingTimeSeconds = 0
                        if (!event.hasError()) {
                            val authority = "${context.packageName}.provider"
                            // Обязательно возвращаем URI через FileProvider, чтобы SimpleX его "съел"
                            val videoUri = FileProvider.getUriForFile(context, authority, videoFile)

                            val resultIntent = Intent().apply {
                                data = videoUri
                                putExtra("IS_VIDEO", true)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            (context as? Activity)?.setResult(Activity.RESULT_OK, resultIntent)
                            (context as? Activity)?.finish()
                        } else {
                            videoFile.delete()
                        }
                    }
                } // закрывает when (event)
            } // закрывает pending.start { ... }
        } // закрывает fun startVideoRecording

        @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
fun bindCamera(previewView: PreviewView) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

               val baseSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                // Получаем характеристики текущего сенсора
                val cameraInfo = cameraProvider.getCameraInfo(baseSelector)
                val camera2Info = Camera2CameraInfo.from(cameraInfo)
                
                val availableStabilizationModes = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
                ) ?: intArrayOf()

                val availableOisModes = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
                ) ?: intArrayOf()

                // 1. Превью (видоискатель)
                val previewBuilder = Preview.Builder()
                val camera2Preview = Camera2Interop.Extender(previewBuilder)

                // Оптическая стабилизация (OIS) — включаем, если модуль есть физически
                val supportsOis = availableOisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                if (supportsOis && lensFacing == CameraSelector.LENS_FACING_BACK) {
                    camera2Preview.setCaptureRequestOption(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                }

                // Электронная стабилизация (EIS) для превью и видео:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    availableStabilizationModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
                ) {
                    // Android 13+: стабилизирует превью видоискателя и видеопоток синхронно
                    camera2Preview.setCaptureRequestOption(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                    )
                } else if (availableStabilizationModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                    // Стандартный EIS для видеопотока
                    camera2Preview.setCaptureRequestOption(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    )
                }

                // Настройки превью в ночном режиме
                if (isNightSightActive) {
                    camera2Preview.setCaptureRequestOption(
                        CaptureRequest.NOISE_REDUCTION_MODE,
                        CaptureRequest.NOISE_REDUCTION_MODE_FAST
                    )
                }

                val preview = previewBuilder.build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                // 2. Фотозахват (максимальное качество)
                val captureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

                val camera2Capture = Camera2Interop.Extender(captureBuilder)

                if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    camera2Capture.setCaptureRequestOption(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                }

                if (isNightSightActive) {
                    camera2Capture.setCaptureRequestOption(
                        CaptureRequest.NOISE_REDUCTION_MODE,
                        CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                    )
                    camera2Capture.setCaptureRequestOption(
                        CaptureRequest.HOT_PIXEL_MODE,
                        CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
                    )
                    camera2Capture.setCaptureRequestOption(
                        CaptureRequest.EDGE_MODE,
                        CaptureRequest.EDGE_MODE_HIGH_QUALITY
                    )
                    camera2Capture.setCaptureRequestOption(
                        CaptureRequest.TONEMAP_MODE,
                        CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
                    )
                }

                val imageCapture = captureBuilder.build()
                currentImageCapture = imageCapture

                // 3. Видеозахват (720p HD, энкодер работает в фоне на cameraExecutor)
                val qualitySelector = QualitySelector.from(
                    Quality.HD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                )
                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .setExecutor(cameraExecutor)
                    .build()

                val videoCapture = VideoCapture.withOutput(recorder)
                currentVideoCapture = videoCapture

                // 4. Единый ViewPort под 4:3 или 1:1
                val targetRational = if (selectedAspectRatio == "1:1") {
                    android.util.Rational(1, 1)
                } else {
                    android.util.Rational(3, 4)
                }

                val rotation = previewView.display?.rotation 
                    ?: (context as? Activity)?.windowManager?.defaultDisplay?.rotation 
                    ?: android.view.Surface.ROTATION_0

                val viewPort = androidx.camera.core.ViewPort.Builder(targetRational, rotation)
                    .setScaleType(androidx.camera.core.ViewPort.FILL_CENTER)
                    .build()

                val useCaseGroup = androidx.camera.core.UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(imageCapture)
                    .addUseCase(videoCapture)
                    .build()

                // Хелпер для подписки на зум и выставления экспозиции
                fun configureActiveCamera(camera: androidx.camera.core.Camera) {
                    currentCamera = camera

                    camera.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
                        minZoomRatio = state.minZoomRatio
                        maxZoomRatio = state.maxZoomRatio
                    }

                    val exposureState = camera.cameraInfo.exposureState
                    if (exposureState.isExposureCompensationSupported) {
                        val range = exposureState.exposureCompensationRange
                        val targetIndex = if (isNightSightActive) {
                            (range.upper * 0.4f).toInt().coerceIn(range.lower, range.upper)
                        } else {
                            0
                        }
                        camera.cameraControl.setExposureCompensationIndex(targetIndex)
                    }
                }

                try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        baseSelector,
                        useCaseGroup
                    )
                    configureActiveCamera(camera)
                } catch (e: Exception) {
                    android.util.Log.e("CameraActivity", "Error binding with ViewPort, falling back to direct binding", e)
                    try {
                        // Резервный запуск без кастомного ViewPort, если HAL отклонил связку
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            baseSelector,
                            preview,
                            imageCapture,
                            videoCapture
                        )
                        configureActiveCamera(camera)
                    } catch (fatal: Exception) {
                        android.util.Log.e("CameraActivity", "Fatal camera binding error", fatal)
                    }
                }
            }, ContextCompat.getMainExecutor(context))
        }

// 2. И только ПОД НЕЙ вызывается LaunchedEffect:
        LaunchedEffect(lensFacing, isNightSightActive, selectedAspectRatio, cachedPreviewView) {
            cachedPreviewView?.let { pv ->
                bindCamera(pv)
            }
        }
        
        // Пресеты линз
        val lensPresets = remember(minZoomRatio, maxZoomRatio, lensFacing) {
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                listOf(1.0f to "1×")
            } else {
                val list = mutableListOf<Pair<Float, String>>()
                if (minZoomRatio <= 0.75f) {
                    list.add(minZoomRatio to ".5")
                }
                list.add(1.0f to "1×")
                if (maxZoomRatio >= 2.0f) {
                    list.add(2.0f to "2×")
                }
                if (maxZoomRatio >= 5.0f) {
                    list.add(5.0f to "5×")
                }
                list
            }
        }

        // Аниматор зума
        val zoomAnim = remember { Animatable(1.0f) }
        val onSelectLens: (Float) -> Unit = { targetRatio ->
            currentZoomRatio = targetRatio
            coroutineScope.launch {
                zoomAnim.animateTo(
                    targetValue = targetRatio,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                ) {
                    currentCamera?.cameraControl?.setZoomRatio(this.value)
                }
            }
        }

        // КОРНЕВОЙ КОНТЕЙНЕР ЭКРАНА (Используем Box для правильного наложения)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) { // 1. Открываем Root Box

            // 1. Видоискатель (строго по центру)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (selectedAspectRatio == "1:1") 1f else 3f / 4f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.DarkGray)
                    .align(Alignment.Center)
                    .pointerInput(currentCamera, minZoomRatio, maxZoomRatio) {
                        detectTransformGestures { _, _, zoom, _ ->
                            currentCamera?.let { cam ->
                                val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1.0f
                                val target = (current * zoom).coerceIn(minZoomRatio, maxZoomRatio)
                                cam.cameraControl.setZoomRatio(target)
                            }
                        }
                    }
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            cachedPreviewView = this
                        }
                    }
                )
            }

            // Индикатор ночной съемки
            if (isProcessingNightSight) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BasicText(
                            text = "🌙 Ночная съемка...",
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        BasicText(
                            text = "Держите камеру неподвижно\nКадр $nightSightProgress из auto",
                            style = TextStyle(
                                color = monetAccentSoft,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                        )
                    }
                }
            }

            // 2. Верхняя панель (Кнопка закрытия)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(top = 44.dp, start = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(monetButtonBg, CircleShape)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(16.dp)) {
                        val stroke = 2.5f.dp.toPx()
                        drawLine(monetAccentSoft, Offset(0f, 0f), Offset(size.width, size.height), stroke, StrokeCap.Round)
                        drawLine(monetAccentSoft, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
                    }
                }
            }

            // 3. Таймер записи видео
            if (isRecordingVideo) {
                val minutes = recordingTimeSeconds / 60
                val seconds = recordingTimeSeconds % 60
                val timeFormatted = String.format("%02d:%02d / 02:00", minutes, seconds)

                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 44.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color.Red, CircleShape)
                    )
                    BasicText(
                        text = timeFormatted,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

           // 4. Нижняя панель управления
Column(
    modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.BottomCenter)
        .padding(bottom = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.SpaceEvenly
) {

    // Ряд: [Колонка слева: 4:3 + Фонарик] — [Объективы по центру] — [Кнопка Луна справа]
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        // СЛЕВА: Колонка с кнопкой 4:3 и кнопкой фонарика ПОД НЕЙ
        Column(
            modifier = Modifier.align(Alignment.CenterStart),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Кнопка 4:3 / 1:1
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(monetButtonBg, CircleShape)
                    .clickable {
                        selectedAspectRatio = if (selectedAspectRatio == "4:3") "1:1" else "4:3"
                    },
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = selectedAspectRatio,
                    style = TextStyle(
                        color = monetAccentSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                )
            }

            // Кнопка фонарика строго под 4:3
            if (hasFlashUnit) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (isTorchOn) monetAccent else monetButtonBg,
                            CircleShape
                        )
                        .clickable { toggleTorch() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Фонарик",
                        tint = if (isTorchOn) Color.Black else monetAccentSoft,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Пресеты зума по центру
        if (lensPresets.size > 1) {
            Row(
                modifier = Modifier
                    .background(monetButtonBg, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                lensPresets.forEach { (ratio, label) ->
                    val isSelected = kotlin.math.abs(currentZoomRatio - ratio) < 0.25f

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(
                                if (isSelected) monetAccent else Color.Transparent,
                                CircleShape
                            )
                            .clickable { onSelectLens(ratio) },
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            text = label,
                            style = TextStyle(
                                color = if (isSelected) Color.Black else monetAccentSoft,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                textAlign = TextAlign.Center
                            )
                        )
                    }
                }
            }
        }

        // Кнопка ночного режима справа
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(40.dp)
                .background(
                    if (isNightSightActive) monetAccent else monetButtonBg,
                    CircleShape
                )
                .clickable {
                    isNightSightActive = !isNightSightActive
                    cachedPreviewView?.let { bindCamera(it) }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(18.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.75f, size.height * 0.15f)
                    cubicTo(size.width * 0.35f, size.height * 0.15f, size.width * 0.15f, size.height * 0.45f, size.width * 0.25f, size.height * 0.85f)
                    cubicTo(size.width * 0.55f, size.height * 1.05f, size.width * 0.95f, size.height * 0.85f, size.width * 0.95f, size.height * 0.65f)
                    cubicTo(size.width * 0.65f, size.height * 0.70f, size.width * 0.55f, size.height * 0.35f, size.width * 0.75f, size.height * 0.15f)
                    close()
                }
                drawPath(path = path, color = if (isNightSightActive) Color.Black else monetAccentSoft)
            }
        }
    } // Закрывает Box ряда пресетов

    // Нижний ряд: [Спейсер] — [Затвор] — [Переворот камеры]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.size(48.dp))

        // Затвор (Тап = Фото, Удержание = Видео)
        Box(
            modifier = Modifier
                .size(76.dp)
                .border(4.dp, if (isRecordingVideo) Color.Red else monetAccent, CircleShape)
                .padding(if (isRecordingVideo) 14.dp else 6.dp)
                .background(
                    if (isRecordingVideo) Color.Red else monetAccent,
                    if (isRecordingVideo) RoundedCornerShape(8.dp) else CircleShape
                )
                .pointerInput(currentImageCapture, currentVideoCapture, currentCamera, isNightSightActive) {
                    detectTapGestures(
                        onPress = {
                            val timerJob = coroutineScope.launch {
                                delay(350)
                                currentVideoCapture?.let { vc ->
                                    if (activeRecording == null) {
                                        startVideoRecording(vc)
                                    }
                                }
                            }

                            val released = tryAwaitRelease()
                            timerJob.cancel()

                            if (activeRecording != null || isRecordingVideo) {
                                stopVideoRecording()
                            } else if (released) {
                                currentImageCapture?.let { capture ->
                                    if (isNightSightActive) {
                                        isProcessingNightSight = true
                                    }
                                    takePhoto(
                                        imageCapture = capture,
                                        camera = currentCamera,
                                        isNightMode = isNightSightActive,
                                        scope = coroutineScope,
                                        onProgress = { frame -> nightSightProgress = frame },
                                        onSuccess = {
                                            isProcessingNightSight = false
                                            onImageCaptured()
                                        },
                                        onError = { exc ->
                                            isProcessingNightSight = false
                                            onError(exc)
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
        ) // Закрывает Box затвора

        // Переворот камеры
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(monetButtonBg, CircleShape)
                .clickable {
                    if (isTorchOn) {
                        currentCamera?.cameraControl?.enableTorch(false)
                        isTorchOn = false
                    }
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                    cachedPreviewView?.let { bindCamera(it) }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(20.dp)) {
                drawArc(
                    color = monetAccentSoft,
                    startAngle = 0f,
                    sweepAngle = 280f,
                    useCenter = false,
                    style = Stroke(width = 2.5f.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    } // Закрывает Row нижнего ряда
} // Закрывает Column нижней панелиней панели
        } // 3. Закрывает корневой Box экрана
    } // 4. Закрывает @Composable CameraScreen

    private fun takePhoto(
        imageCapture: ImageCapture,
        camera: Camera?,
        isNightMode: Boolean,
        scope: kotlinx.coroutines.CoroutineScope,
        onProgress: (Int) -> Unit,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        val photoFile = File(cacheDir, "IMG_${System.currentTimeMillis()}.jpg")

        fun deliverSuccess() {
            // Если SimpleX передал целевой URI, копируем байты туда
            outputUri?.let { destUri ->
                try {
                    contentResolver.openOutputStream(destUri)?.use { out ->
                        FileInputStream(photoFile).use { input -> input.copyTo(out) }
                    }
                } catch (e: Exception) {
                    Log.e("CameraActivity", "Failed to write to outputUri", e)
                }
            }

            val authority = "${packageName}.provider"
            val fileUri = FileProvider.getUriForFile(this@CameraActivity, authority, photoFile)
            val finalUri = outputUri ?: fileUri

            val resultIntent = Intent().apply {
                data = finalUri
                putExtra(MediaStore.EXTRA_OUTPUT, finalUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            runOnUiThread {
                onSuccess()
                finish()
            }
        }

        
        if (isNightMode && camera != null) {
            scope.launch {
                try {
                    NightSightConfig.captureMultiFrameNightSight(
                        context = this@CameraActivity,
                        camera = camera,
                        imageCapture = imageCapture,
                        outputFile = photoFile,
                        onProgress = { progress -> runOnUiThread { onProgress(progress) } }
                    )
                    deliverSuccess()
                } catch (e: Exception) {
                    runOnUiThread { onError(e) }
                }
            }
        } else {
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
            imageCapture.takePicture(
                outputOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        deliverSuccess()
                    }

                    override fun onError(exc: ImageCaptureException) {
                        runOnUiThread { onError(exc) }
                    }
                }
            )
        }
    }
} // 5. Финальная скобка класса CameraActivity
