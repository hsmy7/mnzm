# 渲染性能与稳定性审计根治方案

> **对应审计**：[performance-audit-2026-09-09.md](performance-audit-2026-09-09.md)（同日审计，本方案为 §11.3 待确认修复清单的执行设计）
> **执行方式**：任务带 checkbox，可按 Phase 分批交给执行会话逐任务实施；每任务自带验证门槛。

**目标**：对审计 P1-1 至 P3-11 全部 19 项问题给出**结构性根治**设计——修复使该类问题不可能再发生的不变量（线程契约 / 状态机 / 单一权威数据源），而非在症状处加守卫。

**架构总览**：六项全局决策（D1–D6）承载全部修复——GLES 后端补齐与 Vulkan 同构的线程契约（D1）；用「失败台账」状态机替换 4 个生命周期互相矛盾的布尔标记（D2）；初始化错误码 + GPU 信息 + 回退事件打通可观测链（D3）；swapchain 变更收敛到渲染线程串行执行（D4）；prewarm 与 init 超时预算协同（D5）；帧内构造的批量构建器提升为跨帧复用静态资源（D6）。

**技术栈**：Kotlin/JNI/C++20（NDK r27，arm64-v8a）、Vulkan 1.1 / GLES2、Compose UI、SharedPreferences（crash_recovery）。

## 全局约束（每个任务隐含遵守）

- 降级链顺序 `Vulkan → GPU GLES → CPU Canvas` 不可改变（NativeSurfaceView.kt:1059-1069）。
- 每帧绘制路径**不持** `g_rendererLifecycleMutex`（NativeBridge.cpp:53 纪律），Kotlin「渲染线程先停再 shutdown」时序契约（NativeSurfaceView.kt:813-837）保持。
- 渲染路径 Debug/Release 无 `#ifdef` 行为分叉（审计 §9 PASS 项），新增代码不得引入。
- 所有异常捕获点带日志与注释（项目「防御兜底」体例），禁止静默吞噬。
- 既有测试（VulkanPolicyTest / VulkanPolicyQuantifiedThresholdTest / NativeSurfaceViewTest / AndroidSurfaceProviderTest 等）不可回退。

## 根治判据（什么算「根治」而非「打补丁」）

| # | 判据 | 反例（补丁） | 正例（根治） |
|---|---|---|---|
| R1 | 修复后，**同类问题在新代码路径中无法复现**（契约/类型系统/线程模型层面阻断） | 给当前 vector 加锁 | 定义「跨线程状态必须经 m_stateMutex」契约并编码进头文件，后续新增跨线程字段有模板可循 |
| R2 | 修复**整个状态机的生命周期**，而非调整某次读写 | 补一个 KEY_VULKAN_CRASH_DETECTED 写入方 | 用计数+窗口+衰减的台账替换全部布尔标记，四个死分支一并复活或删除 |
| R3 | 修复**数据源头的错位**，而非在错误输入上修匹配 | 调正则 pattern | 让判定消费 C++ 上报的真实 GPU 设备名（持久化），删掉永不命中的伪防线 |
| R4 | 消灭**每帧/每次都会重复支付的成本结构** | 调大栈容量 | 批量构建器跨帧复用，分配次数从 O(帧)→0 |

---

## 第一部分：全局架构决策（D1–D6）

### D1. GLES 后端线程契约（承载 P1-1、P3-4 部分）

**现状结构缺陷**：GlesBackend 2026-09 落地时复制了 VulkanBackend 的「两阶段初始化 + 主线程入队 / 渲染线程消费」拆分设计，但没有复制其同步契约（VulkanBackend.h:219-231 的 `m_gpuMutex` 及事故史注释）。实测确认的跨线程竞争面比审计所述更宽：

- `m_pendingUploads`（GlesBackend.h:107）：主线程 `uploadTexture` push_back（GlesBackend.cpp:382）vs 渲染线程 `drainUploads` 遍历+clear（:349-366，经 submitFrame:440 调用）——**审计已列**；
- `m_nextTexId`（:374）：主线程自增，无同步；
- `m_textures`：渲染线程 `drainUploads` push_back（:361）vs `destroyTexture` 任意线程 erase（:386-394）vs `glFor` 渲染线程读（:425-432）——**审计未列，同类竞争**；
- `destroyTexture` 在调用线程直接 `glDeleteTextures`（:389）——无 EGL 上下文的线程上 GL 调用**静默无效**（越权线程缺陷，审计未列）。

**根治设计**：把「谁拥有哪块状态、跨线程转换走什么通道」编码为显式契约：

1. `m_pendingUploads` / `m_pendingDestroys` / `m_nextTexId` —— 互斥 `m_stateMutex` 保护（与 Vulkan `m_gpuMutex` 同构）；
2. `m_textures` —— **渲染线程独占**（drainUploads 写、glFor 读），`destroyTexture` 不再直接操作，改为入队 `m_pendingDestroys` 由渲染线程删除——同时修复无上下文线程 GL 调用缺陷；
3. 渲染线程 drain 时**锁内只做 move-swap**（微秒级），GL 上传在锁外——锁竞争面 = 主线程罕见的入队操作。

### D2. 渲染失败台账（承载 P2-1、P2-2、P3-6）

**现状结构缺陷**：失败持久化由 4 个布尔标记 + 1 个计数器构成，写入方分散在 8 处（GameActivity prewarm 三分支、VulkanPolicy setDriverVersion/setVulkanDeviceInfo、两个 killedStrategy 自记录），清零方是 `onCleanLaunch` 的一揽子 `remove()`（CrashRecoveryEngine.kt:111-126）。读方（4 个策略分支 + detectTier 2 处 + shouldDisableHardwareAcceleration）在**同一次启动内永远读到清零后的值**（GameActivity.kt:660 先于 :664）。整个「学习坏设备」闭环是死代码。

**根治设计**：单一状态机替换全部标记——

- **kill 计数**（写前标记残留 = 前次进程死在 native 初始化中）：启动时**增量消费**（读到残留 → 计数+1 → 清标记），不再无条件清零；
- **soft-fail 计数**（initDevice/initSurface 返回 false 的优雅失败）：仅真实返回 false 时 +1；
- **窗口与衰减**：计数只在 3 天窗口内累积；14 天无失败自动清零（健康设备永不被旧失败钉死）；Vulkan 建链成功即清零（能建链已证明设备可用，崩溃循环由 kill 计数独立捕获）；
- **阈值**：kill ≥ 3 或 soft-fail ≥ 3（窗口内）→ 下次启动 GLES_PREFERRED（仍是 GPU，不是软件）；未达阈值 → VULKAN_PREFERRED 重试（保留 2026-09「干净启动重试」的合理意图）；
- **附带产出**：台账持久化最近一次成功探测的 GPU 设备信息（vendorId/apiVersion/driverVersion/deviceName），为 P3-6 提供真实输入。

### D3. 初始化错误码与回退可观测（承载 P2-5）

**现状结构缺陷**：`initRenderer` 只回传 boolean（NativeBridge.cpp:241-304），C++ 内 ~25 个失败点全部坍缩成一个 false；GLES 路径全库无 `glGetString`；进入 SOFTWARE 无独立日志。违反任务书「禁止 Renderer initialization failed 式日志」。

**根治设计**：错误码枚举贯通三层——C++ 每个失败点 set `m_lastInitError` → JNI `getLastInitError()` → Kotlin `RenderFallbackReporter` 产出结构化回退事件（from/to/stage/gpu/driver/elapsedMs），logcat ERROR 级 + 持久化 + TapDB 自定义事件。任何一次降级都可事后回答「从哪降到哪、卡在哪个阶段、什么 GPU」。

### D4. swapchain 变更收敛到渲染线程（承载 P2-3、P3-9 部分）

**现状结构缺陷**：`resize` 在主线程销毁/重建 swapchain（VulkanBackend.cpp:955-1010），渲染线程可能正阻塞在 `vkAcquireNextImageKHR`（:2473，UINT64_MAX 超时）——destroy 与在途 acquire 并发是规范级 UB。`m_ready=false` 只挡新帧，保护不了已在 acquire 中的帧。

**根治设计**：复用已被验证的 `consumePendingRenderScale` 模式（NativeSurfaceView.kt:1368-1373：Compose 线程写 @Volatile，渲染线程消费）——`resizeRenderer` JNI 退化为「记录 pending 尺寸」，真实 resize 由渲染线程在**帧边界**消费执行。acquire 与 destroy 从此不可能并发（同一线程顺序执行），UB 窗口按构造消除。

### D5. prewarm 与 init 超时预算协同（承载 P2-7）

**现状结构缺陷**：prewarm 用 `withTimeout(5s)` 放弃**等待**（GameActivity.kt:324-346），但 native 调用继续持 `g_rendererLifecycleMutex` 运行（NativeBridge.cpp:175）；surface 期 `initRenderer` 阻塞在同一锁（:252）；10s 安全网（AndroidSurfaceProvider.kt:200）到期即降级——**Vulkan 健康但慢的设备被误降 GLES 一整个会话**。且超时分支立即 `recordVulkanInitFailure`（GameActivity.kt:357-360），对仍在跑的 prewarm 做了错误定性（实测确认，审计未列）。

**根治设计**：prewarm 移到不可取消的专用线程（结果经台账落地，超时不再伪造失败记录）；10s 安全网预算化——prewarm 在途时，预算 = prewarm 起点 + 8s + 10s，超时事件携带 `prewarm_inflight` 标记上报（区分「真卡死」与「预热挤占」）。

### D6. 帧内静态资源复用（承载 P2-6、§7 溢出日志）

**现状结构缺陷**：`SpriteBatcher` 设计意图是堆缓冲跨帧复用（SpriteBatcher.cpp:6-7 注释自证），但两个实例都是每帧栈构造（NativeBridge.cpp:695、:1162）——复用前提（实例存续）不成立，典型帧 12600 顶点 → 5 次 new/memcpy/delete × 2 实例 / 帧。

**根治设计**：两个 batcher 提升为文件级 static（渲染线程单消费者，drawAllTiles/drawIslandEdges 仅由 RenderThread 经 JNI 调用），分配从 O(帧) → 0；顺带补齐容量封顶静默丢弃的限频日志（审计 §7/附录 17 建议项）。

---

## 第二部分：逐项方案

> 每项格式：根因 → 根治方案 → 具体改动（文件:行号 + 代码）→ 验证 → 风险与回滚。
> 行号以 2026-09-09 工作区为准（审计同日，已实读核对）。

### P1-1【P1·确定】GLES 跨线程状态无契约 → D1

**根因**：见 D1。竞争面四项：`m_pendingUploads`、`m_nextTexId`、`m_textures`、destroyTexture 越权线程 GL 调用。

**具体改动**：

1. `GlesBackend.h`——新增互斥与待删队列，契约写入注释：

```cpp
#include <mutex>

// ── 跨线程状态契约（与 VulkanBackend::m_gpuMutex 同构，见 VulkanBackend.h:219-231
//    事故史注：历史「Tile 短暂纯色」即同类缺锁）──
// 主线程（图集上传 JNI）与渲染线程（submitFrame）共享的可变状态必须经
// m_stateMutex 访问；m_textures 为渲染线程独占（drainUploads 写 / glFor 读，
// destroyTexture 仅入队不直接操作）。
std::mutex m_stateMutex;
std::vector<PendingUpload> m_pendingUploads;   // GUARDED_BY(m_stateMutex)
std::vector<uint32_t> m_pendingDestroys;       // GUARDED_BY(m_stateMutex)
uint32_t m_nextTexId = 1;                      // GUARDED_BY(m_stateMutex)
std::vector<Tex> m_textures;                   // 渲染线程独占
```

2. `GlesBackend.cpp`——重写三个函数：

```cpp
uint32_t GlesBackend::uploadTexture(const void* pixels, int width, int height) {
    if (!pixels || width <= 0 || height <= 0) return 0;
    PendingUpload up;
    up.width = width;
    up.height = height;
    const size_t bytes = static_cast<size_t>(width) * height * 4;
    up.pixels.resize(bytes);
    memcpy(up.pixels.data(), pixels, bytes);   // 像素拷贝在锁外（21MB 级数据不占锁）
    uint32_t id = 0;
    {
        std::lock_guard<std::mutex> lock(m_stateMutex);
        id = m_nextTexId++;
        up.id = id;
        m_pendingUploads.push_back(std::move(up));
    }
    return id;
}

void GlesBackend::destroyTexture(uint32_t id) {
    // 任意线程可调：仅入队；GL 删除由渲染线程 drainUploads 执行
    //（原实现在调用线程直接 glDeleteTextures——无上下文线程上为静默无效调用）
    std::lock_guard<std::mutex> lock(m_stateMutex);
    m_pendingDestroys.push_back(id);
}

void GlesBackend::drainUploads() {
    std::vector<PendingUpload> uploads;
    std::vector<uint32_t> destroys;
    {
        std::lock_guard<std::mutex> lock(m_stateMutex);
        if (m_pendingUploads.empty() && m_pendingDestroys.empty()) return;
        uploads = std::move(m_pendingUploads);
        destroys = std::move(m_pendingDestroys);
        m_pendingUploads.clear();
        m_pendingDestroys.clear();
    }
    // 锁外 GL 工作（渲染线程持有上下文；锁仅防与主线程入队并发）
    for (uint32_t id : destroys) {
        for (auto it = m_textures.begin(); it != m_textures.end(); ++it) {
            if (it->id == id && it->gl) {
                glDeleteTextures(1, &it->gl);
                m_textures.erase(it);
                break;
            }
        }
    }
    for (auto& up : uploads) {
        GLuint tex = 0;
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        // 对齐 Vulkan 采样语义（P3-4）：图集 LINEAR；UV 已内缩 0.5 texel
        //（NativeBridge.cpp:30 UV_EPSILON），LINEAR 不跨精灵渗色
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, up.width, up.height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, up.pixels.data());
        m_textures.push_back({ tex, up.id });
        GLES_LOGI("drainUploads: texture id=%u %dx%d uploaded (gl=%u)",
                  up.id, up.width, up.height, tex);
    }
}
```

3. `shutdown()`（:280-301）：清队列前取一次 `m_stateMutex`（渲染线程已停，防御性加锁）；`m_textures` 清理维持现状（主线程在渲染线程停止后调用，契约见 Kotlin 侧 NativeSurfaceView.kt:813-837）。

4. 白纹理保持 NEAREST（1×1 纹理过滤无意义，:266-267 不动）。

**验证**：
- 真机 GLES 会话（强制方式见 P2-5 第 4 步的调试开关）：进入游戏后连续切换清晰度档位 10 次（每次触发图集重建 → 主线程 uploadTexture 与渲染线程 drainUploads 真实并发），观察无崩溃、无图集撕裂/黑屏；
- 代码走查清单：新增任何 GlesBackend 成员时必须归类到「m_stateMutex 保护 / 渲染线程独占 / 初始化期独占」三者之一。

**风险与回滚**：锁开销 = 主线程入队时一次 + 渲染线程每帧一次空检查（fast path 为一次 mutex lock+empty 判断，纳秒级），无可感知性能影响。回滚 = revert 单 commit。

---

### P2-1【P2·确定】Vulkan 失败持久化整体失效 → D2

**根因**：见 D2。三套防护死代码：①写前标记 SIGSEGV 检测（标记写后必被下轮 onCleanLaunch 清除）；②持久化 GLES_PREFERRED 降级；③`KEY_VULKAN_CRASH_DETECTED` 全库无写入方（CrashRecoveryEngine.kt:47 定义，消费点 :552/:611/:731/:867 永不触发）。

**具体改动**：

1. `CrashRecoveryEngine.kt`——删除 4 个布尔标记 API（`KEY_VULKAN_INIT_FAILED`/`KEY_VULKAN_CRASH_DETECTED`/`KEY_PREWARM_STARTED`/`KEY_SURFACE_INIT_STARTED` 的读写函数保留内部实现，对外语义并入台账）：

```kotlin
// ── Vulkan 失败台账（替代布尔标记的单向状态机）──
private const val KEY_VK_KILL_COUNT = "vk_kill_count"
private const val KEY_VK_SOFT_FAIL_COUNT = "vk_soft_fail_count"
private const val KEY_VK_LAST_FAILURE_AT = "vk_last_failure_at"
private const val KEY_VK_LAST_FAILURE_STAGE = "vk_last_failure_stage"
private const val KEY_GPU_VENDOR_ID = "gpu_vendor_id"
private const val KEY_GPU_API_VERSION = "gpu_api_version"
private const val KEY_GPU_DRIVER_VERSION = "gpu_driver_version"
private const val KEY_GPU_DEVICE_NAME = "gpu_device_name"      // P3-6 的真实输入

const val VK_FAILURE_WINDOW_MS = 3 * 24 * 3_600_000L     // 窗口：3 天
const val VK_FAILURE_DECAY_MS = 14 * 24 * 3_600_000L     // 衰减：14 天无失败清零
const val VK_CRASH_LOOP_THRESHOLD = 3

/** 启动时增量消费写前标记残留：读到残留 = 前次进程死在 native 初始化中 */
fun consumeKillMarks(): String? {
    val prewarmKilled = wasPrewarmKilled()
    val surfaceKilled = wasSurfaceInitKilled()
    if (prewarmKilled) clearPrewarmStarted()
    if (surfaceKilled) clearSurfaceInitStarted()
    if (!prewarmKilled && !surfaceKilled) return null
    val stage = if (prewarmKilled) "prewarm" else "initSurface"
    bumpFailureCounter(KEY_VK_KILL_COUNT, stage)
    Log.e(TAG, "Vulkan kill mark consumed (stage=$stage) — killCount=${getVkKillCount()}")
    return stage
}

/** 优雅失败（initDevice/initSurface 返回 false）——仅真实返回 false 时调用 */
fun recordVulkanSoftFailure(stage: String) = bumpFailureCounter(KEY_VK_SOFT_FAIL_COUNT, stage)

/** Vulkan 建链成功：清零计数 + 持久化设备信息（P3-6 输入） */
fun recordVulkanSuccess(vendorId: Int, apiVersion: Int, driverVersion: Int, deviceName: String) {
    requirePrefs().edit {
        putInt(KEY_VK_KILL_COUNT, 0)
        putInt(KEY_VK_SOFT_FAIL_COUNT, 0)
        putInt(KEY_GPU_VENDOR_ID, vendorId)
        putInt(KEY_GPU_API_VERSION, apiVersion)
        putInt(KEY_GPU_DRIVER_VERSION, driverVersion)
        putString(KEY_GPU_DEVICE_NAME, deviceName)
    }
}

fun isVulkanCrashLoop(): Boolean = requirePrefs().getInt(KEY_VK_KILL_COUNT, 0) >= VK_CRASH_LOOP_THRESHOLD
fun shouldPreferGles(): Boolean {
    val p = requirePrefs()
    val kill = p.getInt(KEY_VK_KILL_COUNT, 0)
    val soft = p.getInt(KEY_VK_SOFT_FAIL_COUNT, 0)
    if (kill < VK_CRASH_LOOP_THRESHOLD && soft < VK_CRASH_LOOP_THRESHOLD) return false
    val lastAt = p.getLong(KEY_VK_LAST_FAILURE_AT, 0L)
    return System.currentTimeMillis() - lastAt < VK_FAILURE_WINDOW_MS
}

private fun bumpFailureCounter(key: String, stage: String) {
    val p = requirePrefs()
    val now = System.currentTimeMillis()
    val lastAt = p.getLong(KEY_VK_LAST_FAILURE_AT, 0L)
    // 窗口语义：距上次失败超过窗口 → 重新从 1 计（无需时间戳列表）
    val next = if (now - lastAt > VK_FAILURE_WINDOW_MS) 1 else p.getInt(key, 0) + 1
    p.edit {
        putInt(key, next)
        putLong(KEY_VK_LAST_FAILURE_AT, now)
        putString(KEY_VK_LAST_FAILURE_STAGE, stage)
    }
}

fun getVkKillCount(): Int = requirePrefs().getInt(KEY_VK_KILL_COUNT, 0)

/** P3-6 输入：读取最近一次成功探测的 GPU 设备信息（无记录返回 null → 默认 Allow） */
fun readPersistedGpuInfo(): VulkanDeviceInfo? {
    val p = requirePrefs()
    val name = p.getString(KEY_GPU_DEVICE_NAME, null) ?: return null
    val vendorId = p.getInt(KEY_GPU_VENDOR_ID, -2)
    return VulkanDeviceInfo(
        vendor = GpuVendor.fromVendorId(vendorId),
        apiVersion = p.getInt(KEY_GPU_API_VERSION, 0),
        driverVersion = p.getInt(KEY_GPU_DRIVER_VERSION, 0),
        deviceName = name,
    )
}
```

2. `onCleanLaunch()`（:111-126）重写——**增量消费而非清零**：

```kotlin
fun onCleanLaunch() {
    val p = requirePrefs()
    consumeKillMarks()                       // 残留 → kill+1，无残留 → 仅清标记
    // 时间衰减：14 天无失败 → 台账清零（自动重试 Vulkan）
    if (System.currentTimeMillis() - p.getLong(KEY_VK_LAST_FAILURE_AT, 0L) > VK_FAILURE_DECAY_MS) {
        p.edit { putInt(KEY_VK_KILL_COUNT, 0); putInt(KEY_VK_SOFT_FAIL_COUNT, 0) }
    }
    Log.d(TAG, "Clean launch: kill marks consumed, ledger preserved")
}
```

3. `VulkanPolicy.kt`——策略链重写（:558-585）：删除 2/4/5/6 四个死分支，替换为单一台账分支：

```kotlin
fun getRenderStrategy(context: Context): RenderStrategy {
    safeModeStrategy()?.let { return it }              // 1. 安全模式（P2-2 治理后含 TTL）
    cloudGamingStrategy()?.let { return it }           // 1b. 云游戏
    emulatorStrategy()?.let { return it }              // 2. 模拟器（内部消费台账）
    ledgerStrategy()?.let { return it }                // 3. 失败台账：kill≥3 或 soft≥3（窗口内）→ GLES_PREFERRED
    oldApiStrategy()?.let { return it }                // 4. API<31
    return tierStrategy(context)                       // 5. 设备分级（P2-4 治理后名单降为 WARNING）
}

private fun ledgerStrategy(): RenderStrategy? {
    if (CrashRecoveryEngine.shouldPreferGles()) {
        val reason = if (CrashRecoveryEngine.isVulkanCrashLoop()) "crash_loop" else "soft_fail_loop"
        Log.w(TAG, "Vulkan failure ledger → GLES_PREFERRED ($reason)")
        return RenderStrategy.GLES_PREFERRED
    }
    return null
}
```

同步修改消费点：`hasPriorVulkanFailure()`（:551-555）→ `CrashRecoveryEngine.isVulkanCrashLoop() || CrashRecoveryEngine.shouldPreferGles()`；`detectTier` 的 0/1 块（:731-740）同替换；`shouldDisableHardwareAcceleration` 的 :867 同替换；删除 `vulkanCrashStrategy`/`persistentVulkanFailureStrategy`/`prewarmKilledStrategy`/`surfaceInitKilledStrategy` 四个函数及 `setDriverVersion`/`setVulkanDeviceInfo` 中的 `recordVulkanInitFailure()` 调用（量化阈值低于时改调 `recordVulkanSoftFailure("threshold")`，仅记台账不再直接定策略）。

4. 成功回写点：`NativeSurfaceView.handleVulkanInitSuccess`（:1011-1035）中 `renderMode == RenderMode.VULKAN` 分支追加 `CrashRecoveryEngine.recordVulkanSuccess(NativeBridge.getVulkanVendorId(), NativeBridge.getVulkanApiVersion(), NativeBridge.getVulkanDriverVersion(), NativeBridge.getVulkanDeviceName())`。

**验证**：
- 新增 `CrashRecoveryEngineLedgerTest`（app/src/test，仿 VulkanPolicyTest 的 prefs 注入方式）：
  - 消费残留：预置 `prewarm_started=true` → `onCleanLaunch()` 后 kill=1 且标记已清；再次启动无残留 → kill 保持 1（不清零）；
  - 窗口：`last_failure_at` 设为 4 天前 + kill=2 → 新 bump 从 1 重计；
  - 衰减：`last_failure_at` 设为 15 天前 + kill=3 → `onCleanLaunch()` 后 `shouldPreferGles()=false`；
  - 成功清零：kill=3 → `recordVulkanSuccess(...)` → `isVulkanCrashLoop()=false`；
- `VulkanPolicyTest` 扩展：预置 kill=3（窗口内）→ `getRenderStrategy` 返回 GLES_PREFERRED；kill=2 → VULKAN_PREFERRED；
- 手工链路：`adb shell am crash` 三次（或调试开关强制 prewarm 失败三次）→ 第 4 次启动日志出现 `Vulkan failure ledger → GLES_PREFERRED`。

**风险与回滚**：误报源 = prewarm 标记窗口内进程被 LMK/用户滑掉（窗口 = native prewarm 时长，秒级）——阈值 3 + 14 天衰减兜底，可接受并在代码注释声明。回滚 = revert；旧布尔键残留无害（无消费方）。

---

### P2-2【P2·确定】安全模式粘性 + 计数语义混乱 → D2

**根因**：①计数来源是**任意**未捕获异常（CrashRecoveryEngine.kt:80-97），OOM/SDK 崩溃也会被定性为「GPU 渲染问题」；②`KEY_RENDER_SAFE_MODE` 永不清理（:111-126 不清它），是全项目唯一真实的「临时失败被永久缓存」实例；③计数器每次干净启动清零，实际语义与注释（:49「Vulkan 崩溃可预判，2 次即可触发」）不符。

**具体改动**（`CrashRecoveryEngine.kt` + `CrashHandler.kt`）：

1. 归因收窄——`recordCrash` 增加线程名参数，只统计渲染链崩溃：

```kotlin
private val RENDER_THREAD_NAMES = setOf("NativeRenderer", "VulkanInit")

fun recordCrash(stackTrace: String? = null, threadName: String? = null) {
    val p = requirePrefs()
    if (!isRenderAttributed(threadName, stackTrace)) {
        Log.w(TAG, "Crash recorded (non-render, not counted toward safe mode): thread=$threadName")
        p.edit { putLong(KEY_LAST_CRASH_TIMESTAMP, System.currentTimeMillis()) }
        return
    }
    // 滚动窗口：保留最近 24h 内的渲染崩溃时间戳（逗号分隔，最多 5 个）
    val now = System.currentTimeMillis()
    val stamps = readCrashTimestamps().filter { now - it < 24 * 3_600_000L } + now
    p.edit { putString(KEY_RENDER_CRASH_TIMESTAMPS, stamps.takeLast(5).joinToString(",")) }
    Log.w(TAG, "Render-attributed crash recorded (${stamps.size} in 24h window)")
    if (stamps.size >= SAFE_MODE_RENDER_CRASH_THRESHOLD) enterSafeMode(p)
}

private fun isRenderAttributed(threadName: String?, stackTrace: String?): Boolean {
    if (threadName in RENDER_THREAD_NAMES) return true
    val s = stackTrace ?: return false
    return s.contains("native-renderer") || s.contains("libhwui") ||
           s.contains("com.xianxia.sect.core.nativebridge")
}

private fun readCrashTimestamps(): List<Long> =
    requirePrefs().getString(KEY_RENDER_CRASH_TIMESTAMPS, null)
        ?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()

private const val KEY_RENDER_CRASH_TIMESTAMPS = "render_crash_ts"
private const val KEY_SAFE_MODE_ENTERED_AT = "safe_mode_entered_at"
const val SAFE_MODE_RENDER_CRASH_THRESHOLD = 3
const val SAFE_MODE_TTL_MS = 7 * 24 * 3_600_000L
```

2. 自动衰减——`isSafeMode()`（:104-106）加 TTL：

```kotlin
fun isSafeMode(): Boolean {
    val p = requirePrefs()
    if (!p.getBoolean(KEY_RENDER_SAFE_MODE, false)) return false
    val enteredAt = p.getLong(KEY_SAFE_MODE_ENTERED_AT, 0L)
    if (System.currentTimeMillis() - enteredAt > SAFE_MODE_TTL_MS) {
        leaveSafeMode()   // 7 天自动解除，重试 GPU（留日志）
        Log.i(TAG, "Safe mode auto-expired after 7 days — retrying GPU rendering")
        return false
    }
    return true
}
```

`enterSafeMode` 写入 `KEY_SAFE_MODE_ENTERED_AT`；`leaveSafeMode()`（:138-144）一并清除 crash 时间戳。

3. `CrashHandler.uncaughtException`（CrashHandler.kt:126）传线程名：`CrashRecoveryEngine.recordCrash(stackTrace, thread.name)`。

4. UI 解除入口：安全模式提示对话框（类文档所述「显示提示对话框告知用户」处，GameActivity 内既有对话框）增加「尝试重新启用 GPU 渲染」按钮 → `CrashRecoveryEngine.leaveSafeMode()` + `activity.recreate()`。

**验证**：
- `CrashRecoveryEngineLedgerTest` 扩展：非渲染线程崩溃（threadName="main"）不触发安全模式；3 个 24h 内渲染时间戳触发；`entered_at` 为 8 天前 → `isSafeMode()=false` 且键被清；
- 手工：调试模式向 "NativeRenderer" 线程抛 3 次未捕获异常 → 安全模式；改系统时间 +8 天 → 自动解除。

**风险与回滚**：归因启发式可能漏计「渲染线程崩溃改道后的间接崩溃」——漏计的后果只是不进安全模式（仍走 Vulkan→GLES→Software 运行时降级），安全方向正确。阈值 2→3 + 窗口化使误触发更难。

---

### P2-3【P2·潜在】resize 与渲染线程的 acquire/destroy 并发 UB → D4

**根因**：见 D4。`:2477` 的二次 m_ready 检查发生在 acquire **返回之后**，保护不了 acquire 自身（VulkanBackend.cpp:2462-2492）。

**具体改动**：

1. `NativeBridge.cpp`——`resizeRenderer`（:362）退化为记录 pending：

```cpp
// resize 请求通道（D4）：主线程仅记录，渲染线程在帧边界消费执行——
// acquire 与 destroy 从此不可能并发（同一线程顺序执行），UB 窗口按构造消除。
// 与 setCamera/setRenderQuality 同纪律：每帧路径无锁，atomic 保证可见性。
static std::atomic<bool> g_resizeRequested{false};
static std::atomic<int> g_pendingResizeW{0};
static std::atomic<int> g_pendingResizeH{0};

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_resizeRenderer(
        JNIEnv*, jobject, jint width, jint height) {
    if (width <= 0 || height <= 0) return JNI_FALSE;
    g_pendingResizeW.store(width, std::memory_order_relaxed);
    g_pendingResizeH.store(height, std::memory_order_relaxed);
    g_resizeRequested.store(true, std::memory_order_release);
    return JNI_TRUE;
}

// NativeBridge 内部：渲染线程每帧调用（drawAllTiles 入口或 renderTick 首 JNI 处）
bool consumePendingResize() {
    if (!g_resizeRequested.exchange(false, std::memory_order_acquire)) return false;
    if (!g_renderer) return false;
    return g_renderer->resize(g_pendingResizeW.load(std::memory_order_relaxed),
                              g_pendingResizeH.load(std::memory_order_relaxed));
}
```

`initRenderer` 入口与 `shutdownRenderer` 清 `g_resizeRequested=false`（防跨 surface 代际残留值误 resize——同 `g_lastCropProgress` 的清理理由，NativeBridge.cpp:130-134）。

2. 消费点：渲染线程帧首。最小侵入位置 = `drawAllTiles` 入口（每帧必经）；更干净位置 = Kotlin 侧 RenderLoop 与 `consumePendingRenderScale`（NativeSurfaceView.kt:1273-1274）并列新增 `consumePendingNativeResize()`（走 JNI 调一个 `nativeConsumeResize()`）。**采用 Kotlin 侧并列方案**（与既有 pending 消费模式一致，C++ 帧函数零改动）：

```kotlin
// NativeSurfaceView.RenderThread.renderLoop，consumePendingRenderScale() 旁：
consumePendingNativeResize()

private fun consumePendingNativeResize() {
    if (!isReady || renderMode == RenderMode.SOFTWARE) return
    NativeBridge.consumePendingResize()
}
```

（`NativeBridge.consumePendingResize()` = 新 JNI 壳，内部调上述 C++ `consumePendingResize()`。）

3. `VulkanBackend::resize`（:955-1010）本体不动——其 `m_ready=false` → waitIdle → 持锁重建的序列在单线程执行下天然无竞争（防御逻辑保留为 belt-and-suspenders）。GLES `resize` 本就只记录尺寸（GlesBackend.cpp:321-328），统一走同通道。

4. 渲染线程未启动期间（init 中）到达的 size 变更：pending 保留至首帧消费；initRenderer 传入的 viewport 已是最新值（Kotlin 侧 `width/height` 捕获），一致。

**验证**：
- 真机旋转/分屏连续切换 50 次无崩溃（骁龙 8 Gen 2 + 一台中端 Mali）；
- `NativeSurfaceViewTest` 扩展：pending resize 在 renderMode=SOFTWARE 时不触 JNI（mock 断言）；
- 代码走查：`vkDestroySwapchainKHR` 全库唯一调用点此后只能由渲染线程到达。

**风险与回滚**：resize 生效延迟 ≤1 帧（10fps 档 ≤100ms）——与现状（主线程异步 resize 本就不保证同帧）无用户可感差异。回滚 = revert 两个文件。

---

### P2-4【P2·确定】旗舰黑名单与「窄 Deny」矛盾 + GLES 功能降档 → D2/D5 收敛 + 策略统一

**根因**：`KNOWN_PROBLEM_MODELS`（VulkanPolicy.kt:379-421，40 款 2023-2024 全系旗舰）命中即 PROBLEMATIC → GLES_PREFERRED（:743-746 → :695-699），与 :748-753 注释宣称的「默认 Vulkan + 窄 Deny」转向直接矛盾；名单无退出机制、无数据闭环（量化阈值闭环被 P2-1 破坏）。GLES 路径无渲染缩放（NativeBridge.cpp:314-318）、NEAREST 采样（P1-1 已顺带修为 LINEAR）、无 mip/ASTC——被降档设备承受全分辨率功耗 + 画质降级。

**根治方案**：**名单从「决策者」降级为「遥测队列标记」**。运行时回退链（Vulkan→GLES→Software，NativeSurfaceView.kt:1053-1135 已验证）+ P2-1 复活的失败台账共同承担真实防线；名单命中只打日志 + 进遥测事件，由版本后数据决定是否恢复 Deny。这正是 :748-753 注释声称已做而实际没做的策略。

**具体改动**（`VulkanPolicy.kt`）：

```kotlin
// 1. 检测命中 → WARNING（不再 PROBLEMATIC）
if (KNOWN_PROBLEM_MODELS.any { model.contains(it) }) {
    Log.w(TAG, "Device in legacy problem-model cohort: $model — " +
        "VULKAN_PREFERRED with runtime fallback (cohort tracked for telemetry)")
    return DeviceTier.WARNING   // tierStrategy: WARNING → VULKAN_PREFERRED (:701-703)
}
```

配套删除：`vulkanCrashStrategy` 引用的死键（P2-1 已处理）；`KNOWN_PROBLEM_MANUFACTURERS` 保留（仅 :764-770 日志信号与 API35+ HWUI 兜底用，不参与地图后端决策——现状即如此）。

**连带影响必须显式接受**：`shouldDisableHardwareAcceleration`（:897-918）在 API35+ 依赖 tier==PROBLEMATIC → 名单降为 WARNING 后这些设备 HW 加速保持开启。依据：名单内 #3088（vkGetDeviceQueue）已有 VulkanBackend.cpp:566-572 重试防御；#9045（RenderThread join deadline）与 Vulkan 初始化无关；名单制定时（lockCanvas 事故期）的归因已过时。

**验证**：
- `VulkanPolicyTest` 扩展：`Build.MODEL="23127pn0cc"`（小米 14 Pro）→ `getRenderStrategy` 返回 VULKAN_PREFERRED、`detectTier` 返回 WARNING；
- 真机（名单内任一机型）：进入游戏走 Vulkan（RenderHealth 日志 mode=VULKAN）、清晰度切换触发 renderScale≠1.0 生效；
- 版本后校准：P2-5 的 `render_backend_session` 事件按 cohort 分组看 Vulkan 成功率，数据异常再考虑恢复窄 Deny。

**风险与回滚**：名单设备若真有 Vulkan 崩溃 → 运行时降级链 + kill 台账接住（最多 3 次启动收敛到 GLES_PREFERRED），不再有「未试先降」。回滚 = 把 WARNING 改回 PROBLEMATIC 一行。

---

### P2-5【P2·确定】Renderer 健康状态上报缺口 → D3

**根因**：见 D3。全 cpp 无 `glGetString`（本方案实读 GlesBackend.cpp 全文确认零命中）；`initRenderer` 只回 boolean；`fallbackToSoftwareRenderer`（NativeSurfaceView.kt:1100-1109）无独立日志。

**具体改动**：

1. `Rhi.h`——新增错误码枚举（两后端共用）：

```cpp
enum class RenderInitError : int {
    NONE = 0, NO_WINDOW = 1,
    VK_INSTANCE = 10, VK_PHYSICAL_DEVICE = 11, VK_LOGICAL_DEVICE = 12, VK_QUEUE = 13,
    VK_SURFACE = 14, VK_SWAPCHAIN = 15, VK_RENDER_PASS = 16, VK_OFFSCREEN = 17,
    VK_DESCRIPTOR_POOL = 18, VK_PIPELINE_LAYOUT = 19, VK_PIPELINE = 20,
    VK_SHADERS = 21, VK_COMMAND_POOL = 22, VK_DEVICE_MEMORY = 23, VK_FENCE = 24,
    GLES_DISPLAY = 30, GLES_CONFIG = 31, GLES_WINDOW_SURFACE = 32, GLES_CONTEXT = 33,
    GLES_SHADER_COMPILE = 34, GLES_PROGRAM_LINK = 35,
    UNKNOWN = 99,
};
```

2. `VulkanBackend`/`GlesBackend` 成员 `RenderInitError m_lastInitError = RenderInitError::NONE;`，每个失败 return false 前赋值（机械改动 ~25 处：VulkanBackend 的 createInstance/selectPhysicalDevice/createLogicalDevice/createSwapchain 等各失败点；GlesBackend 的 initEgl 五个失败点 :141-178、compileShader/linkProgram 失败点）。

3. `GlesBackend::init` 成功路径（:129-131）补 GPU 信息：

```cpp
m_ready.store(true);
const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
const char* vendor = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
GLES_LOGI("GLES backend initialized (%dx%d) renderer=%s vendor=%s version=%s",
          m_viewportW, m_viewportH, renderer ?: "?", vendor ?: "?", version ?: "?");
```

（此时上下文仍绑定在初始化线程——:127 释放之前，合法。）

4. `NativeBridge.cpp`——失败点 delete 前**先收割**错误码到文件级静态（失败路径的 `g_renderer` 随即被 delete，getter 不能读活对象），再暴露只读 JNI：

```cpp
static std::atomic<int> g_lastInitError{0};   // RenderInitError；initRenderer/prewarmDevice 失败时写

// initRenderer 各失败 return 前（GLES 分支 :273-274 / Vulkan Phase2 :284-285 / 完整初始化 :302-303）：
if (!ok) {
    g_lastInitError.store(static_cast<int>(g_renderer->lastInitError()));
    // 后续 delete g_renderer 与现状一致
}
// prewarmDevice 失败点（:196-201）同式收割 vb->lastInitError()

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_getLastInitError(JNIEnv*, jobject) {
    return static_cast<jint>(g_lastInitError.load());
}
```

（`Renderer2D` 接口新增纯虚 `virtual RenderInitError lastInitError() const = 0;`，两后端返回各自 `m_lastInitError`。）

5. Kotlin 侧 `RenderFallbackReporter`（新文件 `android/core/engine/src/main/java/com/xianxia/sect/core/render/RenderFallbackReporter.kt`，与 FrameSkipPolicy/RenderLodPolicy 同包——TapDB 依赖经端口接口注入，见代码块后注）：

```kotlin
object RenderFallbackReporter {
    data class Event(
        val from: String, val to: String,          // VULKAN/GLES/SOFTWARE
        val stage: String,                          // RenderInitError 名或 "init_timeout"
        val elapsedMs: Long,
        val gpu: String?, val driverVersion: Int, val apiVersion: Int,
        val extra: String? = null,                  // 如 "prewarm_inflight=true"
    )

    fun report(event: Event) {
        android.util.Log.e("RenderFallback",
            "FallbackReason: ${event.from}→${event.to} stage=${event.stage} " +
            "elapsed=${event.elapsedMs}ms gpu=${event.gpu} driver=${event.driverVersion} " +
            "api=${event.apiVersion} ${event.extra ?: ""}")
        // 持久化最近一次（下次启动 logDeviceDiagnostics 一并打印，事故复盘不依赖现场 logcat）
        CrashRecoveryEngine.recordLastFallback(
            "${event.from}>${event.to}|${event.stage}|${event.elapsedMs}|${event.gpu}|${event.extra}")
        // 遥测：与 TapDBManager.trackEvent("game_start", …) 同通道（GameActivity.kt:415-419）
        com.xianxia.sect.taptap.TapDBManager.trackEvent("render_fallback", mapOf(
            "from" to event.from, "to" to event.to, "stage" to event.stage,
            "elapsed_ms" to event.elapsedMs, "gpu" to (event.gpu ?: "unknown"),
            "driver" to event.driverVersion, "api" to event.apiVersion,
            "extra" to (event.extra ?: ""),
        ))
    }
}
```

（`CrashRecoveryEngine.recordLastFallback(summary: String)` = 单键 `render_fallback_last` 写入；`logDeviceDiagnostics` 追加一行打印。TapDBManager 位于 app 模块——若 Reporter 放在 core/engine，则经既有 `EngineCrashReporter` 式端口接口注入，避免反向依赖。）

接入点四个：`handleVulkanInitFailure`（:1038-1047，VULKAN→GLES，stage=getLastInitError）；`handleVulkanInitFailurePost` 的软件分支（:1065-1069，→SOFTWARE）；`handleSurfaceInitTimeout`（:1115-1135，stage="init_timeout" + P2-7 的 prewarm_inflight 标记）；`fallbackToSoftwareRenderer`（:1100-1109，兜底统一出口）。

6. 调试开关（为 P1-1/P2-4 的真机验证提供强制路径）：`NativeBridge` 旁新增 `RenderDebugSwitches`（`BuildConfig.DEBUG` 限定），读取 `Settings.Global` 或本地 prefs 的 `force_backend`（0/1/2）注入 `setRenderBackend`——发布构建编译期剔除。

**验证**：
- 单测：错误码枚举 ↔ 字符串映射表覆盖测试（防拼错）；
- 真机：GLES 会话 logcat 出现 `renderer=Adreno (TM) 740 vendor=Qualcomm`；人为制造 GLES context 失败（调试开关传非法 surface）→ `FallbackReason: VULKAN→GLES stage=GLES_CONTEXT ...` ERROR 日志 + prefs 落盘。

**风险与回滚**：纯增量可观测性改动，无行为变化。~25 处 set 点为机械改动，review 清单核对每个 `return false` 前有 set。

---

### P2-6【P2·确定】SpriteBatcher 每帧堆分配链 → D6

**根因**：见 D6。`SpriteBatcher batcher;` 每帧栈构造（NativeBridge.cpp:695 地图层、:1162 边缘层），grow 链 512→…→16384 共 5 次 new/memcpy/delete ×2/帧（SpriteBatcher.cpp:68-84）；16KB 栈数组也随之每帧构造析构。设计意图（cpp:6-7「跨帧复用」注释）因实例生命周期不成立而落空。

**具体改动**：

1. `NativeBridge.cpp`——两处栈构造提升为文件级 static：

```cpp
// ── 帧批量构建器（跨帧复用，D6）──
// 渲染线程单消费者：drawAllTiles/drawIslandEdges 仅由 RenderThread 经 JNI 调用，
// 无并发；grow 一次后堆缓冲跨帧复用，根除每帧 5 次 new/memcpy/delete ×2 的分配链。
// 命名与 g_lastCropProgress 同纪律（文件级状态须在 shutdownRenderer 说明清理策略：
// batcher 无 native 句柄，无需清理，仅容量驻留 ≤2×16384×32B=1MB 堆）。
static SpriteBatcher g_mapBatcher;
static SpriteBatcher g_edgeBatcher;
```

`drawAllTiles` 内 `SpriteBatcher batcher; batcher.begin(...)` → `g_mapBatcher.begin(...)`（后续引用同步替换）；`drawIslandEdges` 同理用 `g_edgeBatcher`。`GROUND_QUAD_ENABLED=false` 死分支内的 `groundBatcher`（:710）保持局部构造（代码保留待驱动定位，不投入静态资源）。

2. `SpriteBatcher.h/cpp`——补容量封顶丢弃的可观测性（审计 §7「静默丢弃无日志」）：

```cpp
// SpriteBatcher.h 成员：
int droppedSprites = 0;   // 容量封顶后被丢弃的精灵数（add() 丢弃路径 ++）

// SpriteBatcher.cpp add() 第二守卫（:27-29）：
if (vertexCount + 6 > capacity) {
    droppedSprites++;
    return;
}
```

3. `NativeBridge.cpp` `drawAllTiles` 的 `end()` 后：

```cpp
if (g_mapBatcher.droppedSprites > 0) {
    logBatcherOverflowOncePerSecond("map", g_mapBatcher.droppedSprites);  // LOGW 限频
}
```

（文件级 `static int64_t s_lastOverflowLogNs` 限频；`drawIslandEdges` 同式。）

**验证**：
- 编译 + 真机 systrace/`simpleperf malloc` 抽样：渲染稳态（拖拽地图 60s）无每帧 ~90KB 级 malloc/free 波形；
- 极小缩放（整岛视图）下 logcat 出现 `batcher overflow` 限频日志（此前静默）；
- 回归：正常缩放画面与改前逐帧一致（纯生命周期改动，绘制数学零变化）。

**风险与回滚**：唯一语义变化 = 堆缓冲跨 surface 驻留（≤1MB）——已在注释中声明。回滚 = revert。

---

### P2-7【P2·潜在】prewarm 超时×生命周期锁×10s 安全网竞态 → D5

**根因**：见 D5。另实测确认一处审计未列的加重项：超时分支 `if (!prewarmOk) recordVulkanInitFailure()`（GameActivity.kt:357-360）在 **TimeoutCancellationException 后也执行**——对仍在运行且可能成功的 prewarm 做了错误失败定性。

**具体改动**：

1. `GameActivity.startVulkanPrewarmAndAtlasPrefetch`（:304-362）重写——prewarm 移到不可取消的专用线程，结果经台账落地：

```kotlin
private fun startVulkanPrewarmAndAtlasPrefetch() {
    if (!vulkanPrewarmLaunched.compareAndSet(false, true)) return
    // 图集预取保留协程（可取消、无锁竞争）；prewarm 改专用线程（JNI 不可取消，
    // withTimeout 只能放弃等待——放弃后仍持 g_rendererLifecycleMutex 运行，
    // 与 surface 期 initRenderer 在同锁上真实竞争）
    lifecycleScope.launch(ioDispatcher.dispatcher) {
        // 图集预取块：GameActivity.kt:307-317 原文逐字保留（try/prefetch/catch 不变）
    }
    thread(name = "VulkanPrewarm", isDaemon = true) {
        NativeBridge.ensureLoaded()
        VulkanPrewarmState.markStarted()
        CrashRecoveryEngine.markPrewarmStarted()
        val ok = try {
            NativeBridge.prewarmDevice(
                applicationContext.cacheDir.absolutePath,
                GameConfig.SectMap.WORLD_WIDTH_CELLS * GameConfig.SectMap.TILE_SIZE,
                GameConfig.SectMap.WORLD_HEIGHT_CELLS * GameConfig.SectMap.TILE_SIZE,
                GameConfig.SectMap.TILE_SIZE
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Vulkan prewarm exception", t); false
        }
        CrashRecoveryEngine.clearPrewarmStarted()
        if (ok) {
            VulkanPolicy.setVulkanDeviceInfo(           // 低于阈值 → recordVulkanSoftFailure("threshold")
                NativeBridge.getVulkanVendorId(),
                NativeBridge.getVulkanApiVersion(),
                NativeBridge.getVulkanDriverVersion(),
                NativeBridge.getVulkanDeviceName()
            )
        } else {
            CrashRecoveryEngine.recordVulkanSoftFailure("prewarm")
        }
        VulkanPrewarmState.markFinished()
    }
}
```

要点：**不再有 withTimeout / 超时伪造失败记录**——prewarm 慢的场景由第 2 步的预算协同接住；prewarm 真挂死则由安全网预算到期降级 + 写前标记残留（进程死后下轮 kill+1）接住。

2. 预算协同——新增 `object VulkanPrewarmState`（core 包）：

```kotlin
object VulkanPrewarmState {
    const val PREWARM_BUDGET_MS = 8_000L
    @Volatile private var startedAtMs: Long = 0
    @Volatile private var finished: Boolean = true
    fun markStarted() { startedAtMs = System.currentTimeMillis(); finished = false }
    fun markFinished() { finished = true }
    fun inFlight(): Boolean = !finished
    fun startedAt(): Long = startedAtMs
}
```

`AndroidSurfaceProvider.startInitTimeout()`（:106-119）增加预算参数（Kotlin override 不可带默认值——默认值声明在 `core/platform` 的 `SurfaceProvider` 接口上：`fun startInitTimeout(timeoutMs: Long = INIT_TIMEOUT_BASE_MS)`）：

```kotlin
override fun startInitTimeout(timeoutMs: Long) {
    cancelInitTimeout()
    val currentGen = genCounter
    val runnable = Runnable {
        initTimeoutRunnable = null
        if (currentGen != genCounter) return@Runnable
        Log.w(TAG, "Vulkan init timed out (${timeoutMs}ms) — falling back")
        listener?.onSurfaceInitTimeout()
    }
    initTimeoutRunnable = runnable
    handler.postDelayed(runnable, timeoutMs)
}
```

`NativeSurfaceView` 在开启安全网处计算预算（GameActivity 与 View 解耦，经 `initCoordinator` 或直接读 `VulkanPrewarmState`）：

```kotlin
// INIT_TIMEOUT_BASE_MS = 10_000L（原 AndroidSurfaceProvider.INIT_TIMEOUT_MS 改名，
// 作为 startInitTimeout 的默认参数值保留原语义）
val budgetMs = if (VulkanPrewarmState.inFlight()) {
    // prewarm 在途：总预算 = prewarm 起点 + 8s + 10s（initRenderer 正阻塞在
    // 生命周期锁上等 prewarm，属健康慢而非卡死——不降级）
    (VulkanPrewarmState.startedAt() + VulkanPrewarmState.PREWARM_BUDGET_MS +
        INIT_TIMEOUT_BASE_MS) - System.currentTimeMillis()
} else INIT_TIMEOUT_BASE_MS
surfaceProvider.startInitTimeout(budgetMs.coerceAtLeast(1_000L))
```

3. 超时事件可观测（联动 P2-5）：`handleSurfaceInitTimeout` 上报 `stage="init_timeout"` + `extra="prewarm_inflight=${VulkanPrewarmState.inFlight()}"`。

**验证**：
- `AndroidSurfaceProviderTest` 扩展：自定义 timeoutMs 生效（注入 Handler 已支持）；
- 慢设备模拟（首次安装无 pipeline cache + 冷启动）：prewarm >5s 场景不再出现 `falling back` 日志，最终 mode=VULKAN；
- 单测：`VulkanPrewarmState.inFlight()=true` 且 startedAt=now → budget ≈ 18s；finished → 10s。

**风险与回滚**：最坏情况安全网从 10s 延长到 ~18s（prewarm 挂死 + init 阻塞）——比「健康设备误降 GLES 一整个会话」代价小一个量级。回滚 = revert 三文件。

---

### P3 清单逐项

#### P3-1 drawAllTiles 每帧全图数组过 JNI（潜在，先 profile 后动）

**根因**：`tileData`+`roadData` 各 16384 int = 128KB/帧过 JNI（NativeBridge.cpp:691/:816，`GetIntArrayElements` 在 ART 非 pinned 时整组拷贝），消费端却只读可见窗口（:733-736）。

**根治方案**（Phase 4，门控于审计 §11.2 的 profile 结论）：地图数据常驻 C++，Kotlin 仅在**数据变化**时推送——

```cpp
// NativeBridge.cpp 文件级常驻副本（单一权威，Kotlin 变更事件驱动更新）
static std::vector<int32_t> g_tileData;
static std::vector<uint8_t> g_roadBits;
static std::vector<float> g_tileUv;      // tileType × 4，图集构建时推送
static std::vector<float> g_buildingData;
static int g_mapCols = 0, g_mapRows = 0;

// 新 JNI（变更时调用，全量推送即可——变更频率 = 建筑/道路事件级，非帧级）：
//   nativeSetTileData(jintArray, cols, rows) / nativeSetRoadBits(jbyteArray)
//   nativeSetTileUv(jfloatArray) / nativeSetBuildingData(jfloatArray, count)
// drawAllTiles 签名收敛：(atlasTexId, buildingVisible, frameAlpha, cropData, cropUVMap, cloudData, cloudUVMap)
```

Kotlin 侧：帧构建器（SectMapViewport/MainGameScreen 的 RenderFrame 组装处）停止每帧携带全图数组；订阅既有 buildingDirty 总线 + 地图重建事件调用 setter。`shutdownRenderer` 清空常驻副本（防跨代际残留，同 g_lastCropProgress 纪律）。

**验证**：profile 前后 `drawAllTiles` JNI 段耗时对比（审计 §11.2 第 1 项）；画面回归逐帧比对。**若 profile 显示 GetIntArrayElements 实际 pinned 零拷贝且耗时 <0.5ms，则维持现状并在此文档记录结论关闭该项**。

#### P3-2 放置模式网格线逐条 draw（非紧急）

**根因**：每条网格线一次 `drawRect` JNI → 一次 `g_renderer->draw`（VulkanRenderBackend.kt:404-431，~100-240 次/帧）。

**根治**：合批为单次白纹 draw——NativeBridge 新增：

```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_drawRects(
        JNIEnv* env, jobject, jfloatArray rectsXYWH, jint count,
        jfloat r, jfloat g, jfloat b, jfloat a) {
    // 复用 g_mapBatcher 后单独 flush：一次 g_renderer->draw（白纹 id=0），
    // 网格线 draw call 从 O(线数) → 1
}
```

Kotlin 侧 VulkanRenderBackend 收集线段为 FloatArray 后单次调用。选中/拆除高亮（:273-277/:336-359）同式并入。

#### P3-3 作物插值帧末裁剪 O(n×m)（维持现状）

有界小集合（NativeBridge.cpp:1030-1039 注释自认 >500 作物需改）。**决策：维持现状**，依据审计「有界小集合；维持现状可接受」。若灵田规模规划超过 500 作物，届时改空间哈希网格（在 cropData 构建处按格分桶），本文档留档此约定。

#### P3-4 GLES 每帧全量 glBufferData + NEAREST

NEAREST → LINEAR：**已并入 P1-1 的 drainUploads 重写**（见上文代码）。mip 链：GLES2 上下文无 `glGenerateMipmap` 保证，手工逐级上传成本高——**决策：GLES 档位维持单级纹理**，与 Vulkan 的画质差异由「GLES 本就是降级档」定位覆盖（NativeBridge.cpp:446-450 回退单级 RGBA 的既有设计）。VBO 全量 `glBufferData`（GlesBackend.cpp:448-452）：`glBufferData` 每帧重分配即标准 orphaning 流式上传模式，~400KB/帧在降级路径可接受——**维持现状**，P3-1 落地后顶点数据源头也一并优化。

#### P3-5 destroyTexture 空实现且无 JNI 出口

**根治**：补齐 Rhi 契约（GLES 版已在 P1-1 修为队列语义；Vulkan 版实现延迟释放——纹理可能正被在途帧采样）：

```cpp
// VulkanBackend.h：
struct RetiredTexture { uint32_t id; uint64_t retiredAtFrame; };
std::vector<RetiredTexture> m_retiredTextures;   // GUARDED_BY(m_gpuMutex)

void VulkanBackend::destroyTexture(uint32_t id) {
    std::lock_guard<std::mutex> lock(m_gpuMutex);
    m_retiredTextures.push_back({ id, m_frameCounter });
}

// submitFrame 的 vkWaitForFences 之后（该帧 fence 已确认，更早帧的采样必已结束）：
for (auto it = m_retiredTextures.begin(); it != m_retiredTextures.end(); ) {
    if (m_frameCounter - it->retiredAtFrame > MAX_FRAMES_IN_FLIGHT) {
        freeTextureResources(it->id);   // view/image/memory/sampler + m_textures erase
        it = m_retiredTextures.erase(it);
    } else ++it;
}
```

`m_frameCounter` 在 submitFrame 末尾递增（若无现成计数，新增）。NativeBridge 补 JNI 出口 `destroyTexture`（Kotlin 暂无调用方，契约完整即可——图集重建路径未来接入时免坑）。

#### P3-6 GPU 正则防线输入错位 → D2 附带产出 + 删除伪防线

**根因**：`KNOWN_PROBLEM_GPU_PATTERNS`（VulkanPolicy.kt:473-495）匹配 `SOC_MODEL/board/hardware`（:775-795，值如 `sm8550`/`taro`/`qcom`，不含 `mali-g`/`adreno` 字样）——**永不命中**；:787 注释「mt6893 含 Mali 信息」不成立。更危险的是：若真把它接到能命中的输入上（如真实设备名 `Adreno (TM) 740`），`adreno.*73[0-9]|75[0-9]|83[0-9]` 会把 P2-4 刚放行的旗舰重新误杀——**这组从行业 MSAA/compute/decal 缺陷报告抄来的 pattern 与本项目渲染特性（2D 精灵、无 MSAA、无 compute）不相关**。

**根治**：删除 `KNOWN_PROBLEM_GPU_PATTERNS` 及两个匹配块（:775-795）与误导注释；量化防线的输入改为台账持久化的真实设备信息——`VulkanPolicy.initialize(context)` 时：

```kotlin
_deviceInfo = CrashRecoveryEngine.readPersistedGpuInfo()   // 上次成功探测的 vendor/api/driver/deviceName
```

`evaluateVulkanTier`（:105-111）据此在**策略期**即可生效（首装首启无历史 → null → 默认 Allow，本次启动 prewarm 后落台账，下次生效）。

#### P3-7 主线程常驻监控微开销

`Looper.setMessageLogging`（XianxiaApplication.kt:143-152）每条主线程消息一次 `startsWith`。**改动**：`if (BuildConfig.DEBUG)` 包裹注册（Release 不注册——ANR 诊断靠 Bugly 主线程卡顿监控覆盖）；FrameMetrics（GameActivity.kt:796）已窗口化，不动。

#### P3-8 CMake 未显式声明优化/LTO

**改动**（cpp/CMakeLists.txt 追加，行为锁定而非依赖 AGP 默认）：

```cmake
# 显式优化策略（P3-8）：Release 锁 -O2 + section GC + ThinLTO；Debug 保持默认。
# 渲染行为不因构建类型分叉（无 #ifdef），仅优化级别差异（审计 §9 PASS 项维持）。
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_compile_options(native-renderer PRIVATE -O2 -ffunction-sections -fdata-sections)
    target_link_options(native-renderer PRIVATE -Wl,--gc-sections)
    set_target_properties(native-renderer PROPERTIES INTERPROCEDURAL_OPTIMIZATION TRUE)
endif()
```

**验证**：Release 构建产物尺寸对比（预期小幅下降）+ 真机回归（渲染无差异）+ `llvm-nm` 抽查无意外符号裁剪（管线函数仍导出）。**注意**：开启后首次构建如遇 NDK r27 ThinLTO 链接问题，退为仅 `-O2 + gc-sections`（LTO 可选，非目标）。

#### P3-9 上传 fence 等待无超时（潜在，device lost 永久阻塞）

**根因**：`submitOneTimeCommands`（VulkanBackend.cpp:1772-1799）`vkWaitForFences(..., UINT64_MAX)`（:1795）且调用方 `uploadTextureImpl` 持 `m_gpuMutex`（:1812）——device lost 时上传线程永久持锁，渲染线程 submitFrame 抢同一锁 → 整个渲染管线静默冻结。

**根治**：有限等待 + 设备失联怀疑计数：

```cpp
static bool waitForFenceBounded(VkDevice device, VkFence fence, uint64_t totalNs) {
    constexpr uint64_t kStepNs = 100'000'000ULL;   // 100ms 步进
    for (uint64_t waited = 0; waited < totalNs; waited += kStepNs) {
        if (vkWaitForFences(device, 1, &fence, VK_TRUE, kStepNs) == VK_SUCCESS) return true;
    }
    return false;
}

// submitOneTimeCommands 内：
if (!waitForFenceBounded(device, fence, 2'000'000'000ULL /*2s*/)) {
    LOGE("upload fence not signaled within 2s — suspect device lost, abandoning upload");
    vkDestroyFence(device, fence, nullptr);
    vkFreeCommandBuffers(device, pool, 1, &cmd);
    return false;    // 调用方走 fail: 清理路径，锁正常释放
}
```

`uploadTextureImpl` 失败路径已有 `fail:` 清理（:2384-2391）。再加连续失败熔断：`m_uploadTimeoutCount`（成功清零，≥3 → `LOGE("device lost suspected — disabling submits")` + `m_ready=false`，渲染停止提交安全黑屏而非无限冻结，后续由 surface 重建/应用重启恢复）。白纹创建处（:176 同款等待）同用 helper。

**验证**：真机正常路径无行为变化（上传毫秒级完成，2s 上限不触碰）；无法真机注入 device lost——代码走查确认所有 `UINT64_MAX` fence 等待仅剩 submitFrame:2466（三缓冲在途帧等待，标准模式）。

#### P3-10 selectPhysicalDevice 注释与实现不符 + SwiftShader 理论风险

**改动**（VulkanBackend.cpp:442-511）：修正 :442 注释为「选首个通过能力检测的设备（移动端均为单集显）」；遍历内加 CPU ICD 跳过：

```cpp
// 跳过 CPU 软件设备（debuggable 构建下系统可能暴露 SwiftShader ICD——
// 生产低概率，纯防御；移动端正常设备不命中）
const char* dn = props.properties.deviceName;
if (strstr(dn, "swiftshader") || strstr(dn, "llvmpipe")) {
    LOGW("Skipping CPU ICD physical device: %s", dn);
    continue;
}
```

#### P3-11 RenderThread 初始 targetFps=10 窗口

**改动**：NativeSurfaceView.kt:131 `var targetFps: Int = 10` → `60`（与 GameEngineCore StateFlow 默认 60 对齐，:1246 的 EWMA/淡入遮蔽逻辑不变）。**验证**：进入游戏首 1s 内无 10fps 段的节拍感（RenderHealth 日志 rendered 计数）。

---

## 第三部分：实施阶段（依赖排序 + 门槛）

> 原则：先止血（稳定性+可观测），再闭环（策略状态机），后时序（线程模型），性能项 profile 门控。每 Phase 结束跑全量 `./gradlew :app:test :feature:game:test :core:engine:test` + detekt + 真机冒烟。

### Phase 1 —— 稳定性与可观测（P1-1 / P2-5 / P3-11）

- [x] **Task 1.1 GLES 线程契约**：按 P1-1 改 GlesBackend.h/.cpp（mutex + destroy 队列 + LINEAR）。commit: `fix(render): GLES 后端补齐跨线程状态契约——m_stateMutex+待删队列，根除上传队列数据竞争（审计 P1-1）`
- [x] **Task 1.2 错误码贯通**：Rhi.h 枚举 + 两后端 ~25 处 set + `g_lastInitError` 文件级静态 + JNI getter（P2-5 第 1/2/4 步）。commit: `feat(render): initRenderer 失败阶段错误码贯通 C++→JNI（审计 P2-5）`
- [x] **Task 1.3 GLES GPU 信息日志**：`glGetString` 三件套入 init 成功日志（P2-5 第 3 步）。
- [x] **Task 1.4 RenderFallbackReporter**：事件模型 + 四接入点 + 持久化 + TapDB 事件（P2-5 第 5 步）。commit: `feat(render): 渲染回退结构化上报 FallbackReason（from/to/stage/gpu）——根治"Renderer init failed"式日志（审计 P2-5）`
- [x] **Task 1.5 调试后端开关**：`force_backend`（DEBUG 构建）（P2-5 第 6 步）。
- [x] **Task 1.6 targetFps 初值**：10→60（P3-11）。
- [ ] **门槛**：GLES 强制会话真机冒烟（清晰度切换 ×10 无崩溃）+ logcat 含 renderer 名 + 人为失败出 FallbackReason + 全量测试绿。

### Phase 2 —— 失败闭环重构（P2-1 / P2-2 / P2-4 / P3-6）

- [x] **Task 2.1 失败台账**：CrashRecoveryEngine 台账 API + `onCleanLaunch` 重写（P2-1 第 1/2 步）+ `CrashRecoveryEngineLedgerTest` 全套（窗口/衰减/成功清零/残留消费）。
- [x] **Task 2.2 策略链接入**：VulkanPolicy 四死分支替换为 ledgerStrategy + 全部消费点改读台账（P2-1 第 3 步）+ VulkanPolicyTest 扩展。commit: `refactor(render): Vulkan 失败持久化整体重构——布尔标记→计数+窗口+衰减台账，四死分支复活（审计 P2-1）`
- [x] **Task 2.3 成功回写**：handleVulkanInitSuccess → recordVulkanSuccess + 设备信息落台账（P2-1 第 4 步）。
- [x] **Task 2.4 安全模式治理**：归因收窄 + 24h 窗口 + 7 天 TTL + UI 解除入口（P2-2 全部）。commit: `fix(recovery): 安全模式归因收窄至渲染链+7天自动解除——根治误判后永久软渲（审计 P2-2）`
- [x] **Task 2.5 黑名单降级为遥测队列**：PROBLEMATIC→WARNING + cohort 入遥测事件（P2-4）。
- [x] **Task 2.6 删除伪 GPU 正则防线**：patterns + 两匹配块删除，`initialize` 读台账设备信息（P3-6）+ QuantizedThresholdTest 改造（输入从 setVulkanDeviceInfo 改为可注入的持久化信息）。commit: `refactor(policy): 旗舰黑名单降为遥测队列+删除永不命中的GPU正则防线，量化阈值改吃台账设备信息（审计 P2-4/P3-6）`
- [ ] **门槛**：名单内真机走 Vulkan（RenderHealth mode=VULKAN）+ `am crash`×3 → 第 4 次启动 GLES_PREFERRED 的手工链路验证 + 全量测试绿。

### Phase 3 —— 线程时序与资源（P2-3 / P3-9 / P2-6 / P3-5 / P3-10）

- [x] **Task 3.1 resize 收敛渲染线程**：pending 通道 + `consumePendingNativeResize` + initRenderer/shutdown 清残留（P2-3）。commit: `fix(render): resize 收敛至渲染线程帧边界消费——按构造消除 acquire/destroy 并发 UB（审计 P2-3）`
- [x] **Task 3.2 fence 有限等待**：waitForFenceBounded + 熔断计数（P3-9）。
- [x] **Task 3.3 batcher 静态化**：g_mapBatcher/g_edgeBatcher + droppedSprites 限频日志（P2-6）。commit: `perf(render): SpriteBatcher 跨帧复用——根除每帧 5×2 次 grow 分配链+容量溢出可观测（审计 P2-6）`
- [x] **Task 3.4 Vulkan destroyTexture**：延迟释放队列 + JNI 出口（P3-5）。
- [x] **Task 3.5 SwiftShader 跳过 + 注释修正**（P3-10）。
- [ ] **门槛**：旋转/分屏 stress ×50 无崩溃 + simpleperf 稳态无每帧分配波形 + 全量测试绿。

### Phase 4 —— 性能项（profile 门控，先执行审计 §11.2 清单）

- [ ] **Task 4.0 真机 profiling**（骁龙 8 Gen 2 + 中端 Mali 各一）：drawAllTiles JNI 段耗时（含 GetIntArrayElements 是否拷贝）/ renderTick 拖拽态分布 / 放置模式 vkCmdDraw 数 / GLES vs Vulkan 帧耗时。**结论回填本文档**，决定 4.1/4.2 是否执行。
- [ ] **Task 4.1 地图数据常驻 native**（P3-1，若 profile 证实 JNI 拷贝成立）。
- [ ] **Task 4.2 网格线合批**（P3-2，若放置模式 CPU 提交占比成立）。commit: `perf(render): 网格线/高亮合批为单次白纹 draw（审计 P3-2）`
- [x] **Task 4.3 Looper 监控 DEBUG 门控**（P3-7）。
- [x] **Task 4.4 CMake 显式优化**（P3-8）。
- [ ] P3-3 / P3-4(mip) 维持现状（本文档已记录决策与重启条件）。

---

## 第四部分：监控与校准闭环

**事件 schema**（TapDB 自定义事件，P2-5 落地后生效）：

| 事件 | 字段 | 用途 |
|---|---|---|
| `render_backend_session` | backend, gpu_name, driver, api, strategy, cohort(名单命中), duration | 版本后统计各后端占比 → 校准 P2-4 名单（数据决定是否恢复窄 Deny） |
| `render_fallback` | from, to, stage, elapsed_ms, gpu, prewarm_inflight | 回退原因分布 → 定位 GLES/软件占比异常的驱动/阶段 |
| `vulkan_kill` | stage(prewarm/initSurface) | 崩溃循环设备的真实规模 |

**看板判读**：某 cohort 的 `render_fallback` 中 `stage=VK_SWAPCHAIN` 占比显著 → 恢复该 cohort Deny；`prewarm_inflight=true` 的 init_timeout 占比高 → 重审 D5 预算。闭环数据替代静态名单——这是 P2-4「无校准闭环」的结构性解。

## 第五部分：根治判据自检表（对照 R1–R4）

| 问题 | 不变量（修复后成立） | 判据 |
|---|---|---|
| P1-1 | GLES 后端所有跨线程状态必须经 m_stateMutex 或渲染线程独占——头文件契约编码 | R1 |
| P2-1 | 失败学习是单调状态机（增量消费+窗口+衰减+成功清零），无「读取前被无条件清零」路径 | R2 |
| P2-2 | 安全模式只由渲染链崩溃（24h 窗口≥3）进入，且任何持久降级态必有 TTL | R2 |
| P2-3 | swapchain 生命周期操作只能由渲染线程在帧边界执行（构造上无并发） | R1 |
| P2-4 | 静态名单不参与决策，决策=量化探测+运行时回退+台账；数据闭环校准 | R3 |
| P2-5 | 任何降级/失败必产出结构化事件（错误码贯通），无 boolean 坍缩 | R1 |
| P2-6 | 帧路径零堆分配（batcher 跨帧复用），溢出可见 | R4 |
| P2-7 | 超时预算全局一致（prewarm+init 一体化），无「放弃等待但资源仍占」的伪造失败 | R2 |
| P3 系列 | 各自局部（真实输入/契约补齐/显式声明），见上文对应项 | R3/R4 |

## 第六部分：与审计 §11.3 清单的覆盖对照

| §11.3 优先级 | 修复项 | 本方案 |
|---|---|---|
| 1 | GLES m_pendingUploads 加锁 | P1-1（D1，含审计未列的 3 处同类竞争面） |
| 2 | Vulkan 失败退避重试 + 补 KEY_VULKAN_CRASH_DETECTED 写入方 | P2-1（D2——比「补写入方」更进一步：整个标记体系重构为台账，写入方问题不复存在） |
| 3 | 安全模式自动衰减 + 归因收窄 | P2-2 |
| 4 | Renderer 健康状态结构化上报 | P2-5（D3） |
| 5 | SpriteBatcher 跨帧复用 | P2-6（D6） |
| 6 | 黑名单复核 / 策略统一 | P2-4 + P3-6（D2 数据闭环） |
| 7 | resize 握手、prewarm 协同、fence 超时 | P2-3（D4）/ P2-7（D5）/ P3-9 |
| 8 | 其余 P3 按迭代节奏 | P3-1~P3-11 逐项（含 2 项「维持现状+重启条件」决策：P3-3、P3-4 mip） |

---

---

## 实施记录（2026-09-09 执行会话回填）

- Phase 1/2/3 全部代码任务 + Task 4.3/4.4 已实施；Phase 1/2/3/4 门槛中「全量测试绿」已完成
  （app/feature:game/core:engine 单测 + detekt + NDK r27 arm64-v8a Debug 编译通过）。
- **预存问题修复**：`gamecore/include/gamecore/system/secret_realm.h:488` 注释与函数声明
  粘连（换行丢失，预存工作区损坏）导致 gamecore 编译失败——已拆行修复，不属本方案范围但为编译前置。
- **真机门槛项未执行**（本会话无设备）：Phase 1 GLES 强制会话冒烟（清晰度切换×10）、
  Phase 2 `am crash`×3 台账链路、Phase 3 旋转/分屏×50 与 simpleperf 分配波形、Task 4.0/4.1/4.2
  profiling 门控项——待真机执行；强制路径用 Task 1.5 调试开关（prefs `render_debug` 键 `force_backend`）。
- **模块边界适配**：CrashRecoveryEngine 在 app 模块而 NativeSurfaceView 在 feature/game——
  成功回写经 `VulkanInitListener.onVulkanChainSucceeded()`（新增默认空实现）回调到宿主落地台账；
  RenderFallbackReporter 的持久化/遥测经 `persistSink`/`telemetrySink` 端口由 GameActivity 注入。
- **P2-7（D5 预算协同）随 Phase 1/2 一并落地**：`VulkanPrewarmState`（core/engine）+
  `SurfaceProvider.startInitTimeout(timeoutMs)` 预算化 + GameActivity prewarm 专用线程化——
  原分 Phase 清单未显式列出该项，此处补记。

*方案完。所有行号基于 2026-09-09 工作区实读核对；执行前如代码已漂移，以函数名+注释锚点二次定位。*
