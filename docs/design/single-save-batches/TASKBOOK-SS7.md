# TASKBOOK-SS7 · 云：灾备 + 换设备续玩

> **本文件是 SS7 的派工真源**（开工补卡）。上位方案 §2.3（云端角色）+ §2.5（上传链）。
> 协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 入口判据：**SS4（单档 UI 终态）与 SS3（持久化面收口）已合入**（`8b2e5c035`/`d1d5940d1`）；SS5/SS6 已并网（上传链挂点就绪）。
> 行号证据：2026-10-02 上午主树实测。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 云端=**灾备 + 换设备续玩**（明确不支持同时多设备）：新设备登录后能从云恢复；写侧有 W>C 防覆盖护栏；槽位维度的最后残留（云侧键空间）随本批坍缩为单键。 |
| 验收① | **换设备续玩链路通**：新设备登录 → 本地无档 → `AutoEntryResolver` 走云分支（`LoadCloud`）→ 云档下载 → 本地落盘 → 进入游戏；测试覆盖"本地无档+云有档"判定与下载落盘链 |
| 验收② | **新云端命名基线坍缩为单键**：`mnzm_v2_slot_1..6` 槽命名族（`TapTapSaveBackend.kt:379` `V2_SLOT_PREFIX` + `slotFromArchiveName` 映射 + `UploadLedger` 按槽键 + `UploadQueue.enqueue(slot,…)`）收口为**单一云档名**（复用 slot 0 的 `mnzm_v2_save` 单档语义或等价新名，报告拍板）；`StorageConstants.CLOUD_SAVE_SLOT` 与 `SlotLockManager` 槽锁命名残留一并清 |
| 验收③ | **`downloadIntoCache(sourceSlot, targetSlot)` 跨槽语义坍缩**（SS1/SS4 报告点名的最后残留）：`CloudSaveCacheWriter` / `SaveLoadViewModelCloudSlotOps`（`resolveCloudConflict`→GameActivity 冲突弹窗消费面保留）改单档签名；SS4 登记的两文件注释历史表述随批清 |
| 验收④ | **W > C 防静默覆盖**：保存前比对云端 `currentCloudSaveId()`（既有面），`W > 本地 C` ⇒ **只读降级 + 玩家提示**，绝不静默覆盖——守卫测试锁定"降级路径无写调用" |
| 验收⑤ | **复用既有云基础设施，不新建云通道**：`SaveArbiter`（`core/data/cloud/SaveArbiter.kt`）/ `UploadLedger` / `UploadQueue`（2s 去抖/同档留最新/60s 限频/退避/熔断）语义不变；SS6 的事件触发落盘入队链（`maybeEnqueueCloudUploadAfterLocalSave`）零改动 |
| 验收⑥ | **存档冲突（`SaveArbiter`）语义按单档+多设备禁令复核**：`W > C` 只读降级 / `C > W` 提示拉取 / 相等直过——三臂与"不支持同时多设备"的产品拍板一致，守卫同步 |
| **不做** | 不做云端历史版本；不做增量上传（TapTap 单 blob）；不引入服务端；不改 SS6 事件触发链；不碰 C++ |

---

## 2. 实测现状（2026-10-02 主树）

| 面 | 位置 |
|---|---|
| 云命名 | `TapTapSaveBackend.kt`：`SESSION_ARCHIVE_NAME="mnzm_v2_save"`（:376）、`V2_SLOT_PREFIX="mnzm_v2_slot_"`（:379）、`slotFromArchiveName` 映射（:289 消费）、`isLegacyArchiveName`（SS0 旧命名删除器） |
| 台账键 | `UploadLedger` 按槽键、`UploadQueue.enqueue(slot,…)`（`core/data/cloud/`）；`SaveLoadViewModelCloudUploadHooks.kt` 按槽入队 |
| 跨槽下载 | `CloudSaveCacheWriter.downloadIntoCache(sourceSlot, targetSlot)`；`SaveLoadViewModelCloudSlotOps`（`resolveCloudConflict` → GameActivity 冲突弹窗，活消费面） |
| 常量 | `StorageConstants.CLOUD_SAVE_SLOT`（core/data） |
| 仲裁 | `SaveArbiter.kt`（core/data/cloud）——W/C 比对语义载体 |

---

## 3. 决策

| # | 决策 | 依据 / 代价 |
|---|---|---|
| **D-1** | **云端单档名复用 `mnzm_v2_save`**（slot 0 命名即单档语义，v2 时代 slot 0 与本地单档已无区别）；`slot_N` 族命名与映射全删 | 最小改名面；旧 `slot_N` 档在新版不可识别（与 SS0 旧命名失联同构） |
| **D-2** | **`UploadLedger`/`UploadQueue` 键坍缩为单键**，队列语义（去抖/限频/退避/熔断）零变更 | 槽维度消失；键只是存储定位 |
| **D-3** | **`resolveCloudConflict` 冲突弹窗保留**（活消费面），内部签名单档化 | 多设备禁令下的用户告知通道 |
| **D-4** | **`isLegacyArchiveName` 删除器扩展覆盖 `mnzm_v2_slot_N`**（v2 槽命名也成"旧命名"） | 旧云档清理双保险语义延续（SS0 W1） |
| **D-5** | **W>C 只读降级为本批硬门** | 方案 Q6 拍板"明确不支持同时多设备"；静默覆盖=数据丢失事故 |

---

## 4. 文件面与切片（≤10 文件/片）

| 片 | 允许改 | 禁止改 | 自检项 |
|---|---|---|---|
| **SS7-a** 命名坍缩 | `TapTapSaveBackend.kt`、`TapCloudSaveManager.kt`、`StorageConstants.kt`、`SlotLockManager`（命名残留） | 仲裁语义 | `slot_N` 族零残留；`isLegacyArchiveName` 扩 v2 槽命名 |
| **SS7-b** 台账/队列单键 | `UploadLedger`、`UploadQueue`、`SaveLoadViewModelCloudUploadHooks.kt` | 队列节流语义 | enqueue 签名单档；既有队列测试绿 |
| **SS7-c** 跨槽下载坍缩 | `CloudSaveCacheWriter.kt`、`SaveLoadViewModelCloudSlotOps.kt`、`AutoEntryResolver`（如涉） | 冲突弹窗入口 | `downloadIntoCache` 单档签名；换设备链测试绿 |
| **SS7-d** W>C 护栏 | `SaveArbiter` 复核/守卫测试、降级提示面 | 三臂语义（除非与 Q6 冲突） | 降级路径无写调用断言 |
| **主线程** | `report-SS7.md`、门禁复跑 | — | §5 清单 |

---

## 5. 门禁清单

```powershell
# 工作目录 = C:\Mnzm\XianxiaSectNative\android
.\gradlew.bat compileReleaseKotlin --console=plain
.\gradlew.bat :core:data:testReleaseUnitTest :feature:game:testReleaseUnitTest :app:testReleaseUnitTest --max-workers=1 "-Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so" --rerun-tasks --console=plain
.\gradlew.bat :core:data:detekt :feature:game:detekt :app:detekt --console=plain
node scripts/check-agent-instructions.mjs
```

**提交前**：`git status` 只留本批改动；构建副产物 `git checkout --` 还原；一次性脚本清零；**文档面编辑一律在 worktree 内完成并随批 commit**。

---

## 6. 登记项

1. **跨批登记（SS10）**：玩家可见变更（换设备续玩+W>C 提示）并入 4.2.00 唯一条目。
2. **真机 pending-device**：换设备续玩链路 + W>C 场景需真机双设备验证（JVM 面以 stub 后端覆盖）。
3. **风险（必须写进报告）**：`slot_N` 档改名后旧云档失联——与 SS0 删档口径一致（W1 双保险），报告列明受影响面。
4. **风险**：`UploadLedger` 键坍缩对既有升级设备的旧键残留——列清理策略（参考 SS0 wipe / `DataPruningScheduler` 孤儿清理先例）。

---

## 7. 一句话给执行者

**把云侧最后的槽位残留坍缩成单键：`mnzm_v2_slot_N` 族与 `downloadIntoCache` 跨槽签名全部单档化、`UploadLedger`/`UploadQueue` 单键、W>C 只读降级护栏加守卫——复用既有仲裁/队列/上传链，不新建任何云通道。**
