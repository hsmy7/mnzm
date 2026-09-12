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
 * Android 音频实现（core 层 [AudioPlayerFacade] 接口的 app 层实现）。
 *
 * core:engine 模块保持零 Android 依赖；
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

    /**
     * 已完成异步解码的 soundId 集合。
     * SoundPool.load 是异步解码——解码完成前 play() 部分_ROM 会打
     * "sample X not READY" 错误日志且无声。此集合让 playSound 只对真正
     * 就绪的音效发声（与既有静默丢音行为一致，但消除了误报日志）。
     */
    private val loadedSoundIds: MutableSet<Int> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    // ==================== 生命周期 ====================

    /**
     * 初始化音频引擎。
     *
     * 在游戏资源预加载阶段调用，在 UI 线程或后台线程均可。
     * 可重复调用（幂等），但通常不推荐重复释放再创建。
     */
    // SDK 边界全量兜底
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
            soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) loadedSoundIds.add(sampleId)
            }
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
        loadedSoundIds.clear()
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
    // SDK 边界全量兜底
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
    @Suppress("ReturnCount")
    override fun playSound(name: String) {
        if (!initialized || !audioConfig.soundEnabled) return
        val pool = soundPool ?: return
        val soundId = soundCache[name] ?: return
        // 异步解码未完成的音效静默丢弃（此时 play 本就无声；
        // 显式拦截可避免部分 ROM 的 "sample not READY" 错误日志）
        if (soundId !in loadedSoundIds) return
        pool.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    // ==================== 背景音乐控制 ====================

    /** BGM 异步准备中标志（prepareAsync 完成前拦截重复创建/启动） */
    @Volatile
    private var bgmPreparing = false

    /**
     * 开始/恢复播放背景音乐。
     *
     * 仅在 [AudioConfig.musicEnabled] 为 true 时实际播放。
     * 如果已有 BGM 在播放，不会重复创建（幂等）。
     *
     * BGM 经 `prepareAsync` 异步准备 + OnPreparedListener 自动 start，
     * 避免在调用线程（主线程）同步解码——bgm_main.mp3（1.5MB）同步 prepare
     * 需数百 ms，是主线程卡顿点；
     * prepared 前的重复 playBGM/resumeBGM 由 [bgmPreparing] 拦截。
     */
    // SDK 边界全量兜底
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override fun playBGM() {
        if (!audioConfig.musicEnabled) return
        if (bgmPlayer?.isPlaying == true) return
        if (bgmPreparing) return  // 异步准备中——prepared 回调自动 start
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
        val player = createBgmPlayer() ?: return
        bgmPreparing = true
        bgmPlayer = player
        try {
            val afd = context.resources.openRawResourceFd(bgmResId)
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            player.prepareAsync()
            Log.d(TAG, "BGM async prepare started (resId=$bgmResId)")
        } catch (e: Exception) {
            bgmPreparing = false
            if (bgmPlayer === player) bgmPlayer = null
            player.release()
            Log.w(TAG, "Failed to create/start MediaPlayer", e)
        }
    }

    /**
     * 构建异步准备的 BGM 播放器（prepared 后自动 start；被 stop/release 取代时
     * 就地释放）。纯构建不触发解码——解码由 [MediaPlayer.setDataSource] +
     * prepareAsync 在系统媒体线程完成。
     */
    // SDK 边界全量兜底
    @Suppress("TooGenericExceptionCaught")
    private fun createBgmPlayer(): MediaPlayer? = try {
        MediaPlayer().apply {
            setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_GAME)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setVolume(1f, 1f)
            isLooping = true
            setOnPreparedListener { mp ->
                bgmPreparing = false
                if (mp !== bgmPlayer) {
                    mp.release()
                    return@setOnPreparedListener
                }
                if (audioConfig.musicEnabled) {
                    try {
                        mp.start()
                        Log.d(TAG, "BGM prepared & started (resId=$bgmResId)")
                    } catch (e: Exception) {
                        Log.w(TAG, "BGM start after prepare failed", e)
                    }
                }
            }
            setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "BGM MediaPlayer error what=$what extra=$extra")
                bgmPreparing = false
                if (mp === bgmPlayer) bgmPlayer = null
                mp.release()
                true
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to create MediaPlayer", e)
        null
    }

    /** 停止并释放背景音乐。 */
    // SDK 边界全量兜底
    @Suppress("TooGenericExceptionCaught")
    override fun stopBGM() {
        bgmPreparing = false
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
    // SDK 边界全量兜底
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
    // SDK 边界全量兜底
    @Suppress("TooGenericExceptionCaught")
    override fun resumeBGM() {
        if (!audioConfig.musicEnabled) return
        if (bgmPreparing) return  // 准备中——prepared 回调自动 start
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
