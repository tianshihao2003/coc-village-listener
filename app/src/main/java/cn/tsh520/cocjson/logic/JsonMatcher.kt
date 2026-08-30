package cn.tsh520.cocjson.logic

import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest

data class VillageSnapshot(val tag: String, val buildingsCount: Int, val raw: String)

object JsonMatcher {
    private val TAG_PREFIX = Regex("""^\{\s*"tag"\s*:\s*"#[0-9A-Z]+"""")

    fun match(raw: String?): VillageSnapshot? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (!TAG_PREFIX.containsMatchIn(s)) return null
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
