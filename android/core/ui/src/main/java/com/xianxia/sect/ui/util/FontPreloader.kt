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
     * Compose [FontFamily]，优先使用预加载的 NotoSansSC 字体。
     * 若字体文件未找到则回退到 [FontFamily.SansSerif]。
     *
     * 使用 [Font] 的 path + assetManager 构造方式，与 Typeface.createFromAsset
     * 共享 AssetManager 的文件缓存，不会重复读取磁盘。
     */
    val fontFamily: FontFamily
        get() {
            val am = assetManager
            return if (am == null || (!regularLoaded && !boldLoaded)) {
                FontFamily.SansSerif
            } else {
                FontFamily(
                    listOfNotNull(
                        if (regularLoaded) Font(FONT_PATH_REGULAR, am, FontWeight.Normal) else null,
                        if (boldLoaded) Font(FONT_PATH_BOLD, am, FontWeight.Bold) else null
                    )
                )
            }
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
