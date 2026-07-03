package com.mbclaw.root.ui

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import com.mbclaw.root.BuildConfig
import com.mbclaw.root.agent.MBclawAgent
import com.mbclaw.root.ui.screens.*

/**
 * MBclawMainScreen — 仿 MiClaw 风格
 *
 * 主屏: 单一聊天页 (无 4-tab)
 * 顶栏: ☰ 抽屉 | 中央标题 | 右上 ⓘ 信息
 * 抽屉: 聊天列表 + 添加助手 + 设置入口
 * 设置: 二级页（账号 / 模型 / 工具 / 智能手 / Token / 隐私 / 清除 / 版本）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MBclawMainScreen() {
    val ctx = LocalContext.current
    val agent = remember { MBclawAgent(ctx.applicationContext as Application) }
    val chatVM = remember { ChatViewModel.get(ctx.applicationContext, agent) }

    // 路由栈 — 后进先出，保证「返回」回到上一页而不是桌面
    val routeStack = remember { mutableStateListOf("chat") }
    val route = routeStack.last()
    fun push(r: String) { if (routeStack.last() != r) routeStack.add(r) }
    fun pop(): Boolean = if (routeStack.size > 1) { routeStack.removeAt(routeStack.size - 1); true } else false

    var showSetup by remember { mutableStateOf(false) }
    var showHand by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showAllSessions by remember { mutableStateOf(false) }
    var showNotice by remember { mutableStateOf(true) } // 启动时显示公告
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 启动立即加载历史会话
    LaunchedEffect(Unit) { chatVM.initIfNeeded() }

    // 版本更新检测 (启动时检查，含热更新版本)
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf("") }
    var updateUrl by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val backend = com.mbclaw.root.data.Endpoints.backend(ctx)
                val current = com.mbclaw.root.BuildConfig.VERSION_NAME
                val patchVer = ctx.getSharedPreferences("mb_hotfix", android.content.Context.MODE_PRIVATE)
                    .getInt("patch_version", 0)
                // 把热更新版本追加到 current 参数，避免误报
                val effectiveVer = if (patchVer > 0) "$current+h$patchVer" else current
                val u = java.net.URL("${backend.trimEnd('/')}/admin/client/version?current=$effectiveVer&version_code=${com.mbclaw.root.BuildConfig.VERSION_CODE}")
                val conn = u.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val j = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                if (j.optBoolean("has_update", false)) {
                    val latest = j.optString("latest", "")
                    // ★ 检查用户是否已忽略此版本
                    val ignoredVer = ctx.getSharedPreferences("mb_update", android.content.Context.MODE_PRIVATE)
                        .getString("ignored_version", "")
                    if (ignoredVer == latest) return@withContext
                    val changelog = j.optString("changelog", "")
                    val dl = j.optString("download_url", "http://8.130.42.188/mbclaw-root-latest.apk")
                    updateInfo = "$latest\n\n$changelog"
                    updateUrl = dl
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        showUpdateDialog = true
                    }
                }
            } catch (_: Exception) {}
        }
    }

    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("发现新版本") },
            text = { Text(updateInfo) },
            confirmButton = {
                Column {
                    Button(onClick = {
                        try {
                            val url = updateUrl.ifBlank { "http://8.130.42.188/mbclaw-root-latest.apk" }
                            val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(i)
                        } catch (_: Exception) {
                            android.widget.Toast.makeText(ctx, "手动下载: http://8.130.42.188", android.widget.Toast.LENGTH_LONG).show()
                        }
                        showUpdateDialog = false
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("📥 立即更新")
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = {
                        showUpdateDialog = false
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("⏰ 稍后（设置→版本中更新）")
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = {
                        ctx.getSharedPreferences("mb_update", android.content.Context.MODE_PRIVATE)
                            .edit().putString("ignored_version", updateInfo.substringBefore('\n')).apply()
                        showUpdateDialog = false
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("🚫 忽略本次")
                    }
                }
            },
            dismissButton = null
        )
    }

    // 首次安装检测: 读标记文件
    val firstLaunchPref = ctx.getSharedPreferences("mb_first", android.content.Context.MODE_PRIVATE)
    val isFirstLaunch = remember { firstLaunchPref.getBoolean("first_root_check", true) }

    // 热更新进度 — 实时轮询SharedPreferences
    var hotfixProgress by remember { mutableStateOf("") }
    var showHotfixBar by remember { mutableStateOf(false) }
    var hotfixDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prefs = ctx.getSharedPreferences("mb_hotfix", android.content.Context.MODE_PRIVATE)
        // 先查是否有已下载待重启的补丁
        val patchVer = prefs.getInt("patch_version", 0)
        if (patchVer > 0 && com.mbclaw.root.agent.HotfixLoader.patchLoaded) {
            hotfixProgress = "✅ 热更新 v$patchVer 已激活"
            showHotfixBar = true
        }
        // 实时轮询下载进度 (最多等2分钟)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var waited = 0
            while (waited < 120) {
                kotlinx.coroutines.delay(500); waited++
                val p = prefs.getString("progress", "") ?: ""
                if (p.isNotBlank()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        hotfixProgress = p
                        showHotfixBar = true
                        if (p.contains("完成") || p.contains("重启")) {
                            hotfixDone = true
                        }
                    }
                }
                // 检查是否下载完成
                if (prefs.getInt("patch_version", 0) > 0 && hotfixDone) break
            }
        }
    }

    // ★ v5.0.5: 首次启动区分 有root vs 无root，修复云手机误判
    var showRootDialog by remember { mutableStateOf(false) }
    var userHasRoot by remember { mutableStateOf(false) }   // 实际root检测结果
    var showPermGrant by remember { mutableStateOf(false) }
    var showNoRootHint by remember { mutableStateOf(false) }
    var rootChecked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!rootChecked) {
            val tier = com.mbclaw.root.agent.PermissionTier.get(ctx)
            tier.refreshRoot()  // ★ 强制刷新，不用缓存
            val hasRoot = tier.hasRoot
            userHasRoot = hasRoot

            if (hasRoot && isFirstLaunch) {
                kotlinx.coroutines.delay(500)
                showRootDialog = true
            } else if (!hasRoot && isFirstLaunch) {
                kotlinx.coroutines.delay(500)
                showRootDialog = true
            } else if (!hasRoot && !isFirstLaunch) {
                showNoRootHint = true
                kotlinx.coroutines.delay(3000)
                showNoRootHint = false
            } else if (hasRoot && !isFirstLaunch) {
                val (g, t) = com.mbclaw.root.agent.RootBootstrap.status(ctx)
                if (g < 20) showPermGrant = true
            }
            rootChecked = true
        }
    }

    // ★ v5.5.0: 启动引导页 — 流畅 HorizontalPager + 动画指示器
    var showOnboarding by remember { mutableStateOf(false) }
    val onboardingPref = ctx.getSharedPreferences("mb_onboarding", android.content.Context.MODE_PRIVATE)
    LaunchedEffect(rootChecked) {
        if (rootChecked && !onboardingPref.getBoolean("done", false)) {
            kotlinx.coroutines.delay(800)
            showOnboarding = true
        }
    }
    if (showOnboarding) {
        val onboardingSteps = remember {
            listOf(
                // 第1页: 欢迎 + 核心能力
                listOf("🤖", "欢迎使用 MBclaw", "你的手机AI智能体",
                       "⚠️ 必备：AI模型 → 决定了「大脑」的智商\n" +
                       "💡 推荐：视觉识图 → 让AI看懂你的屏幕\n" +
                       "💡 推荐：语音对话 → 动口不动手\n" +
                       "📦 可选：扩展插件 → 按需开启更多能力\n\n" +
                       "MBclaw 用自然语言操控手机——\n点击、输入、开关WiFi、管理应用、读写文件。\n由孟白(18岁独立开发者)创造。"),
                // 第2页: AI模型 — 必备核心
                listOf("🧠", "AI 模型配置", "⚠️ 必备 — 决定一切能力上限",
                       "没有模型，MBclaw 就是一个空壳。\n\n" +
                       "方式一：自带 Key\n" +
                       "  • 支持 OpenAI / Anthropic / DeepSeek / 阿里云等\n" +
                       "  • 推荐 mimo-v2.5-pro（小米 MIMO）\n\n" +
                       "方式二：白嫖算力\n" +
                       "  • 服务器自动创建 MiClaw 代理实例\n" +
                       "  • 免费使用，自动配置，无需填 Key\n" +
                       "  • 每次使用约为您节省 ¥0.03~0.30"),
                // 第3页: 视觉+语音 — 推荐开启
                listOf("👁", "视觉与语音", "💡 推荐 — 让AI看懂、听懂",
                       "视觉识图（推荐开启）：\n" +
                       "  • AI 截屏分析屏幕内容，精准定位按钮/文本\n" +
                       "  • 不开 = AI 是「盲人」，只能文字交互\n" +
                       "  • 预设支持：豆包 SeedVision / 智谱 AutoGLM\n\n" +
                       "语音 TTS/ASR（按需开启）：\n" +
                       "  • TTS：AI 朗读回复给你听\n" +
                       "  • ASR：你说 → AI 听 → 自动转文字\n" +
                       "  • 不开 = 纯文本对话，功能完全正常"),
                // 第4页: 扩展能力 — 可选增值
                listOf("🔌", "扩展能力", "📦 可选 — 按需打造专属AI",
                       "Linux 环境：\n" +
                       "  • 手机里跑完整 Alpine Linux（~200MB）\n" +
                       "  • AI 可执行编译、脚本、文件处理等高级操作\n\n" +
                       "MCP 插件 / Skill 技能 / 工具市场：\n" +
                       "  • 连接外部工具和API，无限扩展AI 能力边界\n" +
                       "  • 本地/云端技能按需安装\n\n" +
                       "以上全部可选，基础对话功能不受任何影响。"),
                // 第5页: 完成
                listOf("✅", "准备就绪", "开始你的 MBclaw 之旅",
                       "✅ 模型：可随时在设置中配置\n" +
                       "✅ 权限：已按设备情况自动授权\n" +
                       "✅ 扩展：随时按需启用\n\n" +
                       "所有功能都可以在设置中调整。\n" +
                       "跳过引导不会影响任何功能。\n\n" +
                       "祝你使用愉快！")
            )
        }
        val pageCount = onboardingSteps.size
        val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount) { pageCount }
        val coroutineScope = rememberCoroutineScope()
        val isLast = pagerState.currentPage >= pageCount - 1

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize()) {
                // 顶部跳过按钮
                Row(Modifier.fillMaxWidth().padding(top = 48.dp, end = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        onboardingPref.edit().putBoolean("done", true).apply()
                        showOnboarding = false
                    }) { Text("跳过", color = MaterialTheme.colorScheme.outline, fontSize = 14.sp) }
                }

                // 页面
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                ) { page ->
                    val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            alpha = 1f - kotlin.math.abs(pageOffset).coerceIn(0f, 1f) * 0.6f
                            scaleX = 1f - kotlin.math.abs(pageOffset).coerceIn(0f, 1f) * 0.08f
                            scaleY = 1f - kotlin.math.abs(pageOffset).coerceIn(0f, 1f) * 0.08f
                        }
                    ) {
                        AnimatedContent(
                            targetState = page,
                            transitionSpec = {
                                val dir = if (targetState > initialState) 1 else -1
                                (slideInHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { w -> dir * w } + fadeIn(tween(300)))
                                    .togetherWith(slideOutHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { w -> -dir * w } + fadeOut(tween(200)))
                            },
                        ) { currentPage ->
                            Column(
                                Modifier.fillMaxSize().padding(horizontal = 28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Spacer(Modifier.weight(0.15f))
                                // 图标 — 弹性缩放
                                val iconScale by animateFloatAsState(
                                    targetValue = 1f, animationSpec = spring(dampingRatio = 0.5f, stiffness = 300f)
                                )
                                Box(
                                    Modifier.size(88.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(onboardingSteps[currentPage][0], fontSize = 40.sp)
                                }
                                Spacer(Modifier.height(24.dp))
                                Text(onboardingSteps[currentPage][1], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text(onboardingSteps[currentPage][2], style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(16.dp))
                                Text(onboardingSteps[currentPage][3], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), lineHeight = 22.sp)
                                Spacer(Modifier.weight(0.25f))
                            }
                        }
                    }
                }

                // 底部指示器 + 按钮
                Column(Modifier.padding(horizontal = 32.dp, vertical = 16.dp).navigationBarsPadding()) {
                    // 圆点指示器 — 平滑颜色+大小过渡
                    Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.Center) {
                        repeat(pageCount) { idx ->
                            val isSelected = idx == pagerState.currentPage
                            val dotColor by animateColorAsState(
                                targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                                              else MaterialTheme.colorScheme.outlineVariant,
                                animationSpec = tween(300)
                            )
                            val dotSize by animateDpAsState(
                                targetValue = if (isSelected) 10.dp else 8.dp,
                                animationSpec = tween(300)
                            )
                            Box(
                                Modifier.padding(horizontal = 5.dp).size(dotSize)
                                    .background(dotColor, CircleShape),
                            )
                        }
                    }
                    // 按钮
                    Button(
                        onClick = {
                            if (isLast) {
                                onboardingPref.edit().putBoolean("done", true).apply()
                                showOnboarding = false
                            } else {
                                coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(if (isLast) "开始使用" else "下一步", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    var rootFailCount by remember { mutableStateOf(0) }
    if (showRootDialog) {
        val hasRootNow = remember { userHasRoot }
        AlertDialog(
            onDismissRequest = { showRootDialog = false },
            title = {
                Text(if (hasRootNow) "✅ 检测到 Root 权限" else "未检测到 Root 权限")
            },
            text = {
                Text(if (hasRootNow)
                    "已检测到 Root，点击下方按钮授予系统权限。"
                else
                    "MBclaw Root 版需要 Root 权限才能使用完整功能。\n\n请授权后重试，或下载非 Root 版本。")
            },
            confirmButton = {
                Column {
                    Button(onClick = {
                        // ★ 强制刷新root检测缓存，不依赖旧缓存
                        val tier = com.mbclaw.root.agent.PermissionTier.get(ctx)
                        tier.refreshRoot()
                        if (tier.hasRoot) {
                            firstLaunchPref.edit().putBoolean("first_root_check", false).apply()
                            showRootDialog = false
                            showPermGrant = true
                        } else {
                            rootFailCount++
                            userHasRoot = false  // ★ 更新为实际状态
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (hasRootNow) "✅ 立即授权系统权限"
                             else "✅ 已授权，重新检测")
                    }
                    if (rootFailCount > 0) {
                        Text(
                            "你的root权限太难到手了，比你追女朋友还难",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("http://8.130.42.188/mbclaw-root-latest.apk"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                        showRootDialog = false
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("📥 去下载非 Root 版本")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showRootDialog = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("🐴 我是倔驴，我就用")
                    }
                }
            },
            dismissButton = null
        )
    }

    // 无root提示条 (非首次启动, 3秒消失)
    AnimatedVisibility(visible = showNoRootHint) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Text(
                "你还没有root哦，就像你没有得到你女朋友的心一样 💔",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }

    // 热更新进度提示
    if (showHotfixBar && hotfixProgress.isNotBlank()) {
        Surface(
            color = if (hotfixDone) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(12.dp).clickable {
                if (hotfixDone && hotfixProgress.contains("重启")) {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }, verticalAlignment = Alignment.CenterVertically) {
                if (!hotfixDone) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.CheckCircle, null, tint = androidx.compose.ui.graphics.Color(0xFF34C759), modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(hotfixProgress, style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (hotfixDone) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f))
                if (hotfixDone) {
                    Text("点击重启 →", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (showPermGrant) {
        PermissionGrantScreen(
            ctx = ctx,
            onDone = { showPermGrant = false },
            onSkip = { showPermGrant = false }
        )
    }

    // BackHandler — 系统返回键统一处理
    //   优先级：弹窗 > 抽屉 > 路由栈 pop > "再次点击退出" > 回桌面
    var backPressTime by remember { mutableStateOf(0L) }
    androidx.activity.compose.BackHandler(enabled = true) {
        when {
            showRootDialog -> showRootDialog = false
            showSetup -> showSetup = false
            showHand -> showHand = false
            showAbout -> showAbout = false
            showAllSessions -> showAllSessions = false
            drawerState.isOpen -> scope.launch { drawerState.close() }
            !pop() -> {
                // 已在根 (chat)
                val now = System.currentTimeMillis()
                if (now - backPressTime < 2000) {
                    (ctx as? android.app.Activity)?.moveTaskToBack(true)
                } else {
                    backPressTime = now
                    android.widget.Toast.makeText(ctx, "再次点击退出程序", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            else -> { /* pop 成功 */ }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = (route == "chat"),
        drawerContent = {
            ChatListDrawer(
                agent = agent,
                vm = chatVM,
                onOpen = { sid -> chatVM.openSession(sid); scope.launch { drawerState.close() } },
                onSettings = {
                    scope.launch { drawerState.close() }
                    push("settings")
                },
            )
        }
    ) {
        when (route) {
            "chat" -> ChatPage(
                vm = chatVM,
                agent = agent,
                onMenuClick = { scope.launch { drawerState.open() } },
                onInfoClick = { showAbout = true },
            )
            "settings" -> SettingsPage(
                agent = agent,
                onBack = { pop() },
                onSetupProvider = { showSetup = true },
                onOpenHand = { showHand = true },
                onOpenTools = { push("tools") },
                onOpenSessions = { showAllSessions = true },
                onOpenCommunity = { push("community") },
            )
            "tools" -> {
                Scaffold(topBar = { TopAppBar(title = { Text("工具市场") }, navigationIcon = { IconButton(onClick = { pop() }) { Icon(Icons.Filled.ArrowBack, "返回") } }) }) { p -> Box(Modifier.padding(p)) { ToolsScreen() } }
            }
            "community" -> CommunityScreen(onBack = { pop() })
        }
    }

    if (showSetup) ProviderSetupScreen(settings = agent.settings, onDone = { showSetup = false })
    if (showHand) AgentHandScreen(onBack = { showHand = false })
    if (showAbout) AboutDialog(ctx = ctx, onDismiss = { showAbout = false })
    if (showNotice) NoticeDialog(ctx = ctx, onDismiss = { showNotice = false })
    if (showAllSessions) AllSessionsSheet(
        agent = agent,
        currentSid = chatVM.sessionId.value,
        onDismiss = { showAllSessions = false },
        onOpen = { sid ->
            chatVM.openSession(sid)
            showAllSessions = false
            // 关回到 chat 页
            routeStack.clear()
            routeStack.add("chat")
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatPage(
    vm: ChatViewModel,
    agent: MBclawAgent,
    onMenuClick: () -> Unit,
    onInfoClick: () -> Unit,
) {
    var showAssistants by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("MBclaw", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick,
                        modifier = Modifier.padding(8.dp)) {
                        Surface(shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Menu, "聊天列表",
                                     modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.newSession() }) {
                        Icon(Icons.Filled.Add, "新对话")
                    }
                    // 右滑打开助手，用文字提示替代原来的 🦊 按钮
                    Text("← 右滑助手", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline,
                         modifier = Modifier.padding(end = 8.dp))
                    IconButton(onClick = onInfoClick) {
                        Surface(shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Info, "关于",
                                     modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount ->
                            // ★ v5.5.x: 仅限右滑触发 (从右边缘向左滑 ≥200px，防误触)
                            if (dragAmount < -200) {
                                showAssistants = true
                            }
                        }
                    )
                }
        ) {
            ChatScreen(vm)
        }
    }

    if (showAssistants) {
        AssistantsSheet(
            currentId = vm.currentAssistantId.value,
            onPick = { id ->
                vm.switchAssistant(id)
                showAssistants = false
            },
            onDismiss = { showAssistants = false },
        )
    }
}

/** 助手选择 Sheet (仿 MiClaw 魔改版聊天列表) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantsSheet(
    currentId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text("选择助手", fontWeight = FontWeight.Bold,
                 style = MaterialTheme.typography.titleLarge)
            Text("不同助手有不同性格 · 记忆通用",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                       verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(com.mbclaw.root.data.AssistantCatalog.ALL) { a ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(a.id) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = if (a.id == currentId)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(a.emoji, style = MaterialTheme.typography.titleLarge)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.name, fontWeight = FontWeight.SemiBold)
                                Text(a.systemPrompt.take(40),
                                     style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.outline,
                                     maxLines = 1)
                            }
                            if (a.id == currentId) {
                                Icon(Icons.Filled.Check, "当前",
                                     tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ChatListDrawer(
    agent: MBclawAgent,
    vm: ChatViewModel,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
) {
    var sessions by remember { mutableStateOf(listOf<com.mbclaw.root.data.SessionRow>()) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var trigger by remember { mutableStateOf(0) }
    LaunchedEffect(trigger) {
        sessions = try { agent.db.getSessions() } catch (_: Exception) { emptyList() }
    }
    val ctx = LocalContext.current

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(0.82f),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 标题
            Row(
                Modifier.fillMaxWidth().padding(20.dp, 24.dp, 20.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("聊天列表",
                     style = MaterialTheme.typography.headlineSmall,
                     fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    vm.newSession()
                    trigger++
                }) { Icon(Icons.Filled.AddCircleOutline, "新对话") }
            }
            // 列表
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(sessions) { row ->
                    val selected = row.id == vm.sessionId.value
                    SessionRowCard(
                        title = row.title ?: "新对话",
                        timestamp = row.updatedAt,
                        selected = selected,
                        onClick = { onOpen(row.id) },
                        onLongClick = { confirmDelete = row.id },
                    )
                }
                if (sessions.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(40.dp),
                            contentAlignment = Alignment.Center) {
                            Text("暂无对话，点击右上 + 新建",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            HorizontalDivider()
            // 底部：添加助手 + 设置
            DrawerBottomItem(Icons.Filled.PersonAdd, "添加助手") {
                android.widget.Toast.makeText(ctx, "助手系统下版本开放", android.widget.Toast.LENGTH_SHORT).show()
            }
            DrawerBottomItem(Icons.Filled.Settings, "设置") { onSettings() }
            Spacer(Modifier.height(8.dp))
        }
    }

    confirmDelete?.let { sid ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Filled.DeleteForever, null,
                          tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除这条对话?") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteSession(sid)
                        confirmDelete = null
                        trigger++
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRowCard(
    title: String,
    timestamp: Long,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interaction,
                indication = indication,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(50),
                    color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("💬", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold,
                     maxLines = 1,
                     overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                     style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Text(formatTime(timestamp),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun DrawerBottomItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(20.dp, 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, null,
             tint = MaterialTheme.colorScheme.outline)
    }
}

private fun formatTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86400_000L -> "${diff / 3600_000L} 小时前"
        else -> java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(ts))
    }
}

@Composable
private fun AboutDialog(ctx: android.content.Context, onDismiss: () -> Unit) {
    var showQrSheet by remember { mutableStateOf(false) }
    val logoBmp = remember {
        try { ctx.assets.open("donate/logo.png").use { android.graphics.BitmapFactory.decodeStream(it) } }
        catch (_: Exception) { null }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            if (logoBmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = logoBmp.asImageBitmap(),
                    contentDescription = "MBclaw",
                    modifier = Modifier.size(56.dp),
                )
            } else {
                Surface(shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("M", color = androidx.compose.ui.graphics.Color.White,
                             fontWeight = FontWeight.Bold,
                             style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        },
        title = { Text("MBclaw", textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                       modifier = Modifier.fillMaxWidth()) },
        text = {
            Column {
                Text("你的全生态 AI 助手",
                     textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                     modifier = Modifier.fillMaxWidth(),
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                // MBclaw 行 → 检测更新 (任务 3)
                MBclawVersionRow(ctx)
                // 任务 10: 显示 QQ 号
                AboutRow("作者 QQ", "1973054239 (点击复制)") {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("MBclaw QQ", "1973054239"))
                    android.widget.Toast.makeText(ctx, "已复制 QQ：1973054239",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
                // 酷安主页
                AboutRow("酷安", "coolapk.com/u/26771405 · 关注作者") {
                    val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.coolapk.com/u/26771405"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                }
                // 友情赞助
                AboutRow("💖 友情赞助", "请作者喝杯奶茶") { showQrSheet = true }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    if (showQrSheet) DonateQRSheet(onDismiss = { showQrSheet = false })
}

@Composable
private fun AboutRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                 fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DonateQRSheet(onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("💖 友情赞助", textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                       modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("感谢支持，请扫码赞赏 ✨",
                     textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QrFromAsset(ctx, "donate/wechat.png", "微信",
                        androidx.compose.ui.graphics.Color(0xFF07C160))
                    QrFromAsset(ctx, "donate/alipay.jpg", "支付宝",
                        androidx.compose.ui.graphics.Color(0xFF1677FF))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun QrFromAsset(ctx: android.content.Context, path: String, label: String, tint: androidx.compose.ui.graphics.Color) {
    val bmp = remember(path) {
        try {
            ctx.assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.size(140.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = label,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                } else {
                    Icon(Icons.Filled.QrCode2, label, modifier = Modifier.size(64.dp), tint = tint)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = tint, fontWeight = FontWeight.SemiBold)
    }
}

/** 任务 7: 全部会话搜索浮层 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AllSessionsSheet(
    agent: MBclawAgent,
    currentSid: String,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    var sessions by remember { mutableStateOf(listOf<com.mbclaw.root.data.SessionRow>()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        sessions = try { agent.db.getSessions() } catch (_: Exception) { emptyList() }
    }
    val filtered = if (query.isBlank()) sessions
                   else sessions.filter { (it.title ?: "").contains(query, true) || it.id.contains(query, true) }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(16.dp, 4.dp, 16.dp, 16.dp).fillMaxHeight(0.85f)) {
            Text("全部会话", fontWeight = FontWeight.Bold,
                 style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索标题或会话 ID") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            Text("${filtered.size} / ${sessions.size}",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered) { row ->
                    SessionRowCard(
                        title = row.title ?: "新对话",
                        timestamp = row.updatedAt,
                        selected = row.id == currentSid,
                        onClick = { onOpen(row.id) },
                        onLongClick = {},
                    )
                }
            }
        }
    }
}

/** MBclaw 版本行 — 显示当前 + 最新, 点击检测/下载 */
@Composable
private fun MBclawVersionRow(ctx: android.content.Context) {
    var latest by remember { mutableStateOf("检测中...") }
    var hasUpdate by remember { mutableStateOf(false) }
    var downloadUrl by remember { mutableStateOf("") }
    val current = BuildConfig.VERSION_NAME

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val backend = com.mbclaw.root.data.Endpoints.backend(ctx)
                // 加时间戳防缓存
                val ts = System.currentTimeMillis()
                val u = java.net.URL("${backend.trimEnd('/')}/admin/client/version?current=$current&_t=$ts")
                val conn = u.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.setRequestProperty("Cache-Control", "no-cache, no-store")
                conn.setRequestProperty("Pragma", "no-cache")
                val txt = conn.inputStream.bufferedReader().readText()
                val j = org.json.JSONObject(txt)
                val ver = j.optString("latest", current)
                val hasNew = j.optBoolean("has_update", false)
                val du = j.optString("download_url", "")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    latest = ver; hasUpdate = hasNew; downloadUrl = du
                }
            } catch (_: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    latest = "无法连接服务器"
                }
            }
        }
    }

    Row(
        Modifier.fillMaxWidth().clickable {
            // 点击 = 跳下载页, 带时间戳防系统下载器缓存
            val rawUrl = if (downloadUrl.startsWith("http")) downloadUrl
                      else com.mbclaw.root.data.Endpoints.download(ctx)
            val url = if (rawUrl.contains("?")) "$rawUrl&_t=${System.currentTimeMillis()}"
                      else "$rawUrl?_t=${System.currentTimeMillis()}"
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MBclaw", style = MaterialTheme.typography.bodyMedium,
                     fontWeight = FontWeight.SemiBold)
                if (hasUpdate) {
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.errorContainer) {
                        Text("有更新",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Text("当前 v$current → 最新 $latest", style = MaterialTheme.typography.labelSmall,
                 color = if (hasUpdate) MaterialTheme.colorScheme.error
                         else MaterialTheme.colorScheme.outline)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}
