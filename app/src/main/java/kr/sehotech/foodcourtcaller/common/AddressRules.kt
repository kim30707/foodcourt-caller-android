package kr.sehotech.foodcourtcaller.common

/** 방송 장치 주소 입력 정리(순수 로직) */
object AddressRules {
    private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?::(\d{1,5}))?""")

    /**
     * 사람이 넣은 주소를 "IP:포트"로 정리한다.
     * - "http://…/" 같은 앞뒤 문자 제거, 포트가 없으면 기본 포트
     * - 키패드 숫자가 섞여 들어간 "366192.168.0.44:8080" 같은 값에서 올바른 IP만 골라낸다
     * - IP 모양이 아니면(이름 주소 등) 앞뒤만 정리해 그대로 둔다
     */
    fun normalize(raw: String, defaultPort: Int = Protocol.DEFAULT_PORT): String {
        var s = raw.trim().replace(" ", "")
            .removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (s.isEmpty()) return ""
        // 앞쪽부터 올바른 IPv4(각 자리 0~255)를 찾는다. 앞에 붙은 잡숫자는 건너뛴다.
        var start = 0
        while (start < s.length) {
            val m = IPV4.find(s, start) ?: break
            val octets = (1..4).map { m.groupValues[it].toInt() }
            if (octets.all { it in 0..255 }) {
                val port = m.groupValues[5].toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
                return "${octets.joinToString(".")}:$port"
            }
            start = m.range.first + 1
        }
        val hasPort = if (s.startsWith("[")) s.contains("]:") else s.count { it == ':' } == 1
        if (!hasPort) s = "$s:$defaultPort"
        return s
    }
}
