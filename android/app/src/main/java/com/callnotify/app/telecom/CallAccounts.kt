package com.callnotify.app.telecom

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.callnotify.app.core.AppState
import com.callnotify.app.net.NotificationPayload

/**
 * 通话账户管理：向 Telecom 注册一个电话账户，来电时由系统电话应用展示。
 *
 * 用的是 CAPABILITY_CALL_PROVIDER（与 Phony 相同），系统原生拨号界面接管来电展示，
 * 不自己画界面，也不重写 onShowIncomingCallUi。
 *
 * AOSP 侧真正的闸门（TelecomServiceImpl.enforcePhoneAccountIsRegisteredEnabled）只有两条：
 *   1. "This PhoneAccountHandle is not registered for this user!"  → 账户没注册上
 *   2. "This PhoneAccountHandle is not enabled for this user!"     → 账户注册了但没启用
 * 下面所有诊断都是为了把这两条区分出来。
 */
object CallAccounts {

    private const val TAG = "CallAccounts"
    const val ACCOUNT_ID = "call_notify_provider"

    data class TriggerResult(val ok: Boolean, val reason: String? = null)

    data class Status(
        val registered: Boolean,
        val enabled: Boolean,
        val detail: String,
    )

    fun handle(context: Context): PhoneAccountHandle = PhoneAccountHandle(
        ComponentName(context, FakeCallConnectionService::class.java),
        ACCOUNT_ID
    )

    /** 注册通话账户，返回结果描述（成功 / 异常原因） */
    fun register(context: Context): String {
        val tm = context.getSystemService(TelecomManager::class.java)
            ?: return "系统没有 TelecomManager"
        val account = PhoneAccount.Builder(handle(context), "来电通知")
            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
            .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
            .setShortDescription("接收远程通知并模拟来电")
            .build()
        return runCatching {
            tm.registerPhoneAccount(account)
            "registerPhoneAccount 成功"
        }.getOrElse {
            Log.e(TAG, "registerPhoneAccount 失败", it)
            "registerPhoneAccount 异常：${it.javaClass.simpleName}: ${it.message}"
        }
    }

    private fun hasPhoneNumbersPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_PHONE_NUMBERS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** 读当前状态，detail 直接展示在界面上 */
    fun status(context: Context): Status {
        val tm = context.getSystemService(TelecomManager::class.java)
            ?: return Status(false, false, "系统没有 TelecomManager")

        val handle = handle(context)
        val sb = StringBuilder()

        val canReadAccount = hasPhoneNumbersPermission(context)
        if (!canReadAccount) {
            sb.appendLine("⚠ 未授予 READ_PHONE_NUMBERS，读不到账户详情（下面的“未启用”不可信）")
        }

        val accountResult = runCatching { tm.getPhoneAccount(handle) }
        val account = accountResult.getOrNull()
        val queryFailed = accountResult.isFailure

        sb.appendLine(
            "账户注册：" + when {
                account != null -> "已注册"
                queryFailed -> "未知（查询被拒）"
                else -> "未注册"
            }
        )
        accountResult.exceptionOrNull()?.let {
            sb.appendLine("getPhoneAccount 异常：${it.javaClass.simpleName}: ${it.message}")
        }
        sb.appendLine(
            "账户启用：" + when {
                account != null -> if (account.isEnabled) "已启用" else "未启用"
                queryFailed -> "未知"
                else -> "未注册"
            }
        )

        // 不需要 READ_PHONE_NUMBERS，只要 MANAGE_OWN_CALLS —— 这才是 addNewIncomingCall 真正看的门
        val permitted = runCatching { tm.isIncomingCallPermitted(handle) }
        sb.appendLine(
            "允许来电：" + when {
                permitted.isSuccess -> if (permitted.getOrThrow()) "是" else "否（账户未启用）"
                else -> "查询失败：${permitted.exceptionOrNull()?.message}"
            }
        )

        val accountsResult = runCatching { tm.callCapablePhoneAccounts }
        val accounts = accountsResult.getOrNull().orEmpty()
        if (accountsResult.isFailure) {
            sb.appendLine("读通话账户列表失败：${accountsResult.exceptionOrNull()?.message}")
        } else {
            sb.appendLine("系统可见的通话账户 ${accounts.size} 个：")
            accounts.take(8).forEach { accHandle ->
                val id = runCatching { accHandle.id }.getOrDefault("?")
                val ours = id == ACCOUNT_ID
                sb.appendLine("  ${if (ours) "→" else "·"} $id")
            }
        }

        val registered = account != null || accounts.any {
            runCatching { it.id }.getOrDefault("") == ACCOUNT_ID
        }
        val enabled = account?.isEnabled == true
        return Status(registered, enabled, sb.toString().trim())
    }

    /** 注册通话账户，返回当前是否已启用 */
    fun ensure(context: Context): Boolean {
        val before = status(context)
        if (before.enabled) return true
        AppState.log(register(context))
        val after = status(context)
        if (!after.enabled) {
            AppState.log(
                if (after.registered) "通话账户已注册但未启用，请到系统设置里允许「来电通知」"
                else "通话账户尚未注册成功，点「重新注册通话账户」查看系统报错",
                2
            )
        }
        return after.enabled
    }

    fun isRegistered(context: Context): Boolean = status(context).registered

    fun isEnabled(context: Context): Boolean = status(context).enabled

    /**
     * 触发一次来电。
     *
     * 不用「本地查询账户状态」当闸门：查询失败（缺 READ_PHONE_NUMBERS 等）不等于发不起来电，
     * 直接请求一次，让 Telecom 把真实原因告诉我们。
     */
    fun trigger(context: Context, payload: NotificationPayload): TriggerResult {
        val tm = context.getSystemService(TelecomManager::class.java)
            ?: return TriggerResult(false, "系统没有 Telecom 服务")

        if (!isRegistered(context)) {
            AppState.log(register(context))
        }

        val number = payload.callerNumber.ifBlank { "10086" }
        val extras = Bundle().apply {
            putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null)
            )
            putBundle(
                TelecomManager.EXTRA_INCOMING_CALL_EXTRAS,
                Bundle().apply {
                    putString(CallExtras.CALLER_NAME, payload.callerName)
                    putString(CallExtras.CALLER_NUMBER, number)
                    putString(CallExtras.TEXT, payload.text)
                    putInt(CallExtras.RING_SECONDS, payload.ringSeconds)
                    putInt(CallExtras.SPEAK_TIMES, payload.speakTimes)
                    putString(CallExtras.NOTIFY_ID, payload.id)
                }
            )
        }

        return runCatching {
            tm.addNewIncomingCall(handle(context), extras)
            TriggerResult(true)
        }.getOrElse {
            Log.e(TAG, "addNewIncomingCall 失败", it)
            TriggerResult(false, explain(it))
        }
    }

    /** 把 Telecom 抛出来的原话翻译成能照做的提示 */
    private fun explain(t: Throwable): String {
        val msg = t.message.orEmpty()
        val hint = when {
            msg.contains("not enabled", true) ->
                "通话账户已注册但被系统判为未启用：去 设置 → 应用 → 默认应用 → 电话（或 设置 → 通话 → 通话账户）里启用「来电通知」"
            msg.contains("not registered", true) ->
                "通话账户没登记上：点「重新注册通话账户」，或重装 App"
            t is SecurityException && msg.contains("MANAGE_OWN_CALLS", true) ->
                "缺少 MANAGE_OWN_CALLS 权限"
            t is SecurityException ->
                "系统拒绝了来电请求，可尝试把本应用设为默认电话应用"
            else -> "见下方账户诊断"
        }
        return "${t.javaClass.simpleName}: ${msg.ifBlank { "无详细信息" }}（$hint）"
    }

    /**
     * 打开「通话账户」设置页。
     *
     * 这个入口在各家 ROM 上极不统一，一加/OPPO（ColorOS）甚至把它从设置里藏掉了，
     * 标准 action `android.telecom.action.CHANGE_PHONE_ACCOUNTS` 根本不解析。
     * 所以按下面顺序逐个试，谁先成功用谁：
     *
     *   0. 有系统权限（系统应用构建）时直接 enablePhoneAccount，连页面都不用开；
     *   1. 显式组件：AOSP / ColorOS 的 EnableAccountPreferenceActivity；
     *   2. 标准 action；
     *   3. 默认应用设置；
     *   4. 应用详情页（最后兜底，同时给用户可复制的 adb 命令）。
     */
    fun openSettings(context: Context): Boolean {
        if (enableAccountDirectly(context)) {
            AppState.log("已通过系统权限直接启用通话账户")
            return true
        }

        for (component in accountSettingsComponents) {
            val ok = runCatching {
                context.startActivity(
                    Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
            if (ok) {
                AppState.log("已打开通话账户页：${component.flattenToShortString()}")
                return true
            }
            AppState.log("打不开 ${component.flattenToShortString()}（多为 exported=false，需要 adb）")
        }

        val standard = listOf(
            Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
        for (intent in standard) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
                return true
            }
        }
        AppState.log("打不开通话账户设置页，请用「复制开启命令」里的 adb 命令", 2)
        return false
    }

    /**
     * 有 MODIFY_PHONE_STATE 时可以直接启用账户（系统应用 / priv-app 构建）。
     * 普通安装大概率抛 SecurityException 或被隐藏 API 拦住，所以必须包在 runCatching 里。
     */
    fun enableAccountDirectly(context: Context): Boolean = runCatching {
        val tm = context.getSystemService(TelecomManager::class.java) ?: return@runCatching false
        val method = TelecomManager::class.java.getMethod(
            "enablePhoneAccount", PhoneAccountHandle::class.java, Boolean::class.javaPrimitiveType
        )
        method.invoke(tm, handle(context), true)
        status(context).enabled
    }.getOrDefault(false)

    /** 当前 ROM 上「打开通话账户页」的 adb 命令，给用户复制 */
    fun adbCommand(): String =
        "adb shell am start -a android.intent.action.MAIN -n " +
            "com.android.server.telecom/.settings.EnableAccountPreferenceActivity"

    /** 各家「通话账户」页面的 Activity，按命中概率排序 */
    private val accountSettingsComponents = listOf(
        // 一加 / OPPO / realme（ColorOS）：设置里没有入口，Activity 仍然存在
        ComponentName(
            "com.android.server.telecom",
            "com.android.server.telecom.settings.EnableAccountPreferenceActivity"
        ),
        // AOSP / 类原生（Pixel、LineageOS 等），这个是 exported 的
        ComponentName(
            "com.android.telecom",
            "com.android.telecom.settings.EnableAccountPreferenceActivity"
        ),
        // 部分 ROM 放在 phone 包里
        ComponentName(
            "com.android.phone",
            "com.android.phone.settings.PhoneAccountSettingsActivity"
        ),
        // 三星
        ComponentName(
            "com.android.settings",
            "com.android.settings.Settings\$TelecomCallingAccountSettingsActivity"
        ),
    )
}
