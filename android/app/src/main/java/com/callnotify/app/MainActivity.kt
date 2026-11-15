package com.callnotify.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.callnotify.app.autoconfig.AutoConfig
import com.callnotify.app.core.AppState
import com.callnotify.app.core.ConnState
import com.callnotify.app.core.LogLine
import com.callnotify.app.core.Prefs
import com.callnotify.app.core.SetupAssistant
import com.callnotify.app.telecom.CallAccounts
import com.callnotify.app.ui.CallNotifyTheme
import com.callnotify.app.ui.HomeViewModel
import kotlinx.coroutines.delay

private val Green = Color(0xFF17A34A)
private val Amber = Color(0xFFD97706)
private val Red = Color(0xFFDC2626)
private val Grey = Color(0xFF8A94A6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        setContent {
            CallNotifyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * 一键导入配置：callnotify://setup?server=...&deviceId=...&key=...
     *
     * 团队分发时，管理员在一台手机上注册好，把链接/二维码发给其他人，
     * 扫码即完成配置，不用手抄密钥。
     */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "callnotify") return
        val prefs = Prefs(this)
        val server = data.getQueryParameter("server")
        val deviceId = data.getQueryParameter("deviceId")
        val key = data.getQueryParameter("key")
        if (!server.isNullOrBlank()) prefs.serverUrl = server
        if (!deviceId.isNullOrBlank() && !key.isNullOrBlank()) {
            prefs.saveIdentity(server ?: prefs.serverUrl, deviceId, key)
        }
        AppState.log("已从链接导入配置：${data.host}")
    }
}

@Composable
private fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val conn by AppState.conn.collectAsStateWithLifecycle()
    val detail by AppState.detail.collectAsStateWithLifecycle()
    val logs by AppState.logs.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    val authFailed by AppState.authFailed.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.READ_PHONE_STATE)
            // 读通话账户状态需要它；没有它 getPhoneAccount 会抛 SecurityException，
            // 界面就会误报"账户未启用"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(Manifest.permission.READ_PHONE_NUMBERS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
        vm.refreshAccount()
        // 开箱即用：内置服务器 → 自动注册 → 自动连接，用户不用填任何东西
        vm.bootstrap()

        // 无障碍已开启（手动开的，或用 adb 脚本开的）→ 自动跑一遍配置，不用再点
        delay(1500)
        val prefs = Prefs(context)
        val allReady = SetupAssistant.notificationsGranted(context) &&
            SetupAssistant.batteryOptimizationIgnored(context) &&
            CallAccounts.isEnabled(context)
        if (vm.autoConfigReady() && !prefs.autoConfigDone && !allReady) {
            prefs.autoConfigDone = true
            vm.autoConfigure()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header(conn, detail, appVersion(context)) }
        if (authFailed) {
            item { AuthFailedCard(vm) }
        }
        item { SetupCard(vm) }
        item { ServerCard(vm) }
        if (vm.registered) {
            item { KeyCard(vm) }
        }
        item { BehaviorCard(vm) }
        item { TestCard(vm) }
        item { LogCard(logs) }
    }
}

@Composable
private fun Header(conn: ConnState, detail: String, version: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("来电通知", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            StatusChip(conn)
            Spacer(Modifier.weight(1f))
            if (version.isNotBlank()) {
                Text(
                    "v$version",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            detail.ifBlank { "远程下发通知，手机模拟来电并朗读内容" },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}.getOrDefault("")

@Composable
private fun StatusChip(conn: ConnState) {
    val (text, color) = when (conn) {
        ConnState.ONLINE -> "在线" to Green
        ConnState.CONNECTING -> "连接中" to Amber
        ConnState.ERROR -> "离线" to Red
        ConnState.IDLE -> "未启动" to Grey
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ServerCard(vm: HomeViewModel) {
    SectionCard("服务器", "填写运行 server.js 的地址，手机需能访问到这台机器") {
        OutlinedTextField(
            value = vm.serverInput,
            onValueChange = vm::onServerChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("服务器地址") },
            placeholder = { Text("192.168.1.10:8787") },
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = vm::saveAndConnect,
                enabled = !vm.busy,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    when {
                        vm.busy -> "处理中…"
                        vm.registered -> "保存并重连"
                        else -> "注册设备"
                    }
                )
            }
            if (vm.serviceEnabled) {
                OutlinedButton(onClick = vm::stopService) { Text("停止") }
            } else {
                OutlinedButton(onClick = vm::startService) { Text("启动") }
            }
        }
    }
}

@Composable
private fun KeyCard(vm: HomeViewModel) {
    val clipboard = LocalClipboardManager.current
    var confirmRotate by remember { mutableStateOf(false) }

    SectionCard("设备密钥", "密钥是固定的，只有你在这里手动更换才会变") {
        KeyRow("设备 ID", vm.deviceId) { clipboard.setText(AnnotatedString(vm.deviceId)) }
        Spacer(Modifier.height(10.dp))
        KeyRow("API Key", vm.apiKey) { clipboard.setText(AnnotatedString(vm.apiKey)) }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { confirmRotate = true }, contentPadding = PaddingValues(0.dp)) {
            Text("更换密钥…", fontSize = 12.sp)
        }
    }

    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text("确认更换密钥？") },
            text = {
                Text(
                    "更换后旧密钥立即失效，服务端脚本里的 X-API-Key 必须同步改成新值。\n\n" +
                        "设备 ID 不变，服务端按设备 ID 记的配置不受影响。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRotate = false
                    vm.rotateKey()
                }) { Text("确认更换") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRotate = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun KeyRow(label: String, value: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text("复制", fontSize = 12.sp)
        }
    }
}

@Composable
private fun AuthFailedCard(vm: HomeViewModel) {
    var confirmRegister by remember { mutableStateOf(false) }
    SectionCard("密钥被服务端拒绝", "App 不会自动更换密钥，请从这里手动处理") {
        Text(
            "服务端不认识当前的 API Key。常见原因：服务端 data.json 被清空/换了服务器。\n" +
                "App 每 60 秒会用原密钥重试一次，服务端恢复后会自动连上。",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = { confirmRegister = true }, modifier = Modifier.fillMaxWidth()) {
            Text("重新注册设备（会换新密钥）")
        }
    }

    if (confirmRegister) {
        AlertDialog(
            onDismissRequest = { confirmRegister = false },
            title = { Text("确认重新注册？") },
            text = {
                Text("会生成新的设备 ID 和 API Key，旧密钥立即失效，服务端脚本必须同步更新。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRegister = false
                    vm.reRegister()
                }) { Text("确认重新注册") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegister = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SetupCard(vm: HomeViewModel) {    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { Prefs(context) }

    var notifOk by remember { mutableStateOf(SetupAssistant.notificationsGranted(context)) }
    var batteryOk by remember { mutableStateOf(SetupAssistant.batteryOptimizationIgnored(context)) }
    var autoStartAck by remember { mutableStateOf(prefs.autoStartAck) }
    var autoConfigOk by remember { mutableStateOf(vm.autoConfigReady()) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notifOk = SetupAssistant.notificationsGranted(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifOk = SetupAssistant.notificationsGranted(context)
                batteryOk = SetupAssistant.batteryOptimizationIgnored(context)
                autoStartAck = Prefs(context).autoStartAck
                autoConfigOk = vm.autoConfigReady()
                vm.refreshIdentity()
                vm.refreshAccount()
                vm.syncService()
                vm.refreshTtsEngines()
                vm.refreshWakeState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 第一个还没完成的步骤：按钮直接带你去做，回来再自动推进下一个
    val nextLabel: String
    val nextAction: () -> Unit
    when {
        !notifOk -> {
            nextLabel = "开启通知权限"
            nextAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings(context)
                }
            }
        }
        !batteryOk -> {
            nextLabel = "忽略电池优化"
            nextAction = { SetupAssistant.requestIgnoreBattery(context) }
        }
        !vm.accountEnabled -> {
            nextLabel = "启用通话账户"
            nextAction = { CallAccounts.openSettings(context) }
        }
        !autoStartAck -> {
            nextLabel = "设置自启动"
            nextAction = {
                SetupAssistant.openAutoStartSettings(context)
                prefs.autoStartAck = true
                autoStartAck = true
            }
        }
        else -> {
            nextLabel = "已完成，去「模拟来电」验证"
            nextAction = { vm.testCall() }
        }
    }

    val allReady = notifOk && batteryOk && vm.accountEnabled && autoStartAck

    SectionCard(
        "就绪检查",
        if (allReady) "全部就绪，收到通知就会直接响铃" else "按按钮走完剩下的步骤，只需几次系统弹窗"
    ) {
        CheckRow("通知权限", notifOk, "去开启") { openNotificationSettings(context) }
        DividerLine()
        CheckRow("忽略电池优化", batteryOk, "去设置") { SetupAssistant.requestIgnoreBattery(context) }
        DividerLine()
        CheckRow("通话账户已启用", vm.accountEnabled, "去设置") { CallAccounts.openSettings(context) }
        DividerLine()
        CheckRow("自启动 / 后台运行", autoStartAck, "去设置") {
            SetupAssistant.openAutoStartSettings(context)
            prefs.autoStartAck = true
            autoStartAck = true
        }

        if (!vm.accountEnabled) {
            Spacer(Modifier.height(10.dp))
            Text(
                vm.accountDetail.ifBlank { "点下面「重新注册」可重新向系统申请通话账户" },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = vm::reRegisterAccount, contentPadding = PaddingValues(0.dp)) {
                Text("重新注册通话账户", fontSize = 12.sp)
            }
            // 一加/OPPO 把「通话账户」藏了，标准入口点不开；给出可直接粘的 adb 命令
            val clipboard = LocalClipboardManager.current
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(CallAccounts.adbCommand()))
                    Toast.makeText(context, "命令已复制，到电脑上执行", Toast.LENGTH_SHORT).show()
                },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("复制「打开通话账户」的 adb 命令", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (autoConfigOk) vm.autoConfigure() else {
                    AutoConfig.openAccessibilitySettings(context)
                    Toast.makeText(context, "打开「来电通知」的无障碍开关，回来再点一次", Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (autoConfigOk) "一键自动配置（自动点允许/开关）" else "① 先开无障碍权限")
        }
        TextButton(onClick = { AutoConfig.openAccessibilitySettings(context) }, contentPadding = PaddingValues(0.dp)) {
            Text(if (autoConfigOk) "无障碍已开启，点此复查" else "打不开？点这里手动进无障碍设置", fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (autoConfigOk) "自动配置会自动点掉电池优化弹窗、打开通话账户与自启动开关"
            else "开启无障碍后，剩下的允许与开关都可以自动完成，不用手动翻菜单",
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = nextAction, modifier = Modifier.fillMaxWidth()) {
            Text(nextLabel)
        }
    }
}

@Composable
private fun DividerLine() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 9.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
private fun CheckRow(title: String, ok: Boolean, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(if (ok) Green else Amber, CircleShape))
        Spacer(Modifier.width(9.dp))
        Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (ok) {
            Text("已完成", fontSize = 12.sp, color = Green)
        } else {
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(action, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BehaviorCard(vm: HomeViewModel) {
    val context = LocalContext.current
    SectionCard("来电行为") {
        StepperRow("朗读次数", vm.speakTimes, 1, 5, 1) { vm.updateSpeakTimes(it) }
        DividerLine()
        StepperRow("无人接听挂断", vm.ringSeconds, 10, 120, 5) { vm.updateRingSeconds(it) }
        DividerLine()
        SwitchRow("接听走扬声器", "关闭则使用听筒", vm.speakerOn) { vm.updateSpeakerOn(it) }
        DividerLine()
        SwitchRow("朗读完自动挂断", null, vm.autoHangup) { vm.updateAutoHangup(it) }
        DividerLine()
        SwitchRow("锁屏也保持在线", "息屏后持有 CPU/Wi-Fi 唤醒锁，明显耗电但锁屏也能收到通知", vm.lockScreenKeepAlive) {
            vm.updateLockScreenKeepAlive(it)
        }
        DividerLine()
        SwitchRow("极限保活（守护进程 + 5 秒心跳）", "多进程互相拉起，内存占用略增", vm.guardEnabled) {
            vm.updateGuardEnabled(it)
        }
        DividerLine()
        SwitchRow("激进保活（状态栏显示闹钟）", "决定系统能否按时唤醒，关掉后唤醒可能被推迟", vm.aggressiveWake) {
            vm.updateAggressiveWake(it)
        }
        if (!vm.exactAlarmAllowed) {
            TextButton(
                onClick = { SetupAssistant.requestExactAlarm(context) },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("允许精确闹钟（否则唤醒会被系统推迟）", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        StepButton("−") { onChange((value - step).coerceAtLeast(min)) }
        Text(
            "$value",
            fontSize = 14.sp,
            modifier = Modifier.width(46.dp),
            textAlign = TextAlign.Center
        )
        StepButton("+") { onChange((value + step).coerceAtMost(max)) }
    }
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TestCard(vm: HomeViewModel) {
    SectionCard("测试", "先在本机验证，再接入业务系统") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::testCall, modifier = Modifier.weight(1f)) {
                Text("模拟来电")
            }
            OutlinedButton(onClick = vm::testSpeech, modifier = Modifier.weight(1f)) {
                Text("测试语音")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "语音引擎：${vm.ttsEngines}",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = vm::openTtsSettings, contentPadding = PaddingValues(0.dp)) {
            Text("语音引擎设置", fontSize = 12.sp)
        }
    }
}

@Composable
private fun LogCard(logs: List<LogLine>) {
    SectionCard("运行日志") {
        if (logs.isEmpty()) {
            Text(
                "暂无记录",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                logs.take(12).forEach { line ->
                    Row {
                        Text(
                            line.time,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            line.text,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = if (line.level == 2) Red else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}


private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        )
    }
}
