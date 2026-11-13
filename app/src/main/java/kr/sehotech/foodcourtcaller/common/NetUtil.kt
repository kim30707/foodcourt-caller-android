package kr.sehotech.foodcourtcaller.common

import java.net.Inet4Address
import java.net.InetAddress
import java.net.Inet6Address
import java.net.NetworkInterface

/** 네트워크 도우미 */
object NetUtil {
    /** 이 기기의 IPv4 주소(와이파이 우선). 없으면 null */
    fun localIpv4(): String? = try {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
        val wifi = candidates.firstOrNull { it.first.startsWith("wlan") }
        (wifi ?: candidates.firstOrNull())?.second?.hostAddress
    } catch (e: Exception) {
        null
    }

    /** 주소와 포트를 "IP:포트" 문자열로 */
    fun format(host: InetAddress, port: Int): String =
        if (host is Inet6Address) "[${host.hostAddress?.substringBefore('%')}]:$port"
        else "${host.hostAddress}:$port"
}
