package cn.tsh520.cocjson

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cn.tsh520.cocjson.logic.TargetAppStore
import cn.tsh520.cocjson.shizuku.ShizukuHelper
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var shizukuStatus: TextView
    private lateinit var switchMonitor: MaterialSwitch
    private lateinit var targetInfo: TextView
    private lateinit var diagnoseResult: TextView

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TargetAppStore.applyPresetIfInstalled(this)
        setContentView(R.layout.activity_main)
        shizukuStatus = findViewById(R.id.shizuku_status)
        switchMonitor = findViewById(R.id.switch_monitor)
        targetInfo = findViewById(R.id.target_info)
        diagnoseResult = findViewById(R.id.diagnose_result)

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

    override fun onResume() { super.onResume(); updateUi() }

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
                val text = runCatching { svc.readClipboard() }
                val info = runCatching { svc.modeInfo() }.getOrDefault("?")
                diagnoseResult.text = text.fold(
                    onSuccess = { t -> (t?.take(300) ?: "(剪贴板为空)") + "\n\n[$info]" },
                    onFailure = { "读取失败: ${it.message}\n[$info]" }
                )
            },
            onFail = { diagnoseResult.text = "绑定失败: $it" }
        )
    }
}
