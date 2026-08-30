package cn.tsh520.cocjson.service

import android.content.ClipData
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 运行在 shell 身份的 Shizuku UserService 进程。
 * 通过裸 binder transact 调系统 IClipboard.getPrimaryClip（transaction code 2，
 * descriptor "android.content.IClipboard"）。shell 身份不受 Android 10+ 剪贴板限制。
 * 参数签名跨版本漂移用"候选列表探测"消化：按 SDK 从新到旧尝试，成功即缓存。
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

    override fun version(): String = "1.0"

    override fun modeInfo(): String =
        "lockedMode=$lockedMode sdk=${Build.VERSION.SDK_INT} binder=${clipboardBinder != null}"

    override fun readClipboard(): String? {
        val binder = clipboardBinder ?: return null
        // 先试已锁定的模式，再按序探测其余候选
        val modes = if (lockedMode >= 0) listOf(lockedMode) + (candidates().keys - lockedMode)
                    else candidates().keys.sorted()
        for (mode in modes) {
            val clip = transactGetPrimaryClip(binder, mode) ?: continue
            val text = clipToText(clip) ?: continue
            if (lockedMode != mode) lockedMode = mode
            return text
        }
        return null
    }

    override fun destroy() { /* Shizuku UserService 规范要求的空实现 */ }

    /**
     * 候选参数组合：key 为模式编号，value 为参数写入器。
     * 4参 (pkg, attribution, userId, deviceId) — Android 15+（两枚 int 均传 0，
     *     因此 V 平台若 deviceId/userId 顺序互换，payload 完全相同，无需重复候选）
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
        } catch (t: Throwable) { null } finally { data.recycle(); reply.recycle() }
    }

    private fun clipToText(clip: ClipData): String? = try {
        val item = clip.getItemAt(0) ?: return null
        item.getText()?.toString() ?: item.coerceToText(null)?.toString()
    } catch (t: Throwable) { null }

    private companion object {
        const val DESCRIPTOR = "android.content.IClipboard"
        const val TRANSACTION_GET_PRIMARY_CLIP = 2 // FIRST_CALL_TRANSACTION + 1
        const val PKG = "com.android.shell" // shell uid 下被系统放行的调用方标识
    }
}
