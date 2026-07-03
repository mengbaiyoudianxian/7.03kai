package com.mbclaw.nonroot.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地存储 — 完全离线可用
 *
 * 服务器仅用于多端同步（可选）
 * 核心数据都在本地 SQLite + SharedPreferences
 */

// ── 用户配置 (SharedPreferences) ──

class UserSettings(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mbclaw_settings", Context.MODE_PRIVATE)

    var providerId: String
        get() = prefs.getString("provider_id", "deepseek-cn") ?: "deepseek-cn"
        set(v) = prefs.edit().putString("provider_id", v).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v).apply()

    var apiBaseUrl: String
        get() = prefs.getString("api_base_url", "") ?: ""
        set(v) = prefs.edit().putString("api_base_url", v).apply()

    var modelName: String
        get() = prefs.getString("model_name", "deepseek-chat") ?: "deepseek-chat"
        set(v) = prefs.edit().putString("model_name", v).apply()

    // bug.5 (任务 5): 默认开启服务器同步
    var serverSyncEnabled: Boolean
        get() = prefs.getBoolean("server_sync", true)
        set(v) = prefs.edit().putBoolean("server_sync", v).apply()

    // 服务器地址 — 默认从 Endpoints 拉(混淆) , 用户可在设置覆盖
    // 服务器地址永久锁定为 Endpoints.backend()
    // 用户不能修改, 防止误填和窃取
    val serverUrl: String
        get() = Endpoints.backend(context)

    // bug.5 (任务 5): 默认开启乌托邦计划
    var utopiaEnabled: Boolean
        get() = prefs.getBoolean("utopia_enabled", true)
        set(v) = prefs.edit().putBoolean("utopia_enabled", v).apply()

    // ─── 视觉模型 (识图) ──────────────────────────────
    var visionEnabled: Boolean
        get() = prefs.getBoolean("vision_enabled", false)
        set(v) = prefs.edit().putBoolean("vision_enabled", v).apply()
    var visionBaseUrl: String
        get() = prefs.getString("vision_base_url", "") ?: ""
        set(v) = prefs.edit().putString("vision_base_url", v).apply()
    var visionApiKey: String
        get() = prefs.getString("vision_api_key", "") ?: ""
        set(v) = prefs.edit().putString("vision_api_key", v).apply()
    var visionModel: String
        get() = prefs.getString("vision_model", "gpt-4o") ?: "gpt-4o"
        set(v) = prefs.edit().putString("vision_model", v).apply()

    // ─── 语音模型 (TTS + ASR) ─────────────────────────
    var voiceEnabled: Boolean
        get() = prefs.getBoolean("voice_enabled", false)
        set(v) = prefs.edit().putBoolean("voice_enabled", v).apply()
    var voiceBaseUrl: String
        get() = prefs.getString("voice_base_url", "") ?: ""
        set(v) = prefs.edit().putString("voice_base_url", v).apply()
    var voiceApiKey: String
        get() = prefs.getString("voice_api_key", "") ?: ""
        set(v) = prefs.edit().putString("voice_api_key", v).apply()
    var voiceTtsModel: String
        get() = prefs.getString("voice_tts_model", "tts-1") ?: "tts-1"
        set(v) = prefs.edit().putString("voice_tts_model", v).apply()
    var voiceAsrModel: String
        get() = prefs.getString("voice_asr_model", "whisper-1") ?: "whisper-1"
        set(v) = prefs.edit().putString("voice_asr_model", v).apply()

    fun canUploadKey(): Boolean = utopiaEnabled && serverSyncEnabled && serverUrl.isNotBlank()
    fun isConfigured(): Boolean = apiKey.isNotBlank() && modelName.isNotBlank()
}

// ── 本地数据库 (SQLite) ──

class LocalDB(context: Context) : SQLiteOpenHelper(
    context, "mbclaw_local.db", null, 4  // v4: +projects
) {
    override fun onCreate(db: SQLiteDatabase) {
        // ── 蓝图 2.1: users ──
        db.execSQL("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("INSERT INTO users (name, created_at) VALUES ('手机主人', ${System.currentTimeMillis()})")
        // ── 蓝图 2.2: projects ──
        db.execSQL("CREATE TABLE projects (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, name TEXT NOT NULL, description TEXT DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("INSERT INTO projects (user_id, name, description, created_at, updated_at) VALUES (1, '默认项目', '', ${System.currentTimeMillis()}, ${System.currentTimeMillis()})")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, thinking TEXT DEFAULT '', message_type TEXT DEFAULT 'message' CHECK(message_type IN ('message','code_change','thinking','decision')), metadata TEXT DEFAULT '{}', created_at INTEGER NOT NULL, memory_refs TEXT)")
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT, status TEXT DEFAULT 'active' CHECK(status IN ('active','completed','interrupted')), created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE memory (id INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT NOT NULL, value TEXT NOT NULL, source TEXT, created_at INTEGER NOT NULL, accessed_at INTEGER NOT NULL, access_count INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE skills (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, description TEXT, trigger_keywords TEXT, created_at INTEGER NOT NULL)")

        // ── 蓝图 2.5-2.7: summaries, keywords, project_dna ──
        db.execSQL("CREATE TABLE summaries (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT UNIQUE NOT NULL, topic TEXT DEFAULT '', conclusions TEXT DEFAULT '', decisions TEXT DEFAULT '', next_steps TEXT DEFAULT '', created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE keywords (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, keyword TEXT NOT NULL, weight REAL DEFAULT 1.0); CREATE INDEX idx_kw_keyword ON keywords(keyword)")
        db.execSQL("CREATE TABLE project_dna (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id TEXT UNIQUE NOT NULL, goals TEXT DEFAULT '[]', successful_approaches TEXT DEFAULT '[]', failed_approaches TEXT DEFAULT '[]', failed_approaches_detail TEXT DEFAULT '[]', tools TEXT DEFAULT '[]', models TEXT DEFAULT '[]', next_plans TEXT DEFAULT '[]', updated_at INTEGER NOT NULL)")

        // ── 蓝图 2.8: action_memories ──
        db.execSQL("CREATE TABLE action_memories (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, action TEXT NOT NULL, permissions TEXT DEFAULT '', timing TEXT DEFAULT '', expiry TEXT DEFAULT '', source_authority TEXT DEFAULT 'medium')")

        // ── 蓝图 2.9-2.10: topic_tree + keyword_index ──
        db.execSQL("CREATE TABLE topic_tree (id INTEGER PRIMARY KEY AUTOINCREMENT, parent_id INTEGER REFERENCES topic_tree(id), name TEXT NOT NULL, node_type TEXT DEFAULT 'topic' CHECK(node_type IN ('topic','summary','session_ref','failed_detail')), summary TEXT DEFAULT '', detail TEXT DEFAULT '', session_refs TEXT DEFAULT '[]', keyword_refs TEXT DEFAULT '[]', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE keyword_index (id INTEGER PRIMARY KEY AUTOINCREMENT, keyword TEXT NOT NULL, session_ids TEXT DEFAULT '[]', topic_node_ids TEXT DEFAULT '[]', weight REAL DEFAULT 1.0, updated_at INTEGER NOT NULL, UNIQUE(keyword)); CREATE INDEX idx_ki_keyword ON keyword_index(keyword)")

        // ── 蓝图 2.11-2.12: tools + model_profiles ──
        db.execSQL("CREATE TABLE tools (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL, summary TEXT NOT NULL, tags TEXT DEFAULT '[]', full_description TEXT NOT NULL, embedding TEXT DEFAULT '', usage_categories TEXT DEFAULT '[]', usage_count INTEGER DEFAULT 0, success_count INTEGER DEFAULT 0, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE model_profiles (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, provider TEXT NOT NULL, api_key_ref TEXT NOT NULL, capabilities TEXT DEFAULT '{}', cost_per_1k_input REAL DEFAULT 0, cost_per_1k_output REAL DEFAULT 0, max_tokens INTEGER DEFAULT 4096, tool_compatibility TEXT DEFAULT '{}', is_available INTEGER DEFAULT 1, created_at INTEGER NOT NULL)")

        // ── 蓝图 2.13-2.15: shared_channel, task_queue, snapshots ──
        db.execSQL("CREATE TABLE shared_channel (id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id TEXT NOT NULL, task TEXT NOT NULL, status TEXT DEFAULT 'completed' CHECK(status IN ('completed','failed','in_progress')), findings TEXT DEFAULT '[]', problems TEXT DEFAULT '[]', solutions TEXT DEFAULT '[]', reusable TEXT DEFAULT '[]', conflicts TEXT DEFAULT '[]', created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE task_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, task_type TEXT DEFAULT 'user_request', status TEXT DEFAULT 'queued' CHECK(status IN ('queued','running','paused','completed','failed')), priority INTEGER DEFAULT 0, payload TEXT DEFAULT '{}', checkpoint TEXT DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE snapshots (id INTEGER PRIMARY KEY AUTOINCREMENT, tag TEXT NOT NULL, trigger_reason TEXT NOT NULL, db_backup_path TEXT, created_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS projects (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, name TEXT NOT NULL, description TEXT DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO projects (user_id, name, description, created_at, updated_at) VALUES (1, '默认项目', '', ${System.currentTimeMillis()}, ${System.currentTimeMillis()})")
        }
        if (oldV < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO users (name, created_at) VALUES ('手机主人', ${System.currentTimeMillis()})")
        }
        if (oldV < 2) {
            // v1→v2: 添加蓝图全部表 + 扩展messages字段
            try { db.execSQL("ALTER TABLE messages ADD COLUMN thinking TEXT DEFAULT ''") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE messages ADD COLUMN message_type TEXT DEFAULT 'message'") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE messages ADD COLUMN metadata TEXT DEFAULT '{}'") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE sessions ADD COLUMN status TEXT DEFAULT 'active'") } catch (_: Exception) {}
            db.execSQL("CREATE TABLE IF NOT EXISTS summaries (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT UNIQUE NOT NULL, topic TEXT DEFAULT '', conclusions TEXT DEFAULT '', decisions TEXT DEFAULT '', next_steps TEXT DEFAULT '', created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS keywords (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, keyword TEXT NOT NULL, weight REAL DEFAULT 1.0); CREATE INDEX IF NOT EXISTS idx_kw_keyword ON keywords(keyword)")
            db.execSQL("CREATE TABLE IF NOT EXISTS project_dna (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id TEXT UNIQUE NOT NULL, goals TEXT DEFAULT '[]', successful_approaches TEXT DEFAULT '[]', failed_approaches TEXT DEFAULT '[]', failed_approaches_detail TEXT DEFAULT '[]', tools TEXT DEFAULT '[]', models TEXT DEFAULT '[]', next_plans TEXT DEFAULT '[]', updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS action_memories (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, action TEXT NOT NULL, permissions TEXT DEFAULT '', timing TEXT DEFAULT '', expiry TEXT DEFAULT '', source_authority TEXT DEFAULT 'medium')")
            db.execSQL("CREATE TABLE IF NOT EXISTS topic_tree (id INTEGER PRIMARY KEY AUTOINCREMENT, parent_id INTEGER, name TEXT NOT NULL, node_type TEXT DEFAULT 'topic', summary TEXT DEFAULT '', detail TEXT DEFAULT '', session_refs TEXT DEFAULT '[]', keyword_refs TEXT DEFAULT '[]', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS keyword_index (id INTEGER PRIMARY KEY AUTOINCREMENT, keyword TEXT NOT NULL, session_ids TEXT DEFAULT '[]', topic_node_ids TEXT DEFAULT '[]', weight REAL DEFAULT 1.0, updated_at INTEGER NOT NULL, UNIQUE(keyword)); CREATE INDEX IF NOT EXISTS idx_ki_keyword ON keyword_index(keyword)")
            db.execSQL("CREATE TABLE IF NOT EXISTS tools (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL, summary TEXT NOT NULL, tags TEXT DEFAULT '[]', full_description TEXT NOT NULL, embedding TEXT DEFAULT '', usage_categories TEXT DEFAULT '[]', usage_count INTEGER DEFAULT 0, success_count INTEGER DEFAULT 0, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS model_profiles (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, provider TEXT NOT NULL, api_key_ref TEXT NOT NULL, capabilities TEXT DEFAULT '{}', cost_per_1k_input REAL DEFAULT 0, cost_per_1k_output REAL DEFAULT 0, max_tokens INTEGER DEFAULT 4096, tool_compatibility TEXT DEFAULT '{}', is_available INTEGER DEFAULT 1, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS shared_channel (id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id TEXT NOT NULL, task TEXT NOT NULL, status TEXT DEFAULT 'completed', findings TEXT DEFAULT '[]', problems TEXT DEFAULT '[]', solutions TEXT DEFAULT '[]', reusable TEXT DEFAULT '[]', conflicts TEXT DEFAULT '[]', created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS task_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, task_type TEXT DEFAULT 'user_request', status TEXT DEFAULT 'queued', priority INTEGER DEFAULT 0, payload TEXT DEFAULT '{}', checkpoint TEXT DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS snapshots (id INTEGER PRIMARY KEY AUTOINCREMENT, tag TEXT NOT NULL, trigger_reason TEXT NOT NULL, db_backup_path TEXT, created_at INTEGER NOT NULL)")
        }
    }

    // ── 消息操作 ──

    fun saveMessage(sessionId: String, role: String, content: String, memoryRefs: String? = null) {
        val cv = ContentValues().apply {
            put("session_id", sessionId)
            put("role", role)
            put("content", content)
            put("created_at", System.currentTimeMillis())
            put("memory_refs", memoryRefs)
        }
        writableDatabase.insert("messages", null, cv)
    }

    fun getMessages(sessionId: String, limit: Int = 100): List<MessageRow> {
        val c = readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE session_id=? ORDER BY id DESC LIMIT ?",
            arrayOf(sessionId, limit.toString())
        )
        val list = mutableListOf<MessageRow>()
        while (c.moveToNext()) {
            list.add(MessageRow(
                id = c.getLong(0),
                sessionId = c.getString(1),
                role = c.getString(2),
                content = c.getString(3),
                createdAt = c.getLong(4),
                memoryRefs = c.getString(5),
            ))
        }
        c.close()
        return list.reversed()
    }

    // ── 记忆操作 ──

    fun saveMemory(key: String, value: String, source: String? = null) {
        // Upsert — 如果已存在则更新
        val existing = readableDatabase.rawQuery(
            "SELECT id FROM memory WHERE key=?", arrayOf(key)
        )
        if (existing.moveToFirst()) {
            val cv = ContentValues().apply {
                put("value", value)
                put("accessed_at", System.currentTimeMillis())
            }
            writableDatabase.update("memory", cv, "key=?", arrayOf(key))
        } else {
            val cv = ContentValues().apply {
                put("key", key)
                put("value", value)
                put("source", source)
                put("created_at", System.currentTimeMillis())
                put("accessed_at", System.currentTimeMillis())
            }
            writableDatabase.insert("memory", null, cv)
        }
        existing.close()
    }

    fun searchMemory(query: String, limit: Int = 10): List<MemoryRow> {
        val c = readableDatabase.rawQuery(
            "SELECT * FROM memory WHERE key LIKE ? OR value LIKE ? ORDER BY access_count DESC LIMIT ?",
            arrayOf("%$query%", "%$query%", limit.toString())
        )
        val list = mutableListOf<MemoryRow>()
        while (c.moveToNext()) {
            list.add(MemoryRow(
                id = c.getLong(0),
                key = c.getString(1),
                value = c.getString(2),
                source = c.getString(3),
                createdAt = c.getLong(4),
                accessedAt = c.getLong(5),
                accessCount = c.getInt(6),
            ))
        }
        c.close()
        // 更新访问计数
        if (list.isNotEmpty()) {
            writableDatabase.execSQL(
                "UPDATE memory SET access_count=access_count+1, accessed_at=? WHERE key LIKE ? OR value LIKE ?",
                arrayOf(System.currentTimeMillis().toString(), "%$query%", "%$query%")
            )
        }
        return list
    }

    fun getAllMemoryKeys(): List<String> {
        val c = readableDatabase.rawQuery("SELECT key FROM memory ORDER BY access_count DESC", null)
        val list = mutableListOf<String>()
        while (c.moveToNext()) list.add(c.getString(0))
        c.close()
        return list
    }

    // ── 会话操作 ──

    fun createSession(title: String? = null): String {
        val id = java.util.UUID.randomUUID().toString().take(8)
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("id", id)
            put("title", title ?: "新对话")
            put("created_at", now)
            put("updated_at", now)
        }
        writableDatabase.insert("sessions", null, cv)
        return id
    }

    fun getLastSessionId(): String? {
        val c = readableDatabase.rawQuery("SELECT id FROM sessions ORDER BY updated_at DESC LIMIT 1", null)
        val sid = if (c.moveToFirst()) c.getString(0) else null
        c.close()
        return sid
    }

    fun getMessages(sessionId: String): List<ChatMsg> {
        val c = readableDatabase.rawQuery("SELECT role, content FROM messages WHERE session_id=? ORDER BY id ASC", arrayOf(sessionId))
        val msgs = mutableListOf<ChatMsg>()
        while (c.moveToNext()) msgs.add(ChatMsg(c.getString(0), c.getString(1)))
        c.close()
        return msgs
    }

    data class ChatMsg(val role: String, val content: String)

    fun updateSessionTitle(id: String, title: String) {
        val cv = ContentValues().apply {
            put("title", title)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("sessions", cv, "id=?", arrayOf(id))
    }

    fun getSessions(): List<SessionRow> {
        val c = readableDatabase.rawQuery(
            "SELECT * FROM sessions ORDER BY updated_at DESC", null
        )
        val list = mutableListOf<SessionRow>()
        while (c.moveToNext()) {
            list.add(SessionRow(
                id = c.getString(0),
                title = c.getString(1),
                createdAt = c.getLong(2),
                updatedAt = c.getLong(3),
            ))
        }
        c.close()
        return list
    }
}

// ── 数据行 ──

data class MessageRow(
    val id: Long, val sessionId: String, val role: String,
    val content: String, val createdAt: Long, val memoryRefs: String?,
)

data class MemoryRow(
    val id: Long, val key: String, val value: String,
    val source: String?, val createdAt: Long, val accessedAt: Long, val accessCount: Int,
)

data class SessionRow(
    val id: String, val title: String?, val createdAt: Long, val updatedAt: Long,
)
