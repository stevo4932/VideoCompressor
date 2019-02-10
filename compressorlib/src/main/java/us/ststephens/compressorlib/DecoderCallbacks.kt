package us.ststephens.compressorlib

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.lang.RuntimeException

// Finished listener is used to inform the user that the decoder is complete
// and they should let the encoder know via signalEndOfInputStream().
class DecoderCallbacks(private val mediaExtractor: MediaExtractor, private val encoder: MediaCodec, private val errorListener: (e: MediaCodec.CodecException) -> Unit) : MediaCodec.Callback() {

    var outputVideoFormat: MediaFormat? = null

    var isExtractorFinished: Boolean = false
    var isDecoderDone = false

    //Send the decoded byte buffers to the surface to be processed.
    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        //Ignore config flags.
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            codec.releaseOutputBuffer(index, false)
            return
        }

        val render = info.size != 0
        codec.releaseOutputBuffer(index, render)
        if (render) {
            //TODO Invoke the surfaces.
        }

        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            isDecoderDone = true
            codec.stop()
            codec.release()
            encoder.signalEndOfInputStream()
        }
    }

    //Fill Input buffers with chunks of data from the video so that we can run them through the decoder.
    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
        if (isExtractorFinished) return
        val inputBuffer = codec.getInputBuffer(index) ?:
        throw RuntimeException("No input buffer available.")
        mediaExtractor.readSampleData(inputBuffer, 0).takeUnless { it < 0 }?.let { size ->
            val presentationTime = mediaExtractor.sampleTime
            codec.queueInputBuffer(index, 0, size, presentationTime, mediaExtractor.sampleFlags)
        }
        isExtractorFinished = !mediaExtractor.advance()
        if (isExtractorFinished) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            mediaExtractor.release()
        }
    }

    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
        outputVideoFormat = format
    }

    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
        errorListener(e)
    }


}