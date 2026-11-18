package kr.sehotech.foodcourtcaller.common

import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * 앱이 스스로 새 버전을 설치한다(PackageInstaller 세션).
 * - 전용 기기 모드(기기 소유자)면 언제나 조용히 설치된다.
 * - 안드로이드 12 이상에서 이 앱이 '자기 자신을 설치한 앱'이면 [설치]를 누르지 않아도 된다.
 *   그래서 처음 한 번 [자동 업데이트 켜기]로 자기 자신을 다시 설치해 둔다.
 * - 조용한 설치가 안 되면 알림을 띄워 사람이 누르게 한다.
 */
object UpdateInstaller {
    private const val TAG = "UpdateInstaller"
    const val ACTION_RESULT = "kr.sehotech.foodcourtcaller.INSTALL_RESULT"
    const val EXTRA_INTERACTIVE = "interactive"
    const val EXTRA_VERSION = "version"

    @Volatile
    var lastStatusText: String = ""

    fun adminComponent(context: Context) = ComponentName(context, AdminReceiver::class.java)

    /** 전용 기기 모드(기기 소유자)인지 */
    fun isDeviceOwner(context: Context): Boolean = try {
        context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)
    } catch (e: Exception) {
        false
    }

    /** 이 앱을 설치한 앱이 이 앱 자신인지(안드로이드 11 이상에서만 알 수 있다) */
    fun installedBySelf(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return try {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName == context.packageName
        } catch (e: Exception) {
            false
        }
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    enum class Mode(val label: String, val code: String) {
        DEVICE_OWNER("전용 기기 모드 — 항상 자동 설치", "device_owner"),
        SILENT("자동 업데이트 준비됨", "ready"),
        NEEDS_SETUP("[자동 업데이트 켜기]를 한 번 눌러 주세요", "setup"),
        NOTIFY_ONLY("이 안드로이드 버전은 알림만 (설치는 직접 누름)", "notify"),
    }

    fun mode(context: Context): Mode = when {
        isDeviceOwner(context) -> Mode.DEVICE_OWNER
        Build.VERSION.SDK_INT < 31 -> Mode.NOTIFY_ONLY
        installedBySelf(context) && canInstall(context) -> Mode.SILENT
        else -> Mode.NEEDS_SETUP
    }

    fun silentReady(context: Context): Boolean = mode(context).let { it == Mode.DEVICE_OWNER || it == Mode.SILENT }

    /**
     * [자동 업데이트 켜기]: 지금 설치된 이 앱을 그대로 다시 설치한다(설치 화면에서 [업데이트] 한 번).
     * 그 뒤부터 이 앱이 '설치한 앱'이 되어 조용히 업데이트할 수 있다.
     */
    fun setupSelf(activity: Activity, progress: (String) -> Unit) {
        if (!canInstall(activity)) {
            progress("먼저 '이 출처 허용'을 켜고 돌아와 다시 누르세요")
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        progress("설치 화면이 뜨면 [업데이트](또는 [설치])를 누르세요")
        val apk = File(activity.applicationInfo.sourceDir)
        Thread({
            install(activity.applicationContext, apk, interactive = true, version = "setup")
        }, "self-setup").start()
    }

    /**
     * 설치 파일을 넘긴다. 결과는 [InstallResultReceiver]로 온다.
     * interactive=false 이면 확인 화면이 필요할 때 화면 대신 알림을 띄운다.
     * 메인 스레드에서 부르지 않는다.
     */
    fun install(context: Context, apk: File, interactive: Boolean, version: String): Boolean {
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= 31) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                if (Build.VERSION.SDK_INT >= 26) setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
            }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setAction(ACTION_RESULT)
                    .putExtra(EXTRA_INTERACTIVE, interactive)
                    .putExtra(EXTRA_VERSION, version)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pi.intentSender)
            }
            lastStatusText = "설치 중…"
            true
        } catch (e: Exception) {
            Log.w(TAG, "설치 넘기기 실패", e)
            if (sessionId >= 0) try { installer.abandonSession(sessionId) } catch (_: Exception) {}
            lastStatusText = "설치 실패: ${e.message}"
            false
        }
    }
}

/** 설치 결과를 받는다. 확인이 필요하면 화면(직접 누른 경우) 또는 알림(자동)을 띄운다 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val interactive = intent.getBooleanExtra(UpdateInstaller.EXTRA_INTERACTIVE, false)
        val version = intent.getStringExtra(UpdateInstaller.EXTRA_VERSION).orEmpty()
        val settings = AppSettings(context)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (interactive && confirm != null) {
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        Log.w("InstallResult", "확인 화면 실패", e)
                    }
                } else {
                    // 조용한 설치가 안 됨 → 사람이 누르도록 알림
                    UpdateInstaller.lastStatusText = "자동 설치가 안 되어 알림을 띄웠습니다"
                    settings.autoResultText = stamp() + " 자동 설치 안 됨 → 알림으로 안내"
                    if (version.isNotEmpty() && version != "setup") UpdateChecker.notifyUpdate(context, version)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateInstaller.lastStatusText = "설치 완료"
                settings.autoResultText = stamp() + if (version == "setup") " 자동 업데이트 준비 완료" else " $version 자동 설치 완료"
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Log.w("InstallResult", "설치 실패 $status $msg")
                UpdateInstaller.lastStatusText = "설치 실패($status) $msg"
                settings.autoResultText = stamp() + " 설치 실패($status)"
                if (!interactive && version.isNotEmpty() && version != "setup") UpdateChecker.notifyUpdate(context, version)
            }
        }
    }

    private fun stamp(): String = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.KOREA).format(java.util.Date())
}

/** 전용 기기 모드(기기 소유자)용. adb로 한 번 지정한다(README 참고) */
class AdminReceiver : android.app.admin.DeviceAdminReceiver()
