package kr.sehotech.foodcourtcaller.common

/**
 * 매장 한 곳의 방송 설정.
 * @param name 매장 이름(요청의 store 값과 비교)
 * @param spokenName 방송에서 읽는 이름
 * @param template 방송 문장. 비어 있으면 기본 문장을 쓴다.
 * @param enabled 방송 사용 여부
 */
data class StoreConfig(
    val name: String,
    val spokenName: String,
    val template: String,
    val enabled: Boolean,
) {
    /** 실제로 쓸 문장 틀 */
    val effectiveTemplate: String get() = template.ifBlank { DefaultStores.DEFAULT_TEMPLATE }
}

/** 매장 목록 기본값(지시서 4-3). 방송용 설정 화면에서 바꿀 수 있다. */
object DefaultStores {
    const val DEFAULT_TEMPLATE = "{매장} {번호}번 고객님, 주문하신 음식이 나왔습니다."
    const val TEST_TEMPLATE = "방송 테스트입니다. {매장}"

    val list: List<StoreConfig> = listOf(
        StoreConfig("사나이뚝배기", "사나이뚝배기", "", true),
        StoreConfig("꾸이한끼", "꾸이한끼", "", true),
        StoreConfig("경성파스타", "경성파스타", "", true),
        StoreConfig("경성미엔", "경성미엔", "", true),
        StoreConfig("하즈벤", "하즈벤", "", true),
        StoreConfig("달순", "달순", "", true),
        // 컴포즈: 방송 포함 여부 보류(끔). 음료 문장.
        StoreConfig("컴포즈", "컴포즈", "{매장} {번호}번 고객님, 주문하신 음료가 나왔습니다.", false),
    )

    /** 방송 문장 만들기. number는 앞의 0을 떼고 읽는다. */
    fun sentence(store: StoreConfig, number: String): String =
        store.effectiveTemplate
            .replace("{매장}", store.spokenName.ifBlank { store.name })
            .replace("{번호}", NumberRules.spoken(number))

    fun testSentence(store: StoreConfig): String =
        TEST_TEMPLATE.replace("{매장}", store.spokenName.ifBlank { store.name })
}
