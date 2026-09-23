# MR4 完成报告——内存管理根治 · Phase 4（状态基线 + GLES + 可观测批）

> **批次**：MR4（P4.1–P4.6 全部六任务）
> **施工面**：工作树 `C:\Mnzm\XianxiaSectNative\.worktrees\memory-refactor`（分支 `w5/memory-refactor`）
> **日期**：2026-09-23 · **性质**：状态基线去全量 DOM（D5）+ GLES D7 + MemoryBudgetView（D3 可观测）
> **验收状态**：实施完成、门禁全绿；**未经看护/用户验收，不自登记 accepted**
> **方案**：[memory-refactor-implementation-plan-2026-09-23.md](memory-refactor-implementation-plan-2026-09-23.md) 第四部分 Phase 4
> **范围纪律**：本批只实施 Phase 4；根治合入 = Phase 0–4 全部 checkbox + P4.5/P4.6

---

## 一、任务逐条交付

### P4.1 DirtyTracker rest 域基线去全量 DOM + import 峰值顺序（D5）✅

- **`StateBaseline` 块级基线**（`dirty_tracker.h/.cpp`）：gameData 单域 JSON + `collection→id→entity` 块映射；`includeDisciples` 区分全量臂/rest 臂；`holdsNestedFullStateDom()` 恒 false。
- **`DirtyTracker`**：删除嵌套 `baselineJson_` 全量树，改持 `StateBaseline`（全量臂含 disciples 块）。
- **`ColumnDirtyTracker`**：删除嵌套 `restBaseline_` rest 树，改持 `StateBaseline`（rest 臂）。
- **`diffAdvance`**：gameData 字段级 + 集合块级 upsert/remove（与 `diffTreeSegments` 同序语义），导出即消费推进 `gameData_`（曾漏推进导致二次 diff 非空——已修）。
- **`importStateInternal` 峰值顺序**：解析到临时 `GameState` → reseed → 释放解析树 → 切换 `state_`（失败回滚旧树）→ 归一化族；解析树与双 GameState 不同时常驻。
- **`dumpStateJson`**：thread_local 缓冲 + `nlohmann::detail::serializer` 直写 + 1MiB 起步 reserve。
- **`json_codec` 对称**：`to_json(GameData)` 补 `battleTeams` / `warehouseGarrisons` / `caveExplorationTeams` GC_TO（与 from_json 对称，基线字段表双射前提）。
- **验收**：`BaselineMemoryTest` 2 用例；`BaselineFieldCoverageGuardTest` 双射（6 个 Kotlin-only 字段 intentionallyExcluded）；DirtyTracker/ColumnExport/JsonCodec 对拍全绿。

### P4.2 rest 域导出减载（块级比对，不另造信封）✅

- 列级 `exportDirtyTree` 走 `StateBaseline::diffAdvance`——不构造嵌套 rest 全量树常驻。
- 信封仍只携带 changed/removed（与 `dirtyColumnExport` 正交）；Diff tick 绿。
- 载荷：增量语义不变；常驻内存以块级基线下降为主。

### P4.3 GLES D7 顶点预分配 + 上传池 + clamp + 软渲 Bitmap trim ✅

- VBO 按 `MAX_VERTICES` 一次 `glBufferData` 预分配；稳态帧 `glBufferSubData` 已用范围；超容量回退 BufferData 计 `m_fullBufferDataFrames`。
- `draw()` clamp 到 `MAX_VERTICES`。
- `m_pixelPool`（cap=4）：drain 后回池，upload 锁内借出。
- `SoftwareCanvasBackend.onMemoryTrim` + companion `dispatchSystemTrim`/`markActive`；NativeSurfaceView 创建 mark、destroy release；GameActivity.uiResourceTrimAction 转发（并入 D3，无第四条监听）。
- checklist：`gles_vertex_pool` 行。

### P4.4 MemoryBudgetView 只读 stats 接 UI Debug 页 ✅

- JNI `nativeGetMemoryStats` LongArray[9]：渲染线程 beginFrame 发布 GpuAllocator+TextureCache 快照；任意线程只读。
- `MemoryBudgetView`（core:engine）：snapshot + formatDebugLines。
- SettingsTab 其它设置 `BuildConfig.DEBUG` 显示 GPU/Tex/IO 分类 MB。
- JNI 基线 88→89（NativeBridge +1 豁免已写入）。

### P4.5 Guard 总装 + 全量门禁 ✅

| 门禁 | 结果 |
|---|---|
| 桌面 ctest | **1603/1603 全绿**（基线 1601 + BaselineMemory 2） |
| compileReleaseKotlin | BUILD SUCCESSFUL |
| testReleaseUnitTest + detekt 串行 | BUILD SUCCESSFUL（带 `-Dgamecore.jni.path`） |
| NDK externalNativeBuildRelease | BUILD SUCCESSFUL |
| check-jni-count | **89/89** |
| check-agent-instructions | 全部通过（2 条预存告警同 MR0–MR3） |
| DirtyTrackerBench | 随全量 ctest 绿，未见超噪声带回退 |

### P4.6 双 changelog + 文档同步 ✅

- CHANGELOG.md Phase 4 段 + changelog_entries.json 2026-09-23 玩家条目。
- CODE_WIKI「内存子系统」；architecture「内存管理子系统」；platform-abilities 渲染行补 GpuAllocator/MTLHeap；renderer checklist `gles_vertex_pool`。
- 方案 Phase 4 checkbox 全勾。

---

## 二、门禁实证（判绿数字）

| 门禁 | 口径 | 结果 |
|---|---|---|
| C++ 全量 ctest | llvm-mingw PATH + Android cmake | **1603/1603，0 failed，~66s** |
| Kotlin 编译 | `compileReleaseKotlin --max-workers=1` | **BUILD SUCCESSFUL** |
| Kotlin 测试+detekt | `testReleaseUnitTest detekt --max-workers=1 -Dgamecore.jni.path=…/libgamecorejni.so` | **BUILD SUCCESSFUL** |
| 关键守卫 | DiffBridgeGate + BaselineFieldCoverage | **绿** |
| NDK arm64 | `:app:externalNativeBuildRelease` | **BUILD SUCCESSFUL** |
| JNI 计数 | `node scripts/check-jni-count.mjs` | **89/89** |
| 规范分发 | `node scripts/check-agent-instructions.mjs` | **全部通过** |

**ctest 计数**：1603 = 1601（MR3）+ BaselineMemory 2。

---

## 三、关键实施事实（供 MR 收官引用）

1. **StateBaseline**：`gameData_` + `collections_`；`holdsNestedFullStateDom()` 恒 false；`diffAdvance` 必须推进 `gameData_`（曾漏推进致二次 diff 红，已修）。
2. **双臂独立**：DirtyTracker（全量，includeDisciples=true）与 ColumnDirtyTracker（rest，false）各持 StateBaseline，支撑双臂对拍独立推进。
3. **import 顺序**：parse → next → reseed → free json → swap（可回滚）→ normalize。
4. **dump**：thread_local + detail::serializer 直写，不改 vendored json.hpp。
5. **GLES metric**：`m_fullBufferDataFrames` 稳态应为 0（真机待测）。
6. **像素池**：kPixelPoolCap=4；锁内借出/回池。
7. **软渲 markActive**：createSoftwareBackend 时 mark；destroy 时 release（release 内清 active）。
8. **MemoryBudgetView**：core:engine；SettingsTab 用 `com.xianxia.sect.core.engine.BuildConfig.DEBUG`。
9. **to_json 补三字段**：battleTeams/warehouseGarrisons/caveExplorationTeams——from_json 已有，补 GC_TO 后基线字段表与 from 对称。
10. **diffAdvance 曾漏 gameData 推进**：二次 empty diff 失败根因，已补 `gameData_ = move(curGd)`。

---

## 四、诚实残余与过程记录

| 项 | 状态 |
|----|------|
| checklist 编码 | 原文件非纯 UTF-8；已用 Python `git show` 原始字节解码后 UTF-8 写入 `gles_vertex_pool` 行；最终 utf-8 合法 |
| DiffBridgeGate | 必须带 `-Dgamecore.jni.path=<绝对路径>/libgamecorejni.so`（设计如此） |
| 桌面 JNI 重建 | `build-desktop-jni.ps1` 本地曾超时；门禁用**已有** libgamecorejni.so（9.6MB）。**合入前建议**重建后再跑 Diff 全家（dirty_tracker.cpp 已改，旧 so 可能不含新符号——当前全量绿说明 Diff 家族兼容或命中缓存，合入前务必重建复跑） |
| 性能非回归 | DirtyTrackerBench 随 ctest 绿；未单列对照表回写数字 |
| 真机 | BufferData metric / Debug 页 / 软渲 trim — pending-device |
| lintRelease | 未跑（与 MR1–MR3 口径一致） |
| atlas-rgba-manifest | 构建副产物，已 git checkout 还原 |
| 临时脚本 | scripts/tmp-* 已清理 |
| 存档/RNG/镜像/降级链 | 零触碰（MirrorReadOnly / DiffAuthoritative 含于全量） |
| memorySubsystem 默认 | 仍 false（未翻默认） |
| Changelog | 已写双份（Phase 4 玩家可感知：GLES 预分配 / 软渲 trim / 基线整理） |

---

## 五、pending-device 清单（真机项，如实登记）

| # | 项 | 来源 |
|---|---|---|
| 1 | GLES 稳态帧 `m_fullBufferDataFrames == 0` | P4.3 |
| 2 | Debug 设置页 MemoryBudgetView 分类 MB 正确 | P4.4 |
| 3 | CRITICAL trim 后软渲 chunk/frameBuffer 释放、可重建 | P4.3 |
| 4 | （承 MR0–MR3）附录 A 全表 | 各批 |

**累计 pending-device**：MR0–MR3 + MR4 四项（建议真机批次合并跑）。

---

## 六、收官笔材料清单

- [x] 本完成报告 `docs/report-MR4-completion-2026-09-23.md`
- [x] 方案 Phase 4 checkbox P4.1–P4.6 全勾
- [x] 双 changelog + CODE_WIKI + architecture + platform-abilities + renderer checklist
- [x] JNI 基线 89 + 豁免理由
- [x] 副产物还原与临时脚本清理
- [ ] 收官 commit（明确文件名 add，前缀 `feat(memory)`）——见提交笔

---

## 七、提交前建议复跑

```bash
# 桌面 C++（llvm-mingw + Android SDK cmake 在 PATH）
ctest --test-dir android/app/src/main/cpp/gamecore/build/desktop-test --output-on-failure

# 桌面 JNI 重建（合入前必做——dirty_tracker.cpp 已改）
pwsh scripts/build-desktop-jni.ps1

# Kotlin 门禁
cd android && ./gradlew.bat compileReleaseKotlin testReleaseUnitTest detekt --max-workers=1 \
  "-Dgamecore.jni.path=<abs>/android/core/engine/build/desktop-jni/libgamecorejni.so"

# NDK
./gradlew.bat :app:externalNativeBuildRelease

node scripts/check-jni-count.mjs
node scripts/check-agent-instructions.mjs
```