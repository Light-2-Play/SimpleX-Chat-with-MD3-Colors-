package chat.simplex.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

class AudioFocusManager(context: Context) {

  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private var focusRequest: AudioFocusRequest? = null

  /**
   * Запрос фокуса: ставит стороннюю музыку на паузу.
   */
  fun requestFocus(onLoss: (() -> Unit)? = null): Boolean {
    val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
      if (focusChange == AudioManager.AUDIOFOCUS_LOSS || 
          focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
        onLoss?.invoke()
      }
    }

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

      val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(playbackAttributes)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener(listener)
        .build()

      focusRequest = request
      audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    } else {
      @Suppress("DEPRECATION")
      audioManager.requestAudioFocus(
        listener,
        AudioManager.STREAM_MUSIC,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
      ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
  }

  /**
   * Освобождение фокуса: возвращает воспроизведение фоновой музыке.
   */
  fun abandonFocus() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      focusRequest?.let {
        audioManager.abandonAudioFocusRequest(it)
        focusRequest = null
      }
    } else {
      @Suppress("DEPRECATION")
      audioManager.abandonAudioFocus(null)
    }
  }
}
