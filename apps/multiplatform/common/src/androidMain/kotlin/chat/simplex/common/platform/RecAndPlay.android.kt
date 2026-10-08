package chat.simplex.common.platform

import android.content.Context
import android.media.*
import android.media.AudioManager.AudioPlaybackCallback
import android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
import android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
import android.os.Build
import androidx.compose.runtime.*
import chat.simplex.common.model.*
import chat.simplex.common.platform.AudioPlayer.duration
import chat.simplex.common.views.helpers.*
import chat.simplex.res.MR
import kotlinx.coroutines.*
import java.io.*

actual class RecorderNative: RecorderInterface {
  private var recorder: MediaRecorder? = null
  private var progressJob: Job? = null
  private var filePath: String? = null
  private var recStartedAt: Long? = null
  private fun initRecorder() =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      MediaRecorder(androidAppContext)
    } else {
      MediaRecorder()
    }

  override fun start(onProgressUpdate: (position: Int?, finished: Boolean) -> Unit): String {
    VideoPlayerHolder.stopAll()
    AudioPlayer.stop()
    val rec: MediaRecorder
    recorder = initRecorder().also { rec = it }
    rec.setAudioSource(MediaRecorder.AudioSource.MIC)
    rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
    rec.setAudioChannels(1)
    rec.setAudioSamplingRate(16000)
    rec.setAudioEncodingBitRate(32000)
    rec.setMaxDuration(MAX_VOICE_MILLIS_FOR_SENDING)
    val fileToSave = File.createTempFile(generateNewFileName("voice", "${RecorderInterface.extension}_", tmpDir), ".tmp", tmpDir)
    fileToSave.deleteOnExit()
    val path = fileToSave.absolutePath
    filePath = path
    rec.setOutputFile(path)
    rec.prepare()
    rec.start()
    recStartedAt = System.currentTimeMillis()
    progressJob = CoroutineScope(Dispatchers.Default).launch {
      while(isActive) {
        keepScreenOn(true)
        onProgressUpdate(progress(), false)
        delay(50)
      }
    }.apply {
      invokeOnCompletion {
        onProgressUpdate(realDuration(path), true)
      }
    }
    rec.setOnInfoListener { _, what, _ ->
      if (what == MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED || what == MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
        stop()
      }
    }
    RecorderInterface.stopRecording = { stop() }
    return path
  }

  override fun stop(): Int {
    val path = filePath ?: return 0
    RecorderInterface.stopRecording = null
    runCatching {
      recorder?.stop()
    }
    runCatching {
      recorder?.reset()
    }
    runCatching {
      recorder?.release()
    }
    // Await coroutine finishes in order to send real duration to it's listener
    runBlocking {
      progressJob?.cancelAndJoin()
    }
    progressJob = null
    filePath = null
    recorder = null
    keepScreenOn(false)
    return (realDuration(path) ?: 0).also { recStartedAt = null }
  }

  private fun progress(): Int? = recStartedAt?.let { (System.currentTimeMillis() - it).toInt() }

  /**
  * Return real duration from [AudioPlayer] if it's possible (should always be possible).
  * As a fallback, return internally counted duration
  * */
  private fun realDuration(path: String): Int? = duration(path) ?: progress()
}

actual object AudioPlayer: AudioPlayerInterface {
  private val audioManager = androidAppContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private var audioFocusRequest: AudioFocusRequest? = null

  private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
    when (focusChange) {
      AudioManager.AUDIOFOCUS_LOSS,
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
        // Если другое приложение или звонок перехватили звук — останавливаем войс
        if (player.isPlaying) {
          AudioPlayer.stop()
        }
      }
    }
  }

  private fun requestAudioFocus() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

      val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(playbackAttributes)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener(focusChangeListener)
        .build()

      audioFocusRequest = request
      audioManager.requestAudioFocus(request)
    } else {
      @Suppress("DEPRECATION")
      audioManager.requestAudioFocus(
        focusChangeListener,
        AudioManager.STREAM_MUSIC,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
      )
    }
  }

  private fun abandonAudioFocus() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      audioFocusRequest?.let {
        audioManager.abandonAudioFocusRequest(it)
        audioFocusRequest = null
      }
    } else {
      @Suppress("DEPRECATION")
      audioManager.abandonAudioFocus(focusChangeListener)
    }
  }

  private val player = MediaPlayer().apply {
    setAudioAttributes(
      AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .build()
    )
    audioManager.registerAudioPlaybackCallback(object: AudioPlaybackCallback() {
      override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
        if (configs?.any { it.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION } == true) {
          // In a process of making a call
          RecorderInterface.stopRecording?.invoke()
          AudioPlayer.stop()
        }
        super.onPlaybackConfigChanged(configs)
      }
    }, null)
  }

  private val helperPlayer: MediaPlayer = MediaPlayer().apply {
    setAudioAttributes(
      AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .build()
    )
  }

  override val currentlyPlaying: MutableState<CurrentlyPlayingState?> = mutableStateOf(null)
  private var progressJob: Job? = null

  override val playbackSpeed: MutableState<Float> = mutableStateOf(1.0f)

  override fun setPlaybackSpeed(speed: Float) {
    playbackSpeed.value = speed
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      runCatching {
        val params = player.playbackParams
        params.speed = speed
        params.pitch = 1.0f
        player.playbackParams = params
      }
    }
  }

  // Returns real duration of the track
  private fun start(fileSource: CryptoFile, smallView: Boolean, seek: Int? = null, onProgressUpdate: (position: Int?, state: TrackState) -> Unit): Int? {
    val absoluteFilePath = if (fileSource.isAbsolutePath) fileSource.filePath else getAppFilePath(fileSource.filePath)
    if (!File(absoluteFilePath).exists()) {
      Log.e(TAG, "No such file: ${fileSource.filePath}")
      return null
    }

    VideoPlayerHolder.stopAll()
    RecorderInterface.stopRecording?.invoke()
    val current = currentlyPlaying.value
    if (current == null || current.fileSource.filePath != fileSource.filePath || smallView != current.smallView) {
      stopListener()
      player.reset()
      runCatching {
        if (fileSource.cryptoArgs != null) {
          player.setDataSource(CryptoMediaSource(readCryptoFile(absoluteFilePath, fileSource.cryptoArgs)))
        } else {
          player.setDataSource(absoluteFilePath)
        }
      }.onFailure {
        Log.e(TAG, it.stackTraceToString())
        AlertManager.shared.showAlertMsg(generalGetString(MR.strings.unknown_error), it.stackTraceToString())
        return null
      }
      runCatching { player.prepare() }.onFailure {
        // Can happen when audio file is broken
        Log.e(TAG, it.stackTraceToString())
        AlertManager.shared.showAlertMsg(generalGetString(MR.strings.unknown_error), it.stackTraceToString())
        return null
      }
    }
    if (seek != null) player.seekTo(seek)

    // Запрашиваем временный аудиофокус перед воспроизведением (ставит внешнюю музыку на паузу)
    requestAudioFocus()
    player.start()

    // Применяем сохранённую скорость к начавшемуся треку
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && playbackSpeed.value != 1.0f) {
      runCatching {
        val params = player.playbackParams
        params.speed = playbackSpeed.value
        params.pitch = 1.0f
        player.playbackParams = params
      }
    }

    currentlyPlaying.value = CurrentlyPlayingState(fileSource, onProgressUpdate, smallView)
    progressJob = CoroutineScope(Dispatchers.Default).launch {
      onProgressUpdate(player.currentPosition, TrackState.PLAYING)
      while(isActive && player.isPlaying) {
        keepScreenOn(true)
        if (player.currentPosition == player.duration) {
          onProgressUpdate(player.currentPosition, TrackState.PLAYING)
          break
        }
        delay(50)
        onProgressUpdate(player.currentPosition, TrackState.PLAYING)
      }

      if (isActive) {
        onProgressUpdate(player.duration, TrackState.PAUSED)
      }
      // Освобождаем фокус при завершении трека, чтобы музыка возобновилась
      abandonAudioFocus()
      keepScreenOn(false)
      onProgressUpdate(null, TrackState.PAUSED)

      if (smallView && isActive) {
        stopListener()
      }
    }
    return player.duration
  }

  private fun pause(): Int {
    progressJob?.cancel()
    progressJob = null
    player.pause()
    // Освобождаем фокус при паузе
    abandonAudioFocus()
    keepScreenOn(false)
    return player.currentPosition
  }

  override fun stop() {
    if (currentlyPlaying.value == null) return
    player.stop()
    // Освобождаем фокус при остановке
    abandonAudioFocus()
    stopListener()
    keepScreenOn(false)
  }

  override fun stop(item: ChatItem) = stop(item.file?.fileName)

  override fun stop(fileName: String?) {
    if (fileName != null && currentlyPlaying.value?.fileSource?.filePath?.endsWith(fileName) == true) {
      stop()
    }
  }

  private fun stopListener() {
    val afterCoroutineCancel: CompletionHandler = {
      currentlyPlaying.value?.onProgressUpdate?.invoke(null, TrackState.REPLACED)
      currentlyPlaying.value = null
    }
    if (progressJob != null) {
      progressJob?.invokeOnCompletion(afterCoroutineCancel)
    } else {
      afterCoroutineCancel(null)
    }
    progressJob?.cancel()
    progressJob = null
  }

  override fun play(
    fileSource: CryptoFile,
    audioPlaying: MutableState<Boolean>,
    progress: MutableState<Int>,
    duration: MutableState<Int>,
    resetOnEnd: Boolean,
    smallView: Boolean,
  ) {
    if (progress.value == duration.value) {
      progress.value = 0
    }
    val realDuration = start(fileSource, smallView, progress.value) { pro, state ->
      if (pro != null) {
        progress.value = pro
      }
      if (pro == null || pro == duration.value) {
        audioPlaying.value = false
        if (pro == duration.value) {
          progress.value = if (resetOnEnd) 0 else duration.value
        } else if (state == TrackState.REPLACED) {
          progress.value = 0
        }
      }
    }
    audioPlaying.value = realDuration != null
    realDuration?.let { duration.value = it }
  }

  override fun pause(audioPlaying: MutableState<Boolean>, pro: MutableState<Int>) {
    pro.value = pause()
    audioPlaying.value = false
    keepScreenOn(false)
  }

  override fun seekTo(ms: Int, pro: MutableState<Int>, filePath: String?) {
    pro.value = ms
    if (currentlyPlaying.value?.fileSource?.filePath == filePath) {
      player.seekTo(ms)
    }
  }

  override fun duration(unencryptedFilePath: String): Int? {
    var res: Int? = null
    kotlin.runCatching {
      helperPlayer.setDataSource(unencryptedFilePath)
      helperPlayer.prepare()
      if (helperPlayer.duration <= 0) {
        Log.e(TAG, "Duration of audio is incorrect: ${helperPlayer.duration}")
      } else {
        res = helperPlayer.duration
      }
      helperPlayer.reset()
    }
    return res
  }
}

actual typealias SoundPlayer = chat.simplex.common.helpers.SoundPlayer
actual typealias CallSoundsPlayer = chat.simplex.common.helpers.CallSoundsPlayer

class CryptoMediaSource(val data: ByteArray) : MediaDataSource() {
  override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
    if (position >= data.size) return -1

    val endPosition: Int = (position + size).toInt()
    var sizeLeft: Int = size
    if (endPosition > data.size) {
      sizeLeft -= endPosition - data.size
    }

    System.arraycopy(data, position.toInt(), buffer, offset, sizeLeft)
    return sizeLeft
  }

  override fun getSize(): Long = data.size.toLong()
  override fun close() {}
}
