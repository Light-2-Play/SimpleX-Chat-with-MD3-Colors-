package chat.simplex.common.platform

import android.graphics.SurfaceTexture
import android.media.*
import android.opengl.*
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

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

      // 1. Узнаем исходный угол наклона видео
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

      // 2. Рассчитываем правильные визуальные пропорции
      val isRotated = (rotation == 90 || rotation == 270)
      val visualWidth = if (isRotated) rawHeight else rawWidth
      val visualHeight = if (isRotated) rawWidth else rawHeight

      // 3. Вычисляем размеры энкодера (строго соблюдая пропорции)
      val (targetWidth, targetHeight) = if (visualWidth >= visualHeight) {
        val scale = 1080f / visualHeight.toFloat()
        ((visualWidth * scale).toInt() and 1.inv()) to 1080
      } else {
        val scale = 1080f / visualWidth.toFloat()
        1080 to ((visualHeight * scale).toInt() and 1.inv())
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
      
      // 4. Передаем целевые размеры и угол в отрисовщик для запекания
      outputSurface = CodecOutputSurface(targetWidth, targetHeight, rotation)

      val videoMime = inputVideoFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
      decoder = MediaCodec.createDecoderByType(videoMime).apply {
        configure(inputVideoFormat, outputSurface.surface, null, 0)
      }

      encoder.start()
      decoder.start()

      // ВАЖНО: Не передаем setOrientationHint, контейнер всегда остается 0 градусов,
      // так как кадры уже повернуты физически.
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
    private val rotMatrix = FloatArray(16)
    private var uTexMatrixLoc = -1
    private var uRotMatrixLoc = -1
    private var aPositionLoc = -1
    private var aTexCoordLoc = -1
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    init {
      val textures = IntArray(1)
      GLES20.glGenTextures(1, textures, 0)
      texId = textures[0]
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

      surfaceTexture = SurfaceTexture(texId).apply {
        setOnFrameAvailableListener(this@CodecOutputSurface)
      }
      surface = android.view.Surface(surfaceTexture)

      // Матрица физического вращения текстуры в шейдере
      Matrix.setRotateM(rotMatrix, 0, rotation.toFloat(), 0f, 0f, 1f)

      val vertexCoords = floatArrayOf(
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
      )
      vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
          put(vertexCoords)
          position(0)
        }

      val texCoords = floatArrayOf(
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
      )
      texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
          put(texCoords)
          position(0)
        }

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

      GLES20.glViewport(0, 0, width, height)
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
      GLES20.glUseProgram(program)

      GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, transformMatrix, 0)
      GLES20.glUniformMatrix4fv(uRotMatrixLoc, 1, false, rotMatrix, 0)

      GLES20.glEnableVertexAttribArray(aPositionLoc)
      GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

      GLES20.glEnableVertexAttribArray(aTexCoordLoc)
      GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

      GLES20.glDisableVertexAttribArray(aPositionLoc)
      GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    private fun initShader() {
      val vShader = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uTexMatrix;
        uniform mat4 uRotMatrix;
        varying vec2 vTexCoord;
        void main() {
          gl_Position = aPosition;
          
          // Нормализуем координаты OES-текстуры
          vec4 oesTexCoord = uTexMatrix * aTexCoord;
          
          // Смещаем к центру (0.5, 0.5), вращаем матрицей uRotMatrix и возвращаем обратно
          vec4 centered = oesTexCoord - vec4(0.5, 0.5, 0.0, 0.0);
          vec4 rotated = uRotMatrix * centered;
          vTexCoord = rotated.xy + vec2(0.5, 0.5);
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
      uRotMatrixLoc = GLES20.glGetUniformLocation(program, "uRotMatrix")
      aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
      aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
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
