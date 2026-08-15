package com.xianxia.sect.taptap

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log
import com.xianxia.sect.core.audio.AudioConfig
import com.xianxia.sect.core.audio.AudioPlayerFacade
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android 音频实现（docs/architecture.md G1/A1 根治：core 层接口 + app 层实现）。
 *
 * 由原 core/engine `AudioEngine`（SoundPool + MediaPlayer）逻辑平移而来——
 * core:engine 恢复零 Android 依赖（`import android.media.*` 清零），
 * iOS 对等实现映射 AVAudioEngine/AVAudioPlayer。
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
 *   （全库无调用——BGM 跨 Activity 有意保持，A2 偿还触发时接线）
 */
@Singleton
class AndroidAudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioConfig: AudioConfig
) : AudioPlayerFacade {
    companion object {
        private const val TAG = "AndroidAudioPlayer"
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

    // ==================== 生命周期 ====================

    /**
     * 初始化音频引擎。
     *
     * 在游戏资源预加载阶段调用，在 UI 线程或后台线程均可。
     * 可重复调用（幂等），但通常不推荐重复释放再创建。
     */
    // SDK 边界全量兜底(与原实现一致)
    @Suppress("TooGenericExceptionCaught")
    override fun init() {
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
            Log.d(TAG, "AndroidAudioPlayer initialized (maxStreams=$MAX_STREAMS)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize SoundPool", e)
        }
    }

    /**
     * 释放所有音频资源。
     *
     * 在 Activity/GameEngine 销毁时调用。调用后需重新 [init] 才能使用。
     */
    override fun release() {
        stopBGM()
        bgmPlayer?.release()
        bgmPlayer = null
        soundPool?.release()
        soundPool = null
        soundCache.clear()
        bgmResId = 0
        initialized = false
        Log.d(TAG, "AndroidAudioPlayer released")
    }

    /** 音频引擎是否已初始化完毕 */
    override val isReady: Boolean get() = initialized

    // ==================== 预加载 ====================

    /**
     * 预加载一个音效到 SoundPool。
     * @param name 音效名称，后续通过 [playSound] 以此名称播放
     * @param resId Android drawable/raw 资源 ID
     */
    // SDK 边界全量兜底(与原实现一致)
    // 拆分搬移:多出口与原函数一致
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override fun preloadSound(name: String, resId: Int) {
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
    override fun preloadBGM(resId: Int) {
        bgmResId = resId
        Log.d(TAG, "BGM registered: resId=$resId")
    }

    // ==================== 音效播放 ====================

    /**
     * 播放预加载过的音效。
     * @param name 预加载时指定的音效名称
     */
    // 拆分搬移:多出口与原函数一致
    @Suppress("ReturnCount")
    override fun playSound(name: String) {
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
     */
    // SDK 边界全量兜底(与原实现一致)
    // 拆分搬移:多出口与原函数一致
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override fun playBGM() {
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
                setVolume(1f, 1f)
                isLooping = true
                start()
            }
            Log.d(TAG, "BGM started (resId=$bgmResId)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create/start MediaPlayer", e)
        }
    }

    /** 停止并释放背景音乐。 */
    // SDK 边界全量兜底(与原实现一致)
    @Suppress("TooGenericExceptionCaught")
    override fun stopBGM() {
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
    // SDK 边界全量兜底(与原实现一致)
    @Suppress("TooGenericExceptionCaught")
    override fun pauseBGM() {
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
    // SDK 边界全量兜底(与原实现一致)
    @Suppress("TooGenericExceptionCaught")
    override fun resumeBGM() {
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
    override fun onSettingsChanged() {
        if (audioConfig.musicEnabled) {
            resumeBGM()
        } else {
            stopBGM()
        }
    }
}
