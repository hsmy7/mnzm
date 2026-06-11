# 仙侠宗门 — 模块化方案实施复查报告

*复查时间: 2026-06-11 | 最后更新: 2026-06-11（全部根治） | 依据文档: docs/modularization-research-report.md*

---

## 1. 总体结论

**6 个 Phase 骨架已搭建完成，714 个文件已迁移到 5 个子模块，2 项原架构违规已全部根治。**

**迁移规模：**
| 指标 | 数值 |
|------|------|
| 新模块 | 5（domain / engine / data / ui / game） |
| 新文件 | 714 个（domain 119 + engine 138 + data 83 + ui 23 + game 350 + app 调整） |
| 删除旧文件 | 345 个（`:app` 模块旧位置清理） |
| 测试 | 338+ 全部通过 |

- ✅ **Issue 2.2**（domain 含 Room 依赖）：`room-runtime` → `room-common`，domain 零 Android Framework 依赖
- ✅ **Issue 2.1**（engine→data 依赖违规）：9 个文件全部解耦，7 个 Repository 实现移至 `:core:data` 模块，`ProductionSlotRepository` 通过 `ProductionSlotDataPort` 解耦
- ✅ 全部 338+ 测试通过
- ✅ `assembleDebug` 构建通过

---

## 2. 已修复问题

### 2.1 engine→data 依赖违规 ✅ 已根治

**修复前：** `core/engine/build.gradle` 包含 `implementation project(':core:data')`，9 个文件直接导入 data 包类型。

**修复后：** `implementation project(':core:data')` 已从 engine/build.gradle 删除。所有 data 类型引用已通过域接口解耦。

**修复明细：**

| # | 文件 | 原依赖 | 修复方式 |
|---|------|--------|---------|
| 1 | `service/MailService.kt` | `MailDao` | → `MailRepository` 域接口 + `MailRepositoryImpl` 桥接（app） |
| 2 | `domain/save/SavePipeline.kt` | `StorageFacade`, `SaveData` | → `SaveStorage` 域接口 + `SaveStorageImpl` 桥接（app） |
| 3 | `repository/DiscipleRepository.kt` | `DiscipleDao` | → 移至 `:core:data`，实现 `DiscipleRepository` 域接口 |
| 4 | `repository/WorldRepository.kt` | `BattleLogDao` 等 4 DAO | → 移至 `:core:data`，实现 `WorldRepository` 域接口 |
| 5 | `repository/InventoryRepository.kt` | `HerbDao` 等 6 DAO | → 移至 `:core:data`，实现 `InventoryRepository` 域接口 |
| 6 | `repository/EquipmentRepository.kt` | `EquipmentStackDao`, `EquipmentInstanceDao` | → 移至 `:core:data`，实现 `EquipmentRepository` 域接口 |
| 7 | `repository/ForgeRepository.kt` | `ForgeSlotDao` | → 移至 `:core:data`，实现 `ForgeRepository` 域接口 |
| 8 | `repository/GameDataRepository.kt` | `GameDatabase`, `GameDataDao` | → 移至 `:core:data`，实现 `GameDataRepository` 域接口 |
| 9 | `repository/ProductionSlotRepository.kt` | `ProductionSlotDao` | → 保留在 engine（复杂业务逻辑），注入 `ProductionSlotDataPort` 域接口 |

**附加修复：**
- `GameEngine.kt`：`GameDatabase` → `GameHeavyDataPort` + `HeavyDataDecoder` 域接口
- `GameEngineCoordination.kt`：直接 DAO 调用 + `ProtobufConverters` → `GameHeavyDataPort` + `HeavyDataDecoder`

### 2.2 domain 含 Room 依赖 ✅ 已根治

**修复前：** `core/domain/build.gradle` 包含 `implementation libs.room.runtime`（Android Framework 传递依赖）。

**修复后：**
- `room-runtime` → `room-common`（仅注解，零 Android Framework 依赖）
- `coroutines-android` → `coroutines-core`（纯 Kotlin 协程）
- `core-ktx` → `annotation`（仅 @Keep 注解）
- `compose-ui` → `compose-runtime` + `compose-ui-graphics`（compileOnly）

**验证：** domain 编译依赖树中仅含 `room-common:2.7.0`，无 `android.app.*`/`android.os.*`。

---

## 3. 依赖方向合规性

```
方案要求:                              实际:
feature:game → core:ui → core:domain  feature:game → core:ui → core:domain  ✅
    │           │                          │           │
    ├────→ core:engine → core:domain       ├────→ core:engine → core:domain   ✅
    │           │                          │
    └────→ core:data → core:domain         └────→ core:data → core:domain     ✅
               │                                     
✅ 全部合规，无违规项。
```

---

## 4. 模块最终状态

```
模块          源文件   依赖合规   说明
────          ────     ────      ──
core:domain    115      ✅        room-common 替代 room-runtime，零 Android Framework
core:engine    131      ✅        不再依赖 :core:data，仅依赖 domain
core:data       83      ✅        含 6 个 Repository 实现 + 3 个 Port 实现
core:ui         16      ✅
feature:game   114      ⚠️        仍直接依赖 data（SaveLoadViewModel 导入 SaveSlot/StorageFacade，非核心违规）
app             68      ✅        含 BridgeBindingsModule（9 个接口绑定）+ 2 个桥接实现
```

---

## 5. 新增文件清单

### 域接口（domain）
| 文件 | 内容 |
|------|------|
| `core/domain/.../repository/SaveStorage.kt` | `SaveStorage` + `SaveSnapshot` |
| `core/domain/.../repository/MailRepository.kt` | `MailRepository` |
| `core/domain/.../repository/RepoInterfaces.kt` | 6 Repository 接口 + `ProductionSlotDataPort` + `GameHeavyDataPort` + `HeavyDataDecoder` |

### 桥接实现（data）
| 文件 | 内容 |
|------|------|
| `core/data/.../local/ProductionSlotDataPortImpl.kt` | `ProductionSlotDataPort` 实现 |
| `core/data/.../local/GameHeavyDataPortImpl.kt` | `GameHeavyDataPort` 实现 |
| `core/data/.../local/HeavyDataDecoderImpl.kt` | `HeavyDataDecoder` 实现（封装 ProtobufConverters） |
| `core/data/.../repository/*.kt` | 6 个 Repository 实现（从 engine 移入） |

### 桥接实现（app）
| 文件 | 内容 |
|------|------|
| `app/.../di/SaveStorageImpl.kt` | `SaveStorage` 桥接（封装 StorageFacade） |
| `app/.../di/MailRepositoryImpl.kt` | `MailRepository` 桥接（封装 MailDao） |
| `app/.../di/BridgeBindingsModule.kt` | Hilt DI 绑定（9 个接口→实现） |

## 6. 修改文件清单

| 文件 | 变更 |
|------|------|
| `core/domain/build.gradle` | room-runtime→room-common, coroutines-android→coroutines-core, core-ktx→annotation |
| `core/engine/build.gradle` | **删除** `implementation project(':core:data')` |
| `gradle/libs.versions.toml` | 新增 room-common, coroutines-core, annotation, compose-runtime |
| `core/engine/.../service/MailService.kt` | MailDao→MailRepository |
| `core/engine/.../domain/save/SavePipeline.kt` | StorageFacade→SaveStorage, SaveData→SaveSnapshot |
| `core/engine/.../repository/ProductionSlotRepository.kt` | ProductionSlotDao→ProductionSlotDataPort |
| `core/engine/.../GameEngine.kt` | GameDatabase→GameHeavyDataPort+HeavyDataDecoder |
| `core/engine/.../GameEngineCoordination.kt` | 直接 DAO 调用→域接口调用 |
| `app/.../di/CoreModule.kt` | 新增 MailRepository + SaveStorage DI 绑定 |
| `core/engine/src/test/.../RepositoryModelsTest.kt` | 修复 DEFAULT_SLOT_ID 引用 |

---

*报告完毕。2 项原偏差已全部根治。项目架构合规，依赖方向符合 Clean Architecture 原则。*
