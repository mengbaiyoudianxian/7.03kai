package com.mbclaw.nonroot.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * 持续关键词检测 — 模仿系统语音唤醒
 *
 * 方案: VAD (Voice Activity Detection) + 简单能量检测
 *   1. AudioRecord 低采样率持续监听 (16kHz, MONO)
 *   2. 能量阈值检测 → VAD 判断是否在说话
 *   3. 检测到语音 → 触发 SpeechRecognizer 做关键词匹配
 *   4. 匹配到关键词 → 唤醒 MBclaw
 *
 * 对比 Root 版 DSP 唤醒:
 *   Root: DSP硬件一直监听, 0功耗
 *   NonRoot: 软件AudioRecord, ~50mW (可接受)
 *
 * 唤醒词: "Hey MBclaw" / "你好小孟" / "小孟小孟"
 */
class KeywordDetector {

    companion object {
        const val SAMPLE_RATE = 16000
        const val ENERGY_THRESHOLD = 500       // 能量阈值 (环境自适应)
        const val SILENCE_TIMEOUT_MS = 800      // 静音超时判定
        const val MIN_SPEECH_DURATION_MS = 300  // 最短有效语音
        const val MAX_SPEECH_DURATION_MS = 3000 // 最长单句

        val WAKE_WORDS = listOf("你好小孟", "小孟小孟", "hey mbclaw", "ok mbclaw", "嗨小孟")
        val SHORT_WAKE = listOf("小孟", "mbclaw") // 短词: 检测到就触发 VAD → SpeechRecognizer
    }

    @Volatile var isListening: Boolean = false; private set
    @Volatile var speechDetected: Boolean = false; private set

    private var audioRecord: AudioRecord? = null
    private var vadCallback: ((Boolean) -> Unit)? = null   // true=检测到语音
    private var wakeCallback: ((String) -> Unit)? = null    // 检测到唤醒词

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 开始持续监听 */
    fun startListening(onWake: (String) -> Unit, onVad: ((Boolean) -> Unit)? = null) {
        if (isListening) return
        isListening = true
        wakeCallback = onWake
        vadCallback = onVad

        scope.launch {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )

            try {
                audioRecord?.startRecording()
                val buffer = ShortArray(bufferSize)

                var isSpeaking = false
                var speechStartTime = 0L
                var silenceStartTime = 0L

                while (isActive && isListening) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read <= 0) continue

                    // 计算 RMS 能量
                    var sum = 0.0
                    for (i in 0 until read) {
                        sum += (buffer[i] * buffer[i]).toDouble()
                    }
                    val rms = kotlin.math.sqrt(sum / read)

                    val now = SystemClock.elapsedRealtime()

                    if (rms > ENERGY_THRESHOLD) {
                        if (!isSpeaking) {
                            isSpeaking = true
                            speechStartTime = now
                            vadCallback?.invoke(true)
                            speechDetected = true
                        }
                        silenceStartTime = 0
                    } else {
                        if (isSpeaking) {
                            if (silenceStartTime == 0L) silenceStartTime = now
                            val silenceDuration = now - silenceStartTime

                            if (silenceDuration > SILENCE_TIMEOUT_MS) {
                                // 语音段落结束 → 检查长度 → 触发识别
                                val speechDuration = now - speechStartTime
                                if (speechDuration in MIN_SPEECH_DURATION_MS..MAX_SPEECH_DURATION_MS) {
                                    wakeCallback?.invoke("vad_triggered")
                                }
                                isSpeaking = false
                                speechDetected = false
                                vadCallback?.invoke(false)
                            }
                        }
                    }

                    delay(20) // 50fps 检测
                }
            } catch (e: Exception) {
                // 麦克风权限被拒绝或其他错误
            } finally {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                isListening = false
            }
        }
    }

    fun stopListening() {
        isListening = false
        scope.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    /** 检查关键词匹配 */
    fun matchWakeWord(text: String): Boolean {
        val lower = text.lowercase().trim()
        return WAKE_WORDS.any { lower.contains(it) } || SHORT_WAKE.any { lower == it }
    }
}
