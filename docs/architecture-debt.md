# 架构债务记录

> 本文件记录已知的架构债务和待完成的技术改进项。
>
> **更新于 2026-07-29**：2026-07-28 全量治理已完成，详见 CHANGELOG.md。
> 以下为当前仍保留的 ⏸️（暂缓）项和持续监控项。

---

## ⏸️ 暂缓项（低优先级）

以下项目当前行为正确，无用户可见问题，暂不修复：

### 生命周期低优项

1. **重入串行化硬屏障** — 当前 CAS 软屏障工作正常，可改用 `Mutex.withLock` 加固
2. **LOADING 状态可达补充** — 初次加载补充 `setLoading()`，纯 UI 优化
3. **取消时状态自动回滚** — 记录初始状态，取消时自动恢复，极少触发

### 写入守卫架构债务

详见 [architecture-debt-write-guard.md](architecture-debt-write-guard.md)：
- `store` 底层存储绕过守卫（`@PublishedApi internal val store`）
- `requireWrite` / `onWrite` 为 `@JvmField var`

### 云存档序列化远期优化

- **`SerializableBattleLogMember` 字段命名对齐** — `discipleId` vs `id` 等
- **方案 C：KSP 代码生成** — 从 GameData 自动生成 SerializableGameData，已非必需

---

## 📋 远期性能优化（2026-07-30 代码质量优化遗留）

以下为 Phase 3 性能优化（2026-07-30）中识别出但未纳入当前范围的远期优化项：

### 1. `discipleAggregates` 增量投影

**问题**：`GameStateStoreImpl.discipleAggregates` 当前每 200ms 全量执行 `map { it.toAggregate() }`。弟子 500+ 时仍有 O(n) 开销。

**建议方案**：利用 `DiscipleTables.ChangedIdTracker` 增量构建 aggregates，仅转换已变化的弟子。

**优先级**：低（当前 `.sample(200)` + `.flowOn(Default)` 已足够）。

### 2. `processMonthlyEvents` 单事务化

**问题**：`CultivationEventProcessor.processMonthlyEvents` 当前在独立的 `stateStore.update{}` 内执行（原本有 13+ 子操作共享同一事务）。但 `CultivationService.processMonthlyEvents` 包装层和上游 `GameEngineCore.processMonthYearChange` 的调用链中仍有 2 个事务。

**约束**：
- `processCompletedMissionsLazy` Phase 1 从事务外读取状态 → 移入事务需改为快照读取
- `flushPendingEvents` 必须保持事务外
- `safelyRunInState` 的异常隔离语义必须保留

**优先级**：中（框架性重构，无用户可见 Bug）。

### 3. 月流 HP/MP 恢复统一

**问题**：`processMonthlyCultivationAndAuto` 仍使用旧的 `recoverHpMpForAllDisciples`（全量遍历），而非新的 `recoverHpMpSingle`（单弟子）。

**影响**：每月结算时多一次全弟子遍历。

**优先级**：低（每月只跑 12 次，300 弟子时约 0.1ms）。

### 4. Baseline Profile 真机生成

**问题**：手写的 `baseline.prof` （`app/src/main/baseline-prof/baseline.prof`）包含 111 条通用 HSPL 规则，覆盖主要启动路径。但未经真机运行验证覆盖完整性。

**建议**：使用 `BaselineProfileGenerator` 测试在真机/模拟器上运行一次，自动生成更精准的 profile。

**优先级**：中（发布前做一次即可）。

### 5. `!!` 操作符全库清理

**问题**：虽然本次优化零新增，但全库 Kotlin 文件中仍有 `!!` 操作符残留。

**建议**：分模块逐步替换为 `?.` + `?:` 或 `checkNotNull()`。

**优先级**：低（持续代码规范改进）。

---

## 🔄 持续监控项

### Crash 1: ANR #5076 — TapTap Sandbox Toast

**状态**：⏸️ 待 TapTap SDK 更新

**当前防护**（全部就绪）：
- Looper 主线程超时监控
- ✅ 5s 超时保护 + 降级路径
- ✅ 异常守卫拦截 TapTap lateinit 崩溃
- ✅ `initAdSdk()` 调用顺序修正（2026-07-28）

**根因**：TapTap SDK 沙箱 hook `INotificationManager.enqueueToast`，闭源 SDK 内部 Binder 调用在主线程堵塞 >5s。

### Crash 2: SIGSEGV #3088 — vulkan.adreno.so

**状态**：✅ 已加固 — 继续从 Bugly 收集数据扩充黑名单

**当前防护**（全部就绪）：
- VulkanBackend.cpp: vkGetDeviceQueue 重试 3 次 + VK_NULL_HANDLE 检查
- ✅ driverVersion 检测 + 20+ 机型黑名单
- ✅ 远程配置预备（GameConfigData.VulkanSection）
- ✅ NDK 编译验证
