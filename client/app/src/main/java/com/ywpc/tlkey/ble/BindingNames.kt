package com.ywpc.tlkey.ble

/**
 * 绑定模式下车辆广播名前缀 → modelType。
 * 来源：BindCarBleSearchFragment.AnonymousClass9 与 TLinkBleManager.connectDevice。
 * 注意：顺序即匹配优先级，长前缀在前。
 */
object BindingNames {

    data class Match(val prefix: String, val modelType: Int)

    val matches: List<Match> = listOf(
        Match("TAILG_BGPSBEOZB", 401),
        Match("TAILG_BGPSJLBXW", 928),
        Match("TAILG_BGPSYSACY", 2201),
        Match("TAILG_BGPSXIAIL", 2103),
        Match("TAILG_BEUOZB", 3),
        Match("TAILG_AAIFDX", 1601),
        Match("TAILG_JIFSAD", 1501),
        Match("TAILG_HGSAIJ", 1701),
        Match("TAILG_JLFBXW", 10),
        Match("TAILG_JW", 10),
        Match("Hi-TAILING", 8),
        Match("Q_BASH", 8),
        Match("QBIKE_2002", 8),
        Match("QDemo_2002", 8),
        Match("TL_2002", 8),
        Match("QDemo_2012", 283),
        Match("TL_2012", 283),
        Match("TL_2022", 283),
    )

    val prefixes: List<String> = matches.map { it.prefix }

    fun modelTypeFor(name: String?): Int? {
        if (name.isNullOrBlank()) return null
        return matches.firstOrNull { name.startsWith(it.prefix) }?.modelType
    }

    fun isBindingDevice(name: String?): Boolean = modelTypeFor(name) != null
}
