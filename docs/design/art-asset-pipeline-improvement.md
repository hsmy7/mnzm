# 美术资产管线改进方案（source ↔ drawable 可追溯性 + 运行时纹理质量）

> 决策分级：**架构级**（基建管线 + 守卫测试 + 规则文档 + 渲染质量，触达未来 6 个月资产增长与跨平台）。
> 提供"最小切入路径"备选：先做 A，B 视资源分期。
> 生成日期：2026-09-02。对标对象：Godot 导入管线（.import sidecar + hash 增量）、Android 官方纹理压缩定位、Unity Sprite Atlas（mipmap/padding）。

---

## 一、背景与目标

### 1.1 现状核查（实勘证据）

| # | 事实 | 依据 |
|---|------|------|
| 1 | 源素材权威源 `D:\模拟宗门美术素材` 是**高分辨率**的：物品约 768px 高、丹药/装备 1600~2100px、建筑 768×768、UI 到 3585×3003、弟子立绘 895×1202 | sharp 实测 354 个源 PNG |
| 2 | 运行时 `drawable-nodpi` WebP：**小物件主动烘焙到 ~480px**（如 `pill_bao` 1592×1440→480×434，~0.30x），**大图（建筑/立绘/UI）保留源尺寸**（768×768、895×1202、1536×326 原样） | sharp 实测 615 个 drawable WebP + 源对比 |
| 3 | 仓库两个转换脚本**都不降采样**：`convert-remaining-pngs-to-webp.mjs` 只做格式转换（尺寸不变）；`import-art-assets.mjs` 只处理天枢殿(600×400)+5 云层(原生尺寸) | 读脚本 |
| 4 | `import-art-assets.mjs` 的 IMPORT 表**仅 6 条**（天枢殿 + 5 云层）。其余约 600 个精灵的 source→drawable **无任何自动化映射** | 读脚本 |
| 5 | 规则文档 `rules/static-resources.md` 宣称"登记 IMPORT 表即可重导"，**与事实不符**（IMPORT 仅 6 条） | 读规则 |
| 6 | 地图图集 2048×2048 ASTC 4×4 KTX1，**单 mip**（`numberOfMipmapLevels=1`）；Vulkan sampler `minFilter/magFilter=LINEAR`、`anisotropyEnable=VK_FALSE`、`maxLod=1.0` | `build-atlas.mjs:wrapKtx1`、`VulkanBackend.cpp:1784` |
| 7 | 通用精灵 Compose `ImageBitmap` 运行时解码为 RGBA（未 GPU 压缩），一张 895×1202 立绘≈4MB、2048 级≈16MB | 读 `SectAtlasAssembler` / Compose 图像路径 |

### 1.2 需求要点

1. **让权威源 → 运行时 drawable 的映射自动化、可重导、可校验**（解决 4/5 的脱节）。
2. **把"小物件烘焙到 ~480px / 大图保留源尺寸"这条隐性规则显式化、可配置**（解决 3 的不可追踪）。
3. **修正规则文档与实现不符**（解决 5）。
4. **运行时纹理质量优化**（解决 6/7）：地图图集补 mipmap + 各向异性；通用精灵压缩评估（受 Compose 约束，诚实说明）。

### 1.3 成功标准

- 任意源素材改动 → 一条命令重导 → 守卫测试自动校验源↔产物一致，全部通过。
- 规则文档与实现一致（`source-mapping.json` 为权威映射源，覆盖全部 drawable，ic_launcher 除外）。
- 地图图集带 mipmap + 各向异性，缩放/远景无采样混叠。
- 明确 Compose 下通用精灵压缩的可行边界与替代（不强行违反 Compose 约束）。

---

## 二、技术方案

### 2.1 A. 资产管线补全（source ↔ drawable 自动化）

**A.1 建立权威映射源 `scripts/source-mapping.json`（新，入库）**

按 `SpriteCategory` 分组，每条：
```json
{
  "bakeDefaults": {
    "PILL":       { "maxDim": 480 },
    "MATERIAL":   { "maxDim": 480 },
    "EQUIPMENT":  { "maxDim": 480 },
    "SEED":       { "maxDim": 480 },
    "STORAGE_BAG":{ "maxDim": 480 },
    "PORTRAIT":   { "preserve": true },
    "BUILDING":   { "preserve": true },
    "UI":         { "preserve": true },
    "MAP":        { "preserve": true }
  },
  "categories": [
    {
      "category": "PILL",
      "entries": [
        { "drawable": "pill_bao", "source": "丹药/凡品丹药.png", "modules": ["feature/game","app"], "bake": { "maxDim": 480 } },
        { "drawable": "pill_di",  "source": "丹药/地品丹药.png", "modules": ["feature/game","app"], "bake": { "maxDim": 480 } }
      ]
    }
  ]
}
```

- `bakeDefaults`：每类默认烘焙规则；条目可 `bake` 覆盖。
- `maxDim`：等比缩放到最长边 = 该值；`preserve: true`：保留源尺寸（与现状一致）。
- **不改变现有实际尺寸**：默认值复现"当前 480px / 大图保留"的现状。

**A.2 重导脚本 `scripts/import-art-assets.mjs`（改造）**

- 读 `source-mapping.json`，对每条：读源 PNG → 按 bake 规则处理（`preserve` 或 `maxDim` 等比缩放，`sharp.kernel.lanczos3`）→ 无损 WebP（`lossless:true, effort:6`）→ 写两模块。
- **内容 hash 增量**：per-drawable 记录源 SHA-256 + bake 参数；未变则跳过（复用 `build-atlas.mjs` 的 `contentHash` 模式）。`generatedAt` 剥离（复用 `resource-manifest` 教训，防增量失效）。
- **fail-fast**：映射指向不存在的源、或 source 缺失 → 直接抛错退出非零（对标 `build-atlas.mjs` 边界#4，禁止静默产出缺素材 WebP）。
- **dry-run 模式**（`--dry-run`）：不打文件，仅输出"将变更 / 将跳过 / 将失败"清单，供人工审核。
- 输出产物清单 `scripts/sources-imported.json`（drawable → source + bake 后尺寸 + MD5），供校验守卫消费。

**A.3 烘焙规则显式化**

副作用：把"小物件 480px"从"不可见的美术习惯"变成 `source-mapping.json` 里**每类默认、可配置、可复现**的规则。后续要调小物件清晰度（如升 768px），只改 `bakeDefaults` + 重导 + 更新守卫期望值。

**A.4 校验守卫测试（新，挂 Gradle，均不依赖 D:\）**

- `SpriteSourceMappingCoverageTest`：枚举 `source-mapping.json` 全部 category/entry + `drawable-nodpi` 全部 webp（除 `ic_launcher`），双向覆盖断言（每个 drawable 有映射；每个映射 source 存在且唯一）。缺失时错误消息给"缺哪个、去 source-mapping.json 补哪行"。枚举驱动 + `intentionallyExcluded` 显式排除（`ic_launcher`）+ 操作指引，符合 CLAUDE.md 9.5 守卫测试三要素。
- `SpriteBakeRulePolicyTest`：走 `bakeDefaults` 校准——PILL/MATERIAL/... 类目 drawable 最长边 ≤ 480（或条目覆盖值），PORTRAIT/BUILDING/UI 等 preserve 类目 drawable 尺寸 = 源尺寸（源尺寸经 mapping 读取，已在 mapping 内，无需 D:\）。
- `SpriteSourceMappingFormatTest`：mapping 结构/版本/唯一性/模块合法性校验（防手改破坏）。

**A.5 规则文档修正 `rules/static-resources.md`**

- 第 1 节"素材源目录"：改为"映射由 `scripts/source-mapping.json` 权威维护，覆盖全部 drawable（ic_launcher 除外）；新增素材 = 源目录放图 + mapping 加一行 + 重导 + 守卫通过"。
- 明示仓库内 WebP 是**映射产物**，PNG 源不入库；删除"IMPORT 表仅几条"的误导表述。
- 合并 A.4 守卫到 "自动化范围说明" 章节。

### 2.2 B. 运行时纹理质量优化

**B.1 地图图集补 mipmap + 各向异性（前端受益明确，可行）**

- `build-atlas.mjs`：`wrapKtx1` 支持多 mip（`numberOfMipmapLevels=N`）；`compressAstc` 走 astcenc `-m` 生成 mip 链。
- `app/src/main/cpp/KtxLoader.cpp/h`：解析多 mip 数据段，创建完整 mip 视图（`vkCreateImageView` 多层 + `subresourceRange.levelCount`）。
- `app/src/main/cpp/VulkanBackend.cpp`：`uploadCompressedTexture` 创建 mip 图像/视图；sampler 设 `minFilter=VK_FILTER_LINEAR_MIPMAP_LINEAR`、`mipmapMode=VK_SAMPLER_MIPMAP_MODE_LINEAR`、`minLod=0`、`maxLod=N`；`features.samplerAnisotropy=VK_TRUE` + `anisotropyEnable=VK_TRUE` + `maxAnisotropy`（如 4.0f）。白纹理 sampler 保留 LINEAR（单 mip）。
- Canvas 软渲染路径：`SectAtlasAssembler` 位图无真 mip，用 `isFilterBitmap=true`（双线性）缓解远景闪烁；如反馈仍明显，评估 `Bitmap` 手动 mip 或提升 atlas 精度（登记为债项）。
- 效果：缩放/远景采样混叠消除；代价：图集构建时间 + ASTC mip 约 1.33x 体积。

**B.2 通用精灵 GPU 压缩 —— 诚实评估（受 Compose 约束）**

- **约束**：Compose `ImageBitmap` 由 framework 上传为 RGBA，无成熟"ASTC ImageBitmap"路径；要 GPU 压缩需走 native/SurfaceView，与 Compose UI 架构矛盾。
- **可行替代（分档，推荐轻量项）**：
  - (a) **运行时解码上限**：大图（背景/立绘）`BitmapFactory.Options.inSampleSize` 上限（如最长边 ≤1536/2048），配合缓存 LRU 上限——降低峰值内存，低端设备受益。权衡：高 DPI 大图降采样损失锐度。
  - (b) **地图/低 UI 走 ASTC**：已是现状（地图 ASTC 4×4），保持。
  - (c) 若未来出现 UI 内存/带宽瓶颈或规模化 UI 渲染，评估把大背景转 native 渲染（`KtxLoader` + SurfaceView）——登记为债项，不纳入本期。
- 结论：本期 B.2 只做 (a)；(c) 借债登记。

---

## 三、影响范围清单

| 文件 | 变更类型 | 变更说明 |
|------|---------|---------|
| `scripts/source-mapping.json` | 新增 | 权威 source↔drawable 映射 + bakeDefaults（覆盖全部 drawable，ic_launcher 除外） |
| `scripts/import-art-assets.mjs` | 重构 | 读 mapping、bake 规则、内容 hash 增量、fail-fast、dry-run、输出 sources-imported.json |
| `android/app/src/test/java/.../SpriteSourceMappingCoverageTest.kt` | 新增 | 映射覆盖双向守卫（枚举驱动） |
| `android/app/src/test/java/.../SpriteBakeRulePolicyTest.kt` | 新增 | 烘焙规则与现状一致守卫 |
| `android/app/src/test/java/.../SpriteSourceMappingFormatTest.kt` | 新增 | mapping 结构/唯一性/模块合法性守卫 |
| `scripts/build-atlas.mjs` | 修改 | KTX 多 mip（B.1）+ astcenc `-m` |
| `android/app/src/main/cpp/KtxLoader.cpp/h` | 修改 | 解析多 mip 视图 |
| `android/app/src/main/cpp/VulkanBackend.cpp` | 修改 | mip sampler + 各向异性 feature/anisotropy |
| `rules/static-resources.md` | 修改 | 修正 IMPORT 仅 6 条；权威映射源 = source-mapping.json；新素材流程 |
| `docs/architecture.md` | 修改 | 待办登记表登记债项（B.2-c、Canvas mip 缓解） |
| `docs/knowledge-base.md` | 修改 | 扩展性现状盘点补充"资产管线映射" |

**经济影响（`经济` 标签）**：本方案涉及物品/丹药/材料等资源**图片**，但**不改变任何货币/数值/发放逻辑** → 无经济影响（显式声明）。

**iOS 影响（`iOS` 标签）**：管线脚本 + C++ 采样逻辑均跨平台；mipmap/各向异性为 Vulkan 后端，iOS Metal 对等实现 = 标准 `MTLSamplerDescriptor` `mipFilter`/`magFilter`/`minFilter` + `maxAnisotropy`，无平台阻碍。硬编码 `D:\` 路径仅在导入脚本（开发机/构建机），不影响 iOS 产物。

---

## 四、兼容性分析

- **无存档/Entity/序列化变更** → 无 DB Migration。
- **A 段若 bake 默认复现当前尺寸**：`pill_bao` 等仍为 480px、大图仍保留 → **产物 MD5 不变**，atlas-manifest 的 UID/MD5、build-atlas codegen 布局 hash 均不受影响（布局未变）。
- 若调整 `bakeDefaults`（如小物件升 768px）：重导后 drawable MD5 变化 → **需重生成 `atlas_astc.ktx` + `atlas-manifest.json`**（map 槽位若引用小物件则同步），并更新守卫期望值 + `SpriteCodegenSyncTest`/`ResourceManifest*Test`。此为受控、可预测的连锁，非破坏性。
- **B.1 改变 KTX 产物结构（单 mip → 多 mip）**：`AtlasManifestSyncTest` 中"ktx 文件尺寸/头"期望值需更新；加载端 `KtxLoader` 兼容多 mip 后，旧单 mip 仅需按 1 层处理（向前兼容）。

---

## 五、测试方案

| 测试 | 类型 | 墙钟成本 | 环境依赖 |
|------|------|---------|---------|
| `SpriteSourceMappingCoverageTest` | 守卫（枚举驱动） | 文件/JSON 枚举，<1s | 无（mapping + drawable 均在仓库） |
| `SpriteBakeRulePolicyTest` | 守卫 | 读取 drawable 尺寸 + mapping，<1s | 无 |
| `SpriteSourceMappingFormatTest` | 守卫 | JSON 校验，<1s | 无 |
| `AtlasManifestSyncTest`（B.1 更新期望值） | 集成 | 读 KTX 头/MD5，<1s | 无（产物由 Gradle 生成） |
| `SpriteCodegenSyncTest` 等既有 codegen 守卫 | 回归 | 维持 | 无 |

**环境依赖**：源↔产物**内容一致的强校验**（用源 PNG 现场重导比对 MD5）依赖本机 `D:\`，按 design-plan-review 五环境依赖：挂 Gradle 任务依赖，CI 无源路径时 **skip + 显式标记**（非阻塞）；本地/构建机有源则全量校验。

**对抗性审查要点**：
- 映射遗漏（drawable 无映射 / 源无对应 drawable）→ 覆盖守卫拦截。
- 改源未重导 → 内容一致守卫（有源时）拦截；无源时靠 mapping MD5 + update 流程。
- bake 规则漂移（新素材未按默认 480 烘焙）→ 烘焙策略守卫拦截。
- 双模块不一致 → 复用现有 `resource-manifest` 同名同 MD5 冲突检查。

---

## 六、风险评估与兜底

| 风险 | 缓解 |
|------|------|
| `D:\` 源不可达（CI/他机） | 导入/校验守卫 skip + 环境标记；导入脚本报明确错误；mapping 后填不阻塞 CI |
| mapping 后填工作量（~600 条） | 脚本生成骨架（从 drawable 名 + SpriteCategory 生成空映射），分阶段补齐；守卫先对已映射子集开放，最终闭环 |
| 重导意外改变尺寸 | bake 默认 preserve + `--dry-run` diff 报告；审核后再落盘 |
| mip 后 KTX 体积/构建时间上升 | ASTC mip ≈1.33x；只对地图图集做，build 增量 + 守卫同步；本地可 `--no-mip` 兜底 |
| Canvas 软渲染无真 mip | `isFilterBitmap=true` 缓解 + 登记债项；Vulkan 路径不受影响 |
| Compose 大图内存 | B.2(a) `inSampleSize` 上限 + LRU 缓存，可配置开关（低端设备默认开） |

---

## 七、未来场景推演（≥6 个月档）

| 维度 | 推演 |
|------|------|
| 规模增长 | 内容 ×10：新增 = mapping 加一行 + 重导，线性；UID/MD5/布局 hash 稳定（复用 resource-manifest 稳定引用）。mapping 全覆盖后，新增精灵无"漏登记"风险 |
| 生命周期 | hash 增量 + `generatedAt` 剥离 → 构建/重启/clean 重建行为一致（复用 resource-manifest 教训）；守卫锁"源与产物一致" |
| 平台扩张 | Mipmap/各向异性为跨平台采样；iOS Metal 对等（mipFilter/maxAnisotropy）标准支持，无重做。管线为 Node + 双端产物，iOS 无平台路径 |
| 运营演进 | 改素材 = 改源 + 重导（无需发版），前提走管线——mapping 闭环后成立。需发版场景仅剩材质/数值（另行受控） |
| 兼容回退 | 产物有 hash 增量 + dry-run；映射文件 git 可回滚；B.1 有 `--no-mip` 兜底；A 段不改变现有尺寸可随时回退 |

---

## 八、技术债与偿还计划

| 债项 | 产生原因（为何现在不全做） | 偿还触发条件 |
|------|--------------------------|-------------|
| (a) 通用精灵未 GPU 压缩（Compose 限制） | Compose ImageBitmap 无 ASTC 路径；走 native 渲染与 UI 架构矛盾 | 出现 UI 内存/带宽瓶颈（Bugly 内存告警、大 UI 卡顿反馈）或 UI 迁移 native 渲染时 |
| (b) Canvas 软渲染无真 mip | 软渲染位图不走 GPU mip；本期仅双线性缓解 | Vulkan 已带 mip 后，软渲染设备仍反馈缩放闪烁时（提升 atlas 精度或手动 mip） |
| (c) 大背景转 native 渲染（B.2-c） | 规模化 UI 渲染需求未到；代价大 | 出现大规模 UI / 性能瓶颈时评估 |
| (d) B.1 图集 mipmap | **已清偿**（2026-09：`build-atlas.mjs` 多 mip KTX + KtxLoader/VulkanBackend 落地；`--no-mip` 兜底） | — |
| (e) B.1 各向异性 | **已清偿**（2026-09：`samplerAnisotropy` 特性守卫 + `setTextureQuality` 运行时开关落地；正交投影下增益有限已如实评估） | — |
| (f) 待补 110 条映射（UI 拉丁名↔中文源 / 立绘动态 / 妖兽等） | **已清偿**（2026-09：scaffold `deriveSource` 分类规则 + `MANUAL_OVERRIDES` 补齐 107 条；2 条（`heavenly_trial_map`/`ui_sysmsg`）经核查无 UI 引用而移除；1 条（`disciple_portrait`）为在用弟子通用头像但无独立源图，保留待补） | — |

> 规则：以上为显式登记债项，均有可判断触发条件；本方案**无"现在不做、无触发"的隐藏债**。债项同步登记到 `docs/architecture.md` 待办登记表。

---

## 九、方案自检结论（design-plan-review）

- [x] 未来场景推演小节已写（≥6 个月档）
- [x] 技术债与偿还计划小节已写（三列表 + 触发条件）
- [x] 每个新抽象有当前生产消费者：`source-mapping.json`（导入脚本消费）、守卫测试（CI 消费）；无无消费者抽象
- [x] 新测试墙钟成本已核算（均 <1s，无 >30s 项；一致性强校验按环境依赖 skip）
- [x] rules/ 交叉核对：触碰 `rules/static-resources.md`（修正）、`rules/design-plan-review.md`（本表）；无经济/商业化/社交/数据库冲突
- [x] 决策分级已声明（架构级 + 最小切入路径）
- [x] 影响范围清单含经济/ iOS 标签项

---

## 十、参考来源

| 来源 | 类型 | 等级 | 核心 |
|------|------|------|------|
| Godot 导入管线（.import sidecar + hash 增量） | 官方 | S | 源→资产自动导入 + 内容 hash 增量（本项目已对标） |
| Android Texture Compression targeting（ASTC/ETC2 设备定位） | 官方 | S | 移动端纹理压缩定位实践 |
| Unity Sprite Atlas（mipmap / paddingPower） | 官方 | S | 图集 mipmap + 相邻 padding 防 UV 渗色 |
| Compose ImageBitmap vs ImageVector | 官方 | A | Compose 图像为显式 RGBA/非压缩向量，确认 B.2 约束 |
| 项目自有 `docs/research/tile-map-industry-benchmark.md`（27+ 来源：Unity/Godot/Arm/GDC） | 内部 | S | 图集/压缩/LOD 对标基线 |

> 说明：本方案为**基建管线 + 渲染质量**类，非玩法/商业化/社交/数据类功能，故按 design-plan-review"决策分级"走架构级全流程，但参考来源聚焦管线/压缩/采样（已列 S 级官方）。玩法类所需的 20 条硬配额不适用于本类。

---

## 附：决策确认（2026-09-02，用户确认"3 决策点都做"）

| 决策点 | 结论 | 落地 |
|-------|------|------|
| #1 素材分辨率 | **用户 2026-09-02 定：全部提升到最高分辨率**——所有素材按源分辨率烘焙（不再降采样回 480px），地图图集槽位提升（瓦片 64→128、建筑 256→512、天枢殿 512→1024） | A 段 + `bakeDefaults`（改 `preserve`/高基准）+ 安全护栏 |
| #2 mapping 后填 ~600 条 | 先建机制 + 脚本生成骨架 + 分阶段补齐 + 守卫先对已映射子集开放 | A 段 + 3 守卫 |
| #3 B.2 大图内存优化 | `inSampleSize` 上限 + LRU 缓存，可配置开关（低端默认开） | B.2(a) |

**安全护栏（"全部提升"配套，防误伤低端机/包体超限）：**
1. ASTC 压缩地图图集（4096 图集 ASTC ≈16MB，可控）；
2. 显存 / 包体 / 峰值位图预算上限 + CI 告警；
3. 回退降采样：非 ASTC / Canvas 软渲染路径设分辨率上限（`inSampleSize`）；
4. 单素材上限保护（最长边 >1536 时钳制），避免个别高分辨率素材把小物件撑到异常。

> 相关：新增"自选清晰度（五档）"功能见 `docs/design/graphics-clarity-settings.md`，其 mipmap/各向异性开关依赖本方案 B.1（KTX 多 mip）前置。

---

## 附：实施进展（2026-09-02）

| 阶段 | 状态 | 说明 |
|------|------|------|
| A 段（映射自动化 + 3 守卫） | ✅ 已落地 | `source-mapping.json`（176 映射/110 待补）+ `import-art-assets.mjs`（bake/hash/dry-run/fail-fast）+ `SpriteSourceMappingGuardTest` 通过 |
| C1（地图图集槽位提升） | ✅ 已落地 | 图集 2048→**4096**；瓦片 64→**128**、建筑 256→**512**、天枢殿 512→**1024**；KTX 重建；守卫同步；**Canvas 软渲染图集封顶 2048**（防 4096 建 64MB 位图 OOM）；天枢殿 drawable 已重烘焙到源分辨率 1405×1091 |
| D1（自选清晰度） | ✅ 已落地 | `ClarityMode` + 设置 UI（性能下方，默认中）+ 引擎/FPS/持久化 + `RenderScalePolicy` 叠加；测试通过 |
| B.1（图集 mipmap + 各向异性） | ✅ 已落地 | `build-atlas.mjs` **多 mip KTX**（4096→4，11 级；astcenc 5.7 无 `-m`，改为 sharp 逐级下采样 + 逐级 astcenc 压缩再封装）+ `KtxLoader` 多 mip 解析 + `VulkanBackend` mip 图像/视图 + 三线性 mip sampler + 各向异性（`samplerAnisotropy` 特性守卫，不支持时回退关闭）+ `setTextureQuality(anisotropy, mipmap)` 运行时采样器开关（自选清晰度联动）；`NativeSurfaceView`/`MainGameScreen` 接入 `ClarityMode.anisotropy/mipmap`。Canvas 软渲染主 Paint 已 `isFilterBitmap=true`（双线性）。`AtlasManifestSyncTest` 多 mip 结构校验同步。`--no-mip` 兜底保留 |
| B1（全部素材重烘焙到高分辨率） | ✅ 已落地（小物件全覆盖；大图本就保留源尺寸） | `import-art-assets.mjs` 非 dry-run 执行：**小物件分类（丹药/材料/装备/种子/储物袋/功法/草药）全部 480→1024**；大图（立绘/背景/UI/妖兽/建筑/云层）本就保留源尺寸。天枢殿 1405×1091。**包体：drawable WebP ~123MB→~166MB（+43MB，来自小物件 1024）**。**110 条待补中 107 条补齐 source**（BEAST/SECT_ICON/SPIRIT_STONE/growing_*/UI/CAVE/HEAVENLY_TRIAL/BACKGROUND/PORTRAIT/EQUIPMENT 特例，经 scaffold `deriveSource` 分类规则 + `MANUAL_OVERRIDES`；详见第八节 (f)）；2 条（`heavenly_trial_map`/`ui_sysmsg`）无 UI 引用而移除；剩 1 条（`disciple_portrait`）在用但无独立源图、保留待补 |
