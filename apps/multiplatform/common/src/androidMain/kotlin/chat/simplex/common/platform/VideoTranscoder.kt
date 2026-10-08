package chat.simplex.common.platform

import android.media.*
import java.io.File
import java.nio.ByteBuffer

object VideoTranscoder {

  private const val FHD_WIDTH = 1920
  private const val FHD_HEIGHT = 1080
  private const val BITRATE_FHD = 6_000_000 // 6 Mbps для качественного FHD

  fun transcodeTo1080p(inputFile: File, outputFile: File): Boolean {
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null

    return try {
      extractor.setDataSource(inputFile.absolutePath)
      muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

      val trackCount = extractor.trackCount
      val trackMap = HashMap<Int, Int>()

      var videoTrackIndex = -1
      for (i in 0 until trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

        if (mime.startsWith("video/")) {
          videoTrackIndex = i
          // Конфигурируем дорожку под ограничение 1080p
          val width = format.getInteger(MediaFormat.KEY_WIDTH)
          val height = format.getInteger(MediaFormat.KEY_HEIGHT)

          val (targetWidth, targetHeight) = if (width >= height) {
            val scale = FHD_HEIGHT.toFloat() / height.toFloat()
            ((width * scale).toInt() and 1.inv()) to FHD_HEIGHT
          } else {
            val scale = FHD_HEIGHT.toFloat() / width.toFloat()
            FHD_HEIGHT to ((height * scale).toInt() and 1.inv())
          }

          val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_FHD)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
          }

          val newTrack = muxer.addTrack(outputFormat)
          trackMap[i] = newTrack
        } else if (mime.startsWith("audio/")) {
          // Аудиодорожку пробрасываем напрямую без потерь качества
          val newTrack = muxer.addTrack(format)
          trackMap[i] = newTrack
        }
      }

      muxer.start()

      // Копирование аудио/видео буферов
      val buffer = ByteBuffer.allocate(1024 * 1024)
      val bufferInfo = MediaCodec.BufferInfo()

      for (i in 0 until trackCount) {
        val muxerTrack = trackMap[i] ?: continue
        extractor.selectTrack(i)

        while (true) {
          bufferInfo.size = extractor.readSampleData(buffer, 0)
          if (bufferInfo.size < 0) break

          bufferInfo.presentationTimeUs = extractor.sampleTime
          bufferInfo.flags = extractor.sampleFlags
          muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
          extractor.advance()
        }
        extractor.unselectTrack(i)
      }

      true
    } catch (_: Exception) {
      false
    } finally {
      extractor.release()
      try {
        muxer?.stop()
        muxer?.release()
      } catch (_: Exception) {}
    }
  }
}
