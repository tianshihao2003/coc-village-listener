package cn.tsh520.cocjson.logic

import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest

data class VillageSnapshot(val tag: String, val buildingsCount: Int, val raw: String)

object JsonMatcher {
    // tag 不要求是第一个键，只要求出现在开头附近（兼容键序变化/BOM/前导空白）
    private val TAG_HINT = Regex("""\{\s*"tag"\s*:\s*"#[0-9A-Z]+"""")

    fun match(raw: String?): VillageSnapshot? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        if (s.startsWith('\uFEFF')) s = s.substring(1).trim()
        if (!TAG_HINT.containsMatchIn(s.take(256))) return null
        val root = runCatching { JSONTokener(s).nextValue() as? JSONObject }.getOrNull() ?: return null
        val tag = root.optString("tag", "")
        if (!tag.startsWith("#")) return null
        val buildings = root.optJSONArray("buildings") ?: return null
        return VillageSnapshot(tag = tag, buildingsCount = buildings.length(), raw = s)
    }

    fun fingerprint(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
}
