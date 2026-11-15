package com.callnotify.app.autoconfig

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import com.callnotify.app.core.AppState

/**
 * 自动配置的共享状态。
 *
 * 设计目标：用户只点两下（开无障碍 + 点「一键配置」），剩下的允许、开关、返回
 * 全部由 AutoConfigService 代劳。所有动作都写日志，方便出错时定位是哪一步没点中。
 */
object AutoConfig {

    /** 当前正在等的目标页：设置页里要点的那个开关 */
    enum class Target { NONE, BATTERY, ACCOUNT, AUTOSTART }

    @Volatile
    var pendingTarget: Target = Target.NONE

    /** 是否允许自动点系统权限弹窗 */
    @Volatile
    var allowDialogs: Boolean = false

    /** 要匹配的应用名（无障碍里用文本找人） */
    @Volatile
    var appLabel: String = "来电通知"

    /** 介于"允许"和"不允许"之间的坑：这些一律不点 */
    val DENY_TEXTS = listOf(
        "不允许", "拒绝", "禁止", "取消", "以后再说", "暂不", "稍后", "否"
    )

    /** 可以点的确认类按钮（按优先级） */
    val ALLOW_TEXTS = listOf(
        "始终允许", "仅在使用中允许", "允许所有", "允许", "是", "确定", "好的", "继续", "打开", "启用"
    )

    fun begin() {
        allowDialogs = true
        pendingTarget = Target.NONE
        AppState.log("自动配置开始")
    }

    fun end() {
        allowDialogs = false
        pendingTarget = Target.NONE
        AppState.log("自动配置结束")
    }

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AutoConfigService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (TextUtils.isEmpty(enabled)) return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { AppState.log("打不开无障碍设置：${it.message}", 2) }
    }

    /** 各家 ROM 的系统设置包名，用来判断"现在是不是在设置页里" */
    val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.miui.powerkeeper",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.oppo.safe",
        "com.huawei.systemmanager",
        "com.hihonor.systemmanager",
        "com.vivo.permissionmanager",
        "com.iqoo.secure",
        "com.samsung.android.lool",
        "com.meizu.safe",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.lbe.security.miui",
        // 「通话账户」页：一加/OPPO 藏在 telecom 包里，只能由 adb 显式拉起
        "com.android.server.telecom",
        "com.android.telecom",
        "com.android.phone",
    )

    /**
     * 通话账户页比较特殊：不管有没有走自动流程，只要用户进到这一页，
     * 我们就可以把「来电通知」那一行的开关打开——这永远是我们想要的结果。
     */
    fun isCallingAccountPage(pkg: String): Boolean =
        pkg == "com.android.server.telecom" || pkg == "com.android.telecom" || pkg == "com.android.phone"

    fun isSettingsWindow(pkg: String): Boolean = SETTINGS_PACKAGES.contains(pkg)

    /** Android 版本提示：13+ 才有运行时通知权限 */
    val needsNotificationPermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
