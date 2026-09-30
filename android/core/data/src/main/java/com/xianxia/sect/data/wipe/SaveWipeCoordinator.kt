package com.xianxia.sect.data.wipe

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.data.archive.DataArchiver
import com.xianxia.sect.data.local.GameDatabase
import com.tencent.mmkv.MMKV
import java.io.File

/**
 * 单存档测试期删档重置协调器（SS0）。
 *
 * 职责：在新版本首次启动时把**本机全部旧数据**清零——数据库文件族、启动前
 * 快照/恢复残留、`.sav`/`.bak` 文件层、归档目录、云端台账 MMKV 键、账号与
 * 合规缓存（W5）；并以 `wipe_single_save_done` 标记保证幂等（重复启动零副作用）。
 *
 * 触发两路（W12/D-6）：
 * - 首次启动自动清：[wipeIfNeeded]（Application.onCreate 在 MMKV 初始化后调用，
 *   此时 Room 数据库尚未被 Hilt 打开，删文件安全）；
 * - 开发入口：[requestWipeOnNextLaunch] 置待执行标记 + 进程重启，下次启动走
 *   同一执行路径（游戏运行中引擎/DB 均已加载，禁止原地删文件）。
 *
 * 边界：`AdFreeWhitelist` 为编译期白名单（W11），不在清理范围；云端旧协议档由
 * TapCloudSaveManager.oneTimeCleanup 登录后尽力删除（命名失联才是主保险，D-5）。
 * 后续新增持久化面（新目录/新台账键）时必须同步本清单，否则删档会留下残留。
 */
object SaveWipeCoordinator {

    private const val TAG = "SaveWipeCoordinator"

    /** 幂等标记：本机已执行过一次删档重置 */
    private const val WIPE_DONE_KEY = "wipe_single_save_done"

    /** 开发入口待执行标记：置位后下次启动执行一次删档 */
    private const val WIPE_PENDING_KEY = "wipe_single_save_pending"

    /** 业务偏好 MMKV 实例（与 GamePreferences 同实例——云端台账键所在存储） */
    private const val PREFS_MMKV_INSTANCE_ID = "game_prefs"

    /** 云端台账 MMKV 键前缀（上传账本现役前缀 + 存量迁移旧协议遗留前缀） */
    private val CLOUD_LEDGER_KEY_PREFIXES =
        listOf("cloud_upload_ledger_", "cloud_migration_")

    /** 新版本首次启动删档入口（幂等；待执行标记优先于 done 标记） */
    fun wipeIfNeeded(context: Context) {
        val done = runCatching { prefsKv()?.decodeBool(WIPE_DONE_KEY, false) ?: false }
            .getOrDefault(false)
        val pending = runCatching { prefsKv()?.decodeBool(WIPE_PENDING_KEY, false) ?: false }
            .getOrDefault(false)
        if (done && !pending) return

        Log.i(TAG, "单存档删档重置开始（done=$done pending=$pending）")
        executeWipe(context)
        runCatching {
            prefsKv()?.encode(WIPE_DONE_KEY, true)
            prefsKv()?.removeValueForKey(WIPE_PENDING_KEY)
        }.onFailure { Log.w(TAG, "删档幂等标记写入失败（下次启动将重复清理，清理本身幂等）", it) }
        Log.i(TAG, "单存档删档重置完成")
    }

    /** 开发入口：置待执行标记（调用方随后重启进程，下次启动执行删档） */
    fun requestWipeOnNextLaunch() {
        prefsKv()?.encode(WIPE_PENDING_KEY, true)
        Log.w(TAG, "已置删档待执行标记，进程重启后生效")
    }

    /**
     * 执行本机清零（幂等：不存在之物删除即 no-op）。
     * internal 供 Robolectric 单测直测文件清理面（MMKV 在测试沙箱不可用，键清理
     * 经 runCatching 降级跳过）。
     */
    internal fun executeWipe(context: Context) {
        // 1. 数据库文件族（主文件 + WAL/SHM）——旧库连同影子表/元数据整体清零
        val dbFile = GameDatabase.getUnifiedDatabaseFile(context)
        listOf("", "-wal", "-shm").forEach { suffix ->
            File(dbFile.absolutePath + suffix).delete()
        }

        // 2. 启动前快照与恢复残留（版本化快照/恢复 marker/恢复临时文件）——
        // 与 DB 主文件同目录（databases/，非 filesDir 根）
        val snapshotPrefixes = listOf(
            dbFile.name + ".pre_migrate_backup",
            dbFile.name + ".restore"
        )
        dbFile.parentFile?.listFiles()
            ?.filter { file -> snapshotPrefixes.any(file.name::startsWith) }
            ?.forEach(File::delete)

        // 3. 文件层存档目录与归档目录整树删除
        File(context.filesDir, StorageConstants.BACKUP_DIR_NAME).deleteRecursively()
        File(context.filesDir, DataArchiver.DEFAULT_ARCHIVE_DIR_NAME).deleteRecursively()

        // 4. 云端台账键（上传序号账本 + 存量迁移状态）；MMKV 未就绪时跳过
        //（键留存不构成旧档恢复路径——台账只记序号，不记档内容）
        runCatching {
            val kv = prefsKv() ?: return@runCatching
            kv.allKeys()
                ?.filter { key -> CLOUD_LEDGER_KEY_PREFIXES.any(key::startsWith) }
                ?.forEach(kv::removeValueForKey)
        }.onFailure { Log.w(TAG, "云端台账键清理跳过（MMKV 未就绪）", it) }

        // 5. 账号 + 合规缓存（W5：强制玩家重新登录与实名；白名单特权不在其中）
        SessionManager(context).clearAllAccountData()
    }

    /** 业务偏好 MMKV 实例（未初始化返回 null，调用方降级） */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: MMKV 未初始化异常源跨 native 不可枚举, 降级 null+日志留痕, 非静默吞噬
    private fun prefsKv(): MMKV? = try {
        MMKV.mmkvWithID(PREFS_MMKV_INSTANCE_ID)
    } catch (e: Throwable) {
        Log.w(TAG, "MMKV access failed (not initialized?)", e)
        null
    }
}
