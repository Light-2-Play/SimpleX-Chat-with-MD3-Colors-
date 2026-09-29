package chat.simplex.app.ai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

object AudioDecoder {
    /**
     * Декодирует аудиофайл (.m4a, .ogg Opus, .wav) в сырой поток FloatArray (16 kHz Mono).
     */
    fun decodeTo16kMonoSamples(audioFile: File): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(audioFile.absolutePath)

        var audioTrackIndex = -1
        var inputFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                inputFormat = format
                break
            }
        }

        if (audioTrackIndex == -1 || inputFormat == null) {
            extractor.release()
            throw IllegalArgumentException("Аудиодорожка не найдена в файле: ${audioFile.name}")
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val rawPcmBuffer = mutableListOf<Short>()
        val bufferInfo = MediaCodec.BufferInfo()
        var isEOS = false

        while (!isEOS) {
            val inIndex = codec.dequeueInputBuffer(10_000L)
            if (inIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inIndex)!!
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    isEOS = true
                } else {
                    codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            while (outIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outIndex)!!
                outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

                val shortBuffer = outputBuffer.asShortBuffer()
                while (shortBuffer.hasRemaining()) {
                    rawPcmBuffer.add(shortBuffer.get())
                }

                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        // Сведение каналов в моно
        val monoPcm = if (channelCount > 1) {
            val mono = ShortArray(rawPcmBuffer.size / channelCount)
            for (i in mono.indices) {
                var sum = 0
                for (ch in 0 until channelCount) {
                    sum += rawPcmBuffer[i * channelCount + ch]
                }
                mono[i] = (sum / channelCount).toShort()
            }
            mono
        } else {
            rawPcmBuffer.toShortArray()
        }

        // Ресемплинг до 16 кГц и нормализация в диапазон [-1.0 .. 1.0]
        return resampleTo16kFloat(monoPcm, sampleRate)
    }

    private fun resampleTo16kFloat(input: ShortArray, inputSampleRate: Int): FloatArray {
        if (inputSampleRate == 16000) {
            return FloatArray(input.size) { input[it] / 32768.0f }
        }

        val ratio = 16000.0 / inputSampleRate.toDouble()
        val targetSize = (input.size * ratio).toInt()
        val output = FloatArray(targetSize)

        for (i in 0 until targetSize) {
            val srcIndex = i / ratio
            val srcIndexFloor = srcIndex.toInt()
            val fraction = (srcIndex - srcIndexFloor).toFloat()

            val s1 = input.getOrElse(srcIndexFloor) { 0 } / 32768.0f
            val s2 = input.getOrElse(srcIndexFloor + 1) { 0 } / 32768.0f
            output[i] = s1 + fraction * (s2 - s1)
        }
        return output
    }
}
