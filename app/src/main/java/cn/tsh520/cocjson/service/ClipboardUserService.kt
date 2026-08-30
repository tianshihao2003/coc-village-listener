package cn.tsh520.cocjson.service

import android.content.ClipData
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 运行在 shell 身份的 Shizuku UserService 进程。
 * 主通道：裸 binder transact 调系统 IClipboard.getPrimaryClip（transaction code 2，
 * descriptor "android.content.IClipboard"），参数签名跨版本漂移用"候选列表探测"消化。
 * 备用通道：shell 身份执行 `service call clipboard` 并从 Parcel dump 还原文本。
 * shell 身份不受 Android 10+ 应用剪贴板限制。
 */
class ClipboardUserService : IClipboardUserService.Stub() {

    private val clipboardBinder: IBinder? by lazy {
        runCatching {
            HiddenApiBypass.setHiddenApiExemptions("L")
            val sm = Class.forName("android.os.ServiceManager")
            sm.getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder?
        }.getOrNull()
    }

    @Volatile private var lockedMode: Int = -1
    @Volatile private var lastError: String = "尚未执行过读取"

    override fun modeInfo(): String =
        "lockedMode=$lockedMode sdk=${Build.VERSION.SDK_INT} binder=${clipboardBinder != null} lastError=$lastError"

    override fun readClipboard(): String? {
        val binder = clipboardBinder
        if (binder != null) {
            // 先试已锁定的模式，再按序探测其余候选
            val modes = if (lockedMode >= 0) listOf(lockedMode) + (candidates().keys - lockedMode)
                        else candidates().keys.sorted()
            for (mode in modes) {
                val clip = transactGetPrimaryClip(binder, mode)
                val text = clip?.let { clipToText(it) }
                if (!text.isNullOrEmpty()) {
                    if (lockedMode != mode) lockedMode = mode
                    lastError = "无"
                    return text
                }
                if (clip != null) {
                    // transact 成功只是剪贴板无文本：这不算探测失败，锁定该模式
                    if (lockedMode != mode) lockedMode = mode
                    lastError = "剪贴板无文本"
                    return null
                }
            }
            lastError = "全部直调模式失败"
        } else {
            lastError = "clipboard binder 不存在"
        }
        // 备用通道
        return serviceCallRead()?.also { lastError = "无（service call 通道）" }
    }

    override fun diagnose(): String {
        val sb = StringBuilder()
        val binder = clipboardBinder
        if (binder == null) {
            sb.append("clipboard binder 不存在（ServiceManager 反射失败）\n")
        } else {
            for ((mode, writer) in candidates()) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    writer.invoke(data)
                    binder.transact(TRANSACTION_GET_PRIMARY_CLIP, data, reply, 0)
                    reply.readException()
                    val clip = if (Build.VERSION.SDK_INT >= 30) reply.readTypedObject(ClipData.CREATOR)
                               else if (reply.readInt() != 0) ClipData.CREATOR.createFromParcel(reply) else null
                    val text = clip?.let { clipToText(it) }
                    sb.append("直调mode=$mode: 成功 textLen=${text?.length ?: -1}\n")
                } catch (t: Throwable) {
                    sb.append("直调mode=$mode: ${t.javaClass.simpleName}: ${t.message?.take(150)}\n")
                } finally { data.recycle(); reply.recycle() }
            }
        }
        try {
            val raw = serviceCallRaw()
            sb.append("serviceCall: ").append(raw?.take(400) ?: "(无输出)")
        } catch (t: Throwable) {
            sb.append("serviceCall: ${t.javaClass.simpleName}: ${t.message?.take(150)}")
        }
        return sb.toString()
    }

    override fun destroy() { /* Shizuku UserService 规范要求的空实现 */ }

    /**
     * 候选参数组合：key 为模式编号，value 为参数写入器。
     * 4参 (pkg, attribution, userId, deviceId) — Android 15+（两枚 int 均传 0，
     *     因此 deviceId/userId 顺序互换时 payload 相同，无需重复候选）
     * 3参 (pkg, attribution, userId)          — Android 12~14
     * 2参 (pkg, userId)                       — Android 10~11
     */
    private fun candidates(): Map<Int, (Parcel) -> Unit> = sortedMapOf(
        4 to { p -> p.writeString(PKG); p.writeString(PKG); p.writeInt(0); p.writeInt(0) },
        3 to { p -> p.writeString(PKG); p.writeString(PKG); p.writeInt(0) },
        1 to { p -> p.writeString(PKG); p.writeInt(0) },
    )

    private fun transactGetPrimaryClip(binder: IBinder, mode: Int): ClipData? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            candidates().getValue(mode).invoke(data)
            binder.transact(TRANSACTION_GET_PRIMARY_CLIP, data, reply, 0)
            reply.readException()
            if (Build.VERSION.SDK_INT >= 30) reply.readTypedObject(ClipData.CREATOR)
            else if (reply.readInt() != 0) ClipData.CREATOR.createFromParcel(reply) else null
        } catch (t: Throwable) {
            lastError = "mode=$mode ${t.javaClass.simpleName}: ${t.message?.take(100)}"
            null
        } finally { data.recycle(); reply.recycle() }
    }

    private fun clipToText(clip: ClipData): String? = try {
        val item = clip.getItemAt(0) ?: return null
        item.getText()?.toString() ?: item.coerceToText(null)?.toString()
    } catch (t: Throwable) { null }

    // ---------- 备用通道：service call ----------

    /** 执行 service call 并直接还原出 JSON 文本（成功）；失败返回 null */
    private fun serviceCallRead(): String? = serviceCallRaw()?.let { extractJsonFromParcelDump(it) }

    private fun serviceCallRaw(): String? = runCatching {
        val pb = ProcessBuilder(
            "service", "call", "clipboard", "2",
            "s16", PKG, "s16", PKG, "i32", "0", "i32", "0",
        )
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out.takeIf { it.contains("Parcel(") }
    }.getOrNull()

    /**
     * `service call` 输出形如：
     * Result: Parcel(
     *   0x00000000: 00000000 00000001 '........  ....'
     * 从 hex 列重组 parcel 字节流（每个 word 为 32 位小端），整体按 UTF-16LE 解码，
     * 再从中截取平衡的 {"tag":...} JSON 对象。
     */
    private fun extractJsonFromParcelDump(dump: String): String? {
        val bytes = ArrayList<Byte>(2048)
        for (line in dump.lineSequence()) {
            val trimmed = line.trimStart()
            val colon = line.indexOf(':')
            if (colon < 0 || !trimmed.startsWith("0x")) continue
            for (w in WORD_RE.findAll(line.substring(colon + 1))) {
                var v = w.value.toLong(16)
                repeat(4) { bytes.add((v and 0xFF).toByte()); v = v ushr 8 }
            }
        }
        if (bytes.isEmpty()) return null
        val arr = ByteArray(bytes.size)
        bytes.forEachIndexed { i, b -> arr[i] = b }
        val decoded = runCatching { String(arr, Charsets.UTF_16LE) }.getOrNull() ?: return null
        val start = decoded.indexOf(JSON_START)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until decoded.length) {
            val c = decoded[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else when {
                c == '"' -> inStr = true
                c == '{' -> depth++
                c == '}' -> { depth--; if (depth == 0) return decoded.substring(start, i + 1) }
            }
        }
        return null
    }

    private companion object {
        const val DESCRIPTOR = "android.content.IClipboard"
        const val TRANSACTION_GET_PRIMARY_CLIP = 2 // FIRST_CALL_TRANSACTION + 1
        const val PKG = "com.android.shell" // shell uid 下被系统放行的调用方标识
        const val JSON_START = "{\"tag\""
        val WORD_RE = Regex("""[0-9a-fA-F]{8}""")
    }
}
