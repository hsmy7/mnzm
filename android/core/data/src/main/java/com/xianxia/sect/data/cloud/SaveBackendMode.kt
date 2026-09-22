package com.xianxia.sect.data.cloud

import com.xianxia.sect.data.prefs.KeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云存档后端三态开关（方案 §2 模式开关 = 回滚与灰度的总闸，SR-2 引入）。
 *
 * - [LEGACY]：现状——本地为真相，云为镜像。**默认值 = 回滚臂**：本模式下上传队列零活动、
 *   触发点短路、UI 零新增提示，全链行为与 SR-2 落库前逐行一致（硬红线，守卫测试锚定）。
 * - [CLOUD_TRANSITION]：双写——本地照旧 + 云上传收口（SR-3 起逐设备切换）。
 * - [CLOUD_ONLY]：云为真相，本地=缓存，文件层退役（SR-6 迁移达标后切换）。
 *
 * 切换纪律（方案 §4）：任何一级出问题退回上一级；默认值不得擅改——
 * 升档属产品/编排决策（SR-3/SR-6 批内拍板）。
 */
enum class SaveBackendMode {
    LEGACY,
    CLOUD_TRANSITION,
    CLOUD_ONLY
}

/**
 * [SaveBackendMode] 读取端（MMKV 持久化；未写入/非法值一律回落 LEGACY——
 * 防御历史脏数据或手改存储把设备推入未验证模式）。
 */
@Singleton
class SaveBackendModeProvider @Inject constructor(private val store: KeyValueStore) {

    fun current(): SaveBackendMode = fromStored(store.getString(KEY, null))

    /**
     * 写入模式（SR-3/SR-6 切换批接线；本批生产代码零调用，仅守卫测试与后续批消费）。
     */
    fun set(mode: SaveBackendMode) {
        store.putString(KEY, mode.name)
    }

    companion object {
        const val KEY = "save_backend_mode"

        /** 解析存储值；null/未知字符串一律 LEGACY（失败封闭：未知值不得激活新链路） */
        fun fromStored(raw: String?): SaveBackendMode =
            SaveBackendMode.values().firstOrNull { it.name == raw } ?: SaveBackendMode.LEGACY
    }
}

/**
 * 本地保存成功后是否投递云上传队列——**纯函数**，桌面/JVM 可直测
 * （参照 shouldAutoSave 先例（SR-4 更名，月变与 onStop 共用））。LEGACY 短路 = 默认全链零新增行为。
 */
fun shouldEnqueueCloudUpload(mode: SaveBackendMode): Boolean = mode != SaveBackendMode.LEGACY

/**
 * 本地存档文件层（`.sav` / `.bak` / `.tmp` / tombstone）是否仍写入——SR-7 的文件层退役判据。
 *
 * 纯函数（零依赖、JVM 可直测，同 [shouldEnqueueCloudUpload] 先例）。三态语义：
 * - `LEGACY` / `CLOUD_TRANSITION` ⇒ **true**：D5 明令轮转备份"在过渡期继续保护玩家"，
 *   今天全部设备的模式恒为 LEGACY ⇒ 本批零行为变化；
 * - `CLOUD_ONLY` ⇒ **false**：云档是唯一玩家可见存档（D2），本地 Room 是可丢弃会话缓存（D1），
 *   旧 `.sav` 降级为**只读应急源**（读侧不受本判据限制，但不得再被修复性写回）。
 *
 * 判据只关"写"，不关"读"与"清理"：应急读与过期文件清理（N 版保留）在 CLOUD_ONLY 下继续有效。
 */
fun shouldWriteLocalSaveFile(mode: SaveBackendMode): Boolean = mode != SaveBackendMode.CLOUD_ONLY
