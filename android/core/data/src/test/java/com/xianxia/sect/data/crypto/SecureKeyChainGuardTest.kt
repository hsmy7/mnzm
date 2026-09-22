package com.xianxia.sect.data.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `.secure_key` 网络签名链保留守卫（SR-7 C3）。
 *
 * 背景：方案 §4 SR-7 的"schema 第二刀"含"删 crypto 存档加密链，
 * **注意保留 `.secure_key` 网络签名链**"。实测本批开工时该"链"只剩两个死壳
 * （`SaveCryptoKeyCache` 除自身 `initialize` 外零消费者、`StorageConfig` 的
 * `cache_derived_key`/`key_cache_duration_ms` 唯一消费者就是那死壳）——真正的存档载荷
 * 加密码早在 `5a421f3d6`（B 系列死代码第一刀）切掉了。死壳本批删除，
 * 而活的那一条链（登录/网络请求签名 + SR-5 云档载荷 HMAC）一环都不能动。
 *
 * 三条例子各自要能在"改错了"时判红：
 * 1. 链条四件套源文件在位（改名/删除即红，防"顺手清 crypto"殃及签名链）；
 * 2. `SavePayloadSigner` 仍从 `SecureKeyManager.getOrCreateKey` 取 master
 *    （这条依赖断了不会编译失败——它会静默变成"签不上名"，只能靠守卫）；
 * 3. 死壳不得回流（IN6：零调用者的机制要么删、要么别再长回来）。
 *
 * 源目录不可达一律 `AssertionError`，不得静默跳过（同 SR-5/SR-6 守卫纪律）。
 */
class SecureKeyChainGuardTest {

    private companion object {
        /** 必须留在树上的 `.secure_key` 链成员（android 根相对路径） */
        val CHAIN_FILES = listOf(
            "core/data/src/main/java/com/xianxia/sect/data/crypto/SecureKeyManager.kt",
            "core/data/src/main/java/com/xianxia/sect/data/crypto/SecureKeyFileStore.kt",
            "core/data/src/main/java/com/xianxia/sect/data/crypto/SavePayloadSigner.kt",
            "app/src/main/java/com/xianxia/sect/network/RequestSigner.kt"
        )

        /** 已随 C3 删除的死壳面：任何一处回流即红 */
        val DEAD_SHELL_TOKENS = listOf(
            "SaveCryptoKeyCache",
            "cache_derived_key",
            "key_cache_duration_ms",
            "DEFAULT_KEY_CACHE_DURATION_MS",
            "DEFAULT_CACHE_DERIVED_KEY"
        )

        val MODULES = listOf("app", "core/data", "core/domain", "core/engine", "core/ui", "feature/game")
    }

    @Test
    fun `secure_key 网络签名链四件套在位且密钥文件名未变`() {
        val root = androidRoot()
        CHAIN_FILES.forEach { relativePath ->
            val file = File(root, relativePath)
            assertTrue("签名链文件缺失（删除或改名须显式改本守卫）：$relativePath", file.isFile)
        }
        val store = File(root, CHAIN_FILES[1]).readText()
        assertTrue("`SecureKeyFileStore` 必须仍写 `.secure_key` 文件名常量", store.contains("\".secure_key\""))
    }

    @Test
    fun `云档载荷签名仍复用 SecureKeyManager 取主密钥`() {
        val root = androidRoot()
        val signer = File(root, "core/data/src/main/java/com/xianxia/sect/data/crypto/SavePayloadSigner.kt")
            .readText()
        assertTrue(
            "SR-5 的载荷 HMAC 必须以 SecureKeyManager.getOrCreateKey 为 master 源，" +
                "断链不会编译失败、只会静默签不上名 ⇒ 由此守卫兜",
            signer.contains("SecureKeyManager.getOrCreateKey")
        )
    }

    @Test
    fun `存档加密死壳不得回流到生产源码`() {
        val root = androidRoot()
        val offenders = mainKotlinFiles(root).flatMap { file ->
            DEAD_SHELL_TOKENS
                .filter { token -> file.readText().contains(token) }
                .map { token -> "${file.relativePath(root)} → $token" }
        }
        assertTrue("C3 已删的零调用者加解密面重新出现：$offenders", offenders.isEmpty())
    }

    // ── 源面解析（同 SaveMigrationGuardTest / WallClockReflowGuardTest 口径）──

    private fun androidRoot(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = if (dir.name == "android") dir else File(dir, "android")
            if (MODULES.all { File(candidate, "$it/src/main").isDirectory }) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("定位不到 android 源根（user.dir=${System.getProperty("user.dir")}）——守卫不得静默跳过")
    }

    private fun mainKotlinFiles(root: File): List<File> = MODULES.flatMap { module ->
        File(root, "$module/src/main").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    }

    /** Windows 反斜杠必须归一（SR-5 实事故：未归一导致排除清单静默失效） */
    private fun File.relativePath(root: File): String =
        relativeTo(root).path.replace(File.separatorChar, '/')
}
