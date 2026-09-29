package chat.simplex.app.ai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import chat.simplex.common.model.CryptoFile
import chat.simplex.common.platform.CryptoMediaSource
import chat.simplex.common.platform.getAppFilePath
import chat.simplex.common.model.readCryptoFile
import java.io.File
import java.nio.ByteOrder

object AudioDecoder {

    fun decodeTo16kMonoSamples(fileSource: CryptoFile): FloatArray {
        val absoluteFilePath = if (fileSource.isAbsolutePath) fileSource.filePath else getAppFilePath(fileSource.filePath)
        val file = File(absoluteFilePath)
        if (!file.exists() || file.length() == 0L) {
            return FloatArray(0)
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            // Подключаем расшифрованный источник данных
            val cryptoArgs = fileSource.cryptoArgs
if (cryptoArgs != null) {
    extractor.setDataSource(CryptoMediaSource(readCryptoFile(absoluteFilePath, cryptoArgs)))
} else {
    extractor.setDataSource(absoluteFilePath)
}

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
            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 48000
            var channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val pcmData = ArrayList<Short>()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            var noOutputCounter = 0

            while (!sawOutputEOS && noOutputCounter < 60) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    noOutputCounter = 0
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
                        sawOutputEOS = true
                        break
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (sawInputEOS) noOutputCounter++
                }
            }

            if (pcmData.isEmpty()) return FloatArray(0)

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

            return resampleTo16k(monoShorts, sampleRate)

        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
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
