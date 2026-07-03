package com.mbclaw.dev.voice

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.mbclaw.dev.MainActivity

/**
 * NonRoot 语音服务 — 无需 DSP 固件
 *
 * 方案:
 *   1. 前台服务 + 通知栏驻留
 *   2. 点击通知 → 打开 MBclaw 并自动开始语音识别
 *   3. 使用 Android SpeechRecognizer (在线/离线)
 *
 * 对比 Root 版:
 *   - Root: DSP 热词常驻监听 (低功耗)
 *   - NonRoot: 手动触发或通知栏快捷入口
 */
class VoiceService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null

    companion object {
        const val CHANNEL_ID = "mbclaw_voice"
        const val NOTIFY_ID = 2002
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "语音服务", NotificationManager.IMPORTANCE_LOW))
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("auto_start_voice", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎤 MBclaw 语音")
            .setContentText("点击打开语音对话")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        startForeground(NOTIFY_ID, notification)

        // 初始化语音识别
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: return
                val intent = Intent(this@VoiceService, MainActivity::class.java).apply {
                    putExtra("recognized_text", text)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_LISTENING" -> {
                val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                speechRecognizer?.startListening(recognizerIntent)
            }
            "STOP" -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}
