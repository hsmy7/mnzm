# 架构债务记录

> 本文件记录已知的架构债务和待完成的技术改进项。
> ✅ 已完成的债务项已在 2026-07-28 全量治理中清理，详见 [git log](https://github.com/hsmy7/mnzm/commit/chore/building-cost-adjust-20260723) 或 `CHANGELOG.md`。

---

## 生命周期 ⏸️ 项（低优先级）

以下为 `BootPhase/RunState` 双层状态机的低优先级待优化项，当前行为正确，无用户可见问题：

1. **重入串行化硬屏障** — 当前 CAS 软屏障工作正常，可改用 `Mutex.withLock` 加固
2. **LOADING 状态可达补充** — 初次加载补充 `setLoading()`，纯 UI 优化
3. **取消时状态自动回滚** — 记录初始状态，取消时自动恢复，极少触发

## 写入守卫架构债务（⏸️ 低优先级）

详见 [architecture-debt-write-guard.md](architecture-debt-write-guard.md)：
- `store` 底层存储绕过守卫
- `requireWrite` / `onWrite` 为 `@JvmField var`

## Crash 1: ANR #5076 — TapTap Sandbox Toast 主线程阻塞（⚠️ 仍待 TapTap SDK 更新）

**当前措施**：
- Looper 主线程超时监控（>3s 告警日志）
- ✅ `initAdSdk()` 调用顺序修正（2026-07-28）
- ✅ 5s 超时保护 + 降级路径
- ✅ 异常守卫拦截 TapTap lateinit 崩溃

**根因**：TapTap SDK 沙箱环境 hook `INotificationManager.enqueueToast`，`SandboxTapAccountChecker.onCheckAccountPass` 触发 `Toast.show()` 时 Binder 调用在主线程堵塞 >5s。该调用在 TapTap SDK 闭源代码内部，应用侧无法直接干预。

**状态**：⏸️ 待 TapTap SDK 更新（最有效方案），或评估反射拦截方案的兼容性风险后实施。

## Crash 2: SIGSEGV #3088 — vulkan.adreno.so vkGetDeviceQueue（✅ 已加固—仍需 Bugly 数据扩充）

**当前措施**：
- VulkanBackend.cpp: vkGetDeviceQueue 重试 3 次（2ms 间隔）+ VK_NULL_HANDLE 检查
- ✅ **driverVersion 检测**（2026-07-28）：`VulkanPolicy.setDriverVersion()` + `isKnownBadDriverVersion()` + C++ `s_driverVersion` 静态变量 + JNI `getVulkanDriverVersion()`
- ✅ **黑名单扩充**（2026-07-28）：新增 20+ 机型（荣耀/华为/小米/vivo/OPPO/一加）
- ✅ **远程配置预备**（2026-07-28）：`GameConfigData.VulkanSection` 含 `blacklistedModels` + `DriverVersionRange`
- ✅ **NDK 编译验证**（2026-07-28）：`externalNativeBuildRelease` 通过

**根因**：某些 Adreno GPU 驱动（国产 OEM ROM）在 `vkCreateDevice()` 后立即调用 `vkGetDeviceQueue()` 时存在竞争条件，队列句柄未就绪时访问导致 SIGSEGV。

**仍需**：从 Bugly 持续收集崩溃数据扩充 `KNOWN_PROBLEM_MODELS` 黑名单。

## 云存档序列化双路径同步（远期可选优化）

### 已完成
- ✅ **守卫测试已落地** — `SerializationCoverageTest` 等 6 个测试文件覆盖 GameData + 全部嵌套类型。

### 远期优化（⏸️ 可选）
- **`SerializableBattleLogMember` 字段命名对齐域模型** — `discipleId` vs `id`、`remainingHp` vs `hp`、`remainingMp` vs `mp`
- **方案 C：KSP 代码生成** — 从 GameData 声明自动生成 SerializableGameData 和 converter，消除手动同步。已非必需。
