package chat.simplex.app.ai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileInputStream
import java.nio.ByteOrder

object AudioDecoder {

    /**
     * Декодирует аудиофайл (.m4a, .ogg Opus, .wav) в сырой поток FloatArray (16 kHz Mono).
     * Использует FileDescriptor для обхода ограничений доступа MediaExtractor.
     */
    fun decodeTo16kMonoSamples(audioFile: File): FloatArray {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return FloatArray(0)
        }

        val extractor = MediaExtractor()
        val fis = FileInputStream(audioFile)
        var codec: MediaCodec? = null

        try {
            // Передаем FileDescriptor с явным смещением и длиной файла
            extractor.setDataSource(fis.fd, 0L, audioFile.length())

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
                return FloatArray(0)
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 48000
            val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val pcmData = ArrayList<Short>()
            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false

            while (true) {
                if (!isEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEOS = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (shortBuffer.hasRemaining()) {
                            pcmData.add(shortBuffer.get())
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && isEOS) {
                    break
                }
            }

            if (pcmData.isEmpty()) return FloatArray(0)

            // Конвертация многоканального звука в моно
            val monoShorts = if (channelCount > 1) {
                val mono = ShortArray(pcmData.size / channelCount)
                for (i in mono.indices) {
                    var sum = 0
                    for (c in 0 until channelCount) {
                        sum += pcmData[i * channelCount + c]
                    }
                    mono[i] = (sum / channelCount).toShort()
                }
                mono
            } else {
                pcmData.toShortArray()
            }

            // Ресемплинг в 16000 Гц и перевод в диапазон [-1.0f, 1.0f]
            return resampleTo16k(monoShorts, sampleRate)

        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { fis.close() } catch (_: Exception) {}
        }
    }

    private fun resampleTo16k(input: ShortArray, inputSampleRate: Int): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        if (inputSampleRate == 16000) {
            val output = FloatArray(input.size)
            for (i in input.indices) {
                output[i] = input[i] / 32768.0f
            }
            return output
        }

        val ratio = inputSampleRate.toDouble() / 16000.0
        val outputSize = (input.size / ratio).toInt()
        val output = FloatArray(outputSize)

        for (i in 0 until outputSize) {
            val srcIndex = i * ratio
            val indexFloor = srcIndex.toInt()
            val fraction = (srcIndex - indexFloor).toFloat()

            val s1 = input[indexFloor.coerceAtMost(input.size - 1)] / 32768.0f
            val s2 = input[(indexFloor + 1).coerceAtMost(input.size - 1)] / 32768.0f
            output[i] = s1 + fraction * (s2 - s1)
        }
        return output
    }
}
