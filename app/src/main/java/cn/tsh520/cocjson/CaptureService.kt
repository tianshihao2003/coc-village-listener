package cn.tsh520.cocjson

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cn.tsh520.cocjson.logic.ArchiveWriter
import cn.tsh520.cocjson.logic.JsonMatcher
import cn.tsh520.cocjson.logic.TargetAppLauncher
import cn.tsh520.cocjson.logic.TargetAppStore
import cn.tsh520.cocjson.logic.VillageSnapshot
import cn.tsh520.cocjson.shizuku.ShizukuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class CaptureService : Service() {

    companion object {
        @Volatile var running = false; private set
        /** 轮询状态（主界面展示用）：最后一次轮询时间戳与备注 */
        @Volatile var lastPollAt: Long = 0L
        @Volatile var lastPollNote: String = "尚未开始"
        private const val CHANNEL_MONITOR = "monitor"
        private const val CHANNEL_CAPTURE = "capture"
        private const val FOREGROUND_ID = 1
        private const val POLL_MS = 1500L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CaptureService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastFingerprint: String? = null
    private var lastDescHash: String? = null
    private var lastClipCount = -1
    private var svc: cn.tsh520.cocjson.service.IClipboardUserService? = null

    private val prefs by lazy { getSharedPreferences("config", MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannels()
        startForeground(FOREGROUND_ID, monitorNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        ShizukuHelper.addBinderDeadListener { svc = null }
        scope.launch { pollLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        scope.cancel()
        ShizukuHelper.unbindUserService()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            delay(POLL_MS)
            lastPollAt = System.currentTimeMillis()

            // 通道一：全文可读（内容较小时）→ 精确 JSON 校验 + 存档
            val raw = readViaShizuku()
            if (raw != null) {
                val snap = JsonMatcher.match(raw)
                if (snap == null) {
                    lastPollNote = "读到 ${raw.length} 字符非村庄数据"
                    continue
                }
                val fp = JsonMatcher.fingerprint(snap.raw)
                if (fp == lastFingerprint) { lastPollNote = "已捕获过 ${snap.tag}"; continue }
                lastFingerprint = fp
                lastPollNote = "捕获 ${snap.tag}（${snap.raw.length} 字符）"
                handleCapture(snap)
                continue
            }

            // 通道二：内容超 1MB 读不出全文（游戏导出 7MB+ 的场景）
            // → 改用剪贴板元信息指纹变化检测，变化即拉起目标应用
            val svcBound = ensureSvc()
            if (svcBound == null) {
                lastPollNote = "本轮未读到（Shizuku 未授权或绑定失败）"
                continue
            }
            val hash = runCatching { svcBound.detectChange() }.getOrNull() ?: "NO_DESC"
            if (hash == "NO_DESC") {
                // 通道三：系统剪贴板变化回调计数（Android 16 主力通道）
                val cnt = runCatching { svcBound.clipChangeCount() }.getOrDefault(-1)
                if (cnt < 0) {
                    lastPollNote = "剪贴板元信息与回调通道均不可用"
                    continue
                }
                if (lastClipCount == -1) {
                    lastClipCount = cnt
                    lastPollNote = "已注册系统复制回调（计数 $cnt），请复制一次村庄数据"
                    continue
                }
                if (cnt == lastClipCount) {
                    lastPollNote = "等待复制…（回调计数 $cnt）"
                    continue
                }
                lastClipCount = cnt
                lastPollNote = "检测到复制动作（计数 $cnt）→ 已拉起目标软件"
                notifyClipChange()
                val target3 = TargetAppStore.target(this)
                if (target3 != null) runCatching { TargetAppLauncher.launch(this, target3.first) }
                continue
            }
            if (hash == lastDescHash) {
                lastPollNote = "剪贴板未变化（元信息指纹 $hash）"
                continue
            }
            val isFirst = lastDescHash == null
            lastDescHash = hash
            lastPollNote = if (isFirst) "开始跟踪剪贴板（指纹 $hash）"
                           else "检测到剪贴板更新（指纹 $hash）→ 已拉起目标软件"
            if (!isFirst || prefs.getBoolean("open_on_start", true)) {
                notifyClipChange()
                val target = TargetAppStore.target(this)
                if (target != null) runCatching { TargetAppLauncher.launch(this, target.first) }
            }
        }
    }

    private suspend fun ensureSvc(): cn.tsh520.cocjson.service.IClipboardUserService? {
        svc?.let { return it }
        if (!ShizukuHelper.granted()) return null
        return bindSuspend()?.also { svc = it }
    }

    private suspend fun readViaShizuku(): String? {
        val bound = ensureSvc() ?: return null
        return runCatching { bound.readClipboard() }.getOrNull()
    }

    private suspend fun bindSuspend(): cn.tsh520.cocjson.service.IClipboardUserService? =
        suspendCancellableCoroutine { cont ->
            ShizukuHelper.bindUserService(
                onReady = { if (cont.isActive) cont.resume(it) },
                onFail = { if (cont.isActive) cont.resume(null) },
            )
        }

    private fun handleCapture(snap: VillageSnapshot) {
        val archiveOn = prefs.getBoolean("archive_enabled", true)
        if (archiveOn) {
            runCatching { ArchiveWriter.save(this, snap) }
        }
        val target = TargetAppStore.target(this)
        notifyCapture(snap.tag, target)
        if (target != null) {
            runCatching { TargetAppLauncher.launch(this, target.first) }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "后台监听", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CAPTURE, "捕获到村庄数据", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun monitorNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("村庄数据监听中")
            .setContentText("检测到复制村庄JSON后会自动打开目标软件")
            .setOngoing(true)
        val target = TargetAppStore.target(this)
        if (target != null) {
            val launchIntent = packageManager.getLaunchIntentForPackage(target.first)
            if (launchIntent != null) {
                val pi = PendingIntent.getActivity(this, 2001,
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(0, "打开${target.second}", pi)
            }
        }
        return builder.build()
    }

    private fun notifyCapture(tag: String, target: Pair<String, String>?) {        val nm = getSystemService(NotificationManager::class.java)
        val contentText = if (target != null) "已存档，正在打开 ${target.second}"
                          else "已存档（尚未选择要自动打开的软件）"
        val fallbackIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val launchIntent = target?.let { packageManager.getLaunchIntentForPackage(it.first) }
        val pending = if (launchIntent != null) {
            PendingIntent.getActivity(this, tag.hashCode(),
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else fallbackIntent
        val notification = NotificationCompat.Builder(this, CHANNEL_CAPTURE)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("已捕获村庄数据 $tag")
            .setContentText(contentText)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { nm.notify(tag, tag.hashCode(), notification) }
    }

    private fun notifyClipChange() {
        val nm = getSystemService(NotificationManager::class.java)
        val target = TargetAppStore.target(this)
        val launchIntent = target?.let { packageManager.getLaunchIntentForPackage(it.first) }
        val pending = if (launchIntent != null) {
            PendingIntent.getActivity(this, 1001,
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_CAPTURE)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("检测到剪贴板更新")
            .setContentText(if (target != null) "正在打开 ${target.second}，请在其中粘贴/导入"
                            else "尚未选择要自动打开的软件")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { nm.notify("clip_change", 1001, notification) }
    }
}
