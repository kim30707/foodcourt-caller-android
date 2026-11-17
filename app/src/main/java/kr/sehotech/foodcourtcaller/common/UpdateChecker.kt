package kr.sehotech.foodcourtcaller.common

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kr.sehotech.foodcourtcaller.BuildConfig
import kr.sehotech.foodcourtcaller.MainActivity
import kr.sehotech.foodcourtcaller.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * GitHub Releases에서 새 버전을 확인하고, 받아서 설치 화면을 띄운다.
 * 안드로이드 규칙상 설치는 기기에서 [설치]를 한 번 눌러야 끝난다(몰래 설치 불가).
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val REPO = "kim30707/foodcourt-caller-android"
    private const val CHANNEL_ID = "update"
    private const val NOTIF_ID = 7
    const val ACTION_UPDATE = "kr.sehotech.foodcourtcaller.UPDATE"
    private const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000

    /** 이 앱 종류에 맞는 설치 파일 이름 */
    val assetName: String get() = if (BuildConfig.SPEAKER_ONLY) "foodcourt-caller-speaker.apk" else "foodcourt-caller.apk"

    data class Latest(val version: String, val apkUrl: String)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "updater") }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var statusText: String = ""
        private set

    @Volatile
    var latestKnown: String? = null
        private set

    /** 최신 릴리스 정보. 실패하면 null. 메인 스레드에서 부르지 않는다. */
    fun fetchLatest(): Latest? = try {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "foodcourt-caller-app")
        }
        try {
            if (conn.responseCode != 200) null else {
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = o.getString("tag_name")
                val assets = o.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == assetName) url = a.optString("browser_download_url")
                }
                url?.let { Latest(tag.removePrefix("v"), it) }
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "확인 실패: ${e.message}")
        null
    }

    /** 확인만. done(새 버전 또는 null, 문장) */
    fun check(done: (Latest?, String) -> Unit) {
        statusText = "확인 중…"
        executor.execute {
            val l = fetchLatest()
            val text = when {
                l == null -> "확인 실패(인터넷 연결 확인)"
                Versions.isNewer(l.version, BuildConfig.VERSION_NAME) -> "새 버전 ${l.version} 있음 (지금 ${BuildConfig.VERSION_NAME})"
                else -> "최신 버전입니다 (${BuildConfig.VERSION_NAME})"
            }
            if (l != null) latestKnown = l.version
            statusText = text
            main.post { done(l?.takeIf { Versions.isNewer(it.version, BuildConfig.VERSION_NAME) }, text) }
        }
    }

    /** 받아서 설치 화면 띄우기. 처음 한 번은 '이 출처 허용' 화면이 먼저 뜬다. */
    fun downloadAndInstall(activity: Activity, latest: Latest, progress: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            progress("먼저 '이 출처 허용'을 켜고 돌아와 다시 누르세요")
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return
        }
        progress("내려받는 중…")
        executor.execute {
            val file = File(activity.cacheDir, "updates/update.apk")
            val ok = try {
                file.parentFile?.mkdirs()
                val conn = (URL(latest.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "foodcourt-caller-app")
                }
                try {
                    conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                } finally {
                    conn.disconnect()
                }
                file.length() > 100_000
            } catch (e: Exception) {
                Log.w(TAG, "내려받기 실패", e)
                false
            }
            main.post {
                if (!ok) {
                    progress("내려받기 실패(인터넷 연결 확인)")
                    return@post
                }
                progress("설치 화면에서 [업데이트]를 누르세요")
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** 서비스에서 6시간마다 부른다: 새 버전이 있으면 알림을 띄운다 */
    fun periodicCheck(context: Context) {
        val settings = AppSettings(context)
        val now = System.currentTimeMillis()
        if (now - settings.lastUpdateCheck < CHECK_EVERY_MS) return
        settings.lastUpdateCheck = now
        check { l, _ -> if (l != null) notifyUpdate(context.applicationContext, l.version) }
    }

    fun notifyUpdate(context: Context, version: String) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "앱 업데이트", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(
                context, 7,
                Intent(context, MainActivity::class.java).setAction(ACTION_UPDATE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("호출 방송 새 버전 $version")
                .setContentText("눌러서 업데이트하세요")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "알림 실패", e)
        }
    }
}
