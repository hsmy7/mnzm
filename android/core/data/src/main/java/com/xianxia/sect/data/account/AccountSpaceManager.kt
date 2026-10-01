package com.xianxia.sect.data.account

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.util.AccountKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账号数据空间的唯一权威（SS2 分库）。
 *
 * 本地数据从"按设备归属"改为"按账号隔离"：一个账号一个数据空间目录，
 * 数据库（含 -wal/-shm、启动前快照、恢复 marker）、`saves/` 文件层、`archives/`
 * 归档全部落在空间内；`accounts/.current` 标记当前活跃空间。
 *
 * ## 目录结构（验收①）
 *
 * ```
 * filesDir/accounts/
 *   ├── .current                  ← 当前活跃空间标记（内容 = accountKey）
 *   └── <accountKey>/             ← accountKey = 账号标识 SHA-256 截断（AccountKey 派生）
 *       ├── xianxia_sect.db       ← Room 库（+ -wal/-shm + 启动前快照 + 恢复 marker）
 *       ├── saves/                ← .sav/.bak 文件层
 *       └── archives/             ← 归档 .arc
 * ```
 *
 * ## 生命周期契约
 *
 * - **激活**：登录/验证通过后进入游戏前由唯一入口 [activate] 调用——写 `.current`
 *   + 建空间目录树 + 进程内缓存。
 * - **无账号不建库**（D-5）：`require*` 族在无活跃空间时抛 [IllegalStateException]，
 *   保证 DI 链上任何提前注入（登录前）当场暴露而非静默建"匿名空间"。
 * - **登出只清 `.current` 不删空间**（D-4）：[closeCurrent] 后账号空间原样保留，
 *   同账号再次登录进度仍在。
 */
@Singleton
class AccountSpaceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 进程内活跃空间缓存；null = 未激活（含进程冷启动后尚未登录） */
    @Volatile
    private var activeKey: String? = null

    /** accounts 根目录：filesDir/accounts */
    val accountsRoot: File
        get() = File(context.filesDir, ACCOUNTS_DIR_NAME)

    /**
     * 激活账号数据空间（进入游戏前唯一入口）。
     *
     * @param identifier 账号唯一标识（登录 SDK unionId）；非空白由登录链路保证
     * @return 激活的空间根目录（含库/saves/archives 的父目录）
     */
    fun activate(identifier: String): File {
        val key = AccountKey.derive(identifier)
        val root = File(accountsRoot, key).apply { mkdirs() }
        File(root, SAVES_DIR_NAME).mkdirs()
        File(root, ARCHIVES_DIR_NAME).mkdirs()
        File(accountsRoot, CURRENT_MARKER_NAME).writeText(key)
        activeKey = key
        Log.i(TAG, "账号数据空间已激活: $key")
        return root
    }

    /** 当前活跃 accountKey；null = 无活跃空间（未登录或已登出） */
    fun currentKey(): String? {
        activeKey?.let { return it }
        val marker = File(accountsRoot, CURRENT_MARKER_NAME)
        if (!marker.exists()) return null
        val stored = marker.readText().trim()
        return stored.takeIf { AccountKey.isValid(it) }
    }

    /** 当前活跃空间根目录；null = 无活跃空间 */
    fun currentRoot(): File? = currentKey()?.let { File(accountsRoot, it) }

    /** 空间是否已在本进程激活（require* 与延迟初始化门控共用） */
    fun isActive(): Boolean = currentKey() != null

    /** 当前活跃空间根目录；无活跃空间抛 [IllegalStateException]（fail-fast，见类 KDoc） */
    fun requireRoot(): File =
        currentRoot()
            ?: throw IllegalStateException(
                "无活跃账号数据空间（未登录或已登出）——禁止在建库/读写前访问存储；" +
                    "进入游戏前必须先经 AccountSpaceManager.activate 激活空间"
            )

    /** Room 库文件；无活跃空间抛 [IllegalStateException] */
    fun requireDatabaseFile(): File = File(requireRoot(), DATABASE_FILE_NAME)

    /** saves 文件层目录；无活跃空间抛 [IllegalStateException] */
    fun requireSavesDir(): File = File(requireRoot(), SAVES_DIR_NAME)

    /** 归档目录；无活跃空间抛 [IllegalStateException] */
    fun requireArchivesDir(): File = File(requireRoot(), ARCHIVES_DIR_NAME)

    /**
     * 关闭当前数据空间（登出第五件）：只删 `.current` 标记与进程内缓存，
     * **不删空间目录**（D-4——换回同一账号进度仍在）。
     */
    fun closeCurrent() {
        val key = activeKey ?: currentKey()
        File(accountsRoot, CURRENT_MARKER_NAME).delete()
        activeKey = null
        Log.i(TAG, "账号数据空间已关闭（空间保留）: $key")
    }

    companion object {
        private const val TAG = "AccountSpaceManager"

        /** accounts 根目录名（filesDir 下，与 cacheDir 分离——清缓存不影响档） */
        const val ACCOUNTS_DIR_NAME = "accounts"

        /** 当前活跃空间标记文件名 */
        const val CURRENT_MARKER_NAME = ".current"

        /** Room 库文件名（账号空间内；-wal/-shm 与快照由 SQLite/快照链按此名派生） */
        const val DATABASE_FILE_NAME = "xianxia_sect.db"

        /** 文件层存档目录名（与 StorageConstants.BACKUP_DIR_NAME 同值） */
        const val SAVES_DIR_NAME = "saves"

        /** 归档目录名（与 DataArchiver.DEFAULT_ARCHIVE_DIR_NAME 同值） */
        const val ARCHIVES_DIR_NAME = "archives"
    }
}
