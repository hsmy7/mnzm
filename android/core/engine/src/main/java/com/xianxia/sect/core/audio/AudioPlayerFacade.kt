package com.xianxia.sect.core.audio

/**
 * 音频播放端口。
 *
 * core 层保持"零 Android 依赖"以支持跨平台复用：本接口声明契约，app 层
 * `AndroidAudioPlayer` 实现注入（iOS 对等实现映射到 AVAudioEngine/AVAudioPlayer）。
 *
 * ## 线程模型（audio-thread-audit.md 结论）
 *
 * 播放路径全部主线程串行 + 预加载后台线程（SoundPool 线程安全），混音由平台承担
 * （Android SoundPool mixer 线程 / iOS AVAudioEngine），本接口不承载混音职责。
 *
 * ## 资源 ID 语义
 *
 * `resId` 为 Android 资源 ID；iOS 对等实现将名称映射为 Bundle 音频资源。
 */
interface AudioPlayerFacade {
    /** 初始化音频引擎（幂等，UI 线程或后台线程均可） */
    fun init()

    /** 释放全部音频资源（调用后需重新 [init]；A2 偿还触发：登出回主菜单场景接线） */
    fun release()

    /** 是否已初始化完毕 */
    val isReady: Boolean

    /**
     * 预加载音效（幂等：同名已加载跳过）。
     * @param name 音效名称，后续 [playSound] 以此名称播放
     * @param resId 资源 ID
     */
    fun preloadSound(name: String, resId: Int)

    /**
     * 注册 BGM 资源（不立即创建播放器）。
     * @param resId 音乐资源 ID
     */
    fun preloadBGM(resId: Int)

    /**
     * 播放预加载音效（音效开关关闭或未加载时静默跳过）。
     * @param name 预加载时指定的音效名称
     */
    fun playSound(name: String)

    /** 开始/恢复播放背景音乐（音乐开关关闭时跳过，幂等） */
    fun playBGM()

    /** 停止并释放背景音乐 */
    fun stopBGM()

    /** 暂停背景音乐（不释放资源） */
    fun pauseBGM()

    /** 恢复暂停的背景音乐（音乐开关关闭时跳过） */
    fun resumeBGM()

    /** 设置变更时同步音频状态（音乐开关关闭自动停 BGM，开启恢复） */
    fun onSettingsChanged()
}
