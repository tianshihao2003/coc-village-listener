package cn.tsh520.cocjson.logic

import android.content.Context

object TargetAppStore {
    const val PRESET_PKG = "com.runrpa.cungu" // 村姑日记
    private const val PREFS = "config"
    private const val KEY_PKG = "target_pkg"
    private const val KEY_LABEL = "target_label"

    fun setTarget(context: Context, pkg: String, label: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PKG, pkg).putString(KEY_LABEL, label).apply()
    }

    /** 已配置的目标；未配置但预置已装时自动应用预置并返回 */
    fun target(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = prefs.getString(KEY_PKG, null)
        val label = prefs.getString(KEY_LABEL, null)
        if (pkg != null && label != null) return pkg to label
        applyPresetIfInstalled(context)
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg2 = p.getString(KEY_PKG, null) ?: return null
        val label2 = p.getString(KEY_LABEL, null) ?: return null
        return pkg2 to label2
    }

    fun isPresetInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PRESET_PKG, 0); true
    }.getOrDefault(false)

    fun applyPresetIfInstalled(context: Context): Boolean {
        if (!isPresetInstalled(context)) return false
        val label = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(PRESET_PKG, 0)
            ).toString()
        }.getOrDefault("村姑日记")
        setTarget(context, PRESET_PKG, label)
        return true
    }
}
