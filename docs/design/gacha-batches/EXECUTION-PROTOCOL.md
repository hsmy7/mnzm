# G 批实施·执行协议（EXECUTION-PROTOCOL）

> 本文件是《角色卡池重构 G 批》**每个实施批次**的统一作业规程。实施 agent 开工前必读，
> 与 `docs/design/character-gacha-implementation.md` S2.2（每批统一交付形态）配套。
> 权威需求：`docs/character-gacha-redesign-2026-09-23.md`（产品方案 v1.5）。
> 落点清单：`recon-G02-G03.md` / `recon-G04.md` / `recon-G05-G06-G08-G09.md` / `report-G01.md` / `report-G07.md`。

## 1. 铁律（违反即任务未完成）

| # | 规则 |
|---|---|
| 1 | **C++ AUTHORITATIVE**：抽卡/碎片/升星/开局/重伤写入走 native 事务 → `applyDirtyFromNative`；**禁止** Kotlin `stateStore.update` 稳态写这些结果。`MirrorReadOnlyGuardTest` 必须零命中。 |
| 2 | **ActionId 只增不复用**：新增/退役一律改 `scripts/action-catalog/*.mjs`，再跑 `node scripts/gen-action-ids.mjs` 生成 `action_ids.h` + `ActionIds.kt`（两产物禁手改）。退役 = catalog 保留条目 + `desc` 标注废弃 + 删 dispatch case。跑 `node scripts/check-jni-count.mjs`。 |
| 3 | **禁止新增 JNI 导出**：`external fun` 只允许出现在在册桥文件；计数门禁见上。 |
| 4 | **Room 列禁 DROP**：SQLite 3.35 才支持 `ALTER TABLE DROP COLUMN`。删字段 = 保留旧列 + `@Ignore`（或 create-copy-drop-rename），并递增 `@Database(version)` + 新增 `MIGRATION_N_M` + 注册 `ALL_MIGRATIONS` + 迁移测试。见 `rules/database-migration.md`。 |
| 5 | **ProtoBuf `@ProtoNumber` 只增不复用**：删字段在两处 proto 定义（`SerializableXxx` 与 `OldSerializableSaveData.SerializableXxx`）写 `reserved`；过 `ProtoNumberUniquenessTest` / `ProtoNumberCoverageTest`。 |
| 6 | **三端字段链同 commit**：`models.h` → `DiscipleColumn` → `disciple_store.{h,cpp}` → `column_dirty.h`（nameOf + serialize）→ `json_codec.cpp`（GC_TO/GC_FROM）→ `gameview_encode.cpp` → `game_view.proto` → JNI → Kotlin `Disciple*.kt`/`DiscipleTables*.kt`/镜像 → Room。删 `DiscipleColumn` 枚举项会平移后续列索引，必须同 commit 改完所有按索引/按名的双射面。 |
| 7 | **配置源单一真源**：改 `scripts/data/*_sample.json` + `scripts/gen-*.mjs` → 跑生成器 → 提交 `assets/data/game-data.json` + `game-data.hash.txt`（`node scripts/gen-game-data.mjs`，禁手改产物）。 |
| 8 | **UI 不直写 Store**：ViewModel → GameEngine/Facade → Service → Store。 |
| 9 | **确定性 RNG**：新随机逻辑一律 `GameRngManager.getRng(RngPartition.xxx)`（抽卡走 `SYSTEM`）；禁 `kotlin.random.Random`。 |
| 10 | **入库唯一入口**：`InventorySystem.withTrackingSource("<来源>") { addXxx(...) }`，且来源名必须同批登记进 `OverflowMailSender.SOURCE_DISPLAY_NAMES`（守卫 `OverflowMailSenderTest` 会扫字面量）。 |
| 11 | **测试必须串行**：`--max-workers=1`。 |
| 12 | **detekt baseline 只缩不增**：新违规必须实修。 |
| 13 | **注释只描述当前状态**：禁止「之前/原来/新增/删除」等历史性表述（`rules/code-comment.md` 七项）。 |
| 14 | **不做超出批次范围的事**；不中途停下询问（遇产品歧义登记到报告并停下该条目，继续其余条目）。 |

## 2. 门禁命令（Windows / PowerShell）

工作目录 = `C:\Mnzm\XianxiaSectNative\android`。

```powershell
# 0) Kotlin 编译（每次改动后）
.\gradlew.bat compileReleaseKotlin --console=plain

# 1) 桌面 C++ 全量（触碰 C++ 必跑；先构建再 ctest）
cd app\src\main\cpp\gamecore\build\desktop-test
$env:PATH = "C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\bin;C:\Users\cp050\llvm-mingw\llvm-mingw-20260616-ucrt-x86_64\x86_64-w64-mingw32\bin;$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1\bin;$env:PATH"
cmake --build . ; ctest
# 当前基线：1606/1606 全绿

# 2) 桌面 JNI 重建（触碰 C++ 必跑，对拍/跨语言测试用）——在仓库根执行
pwsh -File scripts/build-desktop-jni.ps1

# 3) Kotlin 单测（串行；先重建 JNI 再跑，`-D` 单横线、路径必须是 .so 完整绝对路径）
.\gradlew.bat :core:domain:testReleaseUnitTest :core:engine:testReleaseUnitTest :core:data:testReleaseUnitTest :app:testReleaseUnitTest `
  --max-workers=1 "-Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so" `
  --rerun-tasks --console=plain
# 过滤面示例（只跑相关类，省时）：
#   --tests "*DiscipleTables*" --tests "*Diff*"

# 4) detekt（该批触及模块）
.\gradlew.bat :core:domain:detekt :core:engine:detekt :core:data:detekt :core:ui:detekt :feature:game:detekt :app:detekt --console=plain

# 5) 代码生成门禁（改 proto / game-data / catalog 必跑）
node scripts/gen-action-ids.mjs ; git diff --exit-code -- android/app/src/main/cpp/gamecore/include/gamecore/action_ids.h android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/ActionIds.kt
node scripts/gen-game-data.mjs --check
node scripts/check-jni-count.mjs
node scripts/check-agent-instructions.mjs   # 只在改 AGENTS.md / rules/ / docs/ 后需要
```

**桌面 GTest 运行需要 llvm-mingw 的 `bin` 在 PATH**（只加外层 wrapper 目录会缺 `libc++.dll`）。

## 3. 交付形态（每批必交）

1. **代码**：一次覆盖全部影响点（UI、存储、测试、旧数据兼容），不留「后续优化」。
2. **测试**：新增/修改单测；删除批给「旧用例处置表」（删 / 改断言 / 保留 + 理由）。
3. **报告**：`docs/design/gacha-batches/report-Gxx.md`，含
   - 做了什么（分类表：①搬迁 ②删除或只读 ③命令进 C++/回执出 C++）
   - 验证（门禁实跑数值，不得写"应该通过"）
   - 旧用例处置表
   - 末节「未完成 / 登记」（含跨批收口项与产品歧义）
4. **commit**：中文说明，格式 `feat|refactor|chore(gacha|disciple|gacha-ui|…): Gxx <一句话>`；
   共享五件套（`gacha.mjs` / `action_ids.h` / `ActionIds.kt` / `dispatch_gacha.cpp` / `test/CMakeLists.txt`）**同一 commit**。
   **工作树只留本批改动**；构建副产物（`atlas-rgba-manifest.json` 等）在提交前 `git checkout --` 还原。

## 4. 汇报格式（回报给父 agent）

```
批次：Gxx
commit：<sha> <标题>
门禁：ctest N/N · engine JUnit N/N · domain/app 面 · detekt 模块全绿 · compileReleaseKotlin 绿 · 生成器零漂移
改动规模：N 文件 +A/-D
未完成/登记：<逐条>
风险：<逐条，含「已核实」与「推测」区分>
```

## 5. 跨批串行约束（不可违反）

- 删列批（G02/G03/G04）共享 `models.h` / `disciple_store.h` / `column_dirty.h` / `DiscipleTables*.kt` / `Disciple.kt` → **必须串行合入**，禁并行编辑。
- `execute_dispatch.cpp` 已声明「此后冻结」→ 新域走独立 `src/dispatch_*.cpp`，只在 `execute_dispatch.cpp` 加 1 行端口认领。
- `battle_residual_tx.h` / `disciple_tx.h` / `disciple_lifecycle_tx.h` / `month_settlement.h` / `year_settlement.h` 同属多批共享区，串行。
- 每批开工前 `git status` 必须干净；发现有他批在途改动就**停手报告**（不要在他批改动上叠加）。
