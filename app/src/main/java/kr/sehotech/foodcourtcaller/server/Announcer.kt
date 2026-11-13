package kr.sehotech.foodcourtcaller.server

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kr.sehotech.foodcourtcaller.common.AppSettings
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 음성 방송. 한국어 TTS로 문장을 WAV로 만들어 캐시하고,
 * 재생기 하나로 [앞 무음] → [차임] → [문장] × 반복 순서로 재생한다.
 * 재생 동안 오디오 포커스(MAY_DUCK)를 잡아 같은 폰의 음악 소리를 줄인다.
 * 시스템 음량은 바꾸지 않고 재생기 음량만 앱 설정값으로 맞춘다.
 */
class Announcer(private val context: Context, private val settings: AppSettings) {

    enum class TtsState(val code: String, val label: String) {
        INIT("init", "준비 중"),
        READY("ready", "준비됨"),
        NO_KOREAN("no_korean", "한국어 음성 없음"),
        ERROR("error", "오류"),
    }

    companion object {
        private const val TAG = "Announcer"
        private const val REPEAT_GAP_MS = 800
        private const val TAIL_MS = 250
    }

    @Volatile
    var state: TtsState = TtsState.INIT
        private set

    @Volatile
    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, CountDownLatch>()
    private val results = ConcurrentHashMap<String, Boolean>()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val cacheDir = File(context.cacheDir, "tts").apply { mkdirs() }

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = finish(utteranceId, true)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finish(utteranceId, false)
        override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)

        private fun finish(id: String?, ok: Boolean) {
            if (id == null) return
            results[id] = ok
            pending[id]?.countDown()
        }
    }

    /** TTS 시작(또는 다시 시작). 메인 스레드에서 만든다. */
    fun init() {
        main.post {
            state = TtsState.INIT
            try {
                tts?.shutdown()
            } catch (_: Exception) {
            }
            tts = TextToSpeech(context.applicationContext) { status -> onTtsInit(status) }
        }
    }

    private fun onTtsInit(status: Int) {
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) {
            state = TtsState.ERROR
            Log.e(TAG, "TTS 시작 실패 status=$status")
            return
        }
        val r = t.setLanguage(Locale.KOREAN)
        t.setOnUtteranceProgressListener(listener)
        t.setAudioAttributes(attrs)
        state = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            TtsState.NO_KOREAN
        } else {
            TtsState.READY
        }
        Log.i(TAG, "TTS 상태: $state (엔진 ${t.defaultEngine})")
    }

    fun shutdown() {
        main.post {
            try {
                tts?.shutdown()
            } catch (_: Exception) {
            }
            tts = null
        }
    }

    /** 문장을 방송한다. 작업자 스레드에서 부른다. 끝날 때까지 기다린다. */
    fun announce(text: String): Boolean {
        if (state != TtsState.READY) {
            Log.w(TAG, "TTS 준비 안 됨: $state")
            return false
        }
        val rate = settings.speechRatePercent / 100f
        val wav = synthesize(text, rate)
        val pcm = wav?.let { WavPcm.read(it) }
        if (pcm == null) {
            Log.w(TAG, "WAV 만들기/읽기 실패 → TTS 직접 재생으로 대체")
            return speakFallback(text, rate)
        }
        val program = AudioProgram.build(
            speech = pcm,
            preSilenceMs = settings.preSilenceMs,
            chime = settings.chimeEnabled,
            repeat = settings.repeatCount,
            repeatGapMs = REPEAT_GAP_MS,
            tailMs = TAIL_MS,
        )
        return withFocus { play(program, pcm.sampleRate, pcm.channels) }
    }

    /** synthesizeToFile로 WAV 만들기. 같은 문장·속도는 캐시를 쓴다. */
    private fun synthesize(text: String, rate: Float): File? {
        val t = tts ?: return null
        val key = sha1("${t.defaultEngine}|$rate|$text")
        val f = File(cacheDir, "$key.wav")
        if (f.exists() && f.length() > 44) return f
        val tmp = File(cacheDir, "$key.tmp")
        val id = "syn-$key-${System.nanoTime()}"
        val latch = CountDownLatch(1)
        pending[id] = latch
        try {
            t.setSpeechRate(rate)
            val r = t.synthesizeToFile(text, Bundle(), tmp, id)
            if (r != TextToSpeech.SUCCESS) return null
            val done = latch.await(15, TimeUnit.SECONDS)
            if (!done || results.remove(id) != true || !tmp.exists() || tmp.length() <= 44) {
                tmp.delete()
                return null
            }
            if (!tmp.renameTo(f)) return null
            trimCache()
            return f
        } catch (e: Exception) {
            Log.e(TAG, "합성 오류", e)
            return null
        } finally {
            pending.remove(id)
            results.remove(id)
        }
    }

    private fun trimCache() {
        val files = cacheDir.listFiles()?.filter { it.name.endsWith(".wav") } ?: return
        if (files.size <= 300) return
        files.sortedBy { it.lastModified() }.take(files.size - 300).forEach { it.delete() }
    }

    /** WAV를 못 쓰는 엔진일 때: 차임을 따로 재생하고 TTS로 바로 읽는다 */
    private fun speakFallback(text: String, rate: Float): Boolean {
        val t = tts ?: return false
        return withFocus {
            val sr = 24_000
            val pre = AudioProgram.build(
                speech = WavPcm(sr, 1, ShortArray(0)),
                preSilenceMs = settings.preSilenceMs,
                chime = settings.chimeEnabled,
                repeat = 1, repeatGapMs = 0, tailMs = TAIL_MS,
            )
            play(pre, sr, 1)
            var ok = true
            repeat(settings.repeatCount) { i ->
                if (i > 0) Thread.sleep(REPEAT_GAP_MS.toLong())
                val id = "spk-${System.nanoTime()}"
                val latch = CountDownLatch(1)
                pending[id] = latch
                t.setSpeechRate(rate)
                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.volume / 100f)
                }
                val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
                val done = r == TextToSpeech.SUCCESS && latch.await(30, TimeUnit.SECONDS)
                ok = ok && done && results.remove(id) == true
                pending.remove(id)
            }
            ok
        }
    }

    /** 오디오 포커스를 잡고 실행한 뒤 돌려준다(음악은 작아졌다가 돌아온다) */
    private fun <T> withFocus(block: () -> T): T {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        try {
            audioManager.requestAudioFocus(req)
        } catch (e: Exception) {
            Log.w(TAG, "오디오 포커스 요청 실패", e)
        }
        try {
            return block()
        } finally {
            try {
                audioManager.abandonAudioFocusRequest(req)
            } catch (_: Exception) {
            }
        }
    }

    /** 재생기 하나로 끝까지 재생 */
    private fun play(samples: ShortArray, sampleRate: Int, channels: Int): Boolean {
        if (samples.isEmpty()) return true
        val mask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val ch = if (channels >= 2) 2 else 1
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(mask)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuf * 4, 32_768))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "재생기 만들기 실패", e)
            return false
        }
        return try {
            track.setVolume(settings.volume / 100f)
            track.play()
            var off = 0
            while (off < samples.size) {
                val n = track.write(samples, off, min(8_192, samples.size - off))
                if (n <= 0) break
                off += n
            }
            val totalFrames = samples.size / ch
            val deadline = System.currentTimeMillis() + totalFrames * 1000L / sampleRate + 3_000
            while (track.playbackHeadPosition < totalFrames && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "재생 오류", e)
            false
        } finally {
            try {
                track.stop()
            } catch (_: Exception) {
            }
            track.release()
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** 16비트 PCM 소리 데이터 */
class WavPcm(val sampleRate: Int, val channels: Int, val data: ShortArray) {
    companion object {
        /** WAV(PCM 16비트) 읽기. 다른 형식이면 null */
        fun read(file: File): WavPcm? = try {
            parse(file.readBytes())
        } catch (e: Exception) {
            null
        }

        fun parse(bytes: ByteArray): WavPcm? {
            if (bytes.size < 44) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) return null
            var pos = 12
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var format = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                var size = b.getInt(pos + 4)
                val body = pos + 8
                if (id == "fmt ") {
                    format = b.getShort(body).toInt() and 0xFFFF
                    channels = b.getShort(body + 2).toInt()
                    sampleRate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                } else if (id == "data") {
                    // 크기가 잘못 적힌 파일(스트리밍 기록)은 끝까지 읽는다.
                    if (size <= 0 || body + size > bytes.size) size = bytes.size - body
                    if (format != 1 || bits != 16 || channels !in 1..2 || sampleRate <= 0) return null
                    val count = size / 2
                    val out = ShortArray(count)
                    b.position(body)
                    b.asShortBuffer().get(out, 0, count)
                    return WavPcm(sampleRate, channels, out)
                }
                if (size < 0) return null
                pos = body + size + (size and 1)
            }
            return null
        }
    }
}

/** 재생할 소리 전체를 하나로 이어 붙인다(순수 로직) */
object AudioProgram {
    fun build(
        speech: WavPcm,
        preSilenceMs: Int,
        chime: Boolean,
        repeat: Int,
        repeatGapMs: Int,
        tailMs: Int,
    ): ShortArray {
        val sr = speech.sampleRate
        val ch = if (speech.channels >= 2) 2 else 1
        fun silence(ms: Int) = ShortArray(sr * ms / 1000 * ch)
        val parts = mutableListOf<ShortArray>()
        parts += silence(preSilenceMs)
        if (chime) parts += toChannels(chime(sr), ch)
        val times = repeat.coerceIn(1, 3)
        for (i in 0 until times) {
            if (i > 0) parts += silence(repeatGapMs)
            parts += speech.data
        }
        parts += silence(tailMs)
        val out = ShortArray(parts.sumOf { it.size })
        var off = 0
        parts.forEach { System.arraycopy(it, 0, out, off, it.size); off += it.size }
        return out
    }

    /** 코드로 만든 두 음(딩동) 사인파. 외부 음원 파일은 쓰지 않는다. */
    fun chime(sampleRate: Int): ShortArray {
        val ding = tone(sampleRate, 659.25, 0.45)  // 미(E5)
        val dong = tone(sampleRate, 523.25, 0.70)  // 도(C5)
        val gap = ShortArray(sampleRate * 40 / 1000)
        val after = ShortArray(sampleRate * 250 / 1000)
        return ding + gap + dong + after
    }

    private fun tone(sampleRate: Int, freq: Double, seconds: Double): ShortArray {
        val n = (sampleRate * seconds).toInt()
        val attack = sampleRate * 0.01
        return ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val env = min(1.0, i / attack) * exp(-3.5 * t / seconds)
            // 기본음 + 배음을 조금 섞어 종소리처럼
            val v = 0.75 * sin(2 * PI * freq * t) + 0.25 * sin(2 * PI * freq * 2 * t)
            (v * env * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun toChannels(mono: ShortArray, ch: Int): ShortArray {
        if (ch == 1) return mono
        val out = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            out[2 * i] = mono[i]; out[2 * i + 1] = mono[i]
        }
        return out
    }
}
