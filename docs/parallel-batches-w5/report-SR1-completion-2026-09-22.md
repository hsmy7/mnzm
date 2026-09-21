# SR-1 完成报告——邮件并入 SaveData 快照（wire 面单独走批）

> 日期：2026-09-22（凌晨实施会话交付）
> 施工卡 = `batch-SR1.md`；权威依据 = `docs/save-system-refactor-plan-2026-09-21.md` §4 SR-1 + §0 不变量；
> SR-0 前置 = `docs/sr0-recon-report-2026-09-21.md`（Go）。
> 状态：**全部门禁绿，交付落库；`accepted` 留待用户/验收轮**。

---

## 1. 交付摘要

SaveData 增 `mails: List<MailEntity>`（proto tag **56**），邮件快照生命周期全链路打通：

| 环节 | 实现 | 落点 |
|---|---|---|
| wire 面 | `@ProtoNumber(56)` = 本类现有最大 55+1（908f24180 升序口径）；MailEntity 14 字段显式 `@ProtoNumber(1..14)`（声明序=wire 号）+ 5 非零默认字段 `@EncodeDefault(ALWAYS)` | `SaveData.kt` / `MailEntity.kt` |
| 保存 | 从 `mails` 表读当前 slot 全量入快照：主保存（目标 slot）/ 新游戏首存（slot）/ 重开预存（slot）/ 云上传（会话槽位 getCurrentSlot）四构造点统一 `readSlotMails` 注入 | `SaveDataTrimmer.trimSaveData(snapshot, mails)` **必填参数**（无默认值 = 编译器强制构造点直面整对象替换语义） |
| 本地写库 | `writeAllDataToDatabase` 链邮件整对象替换：`clearOldSlotEntities` 删侧 + `writeMails` 写侧（分批 slotId 盖章），同处 `performFullTransactionSave` 外层事务（IN1） | `StorageEngineWriteOps.kt` |
| 云恢复 | boot 前 `replaceMailsForSlot(云会话槽位0, 快照 mails)` 单表整对象替换——防下次保存用本地残留旧表覆盖云邮件（换设备丢邮件 = 本批根治缺口）；全量落盘仍归 SR-3 不越界 | `SaveLoadViewModelCloudLoadOps.kt` |
| 删档 | `clearAllSlotTables` 已含 `mailDao().deleteAllForSlot`，源扫描守卫自动覆盖，**零改动** | `StorageEngineSaveSupport.kt`（未动） |
| 读失败语义 | 四构造点读邮件失败一律上抛如实失败（保存中止/首存失败/上传中止/云恢复报错），**不做空表静默降级**（降级 = 抹邮件） | feature/game 四 Ops |

**红线合规**：
- **wire 面单独走批** ✓ 本批唯一 wire 变更 = tag 56；不含其他 wire/schema 面；
- **旧档无 mails 字段 ⇒ 默认空表** ✓ 向后兼容单向，`SaveDataVersionMigrator` 零触碰（迁移链历史基线零触碰），roundtrip 实测旧档缺 tag 解码空表；
- **30 天墙钟删除逻辑本批不动** ✓ `MailDao.deleteExpired` / `insertWithEnforceLimit` / `MailService` 零触碰；快照如实携带表内现状（含未清过期行，语义归 SR-5）；
- **Room schema 零变化** ✓ `mails` 表结构不动（仅加序列化注解），无 Room 版本迁移；
- **D7 差分管线零触碰** ✓ IN8 实证见 §3；
- **SR-0 警示遵守** ✓ `replaceMailsForSlot` 单事务先删后写、不吞内层异常（源扫描守卫锁定）。

**顺带收编（非夹带，均有方案依据）**：
1. `createSaveData()` / `createSaveDataSync()` 零调用死代码删除（方案 **IN6**：无调用者即删；后者非挂起无法读表，留存 = 未来误用即静默抹邮件）；`saveOnBackground`（SR-4 规划挂点）**原样保留**。
2. SR-0 台架测试文件潜伏 detekt 违例清理（SR-0 为零产品代码批、无组合门义务，其台架从未过 detekt；本轮组门口首次暴露，纯格式修复、测试语义零变化，实测 7/7 仍绿）——见 §3.2。

## 2. Commit 切分（每子项独立，不夹带）

| # | commit | 内容 |
|---|---|---|
| C1 | `81efe4bd4` | 施工卡 `batch-SR1.md` + 台账实施侧开跑自记 |
| C2 | `e7185c971` | wire 面：SaveData.mails(56) + MailEntity 显式 proto 标注 + IN4 守卫扩展（data/model 入扫 + mails=56 方向锁） |
| C3 | `d44d3babc` | core:data：MailDao.getAllForSlotSync + 写路径整对象替换 + engine/Facade 窄接口 |
| C4 | `0c6d48ced` | feature/game：trim 必填 mails 参数 + 四构造点注入 + 云恢复单表替换 + 死代码删除（IN6） |
| C5 | `df7d59f4b` | 测试：wire roundtrip 逐字段等价 + DB 替换语义 + 写路径源扫描守卫 + trim 注入（12 例） |
| C6a | `8e554a765` | 组合门修复①：邮件 ops 独立成文件（detekt TooManyFunctions 15→11）+ SR-0 台架违例清理 |
| C6b | `252b2e3c0` | 组合门修复②：NewGameOps 注入压缩单行（detekt LongMethod） |
| C6c | 本笔 | 完成报告 + 台账 delivered + 施工卡状态回填 |

## 3. 门禁自检（全绿，证据如下）

### 3.1 六模块组合门（第三轮为最终判绿轮）

```
cd android && JAVA_HOME=C:/Users/cp050/.jdks/jdk-21.0.12.1+1 \
  ./gradlew.bat testReleaseUnitTest --max-workers=1 --rerun-tasks \
  "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so" \
  detekt compileReleaseKotlin lintRelease
```

**结果：`BUILD SUCCESSFUL in 24m 51s`，GATE_EXIT=0**（`/tmp/sr1-gate3.log`）。
按判绿纪律以 **XML executed 计数 + 时间戳**判绿（非 BUILD 字样）：六模块 JUnit XML 汇总
（时间戳 02:37–02:59，全部落在本轮门内）：

| 模块 | executed | failures | skipped |
|---|---|---|---|
| core:engine | 3396 | 0 | 0 |
| core:domain | 1743 | 0 | 0 |
| core:data | 728 | 0 | 15 |
| core:ui | 146 | 0 | 0 |
| feature:game | 889 | 0 | 0 |
| app | 991 | 0 | 2 |
| **合计** | **7893** | **0** | **17** |

- **failures=0 全绿；skipped=17 与基线 17（core:data 15 + app 2）完全一致，零新增 skip**；
- 总数较 7861 基线 +32：本批 +12、SR-0 台架 +7（该批无组合门义务故未入基线）、其余为
  B19/B20 已收官批次的既有增例（engine +38 等均为历史批增量，非本批）；
- `detekt` / `compileReleaseKotlin` / `lintRelease` 全绿。

前两轮门（如实登记）：轮 1 = `:core:data:detekt` 失败（本批 WriteOps 函数数超阈值 + 本批
新测试命名/长行 + **SR-0 台架潜伏违例首次暴露**）；轮 2 = `:feature:game:detekt` 失败
（NewGameOps 注入 +3 行触 LongMethod(60)）。两轮失败均已修复（C6a/C6b），修复过程零测试
语义变更（受影响 17/17 实测仍绿）。

### 3.2 IN8：Diff* 对拍 0 skip ✓

`core:engine` `Diff*` 50 类 XML 汇总：**executed=273 / skipped=0 / failures=0**——与基线
（50 类 / 273 例 / 0 skip）完全一致，差分管线信封语义零破坏（D7）。

### 3.3 存档回归：mails wire 逐字段等价实跑输出（红线项）

`SaveDataMailWireRoundtripTest` 4/4 绿（XML 02:37 本轮门内），样本 5 形态 × 14 字段逐字段断言：

```
testcase name="proto wire roundtrip keeps mails field-by-field"          — NullSafeProtoBuf 裸 wire
testcase name="production wire protobuf+lz4+checksum roundtrip keeps mails field-by-field" — 生产同路径(LZ4+checksum)
testcase name="legacy save without mails field deserializes to empty list" — 旧档缺 tag 56 ⇒ 默认空表
testcase name="empty mails omit from wire and roundtrip to empty list"     — 空快照与旧档同形
```

样本覆盖（`MailEntity` 14 字段逐一相等断言 + data-class 整体兜底）：
① **含附件未领邮件**（红线指定形态：`hasAttachment=true`+`attachmentClaimed=false`，
attachments JSON 含 rarity/itemId/extra 两件）；② 已读已领无附件；③ 未读未领永久有效
（expireTime=0）+ 带 remoteMailId（溢出/直发链路形态）；④ 过期未被惰性清理（快照如实携带，
30 天删除归 SR-5）；⑤ 近全默认（title/content 空、零时刻）。两条 wire 路径
（裸 proto / 生产 PROTOBUF+LZ4+checksum）均逐字段等价，checksumValid=true。

配套实跑：`MailSnapshotStoreTest` 3/3（真 `GameDatabase`：全量读 sendTime DESC + 槽位隔离 +
先删后写整对象替换 + 空快照⇒表空）、`MailSnapshotWritePathGuardTest` 3/3（源扫描守卫）、
`SaveDataTrimmerMailTest` 2/2（注入传递）。

### 3.4 桌面 ctest ✓（桥重建豁免理由）

- 本批 **零 C++/JNI 面**（git 证实：`android/app/src/main/cpp/` 自 B20b 后零提交、工作区零改动）
  ⇒ **桥重建可免**（`batch-SR1.md` §4 预案）；ninja 首跑补齐 2 步陈旧 bench 链接后收敛
  `ninja: no work to do.`（次轮验证），非本批引入；
- **ctest 照跑：`100% tests passed, 0 tests failed out of 1561`（65.69s，EXIT=0）**。
  基线 1558 → 1561 的 +3 为 B19/B20 批次增例（末笔 C++ 提交 `131019d61` B20b 台架），
  与本批无关（本批零 C++ 改动）。

### 3.5 定向门（各 commit 内实跑，XML 计数+时间戳判绿）

| 轮次 | 范围 | 结果 |
|---|---|---|
| C2 | IN4 守卫族 + 迁移链 + MailEntityTest | 守卫 3/3 + 覆盖 4/4 + 迁移链 8/8（零触碰实证）+ MailEntity 16/16 |
| C3 | serialization 族 + ClearAllSlotTablesCoverage | 29/29 绿 |
| C4 | SaveLoadViewModelLoadTest（既有回归） | 34/34 绿 |
| C5 | 本批新增 4 测试类 | 12/12 绿 0 skip |
| C6a | detekt 修复后受影响面 | 17/17 绿（含 SR-0 台架语义不变实证） |

## 4. 设计决策与语义口径（验收轮关注点）

1. **注入槽位选择**：本地保存按**目标 slot** 表读邮件（`performLocalSaveToSlot` 已先
   `setCurrentSlot(slot)`，常规保存=当前档，二者同一；另存他档场景与邮件 UI 的
   active-slot 读取口径自洽）；云上传按**会话槽位**（云会话=slot 0、常规会话=所在档）。
2. **云恢复只替换邮件表**：`performCloudLoad` 现状为纯内存 boot（审计 §3"下载不落盘"，
   SR-3 修复面）。若不做邮件单表替换，云会话期邮件 UI 读到本地残留旧表，且下次保存/上传
   会把旧表覆盖回云——恰为本批要根治的"换设备丢邮件"。单表替换是 SR-1 明文语义
   （"云恢复时整对象替换回表"）的最小实现，全量落盘不越 SR-3 之界。
3. **无条件替换 vs stacksSerialized 条件保留**：旧档缺堆叠字段时写路径跳过删表（可由实例
   重建）；旧档缺 mails 时替换为空表（无重建来源，方案明示"默认空表、无需迁移器条目"）。
   两语义**有意不同**，已在 `clearOldSlotEntities` 注释中显式声明，防后人误改。
4. **trimSaveData 必填 mails 参数**：不给默认值 = 漏传编译错而非静默空表抹邮件；
   SaveData.mails 本身的 `emptyList()` 默认值保持 data-class/既有序列化惯例（测试与
   旧档反序列化面依赖），抹邮件风险的守卫点设在构造面（trim 家族）——生产 engine.save
   三调用方已全部收口（勘察结论见施工卡 §1）。

## 5. 风险与残留（如实登记）

1. **payload 增量**：邮件上限 1000 封/slot（MailDao 容量纪律）。粗估满编极限档增量
   ≈200–300KB（proto+LZ4 前）量级，叠加 SR-0 实测最大档 0.29MB 后仍远低于 IN5 红线建议
   2MB（SR-0 §6.2）；`CloudPayloadSizeBenchTest` 样本未含邮件（SR-0 合成样本），IN5 CI
   断言收紧时建议补邮件满编样本（归 IN5 收紧批，不在本批夹带）。
2. **首轮组合门暴露的 SR-0 台架违例**已清理（纯格式/`error()` 等价替换），但 SR-0 台架
   自此正式进入组合门覆盖——后续批无需再豁免。
3. 邮件快照如实携带未清过期行：30 天删除为惰性触发（写入时清理），快照还原后过期件仍在
   表内等待同一惰性清理——删除语义零变化（SR-5 收口），无行为回归。

## 6. 纪律声明

- 本会话只做 SR-1 一批；SR-2+ 施工面（仲裁/上传队列/重开顺序）零触碰；
- wire 面单独走批：唯一 wire 变更 = SaveData tag 56（+其元素 MailEntity 标注）；
- 迁移链历史基线零触碰：`SaveDataVersionMigrator` 未改一行（迁移链 8/8 定向实证）；
- SR 方案文档保持 untracked 未提交；`atlas-rgba-manifest.json`/`.clash-repair/`/
  `.workbuddy-ai/` 未混入任何提交；台账仅动 SR 相关行与监控日志；
- **`accepted` 留待用户/验收轮**，本会话最高登 `delivered`。
