package com.xianxia.sect.data.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.xianxia.sect.core.util.AccountKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 账号数据空间管理行为测试（SS2）：
 * 激活目录树与 `.current` 标记、标识不落明文（D-1）、登出只清标记不删空间（D-4）、
 * 无活跃空间时 require* fail-fast（D-5）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountSpaceManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `activate builds account space tree and writes current marker`() {
        val space = AccountSpaceManager(context)

        val root = space.activate("union-alpha")

        val key = AccountKey.derive("union-alpha")
        assertEquals(File(context.filesDir, "accounts/$key"), root)
        assertTrue("空间根目录必须存在", root.isDirectory)
        assertTrue("saves/ 目录必须存在", File(root, "saves").isDirectory)
        assertTrue("archives/ 目录必须存在", File(root, "archives").isDirectory)
        assertEquals(
            ".current 标记内容必须是 accountKey",
            key,
            File(context.filesDir, "accounts/.current").readText()
        )
        assertEquals(key, space.currentKey())
        assertEquals(root, space.currentRoot())
    }

    @Test
    fun `activate does not persist plaintext identifier anywhere in accounts tree`() {
        val space = AccountSpaceManager(context)
        val identifier = "union-secret-identifier-3309"

        val root = space.activate(identifier)

        val walked = root.parentFile!!.walkTopDown().filter { it.isFile }.toList() +
            listOf(root.parentFile!!)
        val hits = walked.filter {
            it.name.contains(identifier) ||
                (it.isFile && it.readText().contains(identifier))
        }
        assertTrue("明文标识不得出现在空间目录名或文件内容中（D-1）: $hits", hits.isEmpty())
    }

    @Test
    fun `different identifiers activate different spaces`() {
        val space = AccountSpaceManager(context)

        val rootA = space.activate("union-alpha")
        val keyA = space.currentKey()!!
        val rootB = space.activate("union-beta")
        val keyB = space.currentKey()!!

        assertNotEquals(rootA, rootB)
        assertNotEquals(keyA, keyB)
        assertTrue(rootB.isDirectory)
    }

    @Test
    fun `closeCurrent clears marker but keeps space directory`() {
        val space = AccountSpaceManager(context)
        val root = space.activate("union-alpha")
        File(root, "saves/save.sav").writeText("progress")

        space.closeCurrent()

        assertFalse(".current 必须被清除", File(context.filesDir, "accounts/.current").exists())
        assertEquals(null, space.currentKey())
        assertTrue("登出不删空间（D-4）：目录必须保留", root.isDirectory)
        assertTrue("登出不删进度（D-4）：存档文件必须保留", File(root, "saves/save.sav").exists())
    }

    @Test
    fun `currentKey survives process restart via marker file`() {
        AccountSpaceManager(context).activate("union-alpha")

        // 模拟进程重启：全新管理器实例（activeKey 缓存为空）
        val restarted = AccountSpaceManager(context)
        assertEquals(AccountKey.derive("union-alpha"), restarted.currentKey())
    }

    @Test
    fun `currentKey rejects corrupted marker content`() {
        val accountsRoot = File(context.filesDir, "accounts")
        accountsRoot.mkdirs()
        File(accountsRoot, ".current").writeText("../../etc/passwd")

        assertEquals(null, AccountSpaceManager(context).currentKey())
    }

    @Test
    fun `require accessors fail fast without active space`() {
        val space = AccountSpaceManager(context)

        val message = assertThrows(IllegalStateException::class.java) {
            space.requireDatabaseFile()
        }.message!!
        assertTrue(
            "错误消息必须带操作指引（先 activate）",
            message.contains("AccountSpaceManager.activate")
        )
        assertThrows(IllegalStateException::class.java) { space.requireSavesDir() }
        assertThrows(IllegalStateException::class.java) { space.requireArchivesDir() }
        assertThrows(IllegalStateException::class.java) { space.requireRoot() }
    }

    @Test
    fun `require accessors resolve inside account space after activation`() {
        val space = AccountSpaceManager(context)
        space.activate("union-alpha")

        val dbFile = space.requireDatabaseFile()
        assertTrue(
            "库文件必须落在账号空间内",
            dbFile.absolutePath.startsWith(space.requireRoot().absolutePath)
        )
        assertEquals("xianxia_sect.db", dbFile.name)
        assertTrue(space.requireSavesDir().absolutePath.endsWith("saves"))
        assertTrue(space.requireArchivesDir().absolutePath.endsWith("archives"))
    }
}
