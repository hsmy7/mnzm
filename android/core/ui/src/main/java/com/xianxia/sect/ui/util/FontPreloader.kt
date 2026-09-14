package com.xianxia.sect.ui.util

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * 字体预加载器。
 *
 * 在 Application.onCreate 中调用 [init] 从 assets/fonts 加载 NotoSansSC 字体，
 * 然后通过 [fontFamily] 提供给 Compose Typography。
 *
 * 字体文件缺失时会静默回退到 [FontFamily.SansSerif]，确保 UI 不会白屏。
 */
object FontPreloader {

    private const val TAG = "FontPreloader"

    private const val FONT_PATH_REGULAR = "fonts/NotoSansSC-Regular.ttf"
    private const val FONT_PATH_BOLD = "fonts/NotoSansSC-Bold.ttf"

    @Volatile
    private var assetManager: AssetManager? = null

    @Volatile
    private var regularLoaded = false

    @Volatile
    private var boldLoaded = false

    @Volatile
    private var initialized = false

    /**
     * 已构建的 [FontFamily] 缓存。
     *
     * [fontFamily] 结果在 init 完成后恒定——构建一次缓存即可，
     * 避免每次组合重新分配 Font 列表（15 个 Typography 样式）。
     */
    @Volatile
    private var cachedFamily: FontFamily? = null

    /**
     * Compose [FontFamily]，优先使用预加载的 NotoSansSC 字体。
     * 若字体文件未找到则回退到 [FontFamily.SansSerif]。
     *
     * 使用 [Font] 的 path + assetManager 构造方式，与 Typeface.createFromAsset
     * 共享 AssetManager 的文件缓存，不会重复读取磁盘。
     * init 完成后结果恒定，首次构建后走缓存（零分配）。
     */
    val fontFamily: FontFamily
        get() {
            cachedFamily?.let { return it }
            val am = assetManager
            val family = if (am == null || (!regularLoaded && !boldLoaded)) {
                FontFamily.SansSerif
            } else {
                FontFamily(
                    listOfNotNull(
                        if (regularLoaded) Font(FONT_PATH_REGULAR, am, FontWeight.Normal) else null,
                        if (boldLoaded) Font(FONT_PATH_BOLD, am, FontWeight.Bold) else null
                    )
                )
            }
            // 仅缓存 init 完成后的结果——init 前被访问的临时回退（assetManager 未注入）
            // 不得缓存，否则 init 完成后仍会返回错误的 SansSerif
            if (initialized) cachedFamily = family
            return family
        }

    /** 是否已成功加载过（至少一个字体文件找到）。 */
    val isLoaded: Boolean
        get() = initialized && (regularLoaded || boldLoaded)

    /**
     * 在 Application.onCreate 中调用，从 assets 加载 NotoSansSC 字体。
     *
     * 通过 Typeface.createFromAsset 预加载字体文件到 AssetManager 缓存，
     * 后续 [Font] 构造时复用缓存，避免重复 I/O。
     *
     * 字体文件不存在时只记 warning，不崩溃，UI 自动回退到系统 SansSerif。
     */
    // @Suppress 理由：启动期字体回退兜底——字体文件缺失/损坏时不同 ROM 抛出的
    // 异常类型不可枚举（RuntimeException/IOException 等），漏接即应用无法启动；
    // 吞掉并回退系统 SansSerif 是此处正确语义（KDoc 契约）
    @Suppress("TooGenericExceptionCaught")
    fun init(context: Context) {
        val am = context.assets
        assetManager = am

        regularLoaded = try {
            Typeface.createFromAsset(am, FONT_PATH_REGULAR)
            true
        } catch (e: Exception) {
            Log.w(TAG, "字体文件未找到: $FONT_PATH_REGULAR，使用备选字体", e)
            false
        }
        boldLoaded = try {
            Typeface.createFromAsset(am, FONT_PATH_BOLD)
            true
        } catch (e: Exception) {
            Log.w(TAG, "字体文件未找到: $FONT_PATH_BOLD，使用备选字体", e)
            false
        }
        initialized = true
        if (regularLoaded || boldLoaded) {
            Log.i(TAG, "字体预加载完成: regular=$regularLoaded, bold=$boldLoaded")
        } else {
            Log.w(TAG, "所有字体文件缺失，将使用系统默认字体 SansSerif")
        }
    }
}
