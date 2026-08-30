package cn.tsh520.cocjson.logic

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ArchiveWriter {
    fun save(context: Context, snap: VillageSnapshot, seq: Int = 0): Uri? {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.CHINA).format(Date())
        val suffix = if (seq > 0) "_$seq" else ""
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${snap.tag}_${stamp}$suffix.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/村庄JSON")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(snap.raw.toByteArray(Charsets.UTF_8)) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        }.getOrNull()
    }
}
