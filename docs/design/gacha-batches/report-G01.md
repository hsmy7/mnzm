# report-G01 · 脚手架：配置/协议/域骨架/ActionId 预留

**日期**：2026-09-23  
**分支**：`feat/gacha-m0-m1`  
**依赖**：G00（可并行）

## 做了什么

1. **配置源**：`scripts/data/gacha_config_sample.json`（gachaPools / characterTemplates / gachaDefaults / gachaColors）→ `gen-game-data.mjs` 聚合 → `assets/data/game-data.json`（sha256 `c7626a0a…`，`--check` 过）。
2. **GameData 协议字段**（`@ProtoNumber` 164–167 只增）：
   - `gachaFragmentCounts` / `gachaStarMap` / `gachaPityCounters` : `Map<String,Int>`
   - `gachaHistory` : `List<GachaHistoryEntry>`（环缓冲语义，上限 G12 收口）
   - Room v53→v54 纯加法迁移 + `54.json` KSP 导出
3. **弟子行 `templateId`**（Q32）：三端链
   - Kotlin `Disciple.templateId` + `DiscipleSurrogate@111`
   - C++ `models.h` + `DiscipleColumn::TemplateId`（枚举尾追加）+ `disciple_store` 列 + `column_dirty` + `json_codec` + `gameview_encode` field **112**（110/111 已占用）
   - `DiscipleTables.templateIds` + Assemblers/Write/ColumnRegistry
   - `game_view.proto optional string templateId = 112` + `GameViewDiscipleRows` / `GameViewMirrorCodec` 接线
   - `GameDataFieldPatch` 四键写回
4. **域骨架**：`domain/gacha/{GachaFacade,GachaFacadeImpl,GachaService}` + `CoreModule.provideGachaFacade` + `feature/.../GachaDelegate` 空壳（pull 恒 `NotReady`）。
5. **ActionId**：`action-catalog/gacha.mjs` 空清单 + 段 **1870–1889** 注册进 `gen-action-ids.mjs`（G09 再落 `GACHA_PULL_*`）。
6. **色表 Q31 单源**：`GameConfig.Gacha`（品阶六色 + 灵根数五色）+ `GachaConfigGuardTest`。
7. **守卫**：
   - `GachaConfigGuardTest`（权重和 / 品阶≤4 / 六模板 id / 色表；配置缺失 assumeTrue）
   - `RoomMigrationV53To54Test`（真实 Room 校验 + 旧档默认值）
   - `SaveDataDirectSerializationTest` 空档默认 + gacha/templateId 往返
   - `DiscipleMergeCoverageTest` 登记 `templateId`→unchanging

## 验证

| 门 | 结果 |
|---|---|
| `compileReleaseKotlin` | ✅ BUILD SUCCESSFUL |
| 桌面 ctest 全量 | ✅ **1609/1609**，0 failed，68.7s |
| `RoomMigrationV53To54Test` + `MigrationChainGuardTest` | ✅ |
| `GachaConfigGuardTest` | ✅ |
| `ProtoNumberUniquenessTest` + `ProtoNumberCoverageTest` | ✅ |
| `CloudPayloadSizeBenchTest` + `SaveDataDirectSerializationTest` | ✅ |
| `DiscipleMergeCoverageTest` | ✅ |
| `node scripts/gen-game-data.mjs --check` | ✅ 一致 |
| `node scripts/gen-action-ids.mjs` | ✅ 198 actions / maxId=1861（段预留，零新号） |

## Journey log（≤5）

1. 删除面/脚手架两路 explore 并行摸底，再按批写文件。
2. `templateId` 中间插枚举会打乱列序 → 改枚举尾追加；gameview 110 被 storageBagItems 占用 → 用 **112**。
3. Room 对 `List<GachaHistoryEntry>` 缺 TypeConverter → 补 `CollectionConverters`。
4. `GachaService` 误用 `GameStateStore.scope` → 改 `CoroutineScopeProvider`。
5. 桌面工具链不在 PATH：`llvm-mingw` + Android SDK cmake 显式拼 PATH 后 ctest 全绿。

## 未完成 / 登记

- `GACHA_PULL_ONCE/TEN` 具体号段条目 **G09** 再写入 `gacha.mjs`（本批只预分配段）。
- 色表与旧 `GameConfig.Rarity` **并存**：寻访结果页/徽章 G11 强制 `GameConfig.Gacha`；仓储旧色对齐债挂 §14.5。
- `talents/physiques/affixes` 三表删除 **属 G04**，本批未动（避免中途破坏特质代码）。
- 新字段进 dirty 导出清单：经 `json_codec` 全量键 diff 自动覆盖；`GameDataFieldPatch` 已挂写回。

## 分类表（本批）

| 类别 | 面 |
|---|---|
| ① 搬迁 | — |
| ② 删除/只读 | — |
| ③ 命令进 C++ / 回执出 C++ | 预留 gacha ActionId 段；无新 JNI |
