# Android 图形 API 选择/降级与 GPU 设备分级 —— 行业级深度调研报告

> 调研方法：web_search + web_fetch，抓取官方文档/技术博客原文；5 个并行子代（头部手游、Unity、Unreal、Flutter/Godot、软件渲染与驱动规避）分工调研后汇总之并在本文整理。
> 权威等级：S=官方文档/白皮书/顶会；A=头部厂商技术博客/顶会/官方复盘；B=高质量社区；C=个人博客（不计入配额）。
> 结论适用对象：本自研引擎（有真实 Vulkan 后端 + CPU Canvas 软件渲染兜底），当前"Vulkan 黑名单过宽"导致绝大多数中国厂商设备（MediaTek/非高通国产/Mali/大量机型）被降级到 CPU 软件渲染。

---

## 〇、一句话结论（TL;DR）

> **整个行业（Unity / Unreal / Chromium / Flutter Impeller / Godot）的降级链都是「Vulkan → GPU OpenGL ES → 软件渲染（仅最终兜底）」，且**主流引擎/头部手游**基本不放宽"黑名单"而是用「默认 Vulkan + 精确 SoC/驱动白名单 + 运行时能力探测 + 崩溃自愈 + 逐厂商 workaround」**去保住 GPU 渲染**。没有一家把 CPU 软件渲染当作低端主路径。**
> 本项目当前是「Vulkan → CPU Canvas」且额外**关闭了系统硬件加速**（→ 真·CPU 逐像素渲染），跳过了行业标配的「GPU GLES」中间层，因此性能/电量成为风险。
> **结论：以"扩白名单·保住 Vulkan（精确降级）+ 补一个 GPU GLES 中间层"为主；优化 CPU Canvas 仅作为灾难兜底与保险，不作为低端主路径。**

---

## 一、逐子题发现

### 子题 1：头部手游（原神 / 星铁 / 王者 / 和平精英 / PUBG Mobile）的 GPU/机型白名单与 API 选择

**总纲**：Android 图形 API 不是"全设备统一"，而是**默认 OpenGL ES，按 GPU 厂商 + 型号 + Android 版本 + Vulkan API 版本 + 驱动版本分层放行 Vulkan；Vulkan 不可用/驱动有缺陷就回退 GLES3**。"设备分级+画质档位"与"API 选择"是两套但联动的系统。

- **原神 Genshin（米哈游，深度魔改 Unity / 自研 Renderer）**
  - 5.8（2025-07-31）起对部分 SoC **默认开启 Vulkan**，其余设备仍用 OpenGL ES；玩家可在 设置→图像 查看当前 API。历史有反复：5.2 曾默认开 Vulkan → 更新取消 → 5.8 再开（说明"适配成熟度"是开关关键）。
  - **默认 Vulkan 的 SoC 名单（官方）**：天玑（MediaTek）9000/9200/9300/9400 已支持，**天玑 9400+ 尚未支持**；Exynos 2200/2400/2500 支持；骁龙 8 Elite、8s Gen4 完全适配，8 Gen3/8 Gen2/8/8+ 需"特定驱动或以上版本"才开。
  - 规格/画质：5.8 之前普遍最高 710P；4.7（2024-06-08）新增"极高"=810P，按机型下发。
  - **做法/理由**：Vulkan 画质与渲染效率更好，但受驱动成熟度约束，只在高端旗舰 SoC + 特定驱动放行，避免低端/老驱动翻车。**说明：对 MediaTek 是按"具体型号 + 家族"放行（9000~9400 用，9400+ 不用），不是"联发科一律不用"。**
  - 来源：(A/B)《原神 5.8 图形 API 更新 高端机型默认开启 Vulkan》 https://m.sohu.com/a/919400887_115088/ 2025-07-31。
  - **PowerVR 硬黑名单**：米哈游在 Pixel 10（Imagination PowerVR DXT-48-1536）上因驱动缺陷（数月前的 v24.3，公版已 v25.1）**停止支持 PowerVR GPU**，导致原神在 Pixel 10 几乎无法游玩（谷歌确认是 GPU 驱动更新，用户等待驱动补丁）。来源：(A/B) IT之家 https://m.ithome.com/html/891837.htm 2025-10-23。(A/B) piunikaweb《Genshin Impact stuck on "compiling shaders" for PowerVR》 2025-10-23。

- **崩坏：星穹铁道 HSR（米哈游，Unity）** —— 官方给出**最有价值的 SoC 级档位表 + 黑名单**
  - **推荐配置（Android）**：骁龙 870（Adreno 650）/ 天玑 1300（Mali-G77 MC9）/ 麒麟 9000（Mali-G78 MP24）性能以上 SoC；6GB；Android 9+。
  - **支持配置（Android）**：骁龙 835（Adreno 540）/ 天玑 720（Mali-G57 MC3）/ 麒麟 810（Mali-G52 MP6）以上；4GB；Android 9+。
  - **兼容性黑名单（官方明确）**：**除 Imagination D-Series 外，其他 PowerVR 架构 GPU 均不支持**。低于支持配置仍可进游戏但稳定性变差（卡顿/闪退）。
  - **做法/理由**：点名到"具体 SoC 家族 + Mali 核数 + Adreno 型号"，是典型的"白名单式硬件要求 + 黑名单式 GPU 排除"；对国产 SoC（天玑/麒麟）给了明确档位；对 PowerVR 这种兼容性差的 GPU 直接拉黑。
  - 来源：(S) 米哈游官方公告（经 360/游民星空转载）《崩坏：星穹铁道 硬件性能要求提高》 http://360game.360.cn/article/content?id=695a4c4a01d33943481e6e20 2026-01-04。

- **王者荣耀 Honor of Kings（腾讯天美，Unity）**
  - 用**前向渲染**兼顾写实/非写实材质（GDC2023 天美 L1 高级渲染工程师 Xiaoxin Guo）；目标硬件覆盖"入门机到最新旗舰"，在 1080p60 压住 1 亿+ DAU；对"无强大计算管线/UAV/FrameBuffer Fetch"的老设备用**图形管线而非计算管线**做动态烘焙（按设备能力降级）。
  - Vulkan：2025-09 宣布 9 月底大版本**全面引入 Vulkan 渲染引擎模式**，OPPO ColorOS 适配名单覆盖 Find X8 系列、Reno14、一加 13 系列等（软硬件联合优化+超帧超画）。历史：2022 年曾在三星做过 Vulkan 版本后被"打回 ES"，说明 Vulkan 是分机型、分厂商逐步铺开、可回退。
  - 来源：(A) GDC2023 腾讯 Xiaoxin Guo《移动平台的高性能渲染实用技巧》 http://www.gamelook.com.cn/2023/05/517055/ 2023-05-06；(A/B) OPPO ColorOS 适配《王者荣耀》Vulkan 引擎 https://www.ithome.com/0/883/819.htm 2025-09-18；(B) UWA《Vulkan API 的性能及兼容性》 https://blog.uwa4d.com/archives/TechSharing_314.html 2022-11-22。

- **和平精英 Game for Peace（腾讯光子，UE4）**
  - Vulkan 做成**可在部分机型开启的"模式"**而非全量强制：仅中高端可开，低端开启失败/不显示；新版本"对机型细化，部分机型取消 Vulkan 模式"，且**与手机厂商合作机型才有**（未合作机型配置达标也没有）。低端机整体画质/帧率被压更低。
  - 来源：(C) 《和平精英 vulkan 模式怎么不见了》 https://www.9game.cn/hpjy/10599855.html 2024-11-05；(C) 腾讯云开发者社区 2020-06-10。**注意：未见光子官方逐机型白名单**，此为用户侧证据，等级偏低（C 不计配额）。

- **PUBG Mobile（腾讯/光子，UE4）**
  - 面向数百上千种 Android 机型做适配优化；无官方逐 GPU 的 Vulkan/GLES 表，**推断走 UE4 Device Profile + `r.Android.DisableVulkanSupport` 通用路径**。
  - 来源：(A) Epic 官方博客《UE4 手游〈绝地求生：刺激战场〉开发经验分享》 https://www.unrealengine.com/blog/chn-pubg-mobile-ue4-development-experience (日期未知，页面 403)。

- **共同规律**
  1. 白名单都是**按 GPU/SoC + 驱动版本**，而非"只有高通能上 Vulkan"。
  2. **米哈游/腾讯都没有公开"逐机型完整 Vulkan 黑/白名单"表格**；能确认的官方依据只有：游戏内设置（原神）、官方设备性能公告（星铁）、厂商系统适配名单（王者×ColorOS）、引擎通用机制（Unity/Unreal）。凡缺官方文档处均注明"推断"。

### 子题 2：Unity 的 Vulkan Device Filtering / 自动选择降级 / 不同 SoC 处理

- **核心机制（S 级官方，含精确阈值）**：Unity 默认会阻断在"已知运行表现不佳"的 Android 设备上启用 Vulkan，并按 GPU 厂商套用一组**默认最低规格（minimum spec）**；不达标设备自动回退到 Player 设置里的默认图形 API（GLES3.x）。官方表格：

  | 厂商 | 最低规格 |
  |---|---|
  | NVidia | Vulkan API ≥ 1.0.13 |
  | **ARM（Mali）** | **Vulkan API ≥ 1.0.61** |
  | Imagination（PowerVR） | Vulkan API ≥ 1.1.170 且 Driver ≥ 1.473.1397 |
  | Qualcomm（Adreno） | Driver 最高位(MSB)置位 或 Vulkan API ≥ 1.0.49 |

  - **这就是本项目里"GPU 黑名单对齐 Unity（Mali VK<1.0.61 自动降级）"的真实、有官方出处的依据**。
  - 来源：(S) Unity Manual《Allow or deny Vulkan API usage》 https://docs.unity3d.com/6/Documentation/Manual/allow-deny-vulkan-usage.html （页面构建 2026-08-31）；(S)《Introduction to Vulkan Device Filtering Asset》 https://docs.unity3d.com/6000.2/Documentation/Manual/introduction-vulkan-device-filtering-asset.html （构建 2026-02-05）。

- **Allow/Deny 过滤列表（7 要素 + 正则 + 逻辑与）**：用 Vendor / Device Name / Brand / Product Name / Android OS 版本 / Vulkan API 版本 / Driver 版本 标识一组设备；除 API/Driver 版本外全支持 C# 正则（如 `[A|a]dreno .*6[0-9][0-9]`）；多个参数须**逻辑与**同时匹配。Allow=版本**≥**设定值放行；Deny=版本**≤**设定值禁用。**默认拒绝 Google Pixel 6 用 Vulkan**（须手动从 Deny List 移出）。允许/拒绝设相同值则忽略；受限设备回退到配置的默认 API，**若无可用回退 API，应用不启动**。

- **机制演进**：旧版（2021/2022）的 `androidVulkanDenyFilterList`/`androidVulkanAllowFilterList`（Player 设置字段，脚本 API `PlayerSettings.androidVulkanDenyFilterList` 等）在 **Unity 6.1（6000.1）起被 "Vulkan Device Filtering Asset" 取代**（旧字段标记 obsolete，可导入旧值）；再到 **Unity 6.6 又加入 Vulkan hardware profiles**（C# `AndroidHardwareProfiles`），官方推荐其为主要设备过滤手段，可逐机型强 `SetGraphicsAPI(UseVulkan/UseOpenGles)`（官方示例即"默认 Vulkan、Pixel 6 强制 OpenGLES"），并可逐设备开关 workaround。**注意：hardware profiles 与 Device Filtering Asset 不能同时用，hardware profiles 优先。**

- **自动选择与降级**：Android Player 设置里 **Auto Graphics API 默认开启**——尝试用 Vulkan，设备不支持则回退 GLES3.2/3.1；手动指定列表则按"自上而下"回退（当前列表只有 Vulkan 与 OpenGLES3）。**Unity 6 已移除 OpenGL ES 2.0**，最低提至 **GLES 3.1**（默认最低着色器目标 `SHADER_API_GLES31`）。
  - 来源：(S)《Android Player settings》 https://docs.unity3d.com/6000.6/Documentation/Manual/class-PlayerSettingsAndroid.html 2026；(S)《Android requirements and compatibility》(Unity 6.0) https://docs.unity3d.com/cn/current/Manual/android-requirements-and-compatibility.html 2025-12；(S) `PlayerSettings.SetUseDefaultGraphicsAPIs` https://docs.unity3d.com/ScriptReference/PlayerSettings.SetUseDefaultGraphicsAPIs.html 2026。

- **驱动缺陷 workaround（关键度最高的机制之一）**：Unity Vulkan 后端内置约 16 项**驱动缺陷 workaround**（按 Vendor/Device/Driver/OS/API 自动套用），如 `HasBuggyPipelineCacheDataSize`、`HasBuggyBackBufferCopyImage`、**`HasBuggyResetCommandBuffer`**(重置 command buffer 后驱动崩溃=崩溃自愈前置绕开)、`HasBuggyMSAAResolvePass`、`HasBuggySubpassMerging`、`HasBuggySRGBSwapChain` 等。这些决策**不公开、且偏向"兼容优先于性能"**；开发者可通过 hardware profiles 的 `SetWorkaround(name, State.Enabled/Disabled)` 对指定设备启用/禁用（绕过坏驱动路径而不是禁用整条 API——只要该缺陷只在特定 feature/path 触发）。
  - 来源：(S)《Workarounds》 https://docs.unity3d.com/6000.7/Documentation/Manual/vulkan-hardware-profiles-workarounds.html 2026-09-02；(S)《Graphics API hardware profile settings》 https://docs.unity3d.com/6000.7/Documentation/Manual/vulkan-hardware-profiles-graphics-api.html 2026-09-02。

- **设备分级与 Quality 的联动**：Unity 的 Quality Levels 控制管线/AA/VSync/阴影/纹理/LOD 等，**本身没有"图形 API 选择"项**——即 Unity **没有"按质量档切换图形 API"的原生开箱能力**；要做这类联动需自建（hardware profile + `SystemInfo.graphicsDeviceType` + 运行时逻辑）。设备分级/API 选择在"Player 设置 + 逐设备 hardware profile"层面完成。
  - 来源：(S)《Quality》 https://docs.unity3d.com/cn/current/Manual/class-QualitySettings.html 2025-12。

### 子题 3：Unreal Engine 的 Rendering API 选择 / 设备分级 / 驱动缺陷规避

- **RHI 功能级别三档**：OpenGL ES 3.2（Android 默认）、Android Vulkan（部分高端机）、Metal（iOS）。**UE5 已彻底移除 ES2**；引擎源码 `AndroidRuntimeSettings` 构造里 `bBuildForES31 = bBuildForES31 || !bSupportsVulkan`，即"没开 Vulkan 就强制兜底开 ES3.1"。
- **配置项**（`[/Script/AndroidRuntimeSettings.AndroidRuntimeSettings]`，Base/DefaultEngine.ini）：`bSupportsVulkan`（默认 false）、`bSupportsVulkanSM5`、`bBuildForES31`、`bDetectVulkanByDefault`，以及 CVar `r.Android.DisableVulkanSupport`（**默认=1，即默认关 Vulkan**；支持的设备在 profile 里改 0 开启）、`r.Android.DisableVulkanSM5Support`。`EnsureValidGPUArch()` 校验：若三个布尔全 false 则告警并强制回退 `bBuildForES31=true`。
- **运行时选择与回退顺序**：`FAndroidMisc::IsVulkanAvailable()` 先查设备有无 Vulkan 驱动 → 再查项目是否 `bSupportsVulkan||bSupportsVulkanSM5` → 再查 `bDetectVulkanByDefault` 或命令行；设备 profile 用 `SRC_VulkanAvailable`/`SRC_VulkanVersion` 匹配并筛选出"Vulkan 开/关"两个 profile。**回退顺序 = Vulkan → OpenGL ES 3.1(ES3.2 API) → (ES2 已移除)**，由"设备能力 + 工程配置 + 设备 profile"三者启动期共同决定。
- **设备分级（三层 device profile）**：标准档 `Android_Low / Android_Mid / Android_High` → **GPU 家族档**（`Android_Adreno5xx`、`Android_Mali_T8xx`、`Adreno6xx`、`Mali G72/G76/G77`、`PowerVR GM9xxx`、`Samsung XClipse`）→ **具体机型档**（如 Samsung Note9 Adreno）。匹配规则 `SRC_DeviceModel/DeviceMake/GPUFamily/GlVersion/AndroidVersion/VulkanAvailable/VulkanVersion`，Compare 支持 Regex/Equal/Less/Greater，命中即停。Scalability 0~3 四档把阴影/植被/模型细节/后处理打包，`sg.XXX` 引用（建议在 `*Scalability.ini` 里改）；可运行时动态降级。
- **Vulkan 官方支持设备**：Adreno 6xx、Mali G72/G76/G77、PowerVR GM9xxx、三星 XClipse，且要求 Android 9+。
- **驱动缺陷 workaround（官方做法）**：不是"某目录"，而是分散在 ① device profile CVar（`r.Android.DisableVulkanSupport`）；② VulkanRHI 模块内厂商检测/功能位（Vendor/VK_VENDOR_ID、subpass/PLS/framebuffer_fetch）；③ shader 平台定义里的 Android 特殊分支。典型例子：`Android_Adreno5xx_No_Vulkan`（Android 7 及更早的 Adreno5xx 在 sub-passes/occlusion 有缺陷 → 关 Vulkan）；`Android_Mali_T8xx_No_Vulkan`（Mali-T8 在 Android 8 以下有缺陷 → 关 Vulkan）。**即"特定 GPU 家族 + 特定系统版本组合直接关 Vulkan"，而不是"非高通一律关"。**
- **Mali 专属限制**：Android Vulkan 下 GBuffer 每像素 ≤16 字节（128-bit）、最多 4 个 input attachment、lighting pass 最多取 3 个 color + 1 个 depth；tile memory：Android Vulkan 用 sub-passes、Mali/PowerVR 用 PLS 扩展、Adreno 用 framebuffer_fetch 扩展。
- **堡垒之夜实例（Epic 官方，A 级）**：发布时**绝大多数设备用 OpenGL ES**（更快更稳），仅 Galaxy S9+/Note9 (Adreno) 经三星优化让 **Vulkan 平均快 20%** 才在这批机型启用；Epic 明言"Vulkan 尚未成为 Android 强制要求，早期驱动 bug 多，OpenGL 在多数设备更快更稳"。做层次化 profile（Low/Mid/High/Epic + GPU profile + 机型 profile）+ 大量 RHI 级 workaround（descriptor cache、**Pipeline LRU（仅 Mali——不加此修复部分 Mali 设备根本无法运行）**、去 staging buffer、pipeline cache 指针 hash 修复、纹理上传批量化等）。
  - 渲染 CPU 成本是最大瓶颈；Adreno S9 可扛 >1500 drawcall、中端约 600、低端约 400。
- **UE 5.x 现状/方向**：**Nanite 在移动渲染器不支持**；**Lumen 仅实验性**（需 desktop renderer + Vulkan SM5 高端机 Xclipse/Adreno 7xx/Mali G7xx，官方不建议用于已发布项目）；**Desktop Renderer on Mobile 仅支持 SM5 设备、Android 为实验性**。移动端主力仍是 Mobile Forward（默认）→ Mobile Deferred（较高硬件）。
  - 来源：(S) Epic《Customizing Device Profiles and Scalability in UE Projects for Android》（UE5.8，≈2025） https://dev.epicgames.com/documentation/en-us/unreal-engine/customizing-device-profiles-and-scalability-in-unreal-engine-projects-for-android ；(S)《Using the Android Vulkan Mobile Renderer》（UE5.8）；(S)《Mobile Rendering Features Reference》（UE5.8）；(S)《Using Lumen Global Illumination on Mobile》（UE5.8）；(A)《Fortnite on Android Launch Technical Blog》 https://devtrackers.gg/fortnite/p/51359e66-fortnite-on-android-launch-technical-blog 2018-09；(A) Samsung《A Year in a Fortnite》 https://developer.samsung.com/galaxy-gamedev/gamedev-blog/fortnite.html ≈2019。
- **明确诚实声明**：① 未发现 UE 源码里有名为 `drivers/Android/` 的 vendor workaround 目录，实际分散在上述三处；② **Minecraft 不是 UE 开发**（Java 版自研 LWJGL/GL、基岩版自研 C++ 走 OpenGL ES/Metal），不存在"Minecraft 的 UE 移动端 API 选择"这一事实，故未编造。

### 子题 4：Flutter Impeller 与 Godot 4 的移动端降级

- **Flutter Impeller（Android）：Vulkan 优先，运行时自动回退 OpenGL ES（GPU），不是回退 CPU 软渲染**
  - **后端选择 = 能力探测 + 逐级判断**（官方决策链）：①设备支持 Vulkan？否→OpenGL；是→②Android API ≥29（Android 10，HardwareBuffer/平台视图关键）？否→OpenGL；是→③Vulkan ≥1.1？否→OpenGL；是→④支持必需扩展（如 `VK_ANDROID_external_memory_android_hardware_buffer`）？否→OpenGL；否则→Vulkan。（`engine/impeller/docs/android.md`）
  - **C++ 端真实探测**：`FlutterMain::SelectRenderingAPI()` 先查 `enable_software_rendering`→kSoftware；再按 `api_level<29` 回退；否则**实际构造 `AndroidContextVKImpeller` 并调 `IsValid()`**，无效→回退；有效→Vulkan。即以"实际创建上下文验证"为准，而非只看特性表。
  - **MediaTek/MTK SoC 自动回退（官方 changelog 确凿）**：Flutter 3.29.2 changelog 收录 `[CP] Disable Vulkan on MediaTek SoC`（commit 18b71d6），即检测到 MediaTek 禁用 Vulkan；同期还有 `Disable Impeller on Adreno < 630/640`（引擎 PR #57100，2024-12-13）。**注意：这些都是"禁用 Vulkan → 回退到 OpenGL ES（GPU 渲染）"，不是回退到 CPU 软渲染。**
  - **Mali/部分设备 = 按厂商的 `WorkaroundsVK` 开关（保留 Vulkan）**：Adreno 一概设 `slow_primitive_restart_performance + broken_mipmap_generation`；Adreno≤630 加 `input_attachment_self_dependency_broken + batch_submit_command_buffer_timeout`；PowerVR 加前者。缺陷 GPU 先"关掉具体出问题的特性"，彻底崩坏的厂商/代际才整类禁用 Vulkan（且禁用后仍回退到 GPU GLES）。
  - **开发者可强制**：`flutter run --no-enable-impeller`；Manifest `<meta-data io.flutter.embedding.android.EnableImpeller=false>`（整体关）；**强用 GLES 后端 `io.flutter.embedding.android.ImpellerBackend=opengl`**（遇到 MTK/Mali 崩溃又想要无 shader jank 流畅度的推荐路径，而非整体关 Impeller）。
  - 来源：(S)《Impeller rendering engine》 https://docs.flutter.dev/perf/impeller ；(S) 《Android》(engine/impeller/docs/android.md) https://raw.githubusercontent.com/flutter/engine/main/impeller/docs/android.md ；(S) Flutter Engine 源码 `flutter_main.cc` https://github.com/flutter/engine/blob/main/shell/platform/android/flutter_main.cc ；(S) `workarounds_vk.cc` https://api.flutter.dev/impeller/workarounds__vk_8cc_source.html ；(S/A) Flutter 3.29.2 CHANGELOG "[CP] Disable Vulkan on MediaTek SoC" https://chromium.journaldev.googlesource.com/external/github.com/flutter/flutter/+log/refs/tags/3.29.2/CHANGELOG.md 。

- **Godot 4（Android）：RenderingDevice(Vulkan) vs Compatibility(GLES3)，4.4 起才有自动降级**
  - 三大渲染器：**Forward+**（桌面，Vulkan/D3D12/Metal）、**Mobile**（移动/桌面，Vulkan/D3D12/Metal，**Android 默认**）、**Compatibility**（GL/OpenGL/GLES3，低端/老机/Web）。Forward+/Mobile 走 RenderingDevice 抽象层，Compatibility 走 OpenGL。
  - **移动端选择与降级**：项目设置 `rendering/rendering_device/driver.android` 仅 "vulkan"（Android 无 D3D12/Metal）；`rendering/gl_compatibility/driver.android`="opengl3"(GLES3)。**从 Godot 4.4 起**：Vulkan 不可用→回退 D3D12（Android 无）→回退 Compatibility；由 `rendering/rendering_device/fallback_to_opengl3`（**默认 true**，另有 `fallback_to_vulkan=true`、`fallback_to_d3d12=true`）控制。
  - **差异化结论**：Godot **4.4 之前没有自动 Vulkan→GL 回退**（长期提案 #8006），4.4 才落地且默认可关；Android 上 RD 驱动只有 Vulkan，故回退链是 **Vulkan→Compatibility(GLES3)**。Flutter 则是引擎内置、运行时自动、含厂商黑名单。**注意两套机制差异，勿混用假设。**
  - 来源：(S) Godot《Renderers》 https://docs.godotengine.org/en/stable/tutorials/rendering/renderers.html 4.4；(S) Godot 源码 `main/main.cpp`（`fallback_to_opengl3` 等） https://github.com/godotengine/godot/blob/master/main/main.cpp ；(B) 提案 #8006 https://github.com/godotengine/godot-proposals/issues/8006 ；(B) issue #111729（gl_compatibility→mobile 设备集缩小） / #86112（老安卓机 GLES3 Compatibility 不可用）。

### 子题 5：软件渲染在手游中的定位（何时可接受 / 何时是灾难）

- **SwiftShader/SwANGLE/lvmpipe = 纯 CPU 实现**，官方定位就是"无 GPU 环境 + WebGL 兜底"，不是性能路径。Chromium 将其定义为"一个开源的高性能 Vulkan/OpenGL ES 实现，**纯粹运行在 CPU 上**，因此高级 3D 不需要 GPU"。
- **关键事实（本项目最相关）**：Chromium 官方 GPU Process Fallback 文档明确 **Android（及 ChromeOS）"不支持软件渲染"，因为 Android 系统本就要求硬件加速**。→ **手游不能指望系统级 SwiftShader 兜底，必须自建 CPU Canvas 软渲染路径**（本项目正是如此）。
- **降级由高到低**：`HARDWARE_VULKAN → HARDWARE_GL → SWIFTSHADER → DISPLAY_COMPOSITOR`（Chromium）。官方注释"软件渲染应该比硬件加速更稳定，因为不依赖第三方驱动"——即软渲染=稳定的兜底、Vulkan=最高风险。**Android 因强制硬件加速，GPU 进程崩溃容错阈值反而放宽**（`kGpuFallbackCrashCount` 更高，OS 可任意杀 GPU 进程）。
- **工程判据——何时可接受**：启动/加载帧（首帧前的兼容兜底）、**小分辨率简单 2D UI/菜单**、无 GPU 的 CI/灰度测试、低端机"能进游戏"保底。**何时是灾难**：大分辨率 + 3D + 复杂特效时 CPU 逐像素算着色，帧率与功耗**数量级劣化**——因为 GPU 是专用并行处理器，CPU 串行/弱并行，软渲染只能靠多线程 + SIMD 凑合。**正是"能显示"而非"跑得动"。**
- **引擎侧态度**：Unity/Unreal/Flutter 都走"GPU 优先 + 设备级分流（Vulkan/GLES）"，**不做整帧软渲染**；软渲染只是极端兜底。Flutter/Impeller 用 GPU（Android 上 Vulkan/GLES）；唯一"软件渲染"是为测试/CI 的 Skia 软件路径。

### 子题 6：驱动缺陷规避的通用做法

1. **版本范围黑名单/白名单 + 厂商默认最低规格**（Unity 官方是白皮书级范式）：按 Vendor/Device/Brand/Product/OS 版本/Vulkan API 版本/Driver 版本做 Allow/Deny，恶意用"驱动/API 版本阈值"而非"逐机型枚举"，能覆盖成批未预见的新机型。
2. **逐设备强制 API（硬件配置文件）**：给特定机型/CODENAME 写 `SetGraphicsAPI(UseVulkan/UseOpenGles)`，避免全局开关误伤。
3. **已知缺陷的"能力探测 + 运行期 workaround"（逐 bug 开关）**：为每个已知驱动缺陷提供布尔 workaround（Unity 约 16 项：`HasBuggyPipelineCacheDataSize`、`HasBuggyResetCommandBuffer`、`HasBuggyMSAAResolvePass`、`HasBuggySubpassMerging`、`HasBuggySRGBSwapChain` 等），保留功能但绕开缺陷路径，且免等 SDK 发版就地修复。Impeller 同理（Adreno/PowerVR 按厂商开关）。
4. **渐进式降级（tiered fallback）**：先试最激进（Vulkan）→ 再 GL → 再软渲染/降质（降分辨率、关特效、关 MSAA）。Unity 用 allow/deny/hardware-profile；Chromium 用 fallback 栈（Vulkan→GL→SwiftShader→Compositor）。配**崩溃计数 + 宽限期**的崩溃自愈（3 次短时崩溃才降级，偶发不触发）。
5. **Vulkan 扩展/特性探测（capability probe）**：`vkEnumeratePhysicalDevices` + 查 `VkPhysicalDeviceProperties`/扩展支持，据此决定是否启用该 API/特性。这是"声明式"能力，必须实测枚举才能安全降级。
6. **崩溃自愈（crash self-healing）**：Chromium 崩溃计数 + 宽限期（`GetForgiveMinutes()`）+ 栈空则整进程有意崩溃；本项目已有 `CrashRecoveryEngine`（写前标记 + 崩溃专用 flag + 持久化 init-failure + 安全模式），方向完全正确——**关键是要"精确"而非"一刀切"**（偶发崩溃不应永久降级好设备）。
7. **替代路径：换开源/自研驱动**：UE/Mobile 加载开源 freedreno（高通 Adreno 的 Mesa 替代 Vulkan 驱动）绕开厂商坏驱动（Blurredcode，2025-05）。

### 子题 7：GPU 能力探测 API 与工具（用于设备分级/黑名单评级）

- **Android GPU Inspector (AGI)**：官方 GPU 帧/系统分析器，选目标设备做 workload 剖面与 dump，判断是否值得开 Vulkan、定位驱动/分层问题。S 级 https://developer.android.com/agi 。
- **vulkaninfo**：枚举物理设备/扩展/特性，判断 Vulkan 能力与版本。S 级 https://android.googlesource.com/platform/external/vulkan-tools/+/main/vulkaninfo/vulkaninfo.md 。
- **`adb shell dumpsys gfxinfo`**：帧耗时/GC/渲染统计。`adb shell dumpsys SurfaceFlinger`：合成层/GPU 利用率。
- **`glGetString`/`vkEnumeratePhysicalDevices`**：底层运行时探测 GPU 型号/厂商/API 能力，用于设备分级与黑名单匹配。
- **评级方法**：把"型号 + 驱动/API 版本"映射到白/黑名单（见子题 2/4），配合 AGI/gfxinfo 实测确认是否触发缺陷。
- **Android 系统层**：`android:hardwareAccelerated` 默认 true（minSdk≥14）→ HWUI + RenderThread 用 GPU；设为 false 则走 CPU 软件绘制（Canvas/Skia CPU 后端）。**这是"软渲染兜底"的官方开关，但它是整棵视图树开关，需在 `super.onCreate()` 前按策略设置**才能挡住定制 ROM 的 HWUI 渲染路径。
  - **诚实说明**：① "Chromium 全面禁用 Mali-G57 的 Vulkan"——未检索到官方 commit/文档原文，仅确认"按 GPU+驱动版本选择性禁用 Vulkan"的机制与相关 Mali/Adreno 案例；② "Android<15 定制 ROM（Magic UI/澎湃OS/OriginOS）必回传 SkiaVK/Vulkan HWUI"——未找到官方/头部厂商确凿公开文档，属工程经验向，**建议按"游戏层关 Vulkan 同时显式 `hardwareAccelerated=false` 同步关闭 Activity 级 HWUI"的保守策略处理**（本项目已在做）。

---

## 二、主流厂商/引擎做法对比表

| 厂商/引擎 | 降级链 | 黑名单/白名单策略 | 对非高通国产 SoC/MediaTek 的处理 | 崩溃自愈 | 缺陷规避/工作方式 | 代表性证据 |
|---|---|---|---|---|---|---|
| **米哈游·原神** | Vulkan→GLES | 按 SoC+驱动版本**白名单放行**（天玑9xxx、Exynos、骁龙8系）；**9400+ 排除**；PowerVR 硬黑名单 | 天玑 9000/9200/9300/9400 **默认Vulkan**；9400+ 不支持 | 游戏内设置可见当前 API；5.2→5.8 开-关-开 | 驱动成熟度决定开关 | Sohu 2025-07-31；IT之家 2025-10-23 |
| **米哈游·星铁** | — | **官方 SoC 档位表** + **PowerVR(除D-Series)黑名单** | 天玑 1300(Mali-G77)/720(Mali-G57)、Kirin 9000/810 均列入推荐/支持档 | 低配仍可进但稳定性降 | 明确 SoC 档位+GPU 排除 | 360game 2026-01-04 |
| **腾讯·王者荣耀** | 前向渲染+按能力降级 | 分机型/厂商系统级铺开（×ColorOS） | 按设备能力降级（图形管线替代计算管线） | 2022 三星 Vulkan 版被打回 ES | 前向渲染保覆盖 | GDC2023；IT之家 2025-09-18 |
| **Unity** | Vulkan→GLES3.2/3.1 | **默认按厂商最低规格** + Allow/Deny(7要素+正则) + 逐设备 hardware profile | Mali≥VK1.0.61、PowerVR≥1.1.170+驱动、Adreno 驱动MSB/VK≥0.49 | 无回退 API 则不启动 | **约16项 workaround**（`HasBuggy*`）保 Vulkan | Unity 官方文档 2026 |
| **Unreal** | Vulkan→GLES3.1(ES3.2 API) | Device Profile（Low/Mid/High + GPU 家族 + 机型）| `r.Android.DisableVulkanSupport` 默认=1；`_No_Vulkan` 只对"家族+版本"组合 | 无优雅回退（Vulkan 崩则崩）| device profile CVar + RHI 厂商检测 + shader 分支；Mali GBuffer 128-bit 限制 | Epic 官方 UE5.8；Samsung A Year in a Fortnite |
| **Flutter Impeller** | Vulkan→**OpenGL ES(2.0)** | API29+Vulkan1.1+扩展探测 + `IsValid()` 实测 + **厂商 workaround** | **MediaTek: 禁用 Vulkan（3.29.2 changelog）→回退 GLES**；Adreno<630/640 同上 | 无需开发者介入的自动回退 | workarounds_vk 按厂商关坏特性 | Flutter 官方 doc/源码/3.29.2 changelog |
| **Godot 4** | Vorvkan→Compatibility(GLES3) | 项目级渲染方法/驱动 + 4.4 起 `fallback_to_opengl3`(默认开) | Android RD 驱动只有 Vulkan；老机型直接选 Compatibility | 4.4 前无自动回退（提案#8006） | 渲染器/驱动选择 | Godot 官方 renderers/源码 4.4 |
| **Chromium** | Vulkan→GL→SwiftShader→Compositor | `gpu_driver_bug_list.json`(按GPU+驱动) | Mali/Adreno 按驱动版本禁用/下发 workaround | **崩溃计数+宽限期**（3 次短时崩溃才降级） | 四层栈式降级 | Chromium fallback.md / gpu_driver_bug_list.json |

> **行业共性（决定我们该怎么改的关键）**：①降级链是 **Vulkan → GPU GLES →（软渲染仅最终兜底）**；②没有一家把 CPU 软渲染当低端主路径；③国产/MediaTek 通常是"**按具体 SoC 型号 + 驱动版本放行 Vulkan**"，而不是"厂商一律降级"；④有"运行期能力探测 + 崩溃自愈 + 逐缺陷 workaround"闭环；⑤Android 无法依赖系统级 SwiftShader（系统强制硬件加速），软渲染兜底要自己建（本项目已有）。

---

## 三、明确的推荐方案（兼顾真机性能 vs 崩溃安全）+ 取舍

### 推荐总体思路：把"宽黑名单(只留 Adreno) → 精确降级(默认 Vulkan + 窄 Deny 列表)"反转，并补一个 GPU 中间层；CPU Canvas 降为"灾难兜底"

**1.（首选）在"默认 Vulkan"的框架下做精确设备分级 —— 把 Allowlist 换成 Denylist**
- 目标：让**绝大多数**国产/MediaTek 设备默认走 Vulkan（对齐原神/星铁/Unity/Flutter）。
- 做法：默认 `VULKAN_PREFERRED`；仅有**确凿复现**的 SoC+驱动版本（或经 Bugly 崩溃数据校准后）才入 Deny 列表。参考 Unity 默认最低规格（Mali≥VK1.0.61、PowerVR≥1.1.170+驱动、Adreno 驱动MSB/VK≥1.0.49）作为**分层阈值**而非"整厂商整族"。
- 理由：Genshin 在**天玑 9000~9400 默认开 Vulkan**、星铁把**天玑 1300/720、Kirin 9000/810 列入推荐档**、Unity 给 Mali/PowerVR 设的是**版本阈值**、Flutter 对 MediaTek 也仅是"关 Vulkan → 回退 GPU GLES"。**"非高通一律降级"与行业完全相反。**

**2.（强烈建议，解决结构缺口的根）补一个 GPU OpenGL ES 中间层**
- 现状：本项目只有 Vulkan（C++）+ CPU Canvas 两级，且**关闭了系统硬件加速**（→真·CPU 渲染）。行业是三层：Vulkan → GPU GLES → 软渲染。**缺 GPU GLES 是最大结构性缺口。**
- 做法（二选一，按成本）：
  - a) **新增 GLES 渲染后端**（`RenderBackend` 第三实现），Vulkan 不可用时回退 GLES，仍走 GPU。与现有 `RenderBackendContractTest` / `RenderScalePolicy` / `RenderFrame` 契约对齐即可（双端数学来源已共享）。
  - b) 若短期投资不起 GLES 后端：**保留 Vulkan 默认 + 靠崩溃自愈把"真坏"设备丢到 Canvas**，但**务必保持系统硬件加速 ON**，让 Canvas 走 GPU Skia（EGL），而不是关掉 HWUI 变真 CPU。
- 理由：整个行业（Unity/UE/Chromium/Flutter/Godot）的兜底都是 GPU GLES。**哪怕 MediaTek/Mali 的 Vulkan 真不行，回退到 GPU GLES 也远好于 CPU 逐像素。**

**3.（保留并强化）运行期能力探测 + 崩溃自愈 + 逐缺陷 workaround**
- 用 `vkEnumeratePhysicalDevices` 枚举并校验 **Vulkan 版本 + 必需扩展**（对齐 Impeller 的 `SelectRenderingAPI`：API≥某阈值、Vulkan≥某版本、必需扩展、真建上下文 `IsValid()`）。好设备即使厂商在"可疑名单"，也能实测后留在 Vulkan。
- 崩溃自愈（本项目已有，方向正确）保留：写前标记 + 崩溃专用 flag + 持久化 init-failure + 安全模式。**关键改进：用"崩溃计数 + 宽限期"（Chromium）改为"偶发不降级、短时反复才降级"，避免好设备一次抖动就永久进 CPU 软渲染。**
- 对已知驱动缺陷用"逐 bug workaround"（关坏特性）而非"禁用整条 API"——对齐 Unity `HasBuggy*`、Impeller `workarounds_vk`。

**4.（保留并强化）渐进式降质 + 设备档位分级**
- 把"API 降级"与"质量降级"叠加：Vulkan 保持 GPU 但有缺陷/热控/低端时，先降**渲染分辨率 / 装饰 LOD / 阴影 / 特效 / 锁帧**（本项目已有 render_scale、decor_lod、heat_control_quality、frame_rate 声明、power_save_mode、ADPF），而不是直接掉 CPU。对齐王者/星铁/UE 的"设备档×画质档"分级。
- 用三档设备分级（SAFE/WARNING/PROBLEMATIC→建议新增 GPU 强/中/弱档对应质量档），不要只有"安全/问题"二分。

**5. 优化 CPU Canvas 作为"灾难兜底"，而非"低端主路径"**
- 让它在该用时足够快：渲染分辨率下采样（已有）、脏帧跳过/静止画面跳帧（已有）、视口/建筑剔除（已有）、装饰 LOD（已有）、chunk 烘焙（已有）、尽量减少逐帧 alpha 合成（云层走实例/共享数据）、ASTC 纹理（仅 Vulkan 路径，Canvas 保持 RGBA）。这些要**作为保险**留存，但不要指望它扛起低端主体验。
- 同步：**关闭游戏层 Vulkan 时必须同步关 Activity 级 HWUI 并覆盖所有 Activity 入口**（本项目已在做——这是对的，因为 Android<15 定制 ROM 可能回传 SkiaVK）；但别忘了"系统 HWUI 关掉后 Canvas 变真 CPU"这一点要接受其在兜底场景的代价。

**6.（工程/灰度保障）远程配置 kill switch + 回归数据**
- 用 RemoteConfig 精确控制每类 SoC/驱动是"用 Vulkan / 降 GLES / 降 Canvas"，便于线上快速放量或召回；新增黑名单条目必须附 Bugly 崩溃数据或行业报告引用（本项目规范已有此要求）。参考王者×ColorOS、和平精英"与厂商合作机型"的分机型灰度策略。

### 取舍（Trade-offs）
- **扩白名单 vs 崩溃安全的取舍**：扩白名单/默认 Vulkan ⇒ 更多设备享受 GPU 性能与省电 ⇒ 代价是**可能在极少数真·坏驱动的设备上增加崩溃率**。行业用"能力探测 + workaround + 崩溃自愈 + 远程开关"来兜这个风险，且普遍接受（原神/星铁/Unity/Flutter 都这么做）。**权衡结论：收益（性能/功耗大幅改善，覆盖绝大多数中国厂商设备）显著大于风险（可被上述兜底机制吸收的少数崩溃）。**
- **加 GPU GLES 中间层 vs 只优化 Canvas**：GLES 中间层是结构性最优（对齐行业、把"真坏 Vulkan"的设备救回 GPU），成本是新增一个渲染后端（工作量较大）。若短期不做 GLES，"保持 Vulkan 默认 + 崩溃自愈 + 保持 HW 加速 ON（Canvas 走 GPU Skia）"是次优但可先落地；**绝不要"关闭 HW 加速走真 CPU"作为常规低端路径。**
- **远程开关 vs 纯本地**：纯本地黑名单应变慢、易误伤；远程开关 + 崩溃回归数据能持续校准，但需要埋点与后台。

---

## 四、对本项目的结论（扩白名单 vs 优化 Canvas）

> **结论：以"扩白名单·保住 Vulkan（把宽黑名单改成精确 deny 列表）+ 补 GPU GLES 中间层"为主；优化 CPU Canvas 作为兜底保险、而不是低端主路径。二者是"主+辅"关系，不是二选一。**

理由：
1. **行业全部不是"非高通一律降级"**。Genshin 对天玑 9000~9400 默认开 Vulkan；星铁官方把天玑 1300/720 列入推荐/支持档；Unity 给 Mali 设的是"Vulkan≥1.0.61"版本阈值；Flutter 对 MediaTek 也只是"关 Vulkan→回退 GPU GLES"。**本项目的"MediaTek 一律 PROBLEMATIC → CPU 软渲染"是目前最保守的，与行业相反，也是性能/电量风险的最大来源。**
2. **本项目的游戏是 2D 场景（地砖/装饰/建筑/作物/云层 + UI）**，不是重 3D。Vulkan 是理想路径；GPU GLES 也能流畅；**CPU 逐像素在主流分辨率下才是真正的性能/电量灾难**。所以"保住 GPU（Vulkan 或 GLES）"是核心，CPU Canvas 只该在"真坏 + 应急"时用。
3. **"关闭系统硬件加速"把本可 GPU 加速的 Canvas 变成真 CPU**，放大了风险——即使是兜底，也应尽量保持在 GPU（GLES，或 HW 加速开启的 Skia）上，而非关掉 HWUI。
4. **结论性建议落地顺序（供后续排期）**：
   - P0：把 `detectTier()` 的"MediaTek 一律 PROBLEMATIC""非高通国产一律 PROBLEMATIC"改成"**默认 SAFE/VULKAN_PREFERRED，仅对确凿坏驱动+版本入 Deny**"（对齐 Genshin/Unity 阈值）。
   - P0：`confirm` 一下"是否同步关 HW 加速"——**建议仅对确实有 SkiaVK/Vulkan HWUI 风险的 Android 15+ 问题 ROM 关闭，其余保持 HW 加速**（避免 Canvas 变真 CPU）。
   - P1：新增 GPU GLES 中间层（或至少在 Canvas 上走 GPU Skia），把降级链变成 **Vulkan → GPU GLES → CPU Canvas**。
   - P1：强化"能力探测（Vulkan 版本+扩展）"与"崩溃计数+宽限期"自愈，防止好设备被误降。
   - P1：用 RemoteConfig 做 SoC/驱动级 kill switch + Bugly 崩溃数据校准黑名单。

---

## 五、参考来源清单（≥15 条，S/A 为主，优先 2024/2025/2026）

> 注：B 级用于佐证/计数说明；C 级不计入配额。日期未知的已标注。

**S 级（官方文档/白皮书/源码/顶会）**
1. Unity Manual《Allow or deny Vulkan API usage》 — https://docs.unity3d.com/6/Documentation/Manual/allow-deny-vulkan-usage.html — 页面构建 2026-08-31 — S
2. Unity Manual《Introduction to Vulkan Device Filtering Asset》 — https://docs.unity3d.com/6000.2/Documentation/Manual/introduction-vulkan-device-filtering-asset.html — 2026-02-05 — S
3. Unity Manual《Graphics API hardware profile settings》 — https://docs.unity3d.com/6000.7/Documentation/Manual/vulkan-hardware-profiles-graphics-api.html — 2026-09-02 — S
4. Unity Manual《Workarounds》 — https://docs.unity3d.com/6000.7/Documentation/Manual/vulkan-hardware-profiles-workarounds.html — 2026-09-02 — S
5. Unity Manual《Android Player settings》 — https://docs.unity3d.com/6000.6/Documentation/Manual/class-PlayerSettingsAndroid.html — 2026 — S
6. Unity Manual《Android requirements and compatibility》(Unity 6.0) — https://docs.unity3d.com/cn/current/Manual/android-requirements-and-compatibility.html — 2025-12 — S
7. Epic《Customizing Device Profiles and Scalability in Unreal Engine Projects for Android》(UE5.8) — https://dev.epicgames.com/documentation/en-us/unreal-engine/customizing-device-profiles-and-scalability-in-unreal-engine-projects-for-android — ≈2025（UE5.8 文档）— S
8. Epic《Using the Android Vulkan Mobile Renderer》(UE5.8) — https://dev.epicgames.com/documentation/unreal-engine/using-the-android-vulkan-mobile-renderer-in-unreal-engine — ≈2025 — S
9. Epic《Mobile Rendering Features Reference》(UE5.8) — https://dev.epicgames.com/documentation/unreal-engine/rendering-features-reference — ≈2025 — S
10. Epic《Using Lumen Global Illumination on Mobile》(UE5.8) — https://dev.epicgames.com/documentation/unreal-engine/using-lumen-global-illumination-on-mobile-in-unreal-engine — ≈2025 — S
11. Flutter《Impeller rendering engine》 — https://docs.flutter.dev/perf/impeller — 当前/日期未知 — S
12. Flutter Engine《Android》(impeller/docs/android.md) — https://raw.githubusercontent.com/flutter/engine/main/impeller/docs/android.md — 当前 — S
13. Flutter Engine 源码 `flutter_main.cc`(SelectRenderingAPI) — https://github.com/flutter/engine/blob/main/shell/platform/android/flutter_main.cc — 当前 — S
14. Flutter Impeller 源码 `workarounds_vk.cc/.h` — https://api.flutter.dev/impeller/workarounds__vk_8cc_source.html — 当前 — S
15. Flutter 3.29.2 CHANGELOG《[CP] Disable Vulkan on MediaTek SoC》 — https://chromium.journaldev.googlesource.com/external/github.com/flutter/flutter/+log/refs/tags/3.29.2/CHANGELOG.md — ≈2025 — S/A
16. Godot《Renderers》(4.4) — https://docs.godotengine.org/en/stable/tutorials/rendering/renderers.html — 4.4 — S
17. Godot 源码 `main/main.cpp`(`fallback_to_opengl3` 等) — https://github.com/godotengine/godot/blob/master/main/main.cpp — 当前 — S
18. Chromium《GPU Process Fallback》(4 层栈式降级/崩溃计数) — https://github.com/chromium/chromium/blob/main/content/browser/gpu/fallback.md — 当前/日期未知 — S
19. Chromium《Using Chromium with SwiftShader》(纯 CPU 定位/WebGL 兜底) — https://github.com/chromium/chromium/blob/main/docs/gpu/swiftshader.md — 当前/日期未知 — S
20. Chromium `gpu_driver_bug_list.json`(按 GPU/驱动禁用 Vulkan) — https://chromium.googlesource.com/chromium/src/+/main/gpu/config/gpu_driver_bug_list.json — 日期未知 — S
21. Android Developers《Hardware acceleration》 — https://developer.android.com/guide/topics/graphics/hardware-accel — 当前 — S
22. Android Developers《Android GPU Inspector (AGI)》 — https://developer.android.com/agi — 当前 — S
23. Android Developers《Use Vulkan for graphics》 — https://developer.android.com/games/develop/vulkan/overview — 日期未知 — S
24. 米哈游官方（经 360/游民星空转载）《崩坏：星穹铁道 硬件性能要求提高》(含 SoC 档位 + PowerVR 黑名单) — http://360game.360.cn/article/content?id=695a4c4a01d33943481e6e20 — 2026-01-04 — S
25. Vulkan Tools `vulkaninfo` — https://android.googlesource.com/platform/external/vulkan-tools/+/main/vulkaninfo/vulkaninfo.md — 当前 — S

**A 级（头部厂商技术博客/顶会/官方复盘）**
26. Epic《Fortnite on Android Launch Technical Blog》(Vulkan 仅 S9+/Note9 快 20%) — https://devtrackers.gg/fortnite/p/51359e66-fortnite-on-android-launch-technical-blog — 2018-09 — A
27. Samsung GameDev《A Year in a Fortnite》(RHI 级 workaround/Pipeline LRU) — https://developer.samsung.com/galaxy-gamedev/gamedev-blog/fortnite.html — ≈2019 — A
28. 《原神 5.8 图形 API 更新 高端机型默认开启 Vulkan》(官方信息转载) — https://m.sohu.com/a/919400887_115088/ — 2025-07-31 — A/B
29. IT之家《能否解决〈原神〉几乎无法游玩问题？谷歌确认为 Pixel 10 GPU 驱动更新》(原神停止支持 PowerVR) — https://m.ithome.com/html/891837.htm — 2025-10-23 — A/B
30. GDC 2023 腾讯 Xiaoxin Guo《移动平台的高性能渲染实用技巧》 — http://www.gamelook.com.cn/2023/05/517055/ — 2023-05-06 — A
31. IT之家《OPPO ColorOS 系统适配〈王者荣耀〉Vulkan 引擎》 — https://www.ithome.com/0/883/819.htm — 2025-09-18 — A/B
32. Blurredcode《UE | Mobile Load 开源 freedreno Vulkan 驱动》 — https://www.blurredcode.com/2025/05/af26cfac/ — 2025-05 — A/B
33. flutter/flutter issue #160041《Disable Impeller on Adreno < 630/640》(PR #57100) — https://github.com/flutter/flutter/issues/160041 — 2024-12 — A

**B 级（高质量社区/官方 issue，佐证/背景）**
34. UWA《Vulkan API 的性能及兼容性》(王者荣耀三星 Vulkan 回退) — https://blog.uwa4d.com/archives/TechSharing_314.html — 2022-11-22 — B
35. UWA《卡顿和发热…可能是分档配错了画质》(设备分档/画质匹配方法论) — https://blog.uwa4d.com/archives/UWA_GPM2_46.html — 2026-08-21 — B
36. Godot 提案 #8006(Vulkan 不可用→OpenGL/Compatibility) — https://github.com/godotengine/godot-proposals/issues/8006 — 日期未知 — B
37. Godot issue #111729(gl_compatibility→mobile 设备集缩小) — https://github.com/godotengine/godot/issues/111729 — 2025 — B
38. flutter/flutter issue #190195(Mali SIGABRT / EnableImpeller=false 弃用) — https://github.com/flutter/flutter/issues/190195 — 2025 — B
39. flutter/flutter issue #186067(MT8788/Mali-G57 gralloc4 拒格式) — https://github.com/flutter/flutter/issues/186067 — 2025 — B

> **说明**：以上 S/A 级合计 33 条，满足"≥15 条、以 S/A 为主、日期偏 2024/2025/2026"要求。日期未知的（Unity/UE/Chromium/Godot 持续更新的官方文档、Flutter doc）已标注"当前/日期未知"，非编造。

---

## 六、诚实声明（未见确凿公开资料项）

1. **米哈游/腾讯逐机型完整 Vulkan 黑/白名单表**：均未公开。能确认的是游戏内设置（原神 5.8）、官方设备性能公告（星铁 4.0）、厂商系统适配名单（王者×ColorOS）、引擎通用机制。凡缺官方文档处均标注"推断"。
2. **"Chromium 全面禁用 Mali-G57 的 Vulkan"**：未检索到官方 commit/文档原文；仅确认"按 GPU+驱动版本选择性禁用 Vulkan"的机制与相关 Mali/Adreno 案例。**本项目规范里"对齐 Chromium（Mali-G57 全面禁用 Vulkan）"这条依据需打折扣。**
3. **"Android<15 定制 ROM（Magic UI/澎湃OS/OriginOS）必回传 SkiaVK/Vulkan HWUI"**：未找到官方/头部厂商确凿公开文档，属工程经验向保守策略。**"关游戏层 Vulkan 需同步关 Activity 级 HWUI"是对的方向，但"必回传 SkiaVK"这一因果目前无权威来源。**
4. **Minecraft×UE 的移动端 API 选择**：不存在（Minecraft 非 UE 开发），未编造。
5. **UE 源码中的 `drivers/Android/` vendor workaround 目录**：未找到，UE 的规避实际分散在 device profile CVar + VulkanRHI 厂商检测 + shader 平台定义三处。
6. **AndroBench 用于 GPU 能力探测**：无确凿公开资料（其本为存储基准）。

---

## 七、与本项目后续落地相关的参考（项目自身文档）

- `android/app/src/main/java/com/xianxia/sect/core/VulkanPolicy.kt` — 当前宽黑名单（只留 Adreno）+ MediaTek/非高通国产一律 PROBLEMATIC + API<31 非白名单 → SOFTWARE_ONLY。
- `android/docs/vulkan-crash-defense-design.md` — 六层防御体系 v2（含行业对标表：Unity Device Filtering / Chromium 4 层降级 / Flutter Impeller MTK 回退 / Godot gl_compatibility / 原神 GPU 白名单），可对照本节"推荐方案"逐条校准。
- `android/docs/renderer-feature-checklist.md` — 双后端（Vulkan/Canvas）特性契约；新增 GPU GLES 中间层时需同步加入此清单与 `RenderBackendContractTest`、`SoftwareCanvasBackendTest`。
