package com.callnotify.app.autoconfig

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.callnotify.app.core.AppState

/**
 * 自动配置服务。
 *
 * 只做两件事，都要求窗口里出现我们的应用名，避免误点别人的弹窗：
 *   1. 系统权限弹窗里点「允许 / 始终允许 / 仅在使用中允许」；
 *   2. 设置页里找到「来电通知」那一行的开关，打开它，然后返回。
 *
 * 无障碍在这个用途上属于"用户显式授权自己手机上的自动化"，不上架所以没有商店政策问题；
 * 但它天生依赖各家 UI 的文本，所以每一步都打日志，点不中时能立刻看出卡在哪。
 */
class AutoConfigService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppState.log("自动配置服务已就绪")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val target = AutoConfig.pendingTarget
        val callingAccountPage = event?.packageName?.toString()?.let { AutoConfig.isCallingAccountPage(it) } == true

        if (!AutoConfig.allowDialogs && target == AutoConfig.Target.NONE && !callingAccountPage) return

        val root = rootInActiveWindow ?: return
        val pkg = event?.packageName?.toString().orEmpty()

        // 1) 系统权限弹窗：必须同时看到我们的应用名，避免误点
        if (AutoConfig.allowDialogs && mentionsOurApp(root)) {
            if (clickFirstMatch(root, AutoConfig.ALLOW_TEXTS)) {
                AppState.log("自动点了「允许」")
                return
            }
        }

        // 2) 设置页里的目标开关
        //    「通话账户」页（一加/OPPO 需要用 adb 显式拉起）不依赖目标状态：
        //    只要进了这一页，把「来电通知」的开关打开永远是对的。
        if ((target != AutoConfig.Target.NONE || callingAccountPage) && AutoConfig.isSettingsWindow(pkg)) {
            if (openToggleForOurApp(root)) {
                AppState.log("已自动打开「${AutoConfig.appLabel}」的开关（${if (callingAccountPage) "通话账户" else target.name}）")
                if (!callingAccountPage) AutoConfig.pendingTarget = AutoConfig.Target.NONE
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    override fun onInterrupt() = Unit

    // ---------------------------------------------------------------- 匹配

    private fun mentionsOurApp(root: AccessibilityNodeInfo): Boolean =
        runCatching {
            root.findAccessibilityNodeInfosByText(AutoConfig.appLabel).isNotEmpty() ||
                rootTextContains(root, AutoConfig.appLabel)
        }.getOrDefault(false)

    private fun rootTextContains(root: AccessibilityNodeInfo, needle: String): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 400) {
            visited++
            val node = stack.removeFirst()
            val text = node.text?.toString().orEmpty() + node.contentDescription?.toString().orEmpty()
            if (text.contains(needle)) return true
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return false
    }

    /** 按优先级点第一个能点的按钮；明确避开"不允许/拒绝"这类 */
    private fun clickFirstMatch(root: AccessibilityNodeInfo, texts: List<String>): Boolean {
        for (text in texts) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByText(text) }.getOrNull() ?: continue
            for (node in nodes) {
                val label = (node.text ?: node.contentDescription)?.toString().orEmpty()
                if (AutoConfig.DENY_TEXTS.any { label.contains(it) }) continue
                val clickable = climbToClickable(node) ?: continue
                if (runCatching { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        .getOrDefault(false)
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun climbToClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val n = current ?: return null
            if (n.isClickable && n.isEnabled) return n
            current = n.parent
        }
        return null
    }

    /**
     * 找到「来电通知」所在行里的开关并打开。
     *
     * 做法：先用应用名定位到行的文本节点，再往上爬 6 层找 isCheckable 的兄弟节点，
     * 这样不管页面长什么样，只要那一行有开关就能点中。
     */
    private fun openToggleForOurApp(root: AccessibilityNodeInfo): Boolean {
        val hits = runCatching {
            root.findAccessibilityNodeInfosByText(AutoConfig.appLabel)
        }.getOrNull().orEmpty()
        if (hits.isEmpty()) return false

        for (hit in hits) {
            var row: AccessibilityNodeInfo? = hit
            repeat(6) {
                val current = row ?: return@repeat
                val toggle = findToggle(current)
                if (toggle != null) {
                    if (runCatching { toggle.isChecked }.getOrDefault(false)) return true // 已经是开的
                    return runCatching {
                        toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }.getOrDefault(false)
                }
                row = current.parent
            }
        }
        return false
    }

    private fun findToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 200) {
            visited++
            val node = stack.removeFirst()
            val cls = node.className?.toString().orEmpty()
            if (node.isCheckable || cls.endsWith("Switch") || cls.endsWith("CheckBox") ||
                cls.endsWith("ToggleButton") || cls.endsWith("CompoundButton")
            ) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }
}
