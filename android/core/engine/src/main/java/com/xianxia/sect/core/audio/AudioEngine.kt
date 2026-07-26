package com.xianxia.sect.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频引擎
 *
 * 封装 Android [SoundPool]（SFX 短音效）和 [MediaPlayer]（BGM 背景音乐）。
 *
 * ### 音效 (SFX)
 * - 使用 [SoundPool] 管理，最大 8 流并发
 * - 通过 [preloadSound] 预加载，[playSound] 播放
 *
 * ### 背景音乐 (BGM)
 * - 使用 [MediaPlayer] 循环播放
 * - 通过 [preloadBGM] 指定资源，[playBGM]/[stopBGM]/[pauseBGM]/[resumeBGM] 控制
 *
 * ### 生命周期
 * - [init] 在游戏资源加载阶段调用，创建 SoundPool
 * - [release] 在游戏退出或 Activity 销毁时释放所有音频资源
 */
@Singleton
class AudioEngine @Inject constructor(
    private val context: Context,
    private val audioConfig: AudioConfig
) {
    companion object {
        private const val TAG = "AudioEngine"
        private const val MAX_STREAMS = 8
    }

    private var soundPool: SoundPool? = null
    private var bgmPlayer: MediaPlayer? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var bgmResId: Int = 0

    /** name → SoundPool soundId */
    private val soundCache = ConcurrentHashMap<String, Int>()

    /** 循环淡入淡出相关 */
    private val bgmHandler = Handler(Looper.getMainLooper())
    private var bgmFadeRunnable: Runnable? = null
    private var bgmDurationMs: Int = 0
    private var bgmTargetVolume: Float = 1f

    /** 设置 BGM 音量（0.0～1.0），含淡入淡出安全保护 */
    private fun setBgmVolume(vol: Float) {
        bgmTargetVolume = vol.coerceIn(0f, 1f)
        try { bgmPlayer?.setVolume(bgmTargetVolume, bgmTargetVolume) } catch (_: Exception) {}
    }

    // ==================== 生命周期 ====================

    /**
     * 初始化音频引擎。
     *
     * 在游戏资源预加载阶段调用，在 UI 线程或后台线程均可。
     * 可重复调用（幂等），但通常不推荐重复释放再创建。
     */
    fun init() {
        if (initialized) return
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = SoundPool.Builder()
                .setMaxStreams(MAX_STREAMS)
                .setAudioAttributes(attrs)
                .build()
            initialized = true
            Log.d(TAG, "AudioEngine initialized (maxStreams=$MAX_STREAMS)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize SoundPool", e)
        }
    }

    /**
     * 释放所有音频资源。
     *
     * 在 Activity/GameEngine 销毁时调用。调用后需重新 [init] 才能使用。
     */
    fun release() {
        stopBGM()
        bgmPlayer?.release()
        bgmPlayer = null
        soundPool?.release()
        soundPool = null
        soundCache.clear()
        bgmResId = 0
        initialized = false
        Log.d(TAG, "AudioEngine released")
    }

    /** 音频引擎是否已初始化完毕 */
    val isReady: Boolean get() = initialized

    // ==================== 预加载 ====================

    /**
     * 预加载一个音效到 SoundPool。
     * @param name 音效名称，后续通过 [playSound] 以此名称播放
     * @param resId Android drawable/raw 资源 ID
     */
    fun preloadSound(name: String, resId: Int) {
        if (!initialized) return
        if (soundCache.containsKey(name)) return // 已加载，跳过
        try {
            val pool = soundPool ?: return
            val soundId = pool.load(context, resId, 1)
            if (soundId > 0) {
                soundCache[name] = soundId
                Log.d(TAG, "Preloaded sound: $name -> $soundId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to preload sound: $name", e)
        }
    }

    /**
     * 注册 BGM 资源到引擎（不立即创建 MediaPlayer）。
     * @param resId 音乐资源 ID，后续通过 [playBGM] 创建 MediaPlayer 播放
     */
    fun preloadBGM(resId: Int) {
        bgmResId = resId
        Log.d(TAG, "BGM registered: resId=$resId")
    }

    // ==================== 音效播放 ====================

    /**
     * 播放预加载过的音效。
     * @param name 预加载时指定的音效名称
     */
    fun playSound(name: String) {
        if (!initialized || !audioConfig.soundEnabled) return
        val pool = soundPool ?: return
        val soundId = soundCache[name] ?: return
        pool.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    // ==================== 背景音乐控制 ====================

    /**
     * 开始/恢复播放背景音乐。
     *
     * 仅在 [AudioConfig.musicEnabled] 为 true 时实际播放。
     * 如果已有 BGM 在播放，不会重复创建（幂等）。
     *
     * 不使用 MediaPlayer 内置的 setLooping(true)，而是手动应答 [OnCompletionListener]
     * 并配合淡入淡出，消除循环处源文件的音尾突变声。
     */
    fun playBGM() {
        if (!audioConfig.musicEnabled) return
        if (bgmPlayer?.isPlaying == true) return
        if (bgmPlayer != null) {
            try {
                bgmPlayer?.start()
                return
            } catch (e: Exception) {
                Log.w(TAG, "BGM resume failed, recreating player", e)
                bgmPlayer?.release()
                bgmPlayer = null
            }
        }
        if (bgmResId == 0) return
        try {
            bgmPlayer = MediaPlayer.create(context, bgmResId).apply {
                bgmDurationMs = duration.coerceAtLeast(1)
                setVolume(1f, 1f)
                // 不使用 setLooping(true)，手动处理循环淡入淡出
                setOnCompletionListener {
                    // 确保 fade-out 已降至最低后重启
                    bgmHandler.post {
                        setBgmVolume(0f)
                        try { seekTo(0); start() } catch (_: Exception) {}
                        // 淡入：500ms 从 0→1
                        startFadeIn(500L)
                    }
                }
                isLooping = false
                start()
            }
            // 启动 fade-out 监控：在结束前 600ms 开始淡出
            startFadeOutBeforeEnd(600L)
            Log.d(TAG, "BGM started (resId=$bgmResId, duration=${bgmDurationMs}ms)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create/start MediaPlayer", e)
        }
    }

    /** 启动 fade-out 监控：在结束前 [leadMs] 开始将音量降至 0 */
    private fun startFadeOutBeforeEnd(leadMs: Long) {
        bgmFadeRunnable?.let { bgmHandler.removeCallbacks(it) }
        val r = object : Runnable {
            private val fadeStepMs = 50L
            private val fadeSteps = (leadMs / fadeStepMs).coerceAtLeast(1).toInt()
            private val volStep = 1f / fadeSteps
            private var step = 0
            private var faded = false

            override fun run() {
                val player = bgmPlayer ?: return
                try {
                    val pos = player.currentPosition
                    val remaining = bgmDurationMs - pos
                    if (!faded && remaining <= leadMs) {
                        faded = true
                        step = 0
                    }
                    if (faded) {
                        step++
                        val vol = (1f - volStep * step).coerceIn(0f, 1f)
                        setBgmVolume(vol)
                    }
                } catch (_: Exception) {}
                if (player.isPlaying) {
                    bgmHandler.postDelayed(this, fadeStepMs)
                }
            }
        }
        bgmFadeRunnable = r
        bgmHandler.postDelayed(r, 1000L) // 1s后开始监控
    }

    /** 淡入：从当前音量渐增到 1.0 */
    private fun startFadeIn(durationMs: Long) {
        val fadeStepMs = 50L
        val steps = (durationMs / fadeStepMs).coerceAtLeast(1).toInt()
        val volStep = 1f / steps
        bgmFadeRunnable?.let { bgmHandler.removeCallbacks(it) }
        val r = object : Runnable {
            private var step = 0
            override fun run() {
                step++
                setBgmVolume((volStep * step).coerceIn(0f, 1f))
                if (step < steps && bgmPlayer?.isPlaying == true) {
                    bgmHandler.postDelayed(this, fadeStepMs)
                } else {
                    setBgmVolume(1f)
                }
            }
        }
        bgmFadeRunnable = r
        bgmHandler.post(r)
    }

    /** 停止并释放背景音乐。 */
    fun stopBGM() {
        bgmFadeRunnable?.let { bgmHandler.removeCallbacks(it) }
        bgmFadeRunnable = null
        try {
            bgmPlayer?.let {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            }
            bgmPlayer = null
            Log.d(TAG, "BGM stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping BGM", e)
        }
    }

    /** 暂停背景音乐（不释放资源）。 */
    fun pauseBGM() {
        try {
            bgmPlayer?.let {
                if (it.isPlaying) it.pause()
            }
            Log.d(TAG, "BGM paused")
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing BGM", e)
        }
    }

    /** 恢复暂停的背景音乐。 */
    fun resumeBGM() {
        if (!audioConfig.musicEnabled) return
        try {
            bgmPlayer?.let {
                if (!it.isPlaying) it.start()
            }
            Log.d(TAG, "BGM resumed")
        } catch (e: Exception) {
            Log.w(TAG, "Error resuming BGM", e)
        }
    }

    // ==================== 设置变更响应 ====================

    /**
     * 当游戏设置变更时调用，同步音频状态。
     *
     * 例如用户在设置面板关闭音乐时，自动停止 BGM。
     */
    fun onSettingsChanged() {
        if (audioConfig.musicEnabled) {
            resumeBGM()
        } else {
            stopBGM()
        }
    }
}
