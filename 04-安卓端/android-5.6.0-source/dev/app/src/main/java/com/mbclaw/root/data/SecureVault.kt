package com.mbclaw.dev.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SecureVault — 隐私文件夹（账号/密码/Key 等）
 *
 * 设计：
 *  • 加密算法: AES-256-GCM (认证加密)
 *  • 密钥派生: 基于设备不变信息 (ANDROID_ID + Build.SERIAL/FINGERPRINT + 包名)
 *    → 同一设备/同一应用稳定，换设备/重装即失效
 *  • 存储位置: app 私有目录 /data/data/<pkg>/files/vault/
 *  • 文件命名: hash(label) → 不暴露明文 key 名
 *
 * 用途：
 *  • API Key 存储 (不走 SharedPreferences)
 *  • 用户密码 / Token
 *  • 任何敏感字符串
 *
 * 注意：root 用户依然能强行读取设备指纹，但需要主动逆向，比明文强很多
 */
object SecureVault {

    private const val ALG = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun vaultDir(ctx: Context): File =
        File(ctx.filesDir, "vault").also { it.mkdirs() }

    /** 设备指纹密钥（256-bit） */
    private fun deviceKey(ctx: Context): ByteArray {
        @Suppress("DEPRECATION", "HardwareIds")
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val serial = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else Build.SERIAL
        } catch (_: Exception) { Build.UNKNOWN }
        val mix = "${ctx.packageName}|$androidId|$serial|${Build.FINGERPRINT}|MBclaw-Vault-v1"
        return MessageDigest.getInstance("SHA-256").digest(mix.toByteArray())
    }

    private fun fileFor(ctx: Context, label: String): File {
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(label.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(40)
        return File(vaultDir(ctx), sha)
    }

    /** 写入加密 (返回是否成功) */
    fun put(ctx: Context, label: String, plaintext: String): Boolean = try {
        val key = SecretKeySpec(deviceKey(ctx), "AES")
        val iv = ByteArray(IV_LEN).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALG)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val enc = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // file = [iv (12B)] + [ciphertext + tag]
        fileFor(ctx, label).writeBytes(iv + enc)
        true
    } catch (_: Exception) { false }

    /** 读取解密 (找不到/损坏返回 null) */
    fun get(ctx: Context, label: String): String? = try {
        val f = fileFor(ctx, label)
        if (!f.exists()) null
        else {
            val all = f.readBytes()
            if (all.size <= IV_LEN) null
            else {
                val iv = all.copyOfRange(0, IV_LEN)
                val ct = all.copyOfRange(IV_LEN, all.size)
                val cipher = Cipher.getInstance(ALG)
                cipher.init(Cipher.DECRYPT_MODE,
                    SecretKeySpec(deviceKey(ctx), "AES"),
                    GCMParameterSpec(TAG_BITS, iv))
                String(cipher.doFinal(ct), Charsets.UTF_8)
            }
        }
    } catch (_: Exception) { null }

    /** 删除 */
    fun remove(ctx: Context, label: String): Boolean = try {
        fileFor(ctx, label).delete()
    } catch (_: Exception) { false }

    /** 列出已存储 label 数量（不暴露具体 label，因已 hash） */
    fun count(ctx: Context): Int = vaultDir(ctx).listFiles()?.size ?: 0

    /** 清空整个 vault */
    fun nuke(ctx: Context) {
        vaultDir(ctx).listFiles()?.forEach { it.delete() }
    }
}
