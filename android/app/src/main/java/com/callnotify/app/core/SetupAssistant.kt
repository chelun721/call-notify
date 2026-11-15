package com.callnotify.app.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.callnotify.app.core.AppState

/**
 * 首次配置引导。
 *
 * 安卓上有些开关第三方应用**无法**静默打开（需要系统签名权限），
 * 能做的只有：检测 → 直接把用户送到那个页面 → 回来再检测。
 * 这里集中放各家 ROM 的"自启动管理"直达入口，避免用户翻三层菜单。
 */
object SetupAssistant {

    fun notificationsGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun batteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    fun requestIgnoreBattery(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(direct) }.isSuccess
        if (!ok) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { AppState.log("打不开电池优化设置：${it.message}", 2) }
        }
    }

    /** Android 12+ 的精确闹钟权限（保活闹钟要用） */
    fun requestExactAlarm(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 各厂商「自启动 / 后台运行」管理页的直达组件。
     *
     * 这些都是厂商私有 Activity，不保证每个版本都在，所以按顺序挨个试，
     * 一个都打不开就退回到「应用详情页」，用户自己找。
     */
    private val autoStartComponents = listOf(
        // 小米 / MIUI
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        // 华为 / 荣耀
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        // OPPO / 一加 / realme（ColorOS）
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
        // vivo / iQOO
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        // 三星
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        // 魅族
        ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
    )

    /** 返回是否成功打开了某个厂商页面 */
    fun openAutoStartSettings(context: Context): Boolean {
        for (component in autoStartComponents) {
            val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                AppState.log("已打开 ${component.packageName} 的自启动管理")
                return true
            }
        }
        // 退路：应用详情页
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(fallback) }
            .onFailure { AppState.log("打不开自启动设置：${it.message}", 2) }
        return false
    }
}
