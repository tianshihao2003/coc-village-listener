package cn.tsh520.cocjson

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cn.tsh520.cocjson.logic.TargetAppStore
import cn.tsh520.cocjson.shizuku.ShizukuHelper
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var shizukuStatus: TextView
    private lateinit var switchMonitor: MaterialSwitch
    private lateinit var targetInfo: TextView
    private lateinit var diagnoseResult: TextView
    private lateinit var pollStatus: TextView

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateUi() }

    private val uiTicker = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    private val tickTask = object : Runnable {
        override fun run() {
            updatePollStatus()
            uiTicker.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TargetAppStore.applyPresetIfInstalled(this)
        setContentView(R.layout.activity_main)
        shizukuStatus = findViewById(R.id.shizuku_status)
        switchMonitor = findViewById(R.id.switch_monitor)
        targetInfo = findViewById(R.id.target_info)
        diagnoseResult = findViewById(R.id.diagnose_result)
        pollStatus = findViewById(R.id.poll_status)

        val switchArchive = findViewById<MaterialSwitch>(R.id.switch_archive)
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        switchArchive.isChecked = prefs.getBoolean("archive_enabled", true)
        switchArchive.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("archive_enabled", checked).apply()
        }

        switchMonitor.isChecked = CaptureService.running
        switchMonitor.setOnCheckedChangeListener { _, checked ->
            if (checked) startMonitor() else CaptureService.stop(this)
            updateUi()
        }

        findViewById<Button>(R.id.btn_pick).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        findViewById<Button>(R.id.btn_guide).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        findViewById<Button>(R.id.btn_diagnose).setOnClickListener { diagnose() }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        uiTicker.removeCallbacks(tickTask)
        uiTicker.post(tickTask)
    }

    override fun onPause() {
        super.onPause()
        uiTicker.removeCallbacks(tickTask)
    }

    private fun updatePollStatus() {
        pollStatus.text = if (CaptureService.running) {
            val ago = (System.currentTimeMillis() - CaptureService.lastPollAt) / 1000
            if (CaptureService.lastPollAt == 0L) "轮询状态：服务启动中…"
            else if (ago <= 5) "轮询状态：正常（${timeFmt.format(Date(CaptureService.lastPollAt))}，${CaptureService.lastPollNote}）"
            else "轮询状态：⚠️ ${ago} 秒没有活动——服务可能被系统杀掉了，请按「激活引导」第四步设置保活后重新开关一次监听"
        } else "轮询状态：未开启"
    }

    private fun startMonitor() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        if (!ShizukuHelper.granted()) {
            ShizukuHelper.request()
            Toast.makeText(this, "请在 Shizuku 弹窗中允许权限，然后再点一次「开启监听」", Toast.LENGTH_LONG).show()
            return
        }
        CaptureService.start(this)
    }

    private fun updateUi() {
        shizukuStatus.text = when {
            !ShizukuHelper.installed(this) -> "❌ 未安装 Shizuku——请先看「激活引导」安装并激活"
            !ShizukuHelper.pingBinder() -> "⚠️ Shizuku 未激活（手机重启后需重新激活）——点「激活引导」"
            !ShizukuHelper.granted() -> "⚠️ Shizuku 已激活但未授权本应用——重新点「开启监听」授权"
            else -> "✅ Shizuku 正常"
        }
        targetInfo.text = TargetAppStore.target(this)
            ?.let { "自动打开：${it.second}（${it.first}）" } ?: "自动打开：未设置"
        switchMonitor.isChecked = CaptureService.running
    }

    private fun diagnose() {
        diagnoseResult.text = "诊断中…"
        if (!ShizukuHelper.granted()) { diagnoseResult.text = "请先激活并授权 Shizuku"; return }
        ShizukuHelper.bindUserService(
            onReady = { svc ->
                val report = runCatching { svc.diagnose() }.getOrElse { "诊断失败: ${it.message}" }
                diagnoseResult.text = "提示：诊断前先随便复制一段文字（如聊天消息），再点本按钮。\n\n$report"
            },
            onFail = { diagnoseResult.text = "绑定失败: $it" }
        )
    }
}
