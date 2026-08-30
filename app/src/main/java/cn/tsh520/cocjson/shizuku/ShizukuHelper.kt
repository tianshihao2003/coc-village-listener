package cn.tsh520.cocjson.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import cn.tsh520.cocjson.service.IClipboardUserService
import rikka.shizuku.Shizuku

object ShizukuHelper {
    const val REQUEST_CODE = 10001
    /** UserService 实现变更时必须递增，Shizuku 才会重启该进程 */
    private const val USER_SERVICE_VERSION = 4
    private var connection: ServiceConnection? = null
    private var deadListener: Shizuku.OnBinderDeadListener? = null

    fun installed(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
    }.getOrDefault(false)

    fun pingBinder(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean = runCatching {
        pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun request(requestCode: Int = REQUEST_CODE) {
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    private fun args() = Shizuku.UserServiceArgs(
        ComponentName("cn.tsh520.cocjson", "cn.tsh520.cocjson.service.ClipboardUserService")
    ).processNameSuffix("clipboard").version(USER_SERVICE_VERSION).debuggable(false)

    fun bindUserService(onReady: (IClipboardUserService) -> Unit, onFail: (String) -> Unit) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    runCatching { IClipboardUserService.Stub.asInterface(binder) }
                        .onSuccess(onReady)
                        .onFailure { onFail("接口转换失败: ${it.message}") }
                } else onFail("UserService binder 无效")
            }
            override fun onServiceDisconnected(name: ComponentName?) { /* 调用方经 deadListener 重建 */ }
        }
        connection = conn
        runCatching { Shizuku.bindUserService(args(), conn) }
            .onFailure { onFail("绑定失败: ${it.message}") }
    }

    fun unbindUserService() {
        connection?.let { runCatching { Shizuku.unbindUserService(args(), it, true) } }
        connection = null
    }

    fun addBinderDeadListener(action: () -> Unit) {
        deadListener = Shizuku.OnBinderDeadListener { action() }
            .also { Shizuku.addBinderDeadListener(it) }
    }
}
