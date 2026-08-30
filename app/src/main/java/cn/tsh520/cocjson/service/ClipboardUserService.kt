package cn.tsh520.cocjson.service

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.security.MessageDigest

/**
 * 运行在 shell 身份的 Shizuku UserService 进程。
 *
 * 背景：游戏"数据导出"的村庄 JSON 可达数 MB，而安卓 binder 单次回传有 1MB 上限，
 * 全文读取（getPrimaryClip，code 2）会报 BadParcelableException。因此：
 * - 主检测通道：getPrimaryClipDescription（元信息，极小，不受限），指纹变化即视为剪贴板更新；
 * - 全文通道保留：内容较小时可读出并做 JSON 校验与存档。
 * 所有事务的参数签名与 code 均按候选矩阵探测，天然抗版本漂移。
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
                    if (lockedMode != mode) lockedMode = mode
                    lastError = "剪贴板无文本"
                    return null
                }
            }
            lastError = if (lastError.contains(BIG_PARCEL_MARK)) "剪贴板内容超过1MB，binder无法回传"
                        else "全部直调模式失败"
        } else {
            lastError = "clipboard binder 不存在"
        }
        return serviceCallRead()?.also { lastError = "无（service call 通道）" }
    }

    override fun detectChange(): String {
        val binder = clipboardBinder ?: return "NO_DESC"
        // 扫描候选 code（3~6 中必有一个是 getPrimaryClipDescription）× 参数布局
        for (code in DESC_CODE_CANDIDATES) {
            for ((_, writer) in candidates()) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    writer.invoke(data)
                    binder.transact(code, data, reply, 0)
                    reply.readException()
                    val desc = if (Build.VERSION.SDK_INT >= 30) reply.readTypedObject(ClipDescription.CREATOR)
                               else if (reply.readInt() != 0) ClipDescription.CREATOR.createFromParcel(reply) else null
                    if (desc != null) {
                        val mimes = (0 until desc.mimeTypeCount).joinToString(",") { desc.getMimeType(it) }
                        val extra = runCatching { desc.extras?.toString() ?: "" }.getOrDefault("")
                        val identity = "code=$code label=${desc.label ?: ""} mime=$mimes extra=$extra"
                        val fp = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                            .joinToString("") { "%02x".format(it) }.take(16)
                        lastError = "无（desc code=$code）"
                        return fp
                    }
                } catch (t: Throwable) {
                    // 换下一个候选
                } finally { data.recycle(); reply.recycle() }
            }
        }
        lastError = "描述通道全部候选失败"
        return "NO_DESC"
    }

    override fun diagnose(): String {
        val sb = StringBuilder()
        val binder = clipboardBinder
        if (binder == null) {
            sb.append("clipboard binder 不存在（ServiceManager 反射失败）\n")
        } else {
            // 矩阵摸底：code 3~8 × 参数布局，把 16 的接口全景探出来
            for (code in 3..8) {
                val results = StringBuilder()
                for (layout in candidates().keys.sorted()) {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(DESCRIPTOR)
                        candidates().getValue(layout).invoke(data)
                        binder.transact(code, data, reply, 0)
                        reply.readException()
                        val clipDesc = runCatching {
                            if (Build.VERSION.SDK_INT >= 30) reply.readTypedObject(ClipDescription.CREATOR) else null
                        }.getOrNull()
                        if (clipDesc != null) {
                            results.append("布局$layout=ClipDescription(${clipDesc.label ?: ""}) ")
                        } else {
                            val dataPos = runCatching { reply.dataPosition() }.getOrDefault(-1)
                            results.append("布局$layout=非描述(dataPos=$dataPos) ")
                        }
                    } catch (t: Throwable) {
                        results.append("布局$layout=${t.javaClass.simpleName} ")
                    } finally { data.recycle(); reply.recycle() }
                }
                sb.append("code=$code: ").append(results).append('\n')
            }
            sb.append(readDescriptionDiag(binder)).append('\n')
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
                    sb.append("全文mode=$mode: 成功 textLen=${text?.length ?: -1}\n")
                } catch (t: Throwable) {
                    sb.append("全文mode=$mode: ${t.javaClass.simpleName}: ${t.message?.take(120)}\n")
                } finally { data.recycle(); reply.recycle() }
            }
        }
        for (c in combos()) {
            val result = runCatching {
                val pb = ProcessBuilder(listOf("service", "call", "clipboard", c.code.toString()) + c.args)
                pb.redirectErrorStream(true)
                val p = pb.start()
                val out = p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()
                val json = extractJsonFromParcelDump(out)
                if (json != null) "✓JSON(${json.length}字符)"
                else if (out.contains("Parcel(")) "非JSON(" + out.replace(Regex("\\s+"), " ").take(50) + ")"
                else out.replace(Regex("\\s+"), " ").take(50)
            }.getOrElse { it.javaClass.simpleName }
            sb.append("sc(code=${c.code},${c.args.size / 2}参): ").append(result).append('\n')
        }
        lockedCombo?.let { sb.append("已锁定: code=${it.code} ${it.args.size / 2}参\n") }
        try {
            sb.append("logcat剪贴板相关:\n").append(logcatClips())
        } catch (t: Throwable) {
            sb.append("logcat: ${t.javaClass.simpleName}")
        }
        return sb.toString()
    }

    private fun logcatClips(): String = runCatching {
        val pb = ProcessBuilder("logcat", "-d", "-t", "300")
        pb.redirectErrorStream(true)
        val p = pb.start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text.lineSequence().filter { it.contains("clip", true) }.take(8).joinToString("\n").ifEmpty { "(无相关日志)" }
    }.getOrDefault("(读取失败)")

    private fun readDescriptionDiag(binder: IBinder): String {
        val result = detectChange()
        return if (result == "NO_DESC") "描述: 所有候选均未取得"
               else "描述指纹: $result（detectChange 可用）"
    }

    override fun destroy() { /* Shizuku UserService 规范要求的空实现 */ }

    /**
     * 参数布局候选：key 为模式编号，value 为参数写入器。
     * 4参 (pkg, attribution, userId, deviceId) — Android 15+（两枚 int 均传 0）
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

    // ---------- 备用通道：service call 穷举探测 ----------

    private data class CallCombo(val code: Int, val args: List<String>)

    @Volatile private var lockedCombo: CallCombo? = null

    /** 主探测清单：code 2（getPrimaryClip）与 code 8（16 上另一个返回大对象的方法）× 各种参数布局 */
    private fun combos(): List<CallCombo> = listOf(
        CallCombo(2, listOf("s16", PKG, "s16", PKG, "i32", "0", "i32", "0")),
        CallCombo(2, listOf("s16", PKG, "s16", PKG, "i32", "0")),
        CallCombo(2, listOf("s16", PKG, "i32", "0")),
        CallCombo(2, listOf("s16", PKG, "s16", PKG, "s16", PKG, "i32", "0", "i32", "0")),
        CallCombo(2, listOf("s16", PKG)),
        CallCombo(8, listOf("s16", PKG, "s16", PKG, "i32", "0", "i32", "0")),
        CallCombo(8, listOf("s16", PKG, "s16", PKG, "i32", "0")),
        CallCombo(8, listOf("s16", PKG, "i32", "0")),
    )

    /** 穷举组合直到某个输出的 parcel dump 里能还原出 JSON；成功即锁定组合 */
    private fun serviceCallRead(): String? {
        lockedCombo?.let { return runCombo(it) }
        for (c in combos()) {
            val json = runCombo(c)
            if (!json.isNullOrEmpty()) {
                lockedCombo = c
                lastError = "无（service call 锁定 code=${c.code} ${c.args.size / 2}参）"
                return json
            }
        }
        lastError = "service call 全部组合均未还原出 JSON"
        return null
    }

    private fun runCombo(c: CallCombo): String? = runCatching {
        val pb = ProcessBuilder(listOf("service", "call", "clipboard", c.code.toString()) + c.args)
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        extractJsonFromParcelDump(out)
    }.getOrNull()

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
        const val TRANSACTION_GET_PRIMARY_CLIP = 2
        const val PKG = "com.android.shell"
        const val JSON_START = "{\"tag\""
        const val BIG_PARCEL_MARK = "above allowed limit"
        val WORD_RE = Regex("""[0-9a-fA-F]{8}""")
        /** getPrimaryClipDescription 的事务 code 候选（不同版本可能插方法移位） */
        val DESC_CODE_CANDIDATES = 3..6
    }
}
