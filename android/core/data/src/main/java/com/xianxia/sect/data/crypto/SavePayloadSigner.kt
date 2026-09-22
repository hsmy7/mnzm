package com.xianxia.sect.data.crypto

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * 云存档载荷 HMAC-SHA256 签名器（方案 §4 SR-5「payload 签名最小子集」）。
 *
 * ## 签名域
 * **只签压缩后的载荷字节**（= 实际上传给云端的文件内容）。云档 `extra` 元数据不在
 * 签名域内：它是服务端往返的自由文本，键序/空白不受本端控制，纳入签名会让验证侧
 * 脆弱。元数据里的 `saveId` 因此可被伪造——该如实局限登记在完成报告，它不影响
 * IN2 仲裁（本端账本脏标志才是真相源），但意味着签名不是完整性闭环。
 *
 * ## 密钥
 * 复用 `.secure_key` 体系（[SecureKeyManager.getOrCreateKey]）作 master，派生式与
 * 网络签名链 `RequestSigner.deriveLocalSigningKey` 同构（master ‖ salt → SHA-256×1000），
 * 但 **salt 独立** ⇒ 云存档密钥与网络请求密钥域分离，一侧泄露不等于另一侧可伪造。
 * SR-7 明令保留 `.secure_key` 体系，本类只读不改造。
 *
 * ## 诚实局限（勿当安全闭环宣传）
 * - 验签在**客户端**做、密钥也在同一设备：能取到本机密钥者即可自造合法签名。
 *   本批交付的是格式与接口预埋（后端就绪后把验签点挪到服务端即可），
 *   不是防作弊能力——方案 §4 SR-5 与 §6 风险表原文即此口径；
 * - master 不可得（文件损坏/权限异常）或**取到全零 master**（密钥体系未就绪）时
 *   [sign] 返回 null ⇒ 上传照常（不签），[verify] 返回
 *   [SavePayloadIntegrity.KEY_UNAVAILABLE] ⇒ **不判玩家篡改**。
 *   存档可用性优先于完整性判定（P4 拍板：降级放行 + 显式留痕）；
 * - 密钥**不跨调用缓存**（见 [mac]）：主密钥轮换后不会继续用陈旧密钥，代价是每次
 *   多 1000 轮 SHA-256（微秒级，且频率上限 = 云上传频率）。
 */
@Singleton
class SavePayloadSigner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 对 [payload] 签名。
     *
     * @return 64 字符小写 hex；密钥派生失败返回 null（调用方按"不签名"降级，
     *         签名环节绝不阻断玩家存档）
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 密钥源跨文件/偏好/权限不可枚举, 降级不签名+留痕
    fun sign(payload: ByteArray): String? = try {
        hex(mac().doFinal(payload))
    } catch (e: CancellationException) {
        throw e // 取消穿透：不以"签名失败"冒充
    } catch (e: Exception) {
        Log.w(TAG, "云档签名失败，本次上传不带签名: ${e.message}")
        null
    }

    /**
     * 校验 [payload] 对 [signatureHex]。
     *
     * 三态判据（P4 拍板的落点）：无签名 = [SavePayloadIntegrity.UNSIGNED]（SR-2/SR-3
     * 期间的存量云档常态，非异常）；有签名但对不上 = [SavePayloadIntegrity.MISMATCH]；
     * 本机密钥不可得 = [SavePayloadIntegrity.KEY_UNAVAILABLE]（不误判成玩家篡改）。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 密钥源跨文件/偏好/权限不可枚举, 单列 KEY_UNAVAILABLE
    fun verify(payload: ByteArray, signatureHex: String?): SavePayloadIntegrity {
        if (signatureHex.isNullOrBlank()) return SavePayloadIntegrity.UNSIGNED
        return try {
            val actual = hex(mac().doFinal(payload))
            if (timingSafeEqual(
                    actual.toByteArray(Charsets.UTF_8),
                    signatureHex.toByteArray(Charsets.UTF_8)
                )
            ) {
                SavePayloadIntegrity.VERIFIED
            } else {
                SavePayloadIntegrity.MISMATCH
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "云档验签不可用（密钥派生失败）: ${e.message}")
            SavePayloadIntegrity.KEY_UNAVAILABLE
        }
    }

    /**
     * 每次调用现场派生（**不缓存密钥**）：1000 轮 SHA-256 是微秒级，而签名/验签
     * 频率上限就是云上传频率（TapTap 1 次/分钟）。不缓存换来两件事——
     * ① 主密钥轮换（`handleKeyLossAndRegenerate` 会生成新密钥）后不会继续用陈旧密钥；
     * ② 不会把一次异常窗口里派生出的错误密钥钉死整个进程生命周期。
     */
    private fun mac(): Mac = Mac.getInstance(HMAC_ALGO).apply { init(deriveKey()) }

    /**
     * 与网络签名链同构的派生（摘要/迭代一致），salt 不同值 ⇒ 两侧密钥不可互推。
     *
     * 🔴 全零主密钥直接拒绝派生：[SecureKeyManager.getOrCreateKey] 交的是副本，
     * 调用方擦除自己那份不影响他方（该契约的根治笔见 SecureKeyManager KDoc），
     * 但"文件损坏/密钥体系未就绪"仍可能给出全零——那样派出的密钥语法合法而内容恒定，
     * 会把基础设施故障伪装成"玩家篡改"（MISMATCH）。宁可降级为不签名。
     */
    private fun deriveKey(): SecretKeySpec {
        val master = SecureKeyManager.getOrCreateKey(context)
        try {
            if (master.none { it != 0.toByte() }) {
                throw IllegalStateException("主密钥全零（密钥体系未就绪或已损坏），拒绝派生云档签名密钥")
            }
            var derived = master + SAVE_SALT.toByteArray(Charsets.UTF_8)
            val md = MessageDigest.getInstance(HASH_ALGO)
            repeat(KEY_ITERATIONS) { derived = md.digest(derived) }
            return SecretKeySpec(derived, HMAC_ALGO).also { securelyClear(derived) }
        } finally {
            securelyClear(master) // 副本，可清；清了不影响任何其他调用方
        }
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { b ->
            append(HEX_DIGITS[(b.toInt() shr 4) and 0xF])
            append(HEX_DIGITS[b.toInt() and 0xF])
        }
    }

    companion object {
        private const val TAG = "SavePayloadSigner"
        private const val HMAC_ALGO = "HmacSHA256"
        private const val HASH_ALGO = "SHA-256"
        private const val KEY_ITERATIONS = 1000

        /** 签名格式版本（写入云档 extra 的 `sigVer`，验签侧按此选算法） */
        const val SIGNATURE_VERSION = "hmac-sha256-v1"

        /** 云存档专用 salt——与网络签名 salt 不同值 */
        private const val SAVE_SALT = "xianxia-cloud-save-hmac-v1-2026"

        private const val HEX_DIGITS = "0123456789abcdef"
    }
}

/**
 * 云档载荷完整性判据（SR-5）。四态各有明确处置，见 [SavePayloadSigner.verify]
 * 与 `batch-SR5.md` §1 P4 拍板。
 */
enum class SavePayloadIntegrity {
    /** 签名对上 */
    VERIFIED,

    /** 云档 extra 无签名字段（SR-2/SR-3 期间的存量档）——正常降级，不提示玩家 */
    UNSIGNED,

    /** 有签名但校验失败——可能被改写，如实留痕后仍放行（P4） */
    MISMATCH,

    /** 本机校验密钥不可得——基础设施故障，不判玩家篡改 */
    KEY_UNAVAILABLE
}
