package com.xianxia.sect.platform

import android.content.Context
import com.xianxia.sect.core.platform.AssetSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AndroidAssetSource — [AssetSource] 的 Android 实现（计划 v2 阶段 7
 * 平台能力接口化：引擎注册表/配置服务经引擎层端口读资产，本类承载
 * `Context.assets` 平台细节）。
 *
 * 资产不存在/不可读按端口契约返回 `null`（不抛异常），由调用方按缺省回退。
 */
@Singleton
class AndroidAssetSource @Inject constructor(
    @ApplicationContext private val context: Context
) : AssetSource {

    // 资产不存在（FileNotFoundException⊂IOException）是本端口的正常返回语义而非异常，
    // 静默转 null 即端口契约（调用方回退 + 记录日志）
    @Suppress("SwallowedException")
    override fun open(assetPath: String): InputStream? = try {
        context.assets.open(assetPath)
    } catch (e: IOException) {
        null
    }
}
