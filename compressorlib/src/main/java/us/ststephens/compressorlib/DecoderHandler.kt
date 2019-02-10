package us.ststephens.compressorlib

import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Surface
import java.io.File
import java.io.IOException
import java.lang.Exception
import java.lang.IllegalArgumentException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class DecoderHandler(looper: Looper,
                     private val src: File,
                     private val encoder: MediaCodec,
                     private val error: (e: Exception) -> Unit) : Handler(looper) {

    private val mediaExtractor = MediaExtractor()

    private val lock = ReentrantLock()
    private val lockCondition = lock.newCondition()

    private var decoder: MediaCodec? = null
    private var isSet: Boolean = false
    private var mime: String? = null

    init {
        try {
            mediaExtractor.setDataSource(src.path)
        } finally {
            mediaExtractor.release()
        }
    }

    override fun handleMessage(msg: Message?) {
        try {
            decoder = mime?.let {
                val callBacks = DecoderCallbacks(mediaExtractor, encoder) { error -> handleError(error) }
               createDecoder()?.apply {
                    setCallback(callBacks)
                }
            }
        } catch (ignore: Exception) {}
        lock.withLock {
            isSet = true
            lockCondition.signalAll()
        }
    }

    fun create(mime: String) {
        this.mime = mime
        isSet = false
        sendEmptyMessage(0)
        lock.withLock {
            while (!isSet) {
                try {
                    lockCondition.await()
                } catch (ignore: InterruptedException) { }
            }
        }
    }

    private fun createDecoder(format: MediaFormat, outputSurface: Surface) : MediaCodec? {
        val mime = format.getString(MediaFormat.KEY_MIME)
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

        for (info in codecList.codecInfos) {

            val formatSupported: Boolean = try {
                info.getCapabilitiesForType(mime).isFormatSupported(format)
            } catch (ignored: IllegalArgumentException) {
                continue
            }

            if (formatSupported) {
                val codec = try {
                    MediaCodec.createByCodecName(info.name)
                } catch (e: IOException) {
                    continue
                }

                try {
                    codec.configure(format, outputSurface, null, 0)
                } catch (ignored: IllegalArgumentException) {
                    codec.release()
                    continue
                } catch (ignored: IllegalStateException) {
                    codec.release()
                    continue
                }

                codec.start()
                return codec
            }
        }
        return null
    }

    fun getInputFormat(isAudio: Boolean) : MediaFormat? =
        getTrackIndex(isAudio).takeUnless { it == NO_TRACK }?.let { trackIndex ->
            mediaExtractor.getTrackFormat(trackIndex)
        }

    private fun getTrackIndex(isAudio: Boolean) : Int {
        for (index in 0..(mediaExtractor.trackCount - 1)) {
            val trackFormat = mediaExtractor.getTrackFormat(index)
            if (!isAudio && trackFormat.isVideoFormat() ||
                isAudio && mediaExtractor.getTrackFormat(index).isAudioFormat()) {
                return index
            }
        }
        return NO_TRACK
    }

    private fun MediaFormat.isVideoFormat() : Boolean =
        getString(MediaFormat.KEY_MIME).startsWith("video/")

    private fun MediaFormat.isAudioFormat() : Boolean =
        getString(MediaFormat.KEY_MIME).startsWith("audio/")

    private fun handleError(e: MediaCodec.CodecException) = when{
        e.isRecoverable -> {
            decoder?.stop()
            decoder?.configure()
            decoder?.start()
        }
        !e.isTransient -> error(e)
    }

    companion object {
        private const val NO_TRACK = -5
    }
}