package com.xianxia.sect.core.audio


/**
 * 音频配置管理器
 *
 * 管理游戏音效/音乐的开关状态。
 *
 * 使用 @Volatile 确保跨线程可见性（引擎线程 + UI 线程均可能读写）。
 */
class AudioConfig {

    @Volatile
    var soundEnabled: Boolean = true

    @Volatile
    var musicEnabled: Boolean = true

    /**
     * 从 SessionManager 持久化设置初始化。
     * 在游戏加载前调用（登录/主菜单阶段）。
     */
    fun loadFromPrefs(sound: Boolean, music: Boolean) {
        soundEnabled = sound
        musicEnabled = music
    }

    /**
     * 单独更新某一项设置。
     * @param sound 音效开关，null 表示不修改
     * @param music 音乐开关，null 表示不修改
     */
    fun update(sound: Boolean? = null, music: Boolean? = null) {
        sound?.let { soundEnabled = it }
        music?.let { musicEnabled = it }
    }
}
