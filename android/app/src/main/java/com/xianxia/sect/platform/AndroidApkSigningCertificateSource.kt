package com.xianxia.sect.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.xianxia.sect.core.platform.ApkSigningCertificateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AndroidApkSigningCertificateSource — [ApkSigningCertificateSource] 的 Android 实现
 * （计划 v2 阶段 7 平台能力接口化：签名证书提取属平台细节，SHA-256 摘要与哈希
 * 比对留在引擎层 RedeemCodeService，跨平台一致）。
 *
 * API 28+ 走 `GET_SIGNING_CERTIFICATES`（signingInfo），低版本走已废弃的
 * `GET_SIGNATURES`（minSdk 24 仍需覆盖）。
 */
@Singleton
class AndroidApkSigningCertificateSource @Inject constructor(
    @ApplicationContext private val context: Context
) : ApkSigningCertificateSource {

    // 包不存在（NameNotFoundException，卸载竞态等）按端口契约转语义化 null，
    // 异常本身不含可记录的额外上下文
    @Suppress("SwallowedException")
    override fun primarySigningCertificate(): ByteArray? = try {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        )

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }

        signatures?.firstOrNull()?.toByteArray()
    } catch (e: PackageManager.NameNotFoundException) {
        // 包不存在（卸载竞态等）：按端口契约 null（引擎侧按"校验失败"处理）
        null
    }
}
