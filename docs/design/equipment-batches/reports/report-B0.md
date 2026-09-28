# B0 实施报告 · 存档编号规划与冻结守卫

> 批次：EQ-B0（装备系统重构首批）· 派发件 v2（`7a08e379b`）· 方案 HEAD（`94b910951` 及之后）
> 工作区：`C:\Mnzm\XianxiaSectNative-equipment`（worktree）· 分支 `feat/equipment-set`（开工时快进至 main = `fc19e6d5b`）
> 状态：全部写入面完成，门禁 1–5 实测绿；门禁 6 实测复现 D9 现状（预存、非本批引入，处置见 §3/§5）。

## 1. 做了什么

| 文件 | 变更 | 内容 |
|---|---|---|
| `core/domain/.../core/model/DiscipleSerializer.kt` | 改（+17/-1） | ① `DiscipleSurrogate` 新增**六部位新增段**：`headId(112)/bodyId(113)/handsId(114)/feetId(115)/legsId(116)`（按显示序 头/身/手/脚/武/腿）+ `innateDamageType(117)`——**只声明占号，不写入/不读取**（`buildSurrogate`/`withEquipmentUsage*` 未接线，旧档读到默认值，二进制向后兼容）；② `weaponId(17)` 复用为武器部位列（唯一复用号，语义复用、零代码变更）+ E1 冻结注释；③ 退役在册就地注释：`armorId(18)/bootsId(19)/accessoryId(20)`、`weaponNurture..accessoryNurture(24..27)`（B3 退役）、`pillNurtureSpeedBonus(47)`（B2 退役）、`equipmentNurturingCompletionMonth/Phase(98/99)`（B3 退役）；④ 头注释陈旧的「91 个字段」计数改为不带计数表述 |
| `core/data/.../data/model/SaveData.kt` | 改（+1） | `equipmentStacks(53)` 退役在册注释（B3 删堆叠批退役，禁改指向、退役后号禁复用） |
| `core/data/src/test/.../unified/EquipmentProtoNumberFrozenTest.kt` | **新增**（5 用例） | E1 冻结表守卫（详见 §2） |
| `docs/design/equipment-batches/reports/report-B0.md` | **新增** | 本报告 |

**零变更面确认**：Room schema 零变更（`GameDatabaseConfig.DATABASE_VERSION = 61` 实测于 `GameDatabase.kt:95`，未动；无迁移文件）；C++/UI/经济零触碰；`buildSurrogate`/`buildDisciple` 映射函数未改（新段无接线）；版本号未动。

**新增段字段类型说明**：六个新字段声明为 `String = ""`，与 `DiscipleSurrogate` 内既有枚举型字段（`status`/`spiritRootType`/`discipleType`/`activePillCategory`）的序列化口径一致。B1 接线 `innateDamageType` 时如需换型（varint 枚举），属 wire 破坏性变更，须按 E1 冻结流程改表 + 同步守卫后实施。

## 2. 冻结守卫（EquipmentProtoNumberFrozenTest，5 用例全绿）

| 用例 | 断言面 |
|---|---|
| `equipment section and new section match the frozen table` | 属性→编号逐条对照：`weaponId(17)`（唯一复用号）、`spiritStones(28)/storageBagItems(30)/storageBagSpiritStones(31)` 不动、新增段 `112..116` + `innateDamageType(117)`；缺失与错号分别报错 |
| `existing reserved proto numbers stay unclaimed` | 存量退役号 `7,8,11–16,22,29,50,76,88,93,95,102,104,105,110` 不得被任何 `@ProtoNumber` 重新占用（95 为派发件列举之外追加，依据 = `DiscipleSerializer.kt` 既有「ProtoNumber(95) 已退役」注释） |
| `planned retirement numbers stay pointed at legacy properties` | 退役在册号 `18/19/20/24..27/47/98/99` 在退役批落地前不得改指向 |
| `SaveData equipmentStacks keeps tag 53 for planned retirement` | `equipmentStacks` 保持 53；其他 SaveData 字段不得抢占 53 |
| `new frozen section stays unwired in saved bytes` | wire 级验证：默认弟子 ProtoBuf 字节流顶层字段号 ∩ {112..117} = ∅（防误接线/防 `@EncodeDefault(ALWAYS)`） |

机制：Kotlin 属性反射读 `@ProtoNumber`（RUNTIME retention；`ProtoNumber` 不带 `@SerialInfo`，descriptor 元注解不可读——沿用 `ProtoNumberCoverageTest` 同款机制）。错误消息均含操作指引（先改方案 §四 WP0 + 批次文档 §1 E1 冻结表并登记，再同步守卫）。

## 3. 门禁实测（原数字）

工作目录 = worktree `android/`；测试均带 `--max-workers=1` + `-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-equipment/android/core/engine/build/desktop-jni/libgamecorejni.so`。

1. **compileReleaseKotlin**：BUILD SUCCESSFUL in 2m（119 actionable tasks: 45 executed, 35 from cache, 39 up-to-date）
2. **testReleaseUnitTest 六模块全量**：BUILD SUCCESSFUL。分模块实测（tests/failures/skipped，自 test-results XML 汇总）：
   - app **1028/0/2** · core:data **836/0/15** · core:domain **1585/0/0** · core:engine **2949/0/1** · core:ui **155/0/0** · feature:game **993/0/0**
   - 合计 **7546/0/18**（并网基线 7541/0/18；+5 = 本批新增守卫用例，data 模块 831→836）
   - engine Diff 门含在其中：`DiffBridgeSourceSyncGuardTest` 绿（首轮 failed，归因与处置见 §5-①）
3. **lintRelease detekt**：BUILD SUCCESSFUL in 5m 32s（252 actionable tasks: 111 executed, 7 from cache, 134 up-to-date）
4. **check-jni-count**：`✓ JNI 面计数在基线内：total=87/87，双桥无扩散`；**check-agent-instructions**：`✓ 规范分发架构门禁全部通过`（路由闭包 42 篇、468 条引用全可解析；最坏链路 android/core/engine/AGENTS.md 30555/32768）
5. **codegen 漂移门**：`node scripts/gen-templates.mjs` 重跑后 `git diff` **实测出现** `equipment_db.h`(-22)/`herb_db.h`(-37) 删 `operator==`/`*TemplatesMutable()` 差异 = **D9 现状复现**（预存缺陷，非本批引入；E5 明文生成器补全在 B3 同批完成）。本批未改任何静态数据，生成器输出已全部 `git checkout --` 还原，HEAD 产物原样保留。详见 §5-②。

**收官前树净确认**：`git status` 仅剩本批 3 文件（2 改 + 1 新增测试）；atlas-rgba-manifest.json / scene_uv_tables.h / sprite-uid-map.json / equipment_db.h / herb_db.h / templates/*.json 幽灵已全部还原。

## 4. 旧用例处置表

| 处置 | 数量 | 说明 |
|---|---|---|
| 删除 | 0 | 本批零行为变更 |
| 改断言 | 0 | 同上 |
| 保留 | 全部存量 | 无删改；仅新增守卫类 1 个（5 用例） |

既有相邻守卫共存认证：`ProtoNumberUniquenessTest`（类内唯一性 + Disciple 退役号 22/93/104/105/110 锁）、`ProtoNumberCoverageTest`（GameData/SaveData 注解覆盖）全绿，与本守卫无断言冲突。

## 5. 途中发现与处置（均非本批代码问题）

1. **对拍桥跨工作区行尾漂移（已核实，已处置）**：首轮门禁 2 `DiffBridgeSourceSyncGuardTest` 判红（195 个文件内容不一致）。根因：`core.autocrlf=true` 下 worktree 按标准检出为 CRLF，而主树工作区 gamecore 源文件是历史遗留的 LF；看护拷入的 `.so`+指纹在主树按 LF 内容生成 ⇒ 逐字节比对不齐。**处置**：按守卫官方 rebuild 路径在 worktree 重跑 `scripts/build-desktop-jni.ps1`（指纹 253 源，与基线同规模、文件集零增减）后归绿。产物在 gitignored 的 `build/desktop-jni/` 下，零提交面影响。派发件「勿重跑 build-desktop-jni.ps1、复用拷入件即可」的前提（跨工作区逐字节同源）实测不成立；后续批次在 worktree 内持续自洽，B1 触 C++ 时照常重编即可。
2. **门禁 6 预期与 D9 现状矛盾（已核实，已登记）**：派发件门禁 6 预期「重跑后不得出现删 `operator==`/`*TemplatesMutable()` 类差异」，但 main 现状 = D9 在案（HEAD 生成产物含人工补全成员、当前生成器输出未含，E5 明文 B3 同批收口）⇒ 该差异在 main 上重跑必现，与本批无关。建议看护知悉：G0 的严格红绿判据在 B3 生成器补全落地前无法成立，B0–B2 期间该门的操作语义应为「重跑 + 人检 + 还原」。
3. **worktree 本机件错位（已核实，已处置）**：看护拷贝的 `api.properties`/`keystore.properties` 落在 worktree 根，而构建从 `android/` 读取 ⇒ 配置期失败（`app/build.gradle:33` provider 无值）。已 `mv` 入 `android/` 修正（`local.properties`/`version.properties` 位置本就正确）。
4. **新增段 reserved 锁面比派发件列举多锁 95**：派发件列举存量 reserved 为 `7,8,11–16,22,29,50,76,88,93,102,104,105,110`；代码内另有「ProtoNumber(95) 已退役」注释，守卫一并锁定（更严、有据）。

## 6. 风险

- 已核实：上述 §5 四条。
- 推测：无。
- 遗留移交：无新增债；D9/G0 口径矛盾（§5-②）建议看护在 B1 派发件中更正门禁 6 预期表述。

## 7. 回滚

直接 revert 单笔收官提交（无数据副作用：本批零 Room 迁移、零 proto 写入行为变更）。
