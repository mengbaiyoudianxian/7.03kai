package com.mbclaw.dev.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具注册表 — OpenAI function calling 格式
 *
 * MBclaw 能执行的 31 个真实手机操作
 * 每个工具都有 name/description/parameters schema
 */
object ToolRegistry {

    data class ToolDef(val name: String, val description: String, val parameters: JSONObject)

    val ALL: List<ToolDef> = listOf(
        // ═══ 设备控制 ═══
        ToolDef("toggle_wifi", "打开或关闭WiFi", JSONObject("""{"type":"object","properties":{"enable":{"type":"boolean","description":"true打开,false关闭"}},"required":["enable"]}""")),
        ToolDef("toggle_bluetooth", "打开或关闭蓝牙", JSONObject("""{"type":"object","properties":{"enable":{"type":"boolean"}},"required":["enable"]}""")),
        ToolDef("toggle_flashlight", "打开或关闭手电筒", JSONObject("""{"type":"object","properties":{"enable":{"type":"boolean"}},"required":["enable"]}""")),
        ToolDef("toggle_airplane_mode", "打开或关闭飞行模式", JSONObject("""{"type":"object","properties":{"enable":{"type":"boolean"}},"required":["enable"]}""")),
        ToolDef("set_brightness", "设置屏幕亮度(0-255)", JSONObject("""{"type":"object","properties":{"level":{"type":"integer","minimum":0,"maximum":255}},"required":["level"]}""")),
        ToolDef("set_volume", "设置音量(media/ring/alarm)", JSONObject("""{"type":"object","properties":{"type":{"type":"string","enum":["media","ring","alarm"]},"level":{"type":"integer","minimum":0,"maximum":15}},"required":["type","level"]}""")),
        ToolDef("get_battery", "获取电池电量和状态", JSONObject("""{"type":"object","properties":{}}""")),

        // ═══ 通信 ═══
        ToolDef("send_sms", "发送短信", JSONObject("""{"type":"object","properties":{"phone":{"type":"string","description":"电话号码"},"message":{"type":"string","description":"短信内容"}},"required":["phone","message"]}""")),
        ToolDef("read_sms", "读取最近短信", JSONObject("""{"type":"object","properties":{"limit":{"type":"integer","default":10}},"required":[]}""")),
        ToolDef("make_call", "拨打电话", JSONObject("""{"type":"object","properties":{"phone":{"type":"string"}},"required":["phone"]}""")),

        // ═══ 屏幕操作 ═══
        ToolDef("take_screenshot", "截取当前屏幕并返回base64", JSONObject("""{"type":"object","properties":{}}""")),
        ToolDef("screen_record", "开始录屏(秒)", JSONObject("""{"type":"object","properties":{"duration":{"type":"integer","default":10}},"required":[]}""")),
        ToolDef("click_at", "在屏幕坐标(x,y)点击 (归一化0-1000)", JSONObject("""{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"description":{"type":"string","description":"操作描述用于记忆"}},"required":["x","y"]}""")),
        ToolDef("long_press_at", "在屏幕坐标长按", JSONObject("""{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"duration_ms":{"type":"integer","default":800}},"required":["x","y"]}""")),
        ToolDef("swipe", "滑动屏幕", JSONObject("""{"type":"object","properties":{"x1":{"type":"integer"},"y1":{"type":"integer"},"x2":{"type":"integer"},"y2":{"type":"integer"},"duration_ms":{"type":"integer","default":300}},"required":["x1","y1","x2","y2"]}""")),
        ToolDef("input_text", "在当前焦点输入框输入文本", JSONObject("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""")),
        ToolDef("press_key", "按系统按键(BACK/HOME/RECENTS/ENTER/DELETE)", JSONObject("""{"type":"object","properties":{"key":{"type":"string","enum":["BACK","HOME","RECENTS","ENTER","DELETE","VOLUME_UP","VOLUME_DOWN","POWER"]}},"required":["key"]}""")),

        // ═══ App管理 ═══
        ToolDef("open_app", "打开指定App(包名)", JSONObject("""{"type":"object","properties":{"package_name":{"type":"string"}},"required":["package_name"]}""")),
        ToolDef("list_apps", "列出已安装的App", JSONObject("""{"type":"object","properties":{"filter":{"type":"string","description":"搜索关键词"}},"required":[]}""")),
        ToolDef("uninstall_app", "卸载App(需要Shizuku)", JSONObject("""{"type":"object","properties":{"package_name":{"type":"string"}},"required":["package_name"]}""")),
        ToolDef("force_stop_app", "强制停止App", JSONObject("""{"type":"object","properties":{"package_name":{"type":"string"}},"required":["package_name"]}""")),

        // ═══ 系统信息 ═══
        ToolDef("get_system_info", "获取设备信息(型号/系统版本/屏幕/内存)", JSONObject("""{"type":"object","properties":{}}""")),
        ToolDef("get_clipboard", "读取剪贴板内容", JSONObject("""{"type":"object","properties":{}}""")),
        ToolDef("set_clipboard", "设置剪贴板内容", JSONObject("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""")),
        ToolDef("get_notifications", "获取当前通知栏所有通知", JSONObject("""{"type":"object","properties":{"limit":{"type":"integer","default":10}},"required":[]}""")),

        // ═══ MBclaw内部 ═══
        ToolDef("search_memory", "搜索MBclaw本地记忆库", JSONObject("""{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer","default":5}},"required":["query"]}""")),
        ToolDef("dream_memory", "触发MBclaw梦想整合(总结近期对话)", JSONObject("""{"type":"object","properties":{"session_id":{"type":"string"}},"required":[]}""")),
        ToolDef("classify_conversation", "对当前对话进行语义分类", JSONObject("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""")),
        ToolDef("dual_key_review", "用双Key评审一段内容", JSONObject("""{"type":"object","properties":{"content":{"type":"string"}},"required":["content"]}""")),
        ToolDef("collision_think", "思维碰撞产生创新点子", JSONObject("""{"type":"object","properties":{"keywords":{"type":"array","items":{"type":"string"}}},"required":["keywords"]}""")),
        ToolDef("get_capability", "获取当前MBclaw能力级别(40%/100%)", JSONObject("""{"type":"object","properties":{}}""")),
        ToolDef("read_file", "读取本地文件内容", JSONObject("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")),

        // ═══════════════════════════════════════════════════════════════════
        // 仿 MiClaw 工具集（命名/参数与小米 MiClaw 对齐，方便 LLM 复用其训练偏好）
        // ═══════════════════════════════════════════════════════════════════

        // — WiFi 完整 (MiClaw 兼容命名) —
        ToolDef("list_wifi_networks", "扫描附近 WiFi，返回 SSID 和信号强度。", JSONObject("""{"type":"object","properties":{"timeout_ms":{"type":"integer","default":8000}},"required":[]}""")),
        ToolDef("connect_wifi", "连接指定 WiFi（精确 SSID 或模糊词）。未提供密码先尝试连接已保存网络。", JSONObject("""{"type":"object","properties":{"ssid":{"type":"string"},"password":{"type":"string"}},"required":["ssid"]}""")),
        ToolDef("disconnect_wifi", "断开当前 WiFi 连接。", JSONObject("""{"type":"object","properties":{}}""")),
        ToolDef("switch_wifi", "自动切到信号最强的已保存 WiFi。", JSONObject("""{"type":"object","properties":{}}""")),
        ToolDef("wifi_info", "查询当前 WiFi 连接信息（SSID、信号、IP、速度、频段）。", JSONObject("""{"type":"object","properties":{}}""")),

        // — 蓝牙完整 —
        ToolDef("bluetooth_scan", "扫描附近蓝牙设备（Classic + BLE），返回名称/类型/RSSI/MAC。", JSONObject("""{"type":"object","properties":{"scan_duration":{"type":"integer","default":10}},"required":[]}""")),
        ToolDef("bluetooth_connect", "连接蓝牙设备（device_address MAC）。未配对自动配对。", JSONObject("""{"type":"object","properties":{"device_address":{"type":"string"}},"required":["device_address"]}""")),
        ToolDef("bluetooth_disconnect", "断开指定蓝牙设备。", JSONObject("""{"type":"object","properties":{"device_address":{"type":"string"}},"required":["device_address"]}""")),
        ToolDef("bluetooth_paired_devices", "列出所有已配对蓝牙设备。", JSONObject("""{"type":"object","properties":{}}""")),
        ToolDef("bluetooth_status", "查询蓝牙状态（开关/本机名/已连接/电量）。", JSONObject("""{"type":"object","properties":{}}""")),

        // — 联系人 —
        ToolDef("manage_contacts", "管理联系人 create/update/delete。", JSONObject("""{"type":"object","properties":{"action":{"type":"string","enum":["create","update","delete"]},"contact_id":{"type":"string"},"name":{"type":"string"},"phone":{"type":"string"},"email":{"type":"string"}},"required":["action"]}""")),
        ToolDef("search_contacts", "按姓名或电话号搜索联系人。", JSONObject("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""")),

        // — 位置 / 设备 —
        ToolDef("get_location", "获取设备当前位置（经纬度、城市、地址）。", JSONObject("""{"type":"object","properties":{}}""")),
        ToolDef("device_status", "设备状态: 电池/充电/存储/网络/亮度等。", JSONObject("""{"type":"object","properties":{}}""")),
        ToolDef("send_notification", "向用户发送 Android 通知。", JSONObject("""{"type":"object","properties":{"title":{"type":"string"},"content":{"type":"string"}},"required":["title","content"]}""")),
        ToolDef("send_intent", "发送 Android Intent (Activity/Broadcast/Service)。", JSONObject("""{"type":"object","properties":{"action":{"type":"string"},"data":{"type":"string"},"target":{"type":"string","enum":["activity","broadcast","service"],"default":"activity"}},"required":["action"]}""")),

        // — 文件系统完整 (root 直读) —
        ToolDef("write_file", "写文件（创建/覆盖）。父目录自动创建。", JSONObject("""{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""")),
        ToolDef("append_file", "追加内容到文件末尾。", JSONObject("""{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""")),
        ToolDef("edit_file", "替换文件中 old_text → new_text。", JSONObject("""{"type":"object","properties":{"path":{"type":"string"},"old_text":{"type":"string"},"new_text":{"type":"string"}},"required":["path","old_text","new_text"]}""")),
        ToolDef("delete_file", "删除文件或目录。confirm=true 跳过确认。", JSONObject("""{"type":"object","properties":{"path":{"type":"string"},"confirm":{"type":"boolean","default":false}},"required":["path"]}""")),
        ToolDef("copy_file", "拷贝文件或目录。", JSONObject("""{"type":"object","properties":{"src":{"type":"string"},"dst":{"type":"string"}},"required":["src","dst"]}""")),
        ToolDef("move_file", "移动或重命名。", JSONObject("""{"type":"object","properties":{"src":{"type":"string"},"dst":{"type":"string"}},"required":["src","dst"]}""")),
        ToolDef("list_files", "列出目录文件。", JSONObject("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")),
        ToolDef("search_files", "按文件名/类型搜索文件。", JSONObject("""{"type":"object","properties":{"path":{"type":"string"},"pattern":{"type":"string"}},"required":["path","pattern"]}""")),
        ToolDef("file_grep", "在文件内搜索匹配行（plain/regex）。", JSONObject("""{"type":"object","properties":{"path":{"type":"string"},"pattern":{"type":"string"}},"required":["path","pattern"]}""")),
        ToolDef("file_info", "获取文件元信息（大小/权限/mtime）。", JSONObject("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")),

        // — 浏览器（后台无界面）—
        ToolDef("browser_open", "后台打开无界面浏览器并加载 URL。", JSONObject("""{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}""")),
        ToolDef("browser_extract", "提取浏览器页面正文（Readability）。", JSONObject("""{"type":"object","properties":{}}""")),
        ToolDef("browser_click", "点击页面元素（index / selector）。", JSONObject("""{"type":"object","properties":{"index":{"type":"integer"},"selector":{"type":"string"}},"required":[]}""")),
        ToolDef("browser_input", "在浏览器输入框输入文本。", JSONObject("""{"type":"object","properties":{"index":{"type":"integer"},"text":{"type":"string"}},"required":["text"]}""")),
        ToolDef("browser_close", "关闭后台浏览器释放资源。", JSONObject("""{"type":"object","properties":{}}""")),

        // — Web / 网络 —
        ToolDef("url_fetch", "抓取 URL 返回结构化预览。", JSONObject("""{"type":"object","properties":{"url":{"type":"string"},"detail":{"type":"string","enum":["brief","full"],"default":"brief"}},"required":["url"]}""")),
        ToolDef("web_search", "网络搜索（Bing fallback）。", JSONObject("""{"type":"object","properties":{"query":{"type":"string"},"detail":{"type":"string","enum":["brief","full"],"default":"brief"}},"required":["query"]}""")),

        // — 日历 —
        ToolDef("create_calendar_event", "创建日历事件。", JSONObject("""{"type":"object","properties":{"title":{"type":"string"},"start_time":{"type":"string"},"end_time":{"type":"string"},"location":{"type":"string"}},"required":["title","start_time"]}""")),
        ToolDef("read_calendar", "读取日历事件区间。", JSONObject("""{"type":"object","properties":{"start_time":{"type":"string"},"end_time":{"type":"string"}},"required":[]}""")),
        ToolDef("update_calendar_event", "更新日历事件。", JSONObject("""{"type":"object","properties":{"event_id":{"type":"string"},"title":{"type":"string"},"start_time":{"type":"string"}},"required":["event_id"]}""")),
        ToolDef("delete_calendar_event", "删除日历事件。", JSONObject("""{"type":"object","properties":{"event_id":{"type":"string"}},"required":[]}""")),

        // — 通话 —
        ToolDef("call_log_list", "查询通话记录。", JSONObject("""{"type":"object","properties":{"limit":{"type":"integer","default":20},"phone":{"type":"string"}},"required":[]}""")),
        ToolDef("call_log_delete", "删除指定通话记录条目。", JSONObject("""{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}""")),
        ToolDef("hangup_phone", "强制挂断当前通话。", JSONObject("""{"type":"object","properties":{}}""")),
        ToolDef("dial_phone", "拨打电话（等同 make_call，MiClaw 兼容名）。", JSONObject("""{"type":"object","properties":{"phone":{"type":"string"}},"required":["phone"]}""")),

        // — 媒体 —
        ToolDef("camera", "拍照或录像。", JSONObject("""{"type":"object","properties":{"action":{"type":"string","enum":["photo","video"]},"facing":{"type":"string","enum":["back","front"],"default":"back"}},"required":["action"]}""")),
        ToolDef("control_media", "控制媒体播放: play/pause/toggle/next/prev/seek/volume.", JSONObject("""{"type":"object","properties":{"action":{"type":"string","enum":["play","pause","toggle","next","previous","forward","rewind","volume_up","volume_down"]}},"required":["action"]}""")),
        ToolDef("get_media_info", "当前媒体播放信息（歌曲/歌手/进度）。", JSONObject("""{"type":"object","properties":{}}""")),
        ToolDef("list_media_images", "查询相册图片元数据。", JSONObject("""{"type":"object","properties":{"limit":{"type":"integer","default":20}},"required":[]}""")),
        ToolDef("media_store", "媒体库 query / get_info / delete。", JSONObject("""{"type":"object","properties":{"action":{"type":"string","enum":["query","get_info","delete"]},"id":{"type":"string"}},"required":["action"]}""")),

        // — 时间 / 工具 —
        ToolDef("get_current_time", "获取当前时间（多种格式）。", JSONObject("""{"type":"object","properties":{"format":{"type":"string","default":"iso"}},"required":[]}""")),
        ToolDef("check_permissions", "检查 App 权限授予状态。", JSONObject("""{"type":"object","properties":{}}""")),
        ToolDef("timer", "定时执行 AI 任务（延后/cron）。", JSONObject("""{"type":"object","properties":{"after_seconds":{"type":"integer"},"task":{"type":"string"}},"required":["after_seconds","task"]}""")),
        ToolDef("get_weather", "获取天气与预报。", JSONObject("""{"type":"object","properties":{"city":{"type":"string"}},"required":[]}""")),

        // — App 增强 —
        ToolDef("app_manager", "应用管理（详情/启用/禁用/卸载）。", JSONObject("""{"type":"object","properties":{"action":{"type":"string","enum":["info","enable","disable","uninstall"]},"package_name":{"type":"string"}},"required":["action","package_name"]}""")),
        ToolDef("app_shortcut", "搜索/执行 App 快捷方式。", JSONObject("""{"type":"object","properties":{"action":{"type":"string","enum":["search","execute"]},"query":{"type":"string"},"shortcut_id":{"type":"string"}},"required":["action"]}""")),
        ToolDef("app_usage", "查询应用使用时长统计。", JSONObject("""{"type":"object","properties":{"days":{"type":"integer","default":7}},"required":[]}""")),

        // — 历史 / 技能（MBclaw 长期记忆延展）—
        ToolDef("search_history", "按关键词搜索历史聊天消息。", JSONObject("""{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer","default":10}},"required":["query"]}""")),
        ToolDef("load_message", "按 ID 加载历史消息完整内容（可带前后上下文）。", JSONObject("""{"type":"object","properties":{"message_id":{"type":"string"},"context":{"type":"integer","default":0}},"required":["message_id"]}""")),

        // — 本地沙箱 (root 直接 shell) —
        ToolDef("local_sandbox_run", "在本地执行 Python/Shell 脚本（root 权限）。", JSONObject("""{"type":"object","properties":{"lang":{"type":"string","enum":["python","shell"]},"code":{"type":"string"},"timeout_ms":{"type":"integer","default":15000}},"required":["lang","code"]}""")),

        // — 子 Agent —
        ToolDef("list_agents", "列出可用子 Agent。", JSONObject("""{"type":"object","properties":{}}""")),
        ToolDef("start_agent", "启动子 Agent 进入专用会话。", JSONObject("""{"type":"object","properties":{"agent_id":{"type":"string"},"message":{"type":"string"}},"required":["agent_id"]}""")),

        // ★★★ 视觉工具 (智能手核心) ★★★
        ToolDef("see_screen", "看当前屏幕。返回所有可交互元素列表 (含文字+位置+索引)。必须先调这个再点。", JSONObject("""{"type":"object","properties":{}}""")),
        ToolDef("click_by_index", "按 see_screen 返回的索引点击元素。比 click_at 准。", JSONObject("""{"type":"object","properties":{"index":{"type":"integer","description":"see_screen 输出的 [N]"}},"required":["index"]}""")),
        ToolDef("input_by_index", "在指定索引的输入框输入文字 (自动先聚焦)。", JSONObject("""{"type":"object","properties":{"index":{"type":"integer"},"text":{"type":"string"}},"required":["index","text"]}""")),
        ToolDef("find_by_text", "在屏幕中按文字搜索元素 (模糊匹配)。", JSONObject("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""")),
        ToolDef("wait_screen", "等待 N 毫秒后重新看屏幕 (用于等界面加载/跳转)。", JSONObject("""{"type":"object","properties":{"ms":{"type":"integer","default":1500}},"required":[]}""")),
        // ★ v4.7 新增: VLM 视觉定位通道
        ToolDef("vision_locate", "视觉模型看图定位。把截图发给AI视觉模型，直接返回目标坐标。当see_screen返回空或找不到目标时使用。", JSONObject("""{"type":"object","properties":{"description":{"type":"string","description":"描述你要找什么，如'点击发送按钮'"}},"required":["description"]}""")),
    )

    /** 生成OpenAI function calling格式的tools数组 */
    fun toOpenAITools(): JSONArray {
        val arr = JSONArray()
        for (tool in ALL) {
            arr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                })
            })
        }
        return arr
    }

    fun find(name: String): ToolDef? = ALL.find { it.name == name }
}
