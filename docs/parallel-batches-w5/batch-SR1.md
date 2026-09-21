# SR-1 邮件并入 SaveData 快照施工卡（wire 面单独走批）

> 立卡：2026-09-22（SR 看护派发，新建 ZCode 子会话实施；本会话 = 实施会话）。
> 权威依据 = `docs/save-system-refactor-plan-2026-09-21.md` §4「SR-1」+ §0 决策/不变量
> （IN4 wire 唯一性、IN8 Diff* 0 skip、D7 差分管线信封语义零破坏）；SR-0 侦察结论 =
> `docs/sr0-recon-report-2026-09-21.md`（总判定 Go）。**方案文档 untracked，直读工作区，不自行提交**。
> 编排台账 = `dispatch-ledger.md` SR 系列批次总表。
> 纪律：本会话只做 SR-1 一批，不开下一批；**wire 面单独走批、不夹带**；迁移链历史基线零触碰；
> 每子项独立 commit；不得自行登记 `accepted`（归用户）。

---

## 0. 定位

审计 §10 唯一不可回滚缺口收编：**邮件只存在于 Room `mails` 表、不在 SaveData 快照**——
云唯一存档（D2）下换设备 = 丢邮件。本批把邮件并入 SaveData wire 面：

- **保存**：从 `mails` 表读当前 slot 全量入快照（本地保存 + 云上传同源）；
- **加载/云恢复**：整对象替换回表；
- **删档**：`clearAllSlotTables` 清单纪律（已含 `mailDao().deleteAllForSlot`，守卫自动覆盖，零改动）；
- **30 天墙钟删除逻辑本批不动**（归 SR-5 TimeSource 收口）；快照如实携带表内现状
  （含尚未被惰性清理的过期行），不新增/不改任何删除语义。

**门**：`:core:data` + `:core:domain` 定向 + 六模块组合门 + `Diff*` 对拍 0 skip（IN8）
+ 存档回归逐字段等价实跑输出 + 桌面 ctest 照跑。

## 1. 关键勘察结论（实施前提，2026-09-22 直读代码确认）

1. **SaveData 构造面收敛**：生产 engine.save 仅 3 调用方（主保存 `performSaveOperation` /
   新游戏 `performSynchronousSave` / 重开 `performRestartSave`），SaveData 全部出自
   `SaveDataTrimmer.trimSaveData` 或 `buildRestartSaveData`（RestartOps 直构，同语义）；
   云上传 `performCloudUpload` **不走本地 save**，经 `createSaveDataFromSnapshot` 直连
   `tapCloudSaveManager.uploadSave`——注入点必须在快照构造面，覆盖本地与云两条路。
   `createSaveData()` / `createSaveDataSync()` 零调用者（死代码），且后者非挂起无法读表，
   按方案 IN6 删除（防未来误用 = 静默抹邮件）。
2. **DB 写路径唯一收口**：`writeAllDataToDatabase`（外层 `performFullTransactionSave`
   的 `withTransaction` 内）——邮件整对象替换（先删后写）落在该事务内即满足 IN1；
   备份恢复路径（`StorageEngine:386` performFullTransactionSave 直调）同语义：
   新 `.sav` 带邮件按快照还原，旧 `.sav` 无 tag = 空表（点时还原，方案明示单向兼容）。
3. **云恢复路径现状**（审计 §3/§12-I 已知缺口，SR-3 才落盘全量）：`performCloudLoad`
   → `applyCloudSaveToEngine(processed, CLOUD_SAVE_SLOT=0)` 纯内存 boot。本批在其
   boot 前**只替换 slot 0 邮件表**（整对象替换回表，方案 SR-1 明文），不做全量落盘（不越 SR-3）。
4. **Room schema 零变化**：`mails` 表/实体结构不动（仅加序列化注解），无 Room 版本迁移；
   `ClearAllSlotTablesCoverageTest` 源扫描守卫已覆盖 mailDao 删档路径（零改动）。
5. **CoverageTest 递归约束**：`ProtoNumberCoverageTest` 沿 SaveData @ProtoNumber 字段递归进
   `MailEntity` ⇒ 邮件 14 字段**必须显式 @ProtoNumber**（1..14，声明序 = 隐式序，wire 中性）
   且非零默认（id 随机/source/mailType/senderName/attachments）**必须 @EncodeDefault(ALWAYS)**。
6. **IN4 守卫覆盖面补强**：`ProtoNumberUniquenessTest` 现只扫 domain model——本批把
   core:data `data/model`（SaveData.kt）纳入扫描 + 方向锁（mails 恒 56），参照 908f24180 口径。
7. **SR-0 警示遵守**：Room 2.7.0 嵌套事务"吞内层异常 = 仅回滚内层写"——云恢复替换
   助手不吞内层异常、不做部分提交。

## 2. 子项分解与 commit 切分

| # | 子项 | 内容 | commit |
|---|---|---|---|
| C1 | 施工卡 + 台账 | 本卡 + 台账 SR-1 行 in_progress + 监控日志一行 | 1 笔 |
| C2 | wire 面 | `SaveData.mails: List<MailEntity> = emptyList()`（proto **56** = 现有最大 55+1，参照 908f24180 升序口径）+ `MailEntity` 14 字段显式 @ProtoNumber(1..14) + 5 字段 @EncodeDefault(ALWAYS) + IN4 守卫扩展（扫描面 + 方向锁） | 1 笔 |
| C3 | core:data 读写面 | `MailDao.getAllForSlotSync`（SELECT 全量，ORDER BY sendTime DESC）+ `writeAllDataToDatabase` 链邮件整对象替换（clearOldSlotEntities 删 + writeMails 分批写，同事务）+ engine 内部 `getMailsForSlot` / `replaceMailsForSlot`（单事务删+写，不吞内层异常）+ `StorageFacade` 两窄方法 | 1 笔 |
| C4 | feature/game 注入面 | `trimSaveData(snapshot, mails)` **必填参数**（不给默认值 = 编译器强制每个构造点想一遍）+ 四个构造点注入（主保存按目标 slot / 新游戏按 slot / 重开按 slot / 云上传按会话槽位）+ 云恢复 `applyCloudSaveToEngine` boot 前替换邮件表 + 删除死代码 `createSaveData`/`createSaveDataSync`（IN6） | 1 笔 |
| C5 | 测试 | ① proto roundtrip 逐字段等价（样本含**含附件未领邮件**，附已读已领/未读/永久有效/远端邮件/过期未清形态）+ 旧档缺 tag ⇒ 默认空表；② Robolectric 真库邮件读/替换/槽位隔离；③ 写路径源扫描守卫（writeAllDataToDatabase 链含邮件删+写）；④ trim 注入传递测试 | 1 笔 |
| C6 | 门禁 + 报告 | 定向门 + 六模块组合门（XML executed 计数 + 时间戳判绿）+ Diff* 0 skip + ctest + 完成报告 + 台账 delivered | 1 笔 |

## 3. 兼容红线复述（方案 §4 SR-1 原文口径）

- **wire 面单独走批**：本批唯一 wire 变更 = SaveData 新 tag 56；不夹带其他 wire/schema 面；
- **旧档无 mails 字段 ⇒ 默认空表**：向后兼容单向（旧 App 读新档忽略未知 tag；新 App 读旧档空表），
  无需迁移器条目（SaveDataVersionMigrator 零触碰，迁移链历史基线零触碰）；
- **roundtrip 测试**：存→读→逐字段等价，样本须含「含附件未领邮件」；
- **30 天删除逻辑不动**：MailDao.deleteExpired / insertWithEnforceLimit / MailService 零触碰；
- **IN8**：`Diff*` 对拍 0 skip 照排出厂门；**D7**：差分管线（exportDirty → mergeGameDataChanges）零触碰。

## 4. 门禁自检清单（结果如实写入完成报告）

1. `:core:data` + `:core:domain` 定向测试（新增 + 守卫族 ProtoNumber* / ClearAllSlotTablesCoverage / SaveDataVersionMigrator）；
2. 六模块组合门：`cd android && JAVA_HOME=C:/Users/cp050/.jdks/jdk-21.0.12.1+1 ./gradlew.bat
   testReleaseUnitTest --max-workers=1 --rerun-tasks "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so" detekt compileReleaseKotlin lintRelease`；
   判绿 = XML executed 计数 + 时间戳落在本轮门内；基线 = 最近收官口径（remediation §5.1：7861/0/17，SR-0 后无产品码变更）；
3. `Diff*` 对拍 0 skip（IN8，XML skip 计数）；
4. 存档回归逐字段等价实跑输出（C5-① 测试日志贴完成报告）；
5. 桌面 ctest 照跑（本批零 C++/JNI 面 ⇒ 桥重建可免、`ninja: no work to do.` 预期，ctest 基线 1558 照跑，理由写入报告）。

## 5. 红线复述

- SR 方案文档 untracked，不自行提交；`atlas-rgba-manifest.json`（构建副产物）与
  `.clash-repair/`、`.workbuddy-ai/`（杂项）勿混提交；
- 本会话只做 SR-1；不动 SR-2+ 施工面（TapCloudSaveManager 仲裁/上传队列/重开顺序均不触碰）；
- 每子项独立 commit、不夹带；台账只动 SR 相关行与监控日志；
- `accepted` 归用户/验收轮，本会话最多登 `delivered`。

## 6. 状态

| 子项 | 状态 | commit | 备注 |
|---|---|---|---|
| C1 施工卡 + 台账 | ✅ 完成 | `81efe4bd4` | |
| C2 wire 面 | ✅ 完成 | `e7185c971` | SaveData tag 56 + MailEntity 14 字段显式标注（5 字段 EncodeDefault ALWAYS）+ IN4 守卫扩展（data/model 入扫 + mails=56 方向锁） |
| C3 core:data 读写面 | ✅ 完成 | `d44d3babc` | getAllForSlotSync + 写路径整对象替换（同事务）+ getMailsForSlot/replaceMailsForSlot + StorageFacade 窄接口 |
| C4 feature/game 注入面 | ✅ 完成 | `0c6d48ced` | trim 必填 mails 参数 + 4 构造点注入 + 云恢复 boot 前替换 + 死代码删除（IN6；saveOnBackground 为 SR-4 挂点原样保留） |
| C5 测试 | ✅ 完成 | `df7d59f4b` | wire roundtrip 逐字段等价（5 形态含未领附件）+ 旧档默认空表 + Robolectric 真库替换/隔离 + 源扫描守卫 + trim 注入；12/12 绿 0 skip |
| C6 门禁 + 报告 | ✅ 完成 | `8e554a765`/`252b2e3c0`/本笔 | 组合门两轮 detekt 失败如实修复（TooManyFunctions→邮件 ops 独立成文件；LongMethod→注入压缩单行；SR-0 台架潜伏违例一并清理）后第三轮全绿：**7893/0/17 + Diff* 273/0 skip + ctest 1561/1561**；报告 `report-SR1-completion-2026-09-22.md`；台账 delivered |
