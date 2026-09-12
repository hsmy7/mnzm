package com.xianxia.sect.core.platform

/**
 * APK 签名证书源 — 应用签名校验与平台解耦（平台能力接口化）。
 *
 * 引擎层（兑换码服务防篡改校验）只面向本接口取得当前应用签名证书字节，
 * 不直接依赖 Android `PackageManager`；SHA-256 摘要与哈希比对逻辑留在引擎层
 * （跨平台一致）。平台实现：
 * - Android：app 层 `AndroidApkSigningCertificateSource`（`PackageManager.GET_SIGNING_CERTIFICATES`）
 * - iOS：无 APK 签名概念，返回空列表（引擎侧校验按配置语义拒绝/跳过）
 */
interface ApkSigningCertificateSource {

    /**
     * 当前应用的主签名证书字节（DER 编码）。
     *
     * @return 证书字节；不可用/无签名返回 `null`（调用方按"校验失败"处理）
     */
    fun primarySigningCertificate(): ByteArray?
}
