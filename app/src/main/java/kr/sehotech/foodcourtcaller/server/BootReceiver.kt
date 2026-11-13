package kr.sehotech.foodcourtcaller.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kr.sehotech.foodcourtcaller.common.AppSettings

/** 부팅 완료·앱 업데이트 뒤 방송 서비스를 자동으로 켠다(방송용 기기만) */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (AppSettings(context).role != AppSettings.ROLE_SERVER) return
        Log.i("BootReceiver", "자동 시작: $action")
        BroadcastService.start(context)
    }
}
