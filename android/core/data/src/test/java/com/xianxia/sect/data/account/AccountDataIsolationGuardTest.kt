package com.xianxia.sect.data.account

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 账号数据隔离守卫（SS2 验收②）。
 *
 * 以 `@Database` 实体清单为锚点遍历，把"换账号不串档"钉成 CI 红线：
 * **全部实体所在的单库 + 全部文件存储面必须落在账号数据空间内**，
 * 登出五件套在全部登出入口收敛——任何一条被绕过，即存在跨 accountKey 可见路径。
 *
 * 同步义务（改到对应面时必须让本守卫一起更新，禁止放宽断言）：
 * - 新增文件存储落点：必须经 [AccountSpaceManager] 解析路径；设备级例外
 *   显式加入 [DEVICE_SCOPED_FILE_WRITES] 并注明理由；
 * - 新增登出入口：必须复用 `login/FullLogout.kt` 的 `performFullLogout`。
 */
class AccountDataIsolationGuardTest {

    private companion object {
        /** 模块根 = core/data（测试工作目录）；跨模块源文件带 ../ 前缀 */
        const val DATA_MODULE_ROOT  = "src/main/java/com/xianxia/sect/data"

        /** @Database 实体清单锚点文件 */
        val GAME_DATABASE_SRC = File("$DATA_MODULE_ROOT/local/GameDatabase.kt")

        /** 建库/DAO 装配面 */
        val APP_MODULE_SRC = File("../../app/src/main/java/com/xianxia/sect/di/AppModule.kt")
        val STORAGE_MODULE_SRC = File("../../app/src/main/java/com/xianxia/sect/di/StorageModule.kt")

        /** 文件存储面 */
        val STORAGE_FACADE_SRC = File("$DATA_MODULE_ROOT/facade/StorageFacade.kt")
        val DATA_ARCHIVER_SRC = File("$DATA_MODULE_ROOT/archive/DataArchiver.kt")
        val SAVE_WIPE_SRC = File("$DATA_MODULE_ROOT/wipe/SaveWipeCoordinator.kt")

        /** 登出入口 */
        val FULL_LOGOUT_SRC = File("../../app/src/main/java/com/xianxia/sect/login/FullLogout.kt")
        val MAIN_ACTIVITY_SRC = File("../../app/src/main/java/com/xianxia/sect/ui/MainActivity.kt")
        val GAME_ACTIVITY_SRC = File("../../app/src/main/java/com/xianxia/sect/ui/game/GameActivity.kt")

        /**
         * 设备级（不随账号隔离）的 filesDir 直写白名单——intentionallyExcluded：
         * - `crypto/SecureKeyManager.kt` / `crypto/SecureKeyFileStore.kt`：云端存档
         *   加密密钥，设备级密钥材料（按账号轮换会破坏既有云档解密）；
         * - `core/CrashHandler.kt`（app 模块）：崩溃日志，设备级诊断数据；
         * - `engine/DataPruningScheduler.kt`：删除历史遗留孤儿目录（清理面，非存储面）；
         * - `wal/FunctionalWAL.kt`：应用级事务 WAL 目录——SS3 已登记摘除，摘除前
         *   保持设备级（其内容为临时重放日志，无账号维度数据）；
         * - MMKV/SharedPreferences（game_prefs、xianxia_session 等）：键值存储本批
         *   不动，是否随账号隔离由 SS7/SS8 评估（TASKBOOK-SS2 §6 登记）；
         * - `account/AccountSpaceManager.kt`：账号空间根目录（filesDir/accounts）的
         *   唯一定义处，本身即隔离权威；
         * - `wipe/SaveWipeCoordinator.kt`：删档重置清理面（整树删除，非存储落点）。
         */
        val DEVICE_SCOPED_FILE_WRITES = setOf(
            "crypto/SecureKeyManager.kt",
            "crypto/SecureKeyFileStore.kt",
            "engine/DataPruningScheduler.kt",
            "wal/FunctionalWAL.kt",
            "account/AccountSpaceManager.kt",
            "wipe/SaveWipeCoordinator.kt"
        )

        /** app 模块设备级 filesDir 白名单（相对 app 源根） */
        val APP_DEVICE_SCOPED = setOf("core/CrashHandler.kt")

        fun resolve(relative: String) = relative
    }

    @Test
    fun `entity anchor is parseable - every table lives in the single account-scoped database`() {
        val src = GAME_DATABASE_SRC.readSource()
        val entities = src.substringAfter("entities = [", "").substringBefore("],", "")
        val entityCount = Regex("""(\w+::class)""").findAll(entities).count()

        assertTrue(
            "实体锚点解析失配（0 个实体）——@Database entities 块格式变化后必须同步本守卫的解析逻辑",
            entityCount > 0
        )
        // 全部实体的唯一存储通道 = GameDatabase 单实例，其文件路径由账号空间派生：
        // 库路径在 create 上游经 AccountSpaceManager.requireDatabaseFile 锁定（下一条断言），
        // 此处锚定实体清单 → 单库通道的符号面
        assertTrue(
            "全部 $entityCount 张表的唯一建库入口必须是 GameDatabase.create（dbFile 绝对路径）——" +
                "发现新持久化通道时必须同样落在账号空间内，见 AccountSpaceManager",
            src.contains("fun create(context: Context, dbFile: File)") &&
                src.contains("dbFile.absolutePath")
        )
    }

    @Test
    fun `database builder takes account-space path - fixed device path is retired`() {
        val src = GAME_DATABASE_SRC.readSource()
        assertTrue(
            "GameDatabase 不得再经 context.getDatabasePath 固定设备路径建库（跨账号共享同一库 = 串档根因）；" +
                "库路径必须由 AccountSpaceManager.requireDatabaseFile 派生后传入 create",
            !src.contains("getDatabasePath(")
        )
        val appModule = APP_MODULE_SRC.readSource()
        assertTrue(
            "AppModule.provideGameDatabase 必须经 AccountSpaceManager.requireDatabaseFile 解析库路径" +
                "（无活跃空间时 fail-fast，无账号不建库）",
            appModule.contains("accountSpace.requireDatabaseFile()") &&
                appModule.contains("GameDatabase.create(context.applicationContext, dbFile)")
        )
    }

    @Test
    fun `every Room databaseBuilder site is the single account-scoped factory`() {
        val mainRoots = listOf(
            File("src/main"),
            File("../../app/src/main"),
            File("../../core/engine/src/main"),
            File("../../feature/game/src/main")
        )
        val violations = mainRoots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { f ->
                    if (f.readText().contains("Room.databaseBuilder")) {
                        listOf(root.name + "/" + f.relativeTo(root).invariantSeparatorsPath)
                    } else emptyList()
                }
        }
        assertTrue(
            "Room.databaseBuilder 必须只出现在 GameDatabase.create（建库唯一入口；" +
                "新库实例必须复用同一账号空间路径）: $violations",
            violations == listOf("main/java/com/xianxia/sect/data/local/GameDatabase.kt")
        )
    }

    @Test
    fun `file-layer and archive roots resolve inside account space`() {
        val facade = STORAGE_FACADE_SRC.readSource()
        assertTrue(
            "StorageFacade 初始化 SaveFileManager 必须落在账号空间（accountSpace.requireRoot()）——" +
                "saves/ 目录随账号隔离",
            facade.contains("saveFileManager.initialize(accountSpace.requireRoot())")
        )
        val archiver = DATA_ARCHIVER_SRC.readSource()
        assertTrue(
            "DataArchiver 归档基目录必须经 accountSpace.requireArchivesDir()（archives/ 随账号隔离）",
            archiver.contains("accountSpace.requireArchivesDir()")
        )
        val storageModule = STORAGE_MODULE_SRC.readSource()
        assertTrue(
            "StorageModule.provideDataArchiver 必须注入 AccountSpaceManager（归档路径随账号空间）",
            storageModule.contains("AccountSpaceManager")
        )
    }

    @Test
    fun `wipe covers account space tree and legacy device-scoped leftovers`() {
        val wipe = SAVE_WIPE_SRC.readSource()
        assertTrue(
            "删档重置必须整树删除 accounts/（新结构残留=删档未生效，增量铁律 18）",
            wipe.contains("AccountSpaceManager.ACCOUNTS_DIR_NAME").let { accounts ->
                accounts && wipe.contains("deleteRecursively()")
            }
        )
        assertTrue(
            "删档重置必须清理分库前设备级旧位置残留（databases/ 库文件族 + saves/ + archives/）",
            wipe.contains("getDatabasePath(AccountSpaceManager.DATABASE_FILE_NAME)") &&
                wipe.contains("StorageConstants.BACKUP_DIR_NAME")
        )
    }

    @Test
    fun `logout five-piece set is shared and every entry point reuses it`() {
        val fullLogout = FULL_LOGOUT_SRC.readSource()
        assertTrue(
            "五件套清单必须完整（clearSession + SDK 登出 + 停时长 + 解绑回调 + 关数据空间）",
            fullLogout.contains("sessionManager.clearSession()") &&
                fullLogout.contains("TapTapAuthManager.logout()") &&
                fullLogout.contains("TapDBManager.stopGameDurationTracking()") &&
                fullLogout.contains("ComplianceManager.unregisterCallback()") &&
                fullLogout.contains("accountSpace.closeCurrent()")
        )
        val activityCall  = "com.xianxia.sect.login.performFullLogout(sessionManager, accountSpace)"
        val mainActivity = MAIN_ACTIVITY_SRC.readSource()
        val gameActivity = GAME_ACTIVITY_SRC.readSource()
        assertTrue(
            "MainActivity 登出必须复用共享五件套（login/FullLogout.kt）——禁止手抄清单",
            mainActivity.contains(activityCall)
        )
        assertTrue(
            "GameActivity 两处登出入口（设置页 onLogout / 合规弹窗 performComplianceLogout）" +
                "必须复用共享五件套且与 MainActivity 逐字一致",
            gameActivity.contains(activityCall)
        )
        // 清单唯一性：SDK 登出 API 只允许出现在 FullLogout.kt（收敛面），登出入口不再手抄
        val handCopied = listOf(MAIN_ACTIVITY_SRC, GAME_ACTIVITY_SRC).filter {
            it.readSource().contains("TapTapAuthManager.logout()")
        }
        assertTrue(
            "登出清单必须只在 login/FullLogout.kt 实现一次（手抄清单 = 新入口漏件回归源）: $handCopied",
            handCopied.isEmpty()
        )
    }

    @Test
    fun `no production file write bypasses the account space except declared device-scoped surfaces`() {
        val excluded = DEVICE_SCOPED_FILE_WRITES + APP_DEVICE_SCOPED
        val roots = listOf(
            "src/main/java/com/xianxia/sect/data" to "data",
            "../../app/src/main/java/com/xianxia/sect" to "app",
            "../../feature/game/src/main/java/com/xianxia/sect" to "feature/game"
        )
        val violations = roots.flatMap { (rootPath, label) ->
            val root = File(rootPath)
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .mapNotNull { f ->
                    val rel = f.relativeTo(root).path.replace('\\', '/')
                    if (rel in excluded) return@mapNotNull null
                    val text = f.readText()
                    if (text.contains("context.filesDir") || text.contains("filesDir,")) {
                        "$label/$rel"
                    } else null
                }
        }
        assertTrue(
            "发现绕过账号空间的 filesDir 落点（跨 accountKey 可见路径）：$violations。 " +
                "处理指引：新增游戏数据落点必须经 AccountSpaceManager 路径族；" +
                "确属设备级数据的，把文件相对路径加入 DEVICE_SCOPED_FILE_WRITES 并注明理由",
            violations.isEmpty()
        )
    }

    private fun File.readSource(): String =
        also { f -> assertTrue("源文件不存在: $f（测试工作目录 = core/data 模块根）", f.exists()) }
            .readText()
}
