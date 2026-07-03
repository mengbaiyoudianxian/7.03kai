package com.mbclaw.nonroot.agent

/**
 * PermissionLabels — 权限中文翻译 + 必备性标记
 *
 * 必备 = MBclaw 核心功能依赖, 没有就很多工具失效
 * 可选 = 增强体验
 */
object PermissionLabels {

    data class PermInfo(
        val perm: String,
        val zh: String,
        val desc: String,
        val essential: Boolean = false,
    )

    val ALL = listOf(
        // ★ 必备
        PermInfo("android.permission.READ_EXTERNAL_STORAGE", "读取存储", "读手机文件、相册", true),
        PermInfo("android.permission.WRITE_EXTERNAL_STORAGE", "写入存储", "保存截图、备份", true),
        PermInfo("android.permission.MANAGE_EXTERNAL_STORAGE", "管理所有文件", "完整文件操作", true),
        PermInfo("android.permission.SYSTEM_ALERT_WINDOW", "悬浮窗", "屏幕上层显示", true),
        PermInfo("android.permission.WRITE_SETTINGS", "修改系统设置", "调亮度、铃声", true),
        PermInfo("android.permission.WRITE_SECURE_SETTINGS", "修改安全设置", "切飞行模式等", true),
        PermInfo("android.permission.PACKAGE_USAGE_STATS", "使用情况访问", "看哪个 App 在用", true),
        PermInfo("android.permission.POST_NOTIFICATIONS", "发送通知", "Agent 完成提醒", true),
        PermInfo("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", "通知使用权", "读取通知内容", true),
        PermInfo("android.permission.BIND_ACCESSIBILITY_SERVICE", "无障碍服务", "点击/滑屏自动化", true),

        // 通讯
        PermInfo("android.permission.READ_CONTACTS", "读取联系人", "找联系人"),
        PermInfo("android.permission.WRITE_CONTACTS", "写入联系人", "添加联系人"),
        PermInfo("android.permission.GET_ACCOUNTS", "查看账户", "QQ/微信账号"),
        PermInfo("android.permission.READ_CALL_LOG", "读取通话记录", "查通话历史"),
        PermInfo("android.permission.WRITE_CALL_LOG", "写入通话记录", "删除通话记录"),
        PermInfo("android.permission.READ_PHONE_STATE", "电话状态", "知道是否通话中"),
        PermInfo("android.permission.READ_PHONE_NUMBERS", "读取本机号码", "知道自己手机号"),
        PermInfo("android.permission.CALL_PHONE", "拨打电话", "Agent 帮你拨号"),
        PermInfo("android.permission.ANSWER_PHONE_CALLS", "接听电话", "Agent 接电话"),
        PermInfo("android.permission.PROCESS_OUTGOING_CALLS", "拦截外拨", "处理外拨电话"),
        PermInfo("android.permission.READ_SMS", "读取短信", "查验证码"),
        PermInfo("android.permission.SEND_SMS", "发送短信", "Agent 发短信"),
        PermInfo("android.permission.RECEIVE_SMS", "接收短信", "监听新短信"),
        PermInfo("android.permission.RECEIVE_WAP_PUSH", "WAP 推送", "彩信"),
        PermInfo("android.permission.RECEIVE_MMS", "接收彩信", "图片消息"),
        PermInfo("android.permission.ADD_VOICEMAIL", "添加语音邮件", "语音邮箱"),
        PermInfo("android.permission.USE_SIP", "网络通话", "SIP 协议"),

        // 位置
        PermInfo("android.permission.ACCESS_FINE_LOCATION", "精准定位", "GPS"),
        PermInfo("android.permission.ACCESS_COARSE_LOCATION", "粗略定位", "网络定位"),
        PermInfo("android.permission.ACCESS_BACKGROUND_LOCATION", "后台定位", "锁屏也能定位"),

        // 媒体
        PermInfo("android.permission.READ_MEDIA_IMAGES", "读取图片", "相册"),
        PermInfo("android.permission.READ_MEDIA_VIDEO", "读取视频", "视频"),
        PermInfo("android.permission.READ_MEDIA_AUDIO", "读取音频", "音乐"),
        PermInfo("android.permission.CAMERA", "相机", "拍照"),
        PermInfo("android.permission.RECORD_AUDIO", "录音", "语音输入"),
        PermInfo("android.permission.CAPTURE_AUDIO_OUTPUT", "录系统声音", "录通话内容"),
        PermInfo("android.permission.CAPTURE_VIDEO_OUTPUT", "录视频输出", "录屏"),
        PermInfo("android.permission.READ_FRAME_BUFFER", "读取屏幕缓存", "无声截屏"),
        PermInfo("android.permission.ACCESS_SURFACE_FLINGER", "Surface 访问", "图形系统"),

        // 传感器
        PermInfo("android.permission.BODY_SENSORS", "身体传感器", "心率"),
        PermInfo("android.permission.ACTIVITY_RECOGNITION", "活动识别", "步数"),

        // 日历
        PermInfo("android.permission.READ_CALENDAR", "读取日历", "查日程"),
        PermInfo("android.permission.WRITE_CALENDAR", "写入日历", "建日程"),

        // 蓝牙
        PermInfo("android.permission.BLUETOOTH_SCAN", "蓝牙扫描", "搜附近设备"),
        PermInfo("android.permission.BLUETOOTH_CONNECT", "蓝牙连接", "连耳机/手表"),
        PermInfo("android.permission.BLUETOOTH_ADVERTISE", "蓝牙广播", "被附近发现"),

        // 高级 / 系统级
        PermInfo("android.permission.READ_LOGS", "读取系统日志", "调试用"),
        PermInfo("android.permission.DUMP", "Dumpsys 转储", "深度系统信息"),
        PermInfo("android.permission.MODIFY_PHONE_STATE", "修改电话状态", "切信号"),
        PermInfo("android.permission.CHANGE_CONFIGURATION", "修改设置", "切语言"),
        PermInfo("android.permission.MODIFY_AUDIO_SETTINGS", "修改音频", "调音效"),
        PermInfo("android.permission.MOUNT_UNMOUNT_FILESYSTEMS", "挂载文件系统", "挂磁盘"),
        PermInfo("android.permission.INTERNAL_SYSTEM_WINDOW", "系统级窗口", "顶层显示"),
        PermInfo("android.permission.MANAGE_USERS", "管理用户", "多用户切换"),
        PermInfo("android.permission.INTERACT_ACROSS_USERS_FULL", "跨用户操作", "工作资料"),
        PermInfo("android.permission.REAL_GET_TASKS", "获取真实任务", "前台 App"),
        PermInfo("android.permission.BIND_DEVICE_ADMIN", "设备管理员", "锁屏密码"),
        PermInfo("android.permission.REQUEST_INSTALL_PACKAGES", "请求安装", "装 APK"),
        PermInfo("android.permission.REQUEST_DELETE_PACKAGES", "请求卸载", "卸 APK"),
        PermInfo("android.permission.DELETE_PACKAGES", "卸载应用", "静默卸载"),
        PermInfo("android.permission.INSTALL_PACKAGES", "安装应用", "静默安装"),
        PermInfo("android.permission.FORCE_STOP_PACKAGES", "强制停止", "杀进程"),
    )

    fun get(perm: String): PermInfo =
        ALL.find { it.perm == perm } ?: PermInfo(perm, perm.substringAfterLast('.'), "", false)
}
