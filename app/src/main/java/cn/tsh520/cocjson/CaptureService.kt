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
            val raw = readViaShizuku() ?: continue
            val snap = JsonMatcher.match(raw) ?: continue
            val fp = JsonMatcher.fingerprint(snap.raw)
            if (fp == lastFingerprint) continue
            lastFingerprint = fp
            handleCapture(snap)
        }
    }

    private suspend fun readViaShizuku(): String? {
        if (!ShizukuHelper.granted()) return null
        val bound = svc ?: bindSuspend()?.also { svc = it } ?: return null
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

    private fun monitorNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("村庄数据监听中")
            .setContentText("检测到复制村庄JSON后会自动打开目标软件")
            .setOngoing(true)
            .build()

    private fun notifyCapture(tag: String, target: Pair<String, String>?) {
        val nm = getSystemService(NotificationManager::class.java)
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
}
