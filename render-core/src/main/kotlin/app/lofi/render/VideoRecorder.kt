package app.lofi.render

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * H.264 video (fed through [inputSurface] by the GL pipeline) plus optional AAC audio from the microphone, muxed
 * into an MP4 on [fd]. Audio is filtered by [AudioChain] before encoding, so the "worn" sound is part of the file.
 *
 * Threads: [drainVideo] is called from the GL thread after each frame; the audio runs on its own thread; [finish]
 * and [abort] must NOT be called from the GL thread (finish blocks while the encoders flush).
 */
class VideoRecorder(
    private val fd: FileDescriptor,
    val width: Int,
    val height: Int,
    private val bitrate: Int,
    private val fps: Int,
    /** null = record video only. */
    private val audioProfile: AudioProfile?,
) {
    private lateinit var videoCodec: MediaCodec
    private lateinit var muxer: MediaMuxer
    lateinit var inputSurface: Surface
        private set

    private val lock = Any()
    private var videoTrack = -1
    private var audioTrack = -1
    private var tracksExpected = 1
    private var tracksAdded = 0
    private var muxerStarted = false
    private var muxerFailed = false

    private class Pending(val track: Int, val data: ByteArray, val info: MediaCodec.BufferInfo)
    private val pending = ArrayList<Pending>()
    private var pendingBytes = 0

    private val videoInfo = MediaCodec.BufferInfo()
    private var audioThread: AudioThread? = null
    @Volatile private var stopAudio = false

    /** System.nanoTime() when [start] was called; the GL side uses it to align the first video timestamp. */
    @Volatile var startNano: Long = 0L
        private set

    /** True once the microphone is actually recording. */
    @Volatile var hasAudio: Boolean = false
        private set

    /** Creates the codec, its input surface and the muxer. Throws if the device cannot do this size. */
    fun prepare() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoCodec.createInputSurface()
            muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (audioProfile != null) tracksExpected = 2
        } catch (t: Throwable) {
            release()
            throw t
        }
    }

    fun start() {
        startNano = System.nanoTime()
        videoCodec.start()
        if (audioProfile != null) {
            audioThread = AudioThread(audioProfile).also { it.start() }
        }
    }

    /** GL thread, after each frame. Never blocks. */
    fun drainVideo() = drain(videoCodec, videoInfo, endOfStream = false, isVideo = true)

    /** Flushes the encoders and finalises the file. Returns true if a playable file was written. */
    fun finish(): Boolean {
        var ok = false
        try {
            runCatching { videoCodec.signalEndOfInputStream() }
            drain(videoCodec, videoInfo, endOfStream = true, isVideo = true)
            stopAudio = true
            audioThread?.join(3000)
            synchronized(lock) {
                if (muxerStarted && !muxerFailed) {
                    muxer.stop()
                    ok = true
                }
            }
        } catch (t: Throwable) {
            LofiLog.e("record", "finish failed", t)
        } finally {
            release()
        }
        return ok
    }

    /** Throws everything away (error paths). */
    fun abort() {
        stopAudio = true
        runCatching { audioThread?.join(2000) }
        release()
    }

    private fun release() {
        if (::videoCodec.isInitialized) {
            runCatching { videoCodec.stop() }
            runCatching { videoCodec.release() }
        }
        if (::inputSurface.isInitialized) runCatching { inputSurface.release() }
        if (::muxer.isInitialized) runCatching { muxer.release() }
    }

    // ---------------------------------------------------------------- muxing

    private fun onTrackFormat(isVideo: Boolean, format: MediaFormat) {
        synchronized(lock) {
            if (muxerFailed) return
            try {
                val t = muxer.addTrack(format)
                if (isVideo) videoTrack = t else audioTrack = t
                tracksAdded++
                maybeStartMuxer()
            } catch (e: Exception) {
                LofiLog.e("record", "addTrack failed", e)
                muxerFailed = true
            }
        }
    }

    private fun audioUnavailable() {
        synchronized(lock) {
            if (tracksExpected == 2 && audioTrack < 0) {
                tracksExpected = 1
                maybeStartMuxer()
            }
        }
    }

    private fun maybeStartMuxer() {
        if (muxerStarted || tracksAdded < tracksExpected) return
        muxer.start()
        muxerStarted = true
        for (p in pending) muxer.writeSampleData(p.track, ByteBuffer.wrap(p.data), p.info)
        pending.clear()
        pendingBytes = 0
    }

    private fun writeSample(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (muxerFailed || track < 0) return
            try {
                if (muxerStarted) {
                    muxer.writeSampleData(track, buf, info)
                } else {
                    if (pendingBytes + info.size > MAX_PENDING_BYTES) return   // waiting too long for the other track
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    val copy = MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) }
                    pending.add(Pending(track, bytes, copy))
                    pendingBytes += info.size
                }
            } catch (e: Exception) {
                LofiLog.e("record", "writeSampleData failed", e)
                muxerFailed = true
            }
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, endOfStream: Boolean, isVideo: Boolean) {
        val timeoutUs = if (endOfStream) 10_000L else 0L
        var idle = 0
        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(info, timeoutUs)
            } catch (e: IllegalStateException) {
                return
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idle > 300) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onTrackFormat(isVideo, codec.outputFormat)
                index >= 0 -> {
                    val data = codec.getOutputBuffer(index)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (data != null && info.size > 0) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        writeSample(if (isVideo) videoTrack else audioTrack, data, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    // ---------------------------------------------------------------- audio

    private inner class AudioThread(private val profile: AudioProfile) : Thread("lofi-audio") {
        override fun run() {
            var record: AudioRecord? = null
            var codec: MediaCodec? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBuf > 0) { "AudioRecord.getMinBufferSize returned $minBuf" }
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, 16384),
                )
                record = rec
                check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialised (microphone busy or no permission)" }
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec = enc
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
                rec.startRecording()
                hasAudio = true

                val offsetUs = (System.nanoTime() - startNano) / 1000
                val chain = AudioChain(RATE, profile)
                val pcm = ShortArray(2048)
                val info = MediaCodec.BufferInfo()
                var samples = 0L
                while (!stopAudio) {
                    val n = rec.read(pcm, 0, pcm.size)
                    if (n < 0) break
                    if (n == 0) continue
                    chain.process(pcm, n)
                    feed(enc, pcm, n, offsetUs + samples * 1_000_000L / RATE, false)
                    samples += n
                    drain(enc, info, endOfStream = false, isVideo = false)
                }
                var queued = false
                for (attempt in 0 until 20) {
                    if (feed(enc, pcm, 0, offsetUs + samples * 1_000_000L / RATE, true)) { queued = true; break }
                }
                if (queued) drain(enc, info, endOfStream = true, isVideo = false)
            } catch (t: Throwable) {
                LofiLog.e("record", "audio failed", t)
                hasAudio = false
                audioUnavailable()
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
            }
        }

        /** Returns false if no input buffer was free (the chunk is dropped). */
        private fun feed(codec: MediaCodec, pcm: ShortArray, n: Int, ptsUs: Long, eos: Boolean): Boolean {
            val index = codec.dequeueInputBuffer(20_000)
            if (index < 0) return false
            val buf = codec.getInputBuffer(index) ?: return false
            buf.clear()
            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, n)
            codec.queueInputBuffer(index, 0, n * 2, ptsUs, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            return true
        }
    }

    private companion object {
        const val RATE = 44100
        const val MAX_PENDING_BYTES = 8 * 1024 * 1024
    }
}
