package kr.sehotech.foodcourtcaller.server

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * 다른 음악 앱(유튜브 뮤직 등)을 재생·일시정지한다.
 * 블루투스 리모컨의 재생/정지 버튼과 같은 신호(미디어 키)를 보낸다.
 * 끄기는 미디어 키가 안 먹으면 오디오 포커스를 잠깐 가져와 확실히 멈춘다.
 */
class MusicControl(context: Context) {
    companion object {
        private const val TAG = "MusicControl"
        private const val CHECK_MS = 3_000L
    }

    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private fun sendKey(code: Int) {
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    /** 음악 켜기. 3초 뒤에도 소리가 없으면 재생/정지 토글 키를 한 번 더 보낸다. done(켜졌는지) */
    fun play(done: (Boolean) -> Unit = {}) {
        try {
            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        } catch (e: Exception) {
            Log.w(TAG, "재생 키 실패", e)
        }
        main.postDelayed({
            if (!am.isMusicActive) {
                try {
                    sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                } catch (_: Exception) {
                }
                main.postDelayed({ done(am.isMusicActive) }, CHECK_MS)
            } else {
                done(true)
            }
        }, CHECK_MS)
    }

    /** 음악 끄기. 3초 뒤에도 소리가 나면 오디오 포커스를 가져와 멈춘다. done(꺼졌는지) */
    fun pause(done: (Boolean) -> Unit = {}) {
        try {
            sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        } catch (e: Exception) {
            Log.w(TAG, "정지 키 실패", e)
        }
        main.postDelayed({
            if (am.isMusicActive) forcePauseByFocus()
            main.postDelayed({ done(!am.isMusicActive) }, CHECK_MS)
        }, CHECK_MS)
    }

    /** 오디오 포커스를 길게 가져오면 대부분의 음악 앱이 스스로 멈춘다. 바로 돌려준다. */
    private fun forcePauseByFocus() {
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener { }
                .build()
            am.requestAudioFocus(req)
            main.postDelayed({
                try {
                    am.abandonAudioFocusRequest(req)
                } catch (_: Exception) {
                }
            }, 1_000)
        } catch (e: Exception) {
            Log.w(TAG, "포커스로 멈추기 실패", e)
        }
    }
}
