package chat.simplex.common.platform

import android.graphics.SurfaceTexture
import android.media.*
import android.opengl.*
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object VideoTranscoder {

  private const val TAG = "VideoTranscoder"
  private const val TIMEOUT_USEC = 10_000L
  private const val TARGET_BITRATE = 6_000_000 // 6 Mbps

  fun transcodeTo1080p(inputFile: File, outputFile: File): Boolean {
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var decoder: MediaCodec? = null
    var encoder: MediaCodec? = null
    var eglCore: EglCore? = null
    var outputSurface: CodecOutputSurface? = null

    return try {
      extractor.setDataSource(inputFile.absolutePath)

      var videoTrackIndex = -1
      var audioTrackIndex = -1
      var inputVideoFormat: MediaFormat? = null
      var inputAudioFormat: MediaFormat? = null

      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("video/") && videoTrackIndex == -1) {
          videoTrackIndex = i
          inputVideoFormat = format
        } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
          audioTrackIndex = i
          inputAudioFormat = format
        }
      }

      if (videoTrackIndex == -1 || inputVideoFormat == null) return false

      // 1. Извлекаем исходный угол поворота
      val rotation = try {
        var rot = 0
        if (inputVideoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
          rot = inputVideoFormat.getInteger(MediaFormat.KEY_ROTATION)
        }
        if (rot == 0) {
          val retriever = MediaMetadataRetriever()
          retriever.setDataSource(inputFile.absolutePath)
          rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
          retriever.release()
        }
        rot
      } catch (_: Exception) {
        0
      }

      val rawWidth = inputVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
      val rawHeight = inputVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)

      // 2. Определяем реальную визуальную ориентацию видео
      val isPortrait = (rotation == 90 || rotation == 270)
      val visualWidth = if (isPortrait) rawHeight else rawWidth
      val visualHeight = if (isPortrait) rawWidth else rawHeight

      // 3. Рассчитываем точные размеры без искажения пропорций
      val (targetWidth, targetHeight) = if (isPortrait) {
        // Вертикальное видео: ширина фиксируется на 1080p
        val scale = 1080f / visualWidth.toFloat()
        1080 to ((visualHeight * scale).toInt() and 1.inv())
      } else {
        // Горизонтальное видео: высота фиксируется на 1080p
        val scale = 1080f / visualHeight.toFloat()
        ((visualWidth * scale).toInt() and 1.inv()) to 1080
      }

      val outputVideoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BITRATE)
        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
      }

      encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
        configure(outputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      }

      eglCore = EglCore(encoder.createInputSurface())
      // Передаем угол в рендерер для аппаратного разворота кадра
      outputSurface = CodecOutputSurface(targetWidth, targetHeight, rotation)

      val videoMime = inputVideoFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
      decoder = MediaCodec.createDecoderByType(videoMime).apply {
        configure(inputVideoFormat, outputSurface.surface, null, 0)
      }

      encoder.start()
      decoder.start()

      // Флаг ориентации оставляем 0: видео запекается сразу в правильном положении
      muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

      var muxerVideoTrack = -1
      var muxerAudioTrack = -1
      var muxerStarted = false

      if (audioTrackIndex != -1 && inputAudioFormat != null) {
        muxerAudioTrack = muxer.addTrack(inputAudioFormat)
      }

      extractor.selectTrack(videoTrackIndex)

      val bufferInfo = MediaCodec.BufferInfo()
      var sawInputEOS = false
      var sawOutputEOS = false
      var encoderDone = false

      while (!encoderDone) {
        if (!sawInputEOS) {
          val inIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC)
          if (inIndex >= 0) {
            val inputBuf = decoder.getInputBuffer(inIndex)
            if (inputBuf != null) {
              val sampleSize = extractor.readSampleData(inputBuf, 0)
              if (sampleSize < 0) {
                sawInputEOS = true
                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              } else {
                val pts = extractor.sampleTime
                decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                extractor.advance()
              }
            }
          }
        }

        if (!sawOutputEOS) {
          val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
          if (outIndex >= 0) {
            val render = bufferInfo.size > 0
            decoder.releaseOutputBuffer(outIndex, render)
            if (render) {
              outputSurface.awaitNewImage()
              outputSurface.drawImage()
              eglCore.setPresentationTime(bufferInfo.presentationTimeUs * 1000L)
              eglCore.swapBuffers()
            }
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              sawOutputEOS = true
              encoder.signalEndOfInputStream()
            }
          }
        }

        while (true) {
          val encIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
          if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
            muxer.start()
            muxerStarted = true
          } else if (encIndex >= 0) {
            val encodedData = encoder.getOutputBuffer(encIndex)
            if (encodedData != null && muxerStarted && bufferInfo.size > 0) {
              if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)
              }
            }
            encoder.releaseOutputBuffer(encIndex, false)
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              encoderDone = true
              break
            }
          } else {
            break
          }
        }
      }

      // Проброс исходной аудиодорожки
      if (audioTrackIndex != -1 && muxerStarted) {
        extractor.unselectTrack(videoTrackIndex)
        extractor.selectTrack(audioTrackIndex)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val audioBuffer = ByteBuffer.allocate(512 * 1024)
        val audioInfo = MediaCodec.BufferInfo()

        while (true) {
          audioInfo.size = extractor.readSampleData(audioBuffer, 0)
          if (audioInfo.size < 0) break
          audioInfo.presentationTimeUs = extractor.sampleTime
          audioInfo.flags = extractor.sampleFlags
          muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioInfo)
          extractor.advance()
        }
      }

      true
    } catch (e: Exception) {
      Log.e(TAG, "Transcoding failed: ${e.message}", e)
      false
    } finally {
      try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
      try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
      outputSurface?.release()
      eglCore?.release()
      try {
        muxer?.stop()
        muxer?.release()
      } catch (_: Exception) {}
      extractor.release()
    }
  }

  private class EglCore(surface: Any) {
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE

    init {
      eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      val version = IntArray(2)
      EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

      val attribList = intArrayOf(
        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        0x3142, 1, EGL14.EGL_NONE
      )
      val configs = arrayOfNulls<EGLConfig>(1)
      val numConfigs = IntArray(1)
      EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)

      val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
      eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

      val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
      eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttribs, 0)
      EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    fun setPresentationTime(nsecs: Long) = EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    fun swapBuffers() = EGL14.eglSwapBuffers(eglDisplay, eglSurface)

    fun release() {
      if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
      }
    }
  }

  private class CodecOutputSurface(
    private val width: Int,
    private val height: Int,
    private val rotation: Int
  ) : SurfaceTexture.OnFrameAvailableListener {

    private val surfaceTexture: SurfaceTexture
    val surface: android.view.Surface
    private val lock = Object()
    private var frameAvailable = false
    private var program = 0
    private var texId = 0
    private val transformMatrix = FloatArray(16)
    private val finalMatrix = FloatArray(16)
    private val rotMatrix = FloatArray(16)
    private var uTexMatrixLoc = -1

    init {
      val textures = IntArray(1)
      GLES20.glGenTextures(1, textures, 0)
      texId = textures[0]
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

      surfaceTexture = SurfaceTexture(texId).apply {
        setDefaultBufferSize(width, height)
        setOnFrameAvailableListener(this@CodecOutputSurface)
      }
      surface = android.view.Surface(surfaceTexture)
      initShader()
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
      synchronized(lock) {
        frameAvailable = true
        lock.notifyAll()
      }
    }

    fun awaitNewImage() {
      synchronized(lock) {
        while (!frameAvailable) {
          lock.wait(500)
        }
        frameAvailable = false
      }
      surfaceTexture.updateTexImage()
    }

    fun drawImage() {
      surfaceTexture.getTransformMatrix(transformMatrix)

      // Поворачиваем текстурные координаты вокруг центра кадра (0.5, 0.5)
      if (rotation != 0) {
        Matrix.setIdentityM(rotMatrix, 0)
        Matrix.translateM(rotMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(rotMatrix, 0, -rotation.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(rotMatrix, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(finalMatrix, 0, transformMatrix, 0, rotMatrix, 0)
      } else {
        System.arraycopy(transformMatrix, 0, finalMatrix, 0, 16)
      }

      GLES20.glViewport(0, 0, width, height)
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
      GLES20.glUseProgram(program)
      GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, finalMatrix, 0)
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun initShader() {
      val vShader = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        void main() {
          gl_Position = aPosition;
          vTexCoord = (uTexMatrix * aTexCoord).xy;
        }
      """.trimIndent()

      val fShader = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
          gl_FragColor = texture2D(sTexture, vTexCoord);
        }
      """.trimIndent()

      val vs = loadShader(GLES20.GL_VERTEX_SHADER, vShader)
      val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fShader)
      program = GLES20.glCreateProgram().also {
        GLES20.glAttachShader(it, vs)
        GLES20.glAttachShader(it, fs)
        GLES20.glLinkProgram(it)
      }

      uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")

      val quadData = floatArrayOf(
        -1.0f, -1.0f, 0f, 0f,
         1.0f, -1.0f, 1f, 0f,
        -1.0f,  1.0f, 0f, 1f,
         1.0f,  1.0f, 1f, 1f
      )
      val buffer = ByteBuffer.allocateDirect(quadData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quadData)
      buffer.position(0)
      val aPos = GLES20.glGetAttribLocation(program, "aPosition")
      GLES20.glEnableVertexAttribArray(aPos)
      GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, buffer)

      buffer.position(2)
      val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
      GLES20.glEnableVertexAttribArray(aTex)
      GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, buffer)
    }

    private fun loadShader(type: Int, code: String): Int = GLES20.glCreateShader(type).also {
      GLES20.glShaderSource(it, code)
      GLES20.glCompileShader(it)
    }

    fun release() {
      surface.release()
      surfaceTexture.release()
    }
  }
}
