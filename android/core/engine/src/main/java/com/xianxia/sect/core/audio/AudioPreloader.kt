package com.xianxia.sect.core.audio

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频预加载助手
 *
 * 批量加载 SFX 音效到 [AudioEngine]。
 * 在游戏资源预加载阶段（L0/L1）调用 [preloadAll] 传入 (名称, resId) 列表。
 */
@Singleton
class AudioPreloader @Inject constructor(
    private val audioEngine: AudioEngine
) {
    companion object {
        private const val TAG = "AudioPreloader"
    }

    /**
     * 批量预加载音效。
     * @param sounds (音效名称, 资源 ID) 列表
     * @return 成功预加载的数量
     */
    fun preloadAll(sounds: List<Pair<String, Int>>): Int {
        if (!audioEngine.isReady) {
            Log.w(TAG, "AudioEngine not ready, skipping preload")
            return 0
        }
        var count = 0
        for ((name, resId) in sounds) {
            audioEngine.preloadSound(name, resId)
            count++
        }
        Log.d(TAG, "Preloaded $count sounds")
        return count
    }

    /**
     * 预加载 BGM 资源。
     * @param resId 背景音乐资源 ID
     */
    fun preloadBGM(resId: Int) {
        audioEngine.preloadBGM(resId)
        Log.d(TAG, "BGM preload scheduled: resId=$resId")
    }
}
