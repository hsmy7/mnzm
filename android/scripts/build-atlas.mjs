/**
 * 图集 KTX 生成管线（Vulkan ASTC 压缩）+ 资源 codegen（对标 Godot 导入管线）。
 *
 * 输入（唯一权威源，脚本只读不写）：
 * - 本文件 LAYOUT 常量 — 图集布局（rect/名称/枚举/占地，从原 SpriteAtlasDef.kt 提取）
 * - scripts/resource-registry.json — 精灵注册源数据（分类 → name/res 映射）
 * - scripts/resource-manifest.mjs 生成的 atlas-manifest.json — 资源文件清单（MD5/UID）
 * - feature/game 与 app 的 drawable-nodpi/*.webp — 精灵资源
 *
 * 输出：
 * - core/engine/build/generated/sprite/.../SpriteAtlasDef.kt — Kotlin 图集布局常量（--atlas-def-only）
 * - app/build/generated/sprite/SpriteRegistryData.kt — 精灵注册数据（--codegen）
 * - app/build/generated/sprite/TextureAtlas.h — C++ MAP_SPRITES（--codegen）
 * - app/src/main/cpp/scene/scene_uv_tables.h — C++ 场景 UV 常量表 + 占地表 +
 *   与 Kotlin 同源的渲染常量（--codegen；R3.2/B10：SceneStore 路径 Kotlin 不再
 *   每帧传 SpriteAtlasDef 数组，UV 表同源生成进 C++；与 shaders.h 同属"仓库内
 *   生成物"——桌面 GTest 与 Android 构建无需先跑 codegen 即可编译）
 * - app/src/main/assets/atlas/atlas_astc.ktx — KTX1 封装 ASTC 4×4 LDR 图集（无参数模式）
 * - app/src/main/assets/atlas/atlas-manifest.json — 图集布局清单 + 布局哈希（无参数模式）
 *
 * hash 增量：生成物首次生成后记录内容 hash（非 mtime），源数据/manifest 未变时跳过重生成。
 *
 * ## 预乘 alpha 约定（图集管线不变量）
 * 素材透明区常带非零 RGB（导出工具残留的白色）。直通 alpha 做重采样时这些"看不见的颜色"
 * 会按普通 RGB 混入邻近可见像素，在硬 alpha 边（岛边缘羽化边等）产生灰白毛边。
 * 因此：**槽位内容 → pad 环 → 各级 mip 全部在预乘空间处理**（重采样数学正确），
 * 仅在写盘前 `unpremultiply()` 一次解回直通 alpha（与运行期 SRC_ALPHA 混合一致）。
 * 修改本文件的任何重采样步骤时必须保持该不变量（验收脚本 verify-mip-bleeding.mjs 会暴露回归）。
 *
 * 依赖：Node + sharp（scripts/node_modules）、astcenc（scripts/tools/astcenc/bin/）。
 * 运行：node scripts/build-atlas.mjs [--atlas-def-only | --codegen]
 * astcenc 缺失时给出明确错误并退出非零（gradle task 据此直接构建失败，不允许静默跳过）。
 */
import sharp from 'sharp';
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { execFileSync } from 'child_process';
import { fileURLToPath } from 'url';
import { ensureManifest } from './resource-manifest.mjs';
import { loadPremultipliedRgba, writeOfflineRgbaArtifacts } from './lib/atlas-offline-rgba-lib.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ANDROID_DIR = path.resolve(__dirname, '..');

// ── 路径 ──
const REGISTRY_FILE = path.resolve(__dirname, 'resource-registry.json');
const APP_DRAWABLE_DIR = path.resolve(ANDROID_DIR, 'app/src/main/res/drawable-nodpi');
const GAME_DRAWABLE_DIR = path.resolve(ANDROID_DIR, 'feature/game/src/main/res/drawable-nodpi');
const MANIFEST_OUT = path.resolve(ANDROID_DIR, 'app/build/generated/sprite/atlas-manifest.json');
const ATLAS_DEF_OUT_DIR = path.resolve(ANDROID_DIR, 'core/engine/build/generated/sprite');
const SPRITE_CODE_OUT_DIR = path.resolve(ANDROID_DIR, 'app/build/generated/sprite');
const ASTCENC_DIR = path.resolve(ANDROID_DIR, 'scripts/tools/astcenc/bin');
const OUT_DIR = path.resolve(ANDROID_DIR, 'app/src/main/assets/atlas');
// R3.2/B10：场景 UV 常量表落仓库内生成物（cpp/scene/，提交进版本库）——
// 桌面 GTest（scene_equivalence_test）与 Android NDK 构建共用，无需先跑 codegen
const SCENE_UV_TABLES_OUT = path.resolve(ANDROID_DIR, 'app/src/main/cpp/scene/scene_uv_tables.h');
const TMP_PNG = path.resolve(ANDROID_DIR, 'scripts/tools/atlas_tmp.png');
const TMP_ASTC = path.resolve(ANDROID_DIR, 'scripts/tools/atlas_tmp.astc');

// ASTC 4×4 块尺寸（astcenc -cl 4x4）
const ASTC_BLOCK = 4;
const ASTC_BLOCK_BYTES = 16;
const GL_COMPRESSED_RGBA_ASTC_4x4_KHR = 0x93B0;
const GL_RGBA = 0x1908;

/**
 * 图集布局源数据（唯一权威——由原 core/engine/.../SpriteAtlasDef.kt 与
 * app/src/main/cpp/TextureAtlas.h 提取，两个生成物与图集拼装共用）。
 * 修改布局只改这里，运行 codegen 后 Kotlin/C++ 双端自动同步。
 */
const LAYOUT = {
  atlasW: 4096,
  atlasH: 4096,
  tileSize: 128,
  buildingSize: 512,
  // 图集布局带 gutter：任一相邻槽位间距 ≥ mipGutter（mip0 尺度），配合每精灵
  // pad 环（mipPad）消灭 mip ≥1 级的邻居渗色（Unity SpriteAtlas paddingPower 同款语义）。
  // 建筑行/列距 = 槽位 512 + 双侧 pad 各 4 = 520；瓦片/作物/道路按 8px 间距重排。
  // 设计文档：docs/design/texture-minification-pipeline-overhaul.md
  mipGutter: 8,
  mipPad: 4,
  // 建筑行分布参数（gutter 版）：512 槽位 + 8px 间距 = 520 节距，起点 (0,512)。
  // 原公式 col*512 / 512+row*512 槽位零间距（整图 mip 时代够用），per-sprite mip
  // 需 pad 环入位——节距必须 ≥ slot + mipGutter。
  buildingPitch: 520,
  buildingGridOrigin: [0, 512],
  // 瓦片（TileType：名称/index/rect/kind/sprite/layer/solid/cppName）——x 间距 8px（128 槽位 + 8 gutter）
  //   kind  ：ground=地面 / grass=草 / stone=石 / tree=树 / building=建筑占位标记
  //   sprite：**显示尺寸（格，小数格精度）**——锚点 = 格底边居中（对象"站在"自己格子上）
  //   layer ：'ground'=地面层（跟随地面逐格绘制）/'object'=立体层（与建筑同序 Y 归并绘制，
  //           越界部分按画家序正确遮挡，不被后绘建筑无脑压住）
  //   solid ：实体瓦片（autotile 过渡跳过的障碍：石/树/建筑占位；草参与过渡）
  //   cppName：C++ MAP_SPRITES 精灵名（缺省 = 不入图集，如 TILE_BUILDING 占位）
  // ★ 显示尺寸取值公式（构建期自动校验，见 validateDisplaySizing）：
  //   立体素材（草/石/树/立绘建筑）——屏上不变形：H = W × 素材高 ÷ (0.75 × 素材宽)
  //   （0.75 = TOPDOWN_Y_SCALE；地面做透视压缩、竖向物体不压缩，素材在屏上恢复原比例）
  //   贴地网格素材（草皮/灵田/作物/道路/门楼）——世界纵横比 = 素材纵横比（与地格平铺对齐）
  // ★ index == MAP_SPRITES 精灵索引（前 N 条由图集精灵瓦片按本表顺序生成——
  //   渲染器以瓦片值直接索引 UV 表，故装饰瓦片必须连续排列在 GROUND 与
  //   TILE_BUILDING 之间，且 MAP_SPRITES 序由本表派生，杜绝双写漂移）。
  // 新增装饰种类 = 本表加一行 + TILE_DRAWABLE 加映射 + 资源双模块放置，无其他改动点。
  tiles: [
    { name: 'GROUND', index: 0, kind: 'ground', sprite: [1, 1], layer: 'ground', solid: false, cppName: 'ground_tile', rect: [0, 0, 128, 128] },
    { name: 'GRASS1', index: 1, kind: 'grass', sprite: [1, 1.157], layer: 'ground', solid: false, cppName: 'grass1', rect: [136, 0, 128, 128] },
    { name: 'GRASS2', index: 2, kind: 'grass', sprite: [1, 1.064], layer: 'ground', solid: false, cppName: 'grass2', rect: [272, 0, 128, 128] },
    { name: 'GRASS3', index: 3, kind: 'grass', sprite: [1, 1.157], layer: 'ground', solid: false, cppName: 'grass3', rect: [408, 0, 128, 128] },
    { name: 'GRASS4', index: 4, kind: 'grass', sprite: [1, 1.420], layer: 'ground', solid: false, cppName: 'grass4', rect: [544, 0, 128, 128] },
    // 岩石装饰槽位放 y=0 行 x≥2048 空带（建筑行 y≥512、云层 y≥2816、道路/岛边缘 y≥2624——
    // 与全部其他槽位保持 ≥8px gutter）
    { name: 'STONE1', index: 5, kind: 'stone', sprite: [1, 0.959], layer: 'ground', solid: true, cppName: 'stone1', rect: [2048, 0, 128, 128] },
    { name: 'STONE2', index: 6, kind: 'stone', sprite: [1, 0.909], layer: 'ground', solid: true, cppName: 'stone2', rect: [2184, 0, 128, 128] },
    { name: 'STONE3', index: 7, kind: 'stone', sprite: [1, 1.105], layer: 'ground', solid: true, cppName: 'stone3', rect: [2320, 0, 128, 128] },
    { name: 'TREE1', index: 8, kind: 'tree', sprite: [2, 3.291], layer: 'object', solid: true, cppName: 'tree1', rect: [800, 0, 256, 256] },
    { name: 'TREE2', index: 9, kind: 'tree', sprite: [2, 2.791], layer: 'object', solid: true, cppName: 'tree2', rect: [1064, 0, 256, 256] },
    // 占位（与 GROUND 重叠，drawable=null 不入图集）：建筑脚印标记值——渲染器按
    // 其 index 取 TILE_UV_MAP 时命中 GROUND 槽位，故建筑格恒显示地面底图
    { name: 'TILE_BUILDING', index: 10, kind: 'building', sprite: [1, 1], layer: 'ground', solid: true, rect: [0, 0, 128, 128] },
  ],
  // 建筑（BUILDING_NAMES，按图集排列顺序）
  buildingNames: [
    '灵矿场', '灵植阁', '灵田', '炼丹炉', '锻造坊',
    '仓库', '藏经阁', '问道塔', '青云塔', '天枢殿',
    '执法堂', '任务阁', '巡视楼', '监牢',
    '单人住所', '中级单人住所', '多人住所', '血炼池',
    '中级多人住所',
  ],
  buildingColsPerRow: [5, 5, 5, 4],
  // 建筑专属槽位覆盖（图集名 → 自定义 rect）：天枢殿 18×15 格 ≈ 576×480 世界像素，
  // 3x 放大下 512 槽位上采样比过高，用 1024×1024 高清槽位
  // （gutter 版布局 (3008,1032)：与门楼 y 间距 8px，右缘 4032 留 64px 边距）。
  buildingRectOverrides: {
    '天枢殿': [3008, 1032, 1024, 1024],
    // 灵田占地 1×1 格（48px 显示），槽位 128 与瓦片/道路同 2.67:1× 采样比——
    //   深度降采样过深的槽位在缩放时 mip 跨多级，出现"模糊 ↔ 细节"观感跳变。
    //   槽位取行 1 col2 槽位左上（1044,516），与左邻（灵植阁 x1032 末）间距 12px ≥ gutter。
    '灵田': [1044, 516, 128, 128],
  },
  // 占地尺寸（FOOTPRINT_BY_NAME_INDEX，按建筑索引）——占地 = 建筑底座：
  //   宽 = 精灵宽（左右不互相压盖），深 = 底座进深（塔/亭/池取 2~3 格，院落/山丘/殿保持现状）。
  //   占地只减不增：缩小不会让旧存档建筑越界/重叠，读档 fixup 自动改尺寸且不触发拆除退款。
  footprints: [
    [4, 4], [4, 3], [1, 1], [4, 2], [5, 3], [6, 4], [6, 3], [4, 2], [4, 2], [18, 13],
    [6, 3], [4, 3], [4, 2], [4, 4], [4, 4], [6, 6], [6, 4], [4, 3], [6, 5],
  ],
  // 俯视贴地类建筑（世界纵横比 = 素材纵横比，与地格平铺对齐；其余建筑按"屏上不变形"公式）。
  // 灵田是方形田垄地块，必须与地格对齐铺展，不按立绘口径换算。
  gridAlignedBuildings: ['灵田'],
  // 图集名 → 配置 displayName（住所显示名带分级前缀，图集名保持历史命名）
  displayNameAliases: {
    '单人住所': '初级单人住所',
    '多人住所': '初级多人住所',
  },
  // 灵田作物三阶段（CropStage）——x 间距 8px（树行之后接排）
  crops: [
    { name: 'SEEDLING', cppName: 'crop_seedling', rect: [1384, 0, 128, 128] },
    { name: 'GROWING', cppName: 'crop_growing', rect: [1520, 0, 128, 128] },
    { name: 'MATURE', cppName: 'crop_mature', rect: [1656, 0, 128, 128] },
  ],
  // 固定结构（宗门入口门楼——渲染走建筑层，nameIdx = BUILDING_NAMES.size + index）
  // 统一俯视视角：精灵 6×2 与占地 1:1 贴地，
  //   槽位 768×256（与显示 288×96 同为 2.67:1×，恰为瓦片标准比例，
  //   满足槽位≤显示×4 的深度降采样守卫），俯视素材按 3:1 全幅绘制无需 letterbox。
  structures: [
    { name: '宗门门楼', key: 'sect_gate', rect: [3072, 512, 768, 256], footprint: [6, 2], spriteSize: [6, 2] },
  ],
  // 石板道路系统（RoadSprite：单一主体 + 横/竖边缘条）
  // 位置：建筑行 4（y 2072..2584）下方空带 y 2624..2752（云层下移界 y≥2816 之上）。
  // 槽位尺寸按显示分辨率对齐瓦片标准：
  //   瓦片/作物均为 128 槽位 ↔ 48px 显示（2.67:1×）；道路主体原为 768×768（16:1× 深度
  //   降采样），缩放（0.3~3.0）时 mip 跨越 3+ 级，观感"只有几条模糊线框 ↔ 满格石板细节"
  //   剧烈跳变（视觉上似闪烁）。128 槽位与瓦片同比例后，道路与地面/作物行为一致；
  //   缩放上界 3.0 时显示 144px = 仅 1.125× 放大（与瓦片相同）。边缘条显示 8×24 →
  //   32×96（4:1×，保持源图 1:3 纵横比）。三槽 x 间距 ≥8px（gutter 版布局）。
  roads: [
    { name: 'road_body',   rect: [2048, 2624, 128, 128] },
    { name: 'road_edge_v', rect: [2184, 2624, 32, 96] },
    { name: 'road_edge_h', rect: [2264, 2624, 96, 32] },
  ],
  // 布局池（9 池固定顺序：TOP/BOTTOM/LEFT/RIGHT/CORNER_TL/CORNER_TR/
  // CORNER_BL/CORNER_BR/ROCKS）——序 = 精灵绝对索引序（C++/Kotlin 同源）
  // 云层精灵（世界顶部动态云朵的图集槽位——仅提供精灵，位置/运动由
  // CloudLayerAnimator 逐帧驱动。放在图集空闲区，保持源素材纵横比）
  // 布局约束：云层槽位**不得与任何其他精灵的实体矩形重叠**（浮空岛左右环精灵
  //   位于 x 2560..3358 / y 2624..3022——cloud_3 因此落 x≥3088 空带）；
  //   实体不重叠由守卫测试全量校验（含云层，无豁免）。
  // 相邻槽位零间距（云1|云2、云4|云5 两两紧贴）——per-sprite pad 环在此处会覆盖
  //   前一张云右缘 4px。当前云素材右缘全透明（alpha=0），覆盖区落在透明带内
  //   无视觉影响；**替换为硬边缘云素材时必须给云层行加 gutter（≥8px）**
  //   （gutter 守卫测试对云层显式豁免，见 SpriteAtlasDefGeneratedTest）。
  clouds: [
    { name: 'cloud_1', rect: [0, 2816, 968, 240] },
    { name: 'cloud_2', rect: [968, 2816, 904, 376] },
    { name: 'cloud_3', rect: [3088, 2816, 976, 192] },
    { name: 'cloud_4', rect: [0, 3240, 1048, 216] },
    { name: 'cloud_5', rect: [1048, 3240, 944, 400] },
  ],
  // 双端共享渲染常量（单一数据源，Kotlin/C++ 双产物自动一致）
  lodThreshold: 0.6,
  shadowOffsetTiles: 0.25,
  shadowAlpha: 0.2,
  // 统一俯视视角：纵向压缩系数（接近正上方的俯视投影）。
  //   screenY = (worldY - camY) × scale × 本系数——正交、无近大远小、网格仍为
  //   规整矩形，仅整屏 Y 压缩模拟俯角（1.0 = 纯 90° 垂直向下；0.75 ≈ 48.6° 仰角，
  //   与 Clash of Clans 4:3 地面瓦片（64×48）同观感，地面格纵横比亦为 4:3；
  //   调大→更接近垂直，调小→俯角更明显）。
  //   双端消费：Kotlin（BaseCameraState/SectCameraState/SoftwareCanvasBackend
  //   /SectCameraStateTest 镜像常量）与 C++（cameraProjMatrix/NativeBridge 视野
  //   边界）必须同值。
  topdownYScale: 0.75,
  // ── 世界叠加层（overlay）视觉常量（重构方案 2026-09-17 R3.3/B11）──
  // 四类叠加层——放置/移动模式网格线、占地预览框、选中高亮、一键拆除高亮——的
  // 颜色/不透明度/线宽常量。R3.3 起叠加层几何由 C++ drawFrame（buildOverlayLayers）
  // 生成；B18-臂β 后**消费者为两路**：C++ 几何生成（四类全消费）+ Kotlin
  // （Canvas 兜底 SoftwareCanvasBackend 手绘 + VulkanRenderBackend 的
  // OVERLAY_FLAG_* 位定义装配 overlayFlags），同值由构造保证。
  // ⚠️ 原 Kotlin 逐 rect 绘制臂（VulkanRenderBackend 私有常量 +
  // drawGridOverlay/drawPreviewHighlight/drawSelectionHighlight/drawDemolishMarker）
  // 已随 B18-臂β 退役——生成常量仍被 Canvas/C++ 两路消费，勿据此删条目。
  // 色值 = 十六进制色的 0-1 分量（沿用旧 Compose 覆盖层配色）；
  // 线宽公式：高亮类屏幕线宽 max(2px, tileSize×0.06×scale)，除 scale 折回世界坐标；
  // 网格线世界线宽 max(0.5, 2/scale)——离屏降采样下 1 物理屏像素 = 0.5 离屏像素，
  // 细线光栅化会被整条丢弃，故按 2 物理屏像素起。
  // 条目形态：kotlin = SpriteAtlasDef 常量名，cpp = scene:: 常量名，note = 双端注释。
  overlay: [
    { kotlin: 'GOLD_R', cpp: 'kGoldR', v: 1.0, note: '选中高亮金色 #FFD700（R=255/255）' },
    { kotlin: 'GOLD_G', cpp: 'kGoldG', v: 0.843, note: '选中高亮金色 #FFD700（G=215/255）' },
    { kotlin: 'GOLD_B', cpp: 'kGoldB', v: 0.0, note: '选中高亮金色 #FFD700（B=0/255）' },
    { kotlin: 'HIGHLIGHT_FILL_ALPHA', cpp: 'kHighlightFillAlpha', v: 0.15, note: '选中高亮填充不透明度（金色半透明填充）' },
    { kotlin: 'HIGHLIGHT_EDGE_ALPHA', cpp: 'kHighlightEdgeAlpha', v: 0.9, note: '选中高亮描边不透明度' },
    { kotlin: 'DEMOLISH_GREEN_R', cpp: 'kDemolishGreenR', v: 0.298, note: '拆除未选中绿 #4CAF50（R=76/255）' },
    { kotlin: 'DEMOLISH_GREEN_G', cpp: 'kDemolishGreenG', v: 0.686, note: '拆除未选中绿 #4CAF50（G=175/255）' },
    { kotlin: 'DEMOLISH_GREEN_B', cpp: 'kDemolishGreenB', v: 0.314, note: '拆除未选中绿 #4CAF50（B=80/255）' },
    { kotlin: 'DEMOLISH_RED_R', cpp: 'kDemolishRedR', v: 0.957, note: '拆除选中红 #F44336（R=244/255）' },
    { kotlin: 'DEMOLISH_RED_G', cpp: 'kDemolishRedG', v: 0.267, note: '拆除选中红 #F44336（G=68/255）' },
    { kotlin: 'DEMOLISH_RED_B', cpp: 'kDemolishRedB', v: 0.212, note: '拆除选中红 #F44336（B=54/255）' },
    { kotlin: 'DEMOLISH_FILL_ALPHA', cpp: 'kDemolishFillAlpha', v: 0.4, note: '拆除填充不透明度（0x66 = 40% 半透明）' },
    { kotlin: 'DEMOLISH_EDGE_ALPHA', cpp: 'kDemolishEdgeAlpha', v: 1.0, note: '拆除描边不透明度' },
    { kotlin: 'PREVIEW_GREEN_R', cpp: 'kPreviewGreenR', v: 0.298, note: '可放置 #4CAF50（R=76/255）' },
    { kotlin: 'PREVIEW_GREEN_G', cpp: 'kPreviewGreenG', v: 0.686, note: '可放置 #4CAF50（G=175/255）' },
    { kotlin: 'PREVIEW_GREEN_B', cpp: 'kPreviewGreenB', v: 0.314, note: '可放置 #4CAF50（B=80/255）' },
    { kotlin: 'PREVIEW_RED_R', cpp: 'kPreviewRedR', v: 0.957, note: '不可放置 #F44336（R=244/255）' },
    { kotlin: 'PREVIEW_RED_G', cpp: 'kPreviewRedG', v: 0.267, note: '不可放置 #F44336（G=68/255）' },
    { kotlin: 'PREVIEW_RED_B', cpp: 'kPreviewRedB', v: 0.212, note: '不可放置 #F44336（B=54/255）' },
    { kotlin: 'PREVIEW_BOX_FILL_ALPHA', cpp: 'kPreviewBoxFillAlpha', v: 0.35, note: '占地框填充不透明度（0x59 ≈ 35% 半透明）' },
    { kotlin: 'PREVIEW_BOX_EDGE_ALPHA', cpp: 'kPreviewBoxEdgeAlpha', v: 0.9, note: '占地框描边不透明度（0xE6 ≈ 90%）' },
    { kotlin: 'GRID_R', cpp: 'kGridR', v: 0.894, note: '网格线 #E4DDD0（R=228/255）' },
    { kotlin: 'GRID_G', cpp: 'kGridG', v: 0.867, note: '网格线 #E4DDD0（G=221/255）' },
    { kotlin: 'GRID_B', cpp: 'kGridB', v: 0.816, note: '网格线 #E4DDD0（B=208/255）' },
    { kotlin: 'GRID_ALPHA', cpp: 'kGridAlpha', v: 1.0, note: '网格线不透明度' },
    { kotlin: 'HIGHLIGHT_LINE_WIDTH_TILES', cpp: 'kHighlightLineWidthTiles', v: 0.06, note: '高亮线宽（格数）：max(2px, tileSize×0.06) 的格数分量' },
    { kotlin: 'HIGHLIGHT_LINE_MIN_PX', cpp: 'kHighlightLineMinPx', v: 2.0, note: '高亮线宽下限（屏幕像素）' },
    { kotlin: 'GRID_LINE_WIDTH_MIN_WORLD', cpp: 'kGridLineWidthMinWorld', v: 0.5, note: '网格线世界线宽下限（防退化 quad）' },
    { kotlin: 'GRID_LINE_WIDTH_PX', cpp: 'kGridLineWidthPx', v: 2.0, note: '网格线目标屏幕线宽（2 物理屏像素，防降采样整条丢弃）' },
    { kotlin: 'OVERLAY_MIN_SCALE', cpp: 'kOverlayMinScale', v: 0.001, note: '缩放下限（线宽除 scale 的除零防御）' },
  ],
  // ── Tier1 文本资产（重构方案 2026-09-17 R3.8/B13）──
  // 目的：浮动文字（伤害/治疗/词条提示）不引入动态字体渲染——数字 0-9、拉丁
  // A-Z、少量标点、以及**有限固定游戏词条**在构建期预烘焙为 sprite 资产进图集，
  // 运行期按资产索引直取 UV，零每帧字形光栅化、零每帧 JNI。
  // Tier2（任意 CJK 动态字形 / 富文本 / 排版引擎）**明确不在本批**——本表即
  // 「Tier1 词表冻结清单」，新增词条须显式在本表加一行并重跑 codegen。
  // 布局位置：图集 y 3640..4096 空带（云层行 2 底 = 3640 为全图最低既有边界，
  //   与任何既有槽位不重叠；本段与上方保持 ≥gutter 间距）。
  //   两行等高 224：行 1 = 词条（宽格，2 字 CJK），行 2 = 单字形（窄格）。
  //   容量：行 1 每格 wordCellW=329 + 8 gutter（4096 宽可容 12 词，现用 8）；
  //         行 2 每格 glyphCellW=88 + 8 gutter（可容 42 字形，现用 40）。
  // 渲染口径：所有字形按**同一缩放因子**（= 基准 CJK 全高字形 ink 高 → 格高
  //   × glyphScale）等比缩放、居中入格——保证词条/数字/字母/标点视觉相对大小
  //   一致（若各自 trim 后拉满格，'-' 这类扁平字形会被放大成巨块）。
  //   字形素材为**白色**：运行期按样式档（普通/暴击/治疗/警告）整体染色，
  //   避免为每种颜色各烘焙一份。
  tier1: {
    // 段起 y（= 云层行 2 底 3240+400）。行 1 顶 = originY，行 2 顶 = originY + rowH + gutter
    originY: 3640,
    rowH: 224,
    gutter: 8,
    // 单字形格宽（40 字形 × 88 + 39 × 8 = 3832 ≤ 4096，余 264px 供后续扩表）
    glyphCellW: 88,
    // 词条格宽（2 字 CJK 在字高 = 224 × glyphScale 下的实际宽度 + 内缩）
    wordCellW: 329,
    // 字形 ink 高 / 格高（0.62 → CJK 全高字形 139px，居中留出动画位移余量）
    glyphScale: 0.62,
    // 基准字形：定义「字高标尺」的 CJK 全高字（所有格共用其缩放因子）
    refGlyph: '国',
    // 生成期字体与 DPI（仅构建脚本使用，不入运行期）
    fontFile: 'C:/Windows/Fonts/msyhbd.ttc',
    fontDpi: 150,
    // ★ 冻结词条清单（顺序 = 资产索引序，双端以此为唯一权威）
    words: ['会心', '格挡', '闪避', '连击', '暴击', '破防', '吸血', '免疫'],
    // ★ 冻结单字形清单（顺序 = 资产索引序 = words.length + i）
    glyphs: [
      '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
      'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
      'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
      '+', '-', '.', '%',
    ],
    // 运行期样式档（R3.8/B13）——**仅档位索引**（颜色/透明度在 C++ 浮字池内
    // 按档位查表，避免为每种颜色各烘焙一份字形素材）。双端共享，防索引漂移。
    styles: [
      { kotlin: 'FLOAT_STYLE_NORMAL', cpp: 'kFloatStyleNormal', v: 0, note: '普通伤害浮字档（白）' },
      { kotlin: 'FLOAT_STYLE_CRIT', cpp: 'kFloatStyleCrit', v: 1, note: '暴击浮字档（金，带上浮弹跳）' },
      { kotlin: 'FLOAT_STYLE_HEAL', cpp: 'kFloatStyleHeal', v: 2, note: '治疗浮字档（绿）' },
      { kotlin: 'FLOAT_STYLE_WARN', cpp: 'kFloatStyleWarn', v: 3, note: '警告/减益浮字档（红）' },
    ],
    // 样式档总数（C++ 池按此分配颜色表；新增档位须同步）
    styleCount: 4,
  },
  // C++ MAP_SPRITES 由 LAYOUT 各段派生（见下方 buildMapSprites——瓦片段取自
  // LAYOUT.tiles.cppName，建筑段取自 buildingNames + 行公式，杜绝双写漂移）
};

/**
 * C++ MAP_SPRITES 条目序列（LAYOUT 各段派生——**禁止手写第二份精灵表**）：
 * 瓦片段（带 cppName 的瓦片，顺序 = TileType 序 → 瓦片 index == 精灵索引）、
 * 作物段、建筑段、固定结构段、云层段、道路段、浮空岛边缘段。
 */
LAYOUT.mapSprites = buildMapSprites();

/** 瓦片资源名映射（瓦片名 → drawable-nodpi 资源名；缺省 = 该瓦片不入图集） */
const TILE_DRAWABLE = {
  GROUND: 'map_grass_1',
  GRASS1: 'decoration_grass1',
  GRASS2: 'decoration_grass2',
  GRASS3: 'decoration_grass3',
  GRASS4: 'decoration_grass4',
  STONE1: 'decoration_stone1',
  STONE2: 'decoration_stone2',
  STONE3: 'decoration_stone3',
  TREE1: 'decoration_tree1',
  TREE2: 'decoration_tree2',
  TILE_BUILDING: null, // 占位（与 GROUND 重叠，图集拼装同样跳过）
};

/** 石板道路资源名映射（LAYOUT.roads → drawable-nodpi 资源名） */
const ROAD_DRAWABLE = {
  road_body: 'road_body',
  road_edge_v: 'road_edge_v',
  road_edge_h: 'road_edge_h',
};

/** 作物资源名（与 buildAtlasBitmap cropDrawableMap 一致，按 ordinal） */
const CROP_DRAWABLE = ['growing_spiritgrass7', 'growing_spiritgrass8', 'growing_spiritgrass9'];

/**
 * 建筑资源名映射（图集名 → drawable-nodpi 资源文件名，与 BuildingFeatureBoot.kt
 * 的 drawableRes 一一对应；与 SectAtlasAssembler 的 effectiveSpriteName 语义一致——
 * 图集名是精灵名，可能不同于带分级前缀的显示名）。
 */
const BUILDING_DRAWABLE = {
  '灵矿场': 'building_spirit_mine',
  '灵植阁': 'building_herb_garden',
  '灵田': 'building_spirit_field',
  '炼丹炉': 'building_alchemy',
  '锻造坊': 'building_forge',
  '仓库': 'building_warehouse',
  '藏经阁': 'building_library',
  '问道塔': 'building_wen_dao_peak',
  '青云塔': 'building_qingyun_peak',
  '天枢殿': 'building_tianshu_hall',
  '执法堂': 'building_law_enforcement',
  '任务阁': 'building_mission_hall',
  '巡视楼': 'building_patrol_tower',
  '监牢': 'building_reflection_cliff',
  '单人住所': 'building_single_residence',
  '中级单人住所': 'building_single_residence_upgraded',
  '多人住所': 'building_multi_residence',
  '血炼池': 'blood_refining_pool',
  '中级多人住所': 'building_multi_residence_upgraded',
};

/** 语义索引推导（构建期断言：LAYOUT 缺语义名称即报错，防索引漂移） */
function semanticIndices(layout) {
  const idx = (names, name) => {
    const i = names.indexOf(name);
    if (i < 0) throw new Error(`LAYOUT 缺少语义名称: "${name}"（codegen 渲染常量依赖）`);
    return i;
  };
  const tileNames = layout.tiles.map((t) => t.name);
  // 地面草皮变体：TileType 名以 GROUND 开头的瓦片（渲染器按此把变体格映射到自身地面纹理）
  const groundVariants = layout.tiles
    .filter((t) => t.name.startsWith('GROUND'))
    .map((t) => t.index)
    .sort((a, b) => a - b);
  // 装饰瓦片（草/石/树）：渲染叠加层判据；须为连续区间（渲染器按区间判定，
  // 连续性由 LAYOUT.tiles 排列保证——非连续即报错，防错位静默丢装饰）
  const decorTiles = layout.tiles.filter((t) => t.kind === 'grass' || t.kind === 'stone' || t.kind === 'tree');
  const decorMin = decorTiles.length > 0 ? Math.min(...decorTiles.map((t) => t.index)) : 0;
  const decorMax = decorTiles.length > 0 ? Math.max(...decorTiles.map((t) => t.index)) : 0;
  if (decorTiles.length !== decorMax - decorMin + 1) {
    throw new Error(`装饰瓦片 index 必须连续（${decorMin}..${decorMax}，实到 ${decorTiles.length} 个）——LAYOUT.tiles 排列错误`);
  }
  // 显示尺寸表 / 绘制层表（按 index 索引；缺 index 的瓦片按 1 格/地面层处理，防稀疏表越界读）
  const maxTileIndex = Math.max(...layout.tiles.map((t) => t.index));
  const tileSpriteW = [];
  const tileSpriteH = [];
  const objectLayer = [];
  const solidIndices = [];
  for (let i = 0; i <= maxTileIndex; i++) {
    const t = layout.tiles.find((x) => x.index === i);
    if (!t) throw new Error(`LAYOUT.tiles 缺少 index=${i} 的瓦片（index 必须连续无空洞）`);
    if (!Array.isArray(t.sprite) || t.sprite.length !== 2 ||
        !(t.sprite[0] > 0) || !(t.sprite[1] > 0)) {
      throw new Error(`瓦片 ${t.name} sprite 非法: ${JSON.stringify(t.sprite)}（须为 [宽格, 高格] 正数）`);
    }
    if (t.layer !== 'ground' && t.layer !== 'object') {
      throw new Error(`瓦片 ${t.name} layer 非法: ${t.layer}（仅支持 ground/object）`);
    }
    tileSpriteW.push(t.sprite[0]);
    tileSpriteH.push(t.sprite[1]);
    objectLayer.push(t.layer === 'object' ? 1 : 0);
    if (t.solid) solidIndices.push(t.index);
  }
  // 装饰越界余量（供渲染器扩大遍历/可见性范围——装饰精灵底边居中锚定在格上，
  // 可向上伸出 (H−1) 格、向左右各伸出 (W−1)/2 格，超出范围的格子会被整块漏绘/裁切）
  const decorMaxW = Math.max(...decorTiles.map((t) => t.sprite[0]));
  const decorMaxH = Math.max(...decorTiles.map((t) => t.sprite[1]));
  return {
    spiritMine: idx(layout.buildingNames, '灵矿场'),
    spiritField: idx(layout.buildingNames, '灵田'),
    tileGround: idx(tileNames, 'GROUND'),
    tileBuilding: idx(tileNames, 'TILE_BUILDING'),
    structureNameBase: layout.buildingNames.length,
    groundVariants,
    decorMin,
    decorMax,
    tileSpriteW,
    tileSpriteH,
    objectLayer,
    decorMaxW,
    decorMaxH,
    decorMarginCols: Math.ceil((decorMaxW - 1) / 2),
    decorMarginRows: Math.ceil(decorMaxH - 1),
    solidIndices,
    tileTypeCount: maxTileIndex + 1,
  };
}

/**
 * 复刻 SpriteAtlasDef.buildingRect（图集行分布公式，支持建筑专属槽位覆盖）。
 * 覆盖表：图集名 → [x, y, w, h]（大显示建筑用高清槽位，见 LAYOUT.buildingRectOverrides）。
 * ★ gutter 版公式：节距 = buildingPitch（520 = 512 槽位 + 8 gutter），行起点 y=512——
 *   与 SpriteAtlasDef.kt 生成物 BUILDING_UV_MAP/buildingRect 同式（改此须同步生成器模板）。
 */
function buildingRectOf(colsPerRow, nameIndex) {
  const name = LAYOUT.buildingNames[nameIndex];
  const override = name != null ? LAYOUT.buildingRectOverrides?.[name] : null;
  if (override) {
    return { x: override[0], y: override[1], w: override[2], h: override[3] };
  }
  const pitch = LAYOUT.buildingPitch;
  const originY = LAYOUT.buildingGridOrigin[1];
  let idx = 0;
  for (let rowIndex = 0; rowIndex < colsPerRow.length; rowIndex++) {
    for (let col = 0; col < colsPerRow[rowIndex]; col++) {
      if (idx === nameIndex) {
        return { x: col * pitch, y: originY + rowIndex * pitch, w: LAYOUT.buildingSize, h: LAYOUT.buildingSize };
      }
      idx++;
    }
  }
  throw new Error(`buildingRect 越界: ${nameIndex}`);
}

// ── Tier1 文本资产几何（R3.8/B13）──

/**
 * Tier1 文本资产的全部 rect（词条行 + 单字形行）——**单一权威**，
 * 生成器（C++ 头 / Kotlin 常量）与图集拼装（buildSpriteList）都消费本函数，
 * 杜绝双写漂移。
 *
 * 布局：
 *   行 1（词条）   y = originY，            格宽 wordCellW，x = i × (wordCellW + gutter)
 *   行 2（单字形） y = originY + rowH + gutter，格宽 glyphCellW，x = i × (glyphCellW + gutter)
 *
 * 资产索引序（双端以此为准）：
 *   [0 .. words.length-1]                       → 词条（tier1.words 声明序）
 *   [words.length .. words.length+glyphs-1]     → 单字形（tier1.glyphs 声明序）
 *
 * @returns {{entries: Array<{kind: string, text: string, index: number, rect: number[]}>}}
 */
function tier1Entries(layout) {
  const t1 = layout.tier1;
  if (!t1) throw new Error('LAYOUT.tier1 缺失（R3.8/B13 文本资产段）');
  if (!Number.isInteger(t1.originY) || !Number.isInteger(t1.rowH) || !Number.isInteger(t1.gutter)) {
    throw new Error(`LAYOUT.tier1 几何参数非法: originY=${t1.originY} rowH=${t1.rowH} gutter=${t1.gutter}`);
  }
  if (!Array.isArray(t1.words) || t1.words.length === 0) {
    throw new Error('LAYOUT.tier1.words 须为非空数组（Tier1 冻结词表）');
  }
  if (!Array.isArray(t1.glyphs) || t1.glyphs.length === 0) {
    throw new Error('LAYOUT.tier1.glyphs 须为非空数组（Tier1 冻结字形表）');
  }
  // 非法输入自抓：词表/字形表不得含空串或重复（重复 = 资产索引歧义，静默错误）
  const seen = new Set();
  for (const [tag, list] of [['words', t1.words], ['glyphs', t1.glyphs]]) {
    for (const item of list) {
      if (typeof item !== 'string' || item.length === 0) {
        throw new Error(`LAYOUT.tier1.${tag} 含非法条目: ${JSON.stringify(item)}（须为非空字符串）`);
      }
      if (seen.has(item)) throw new Error(`LAYOUT.tier1 条目重复: "${item}"（资产索引歧义）`);
      seen.add(item);
    }
  }
  // 两行不得越出图集下缘 / 右缘（构建期快速失败，防静默裁切）
  const row2Y = t1.originY + t1.rowH + t1.gutter;
  const bottom = row2Y + t1.rowH;
  if (bottom > layout.atlasH) {
    throw new Error(`LAYOUT.tier1 越出图集下缘: 底=${bottom} > atlasH=${layout.atlasH}`);
  }
  const wordRight = t1.words.length * t1.wordCellW + (t1.words.length - 1) * t1.gutter;
  const glyphRight = t1.glyphs.length * t1.glyphCellW + (t1.glyphs.length - 1) * t1.gutter;
  if (wordRight > layout.atlasW || glyphRight > layout.atlasW) {
    throw new Error(`LAYOUT.tier1 越出图集右缘: 词条右=${wordRight} 字形右=${glyphRight} > atlasW=${layout.atlasW}`);
  }

  const entries = [];
  t1.words.forEach((text, i) => {
    entries.push({
      kind: 'word', text, index: i,
      rect: [i * (t1.wordCellW + t1.gutter), t1.originY, t1.wordCellW, t1.rowH],
    });
  });
  t1.glyphs.forEach((text, i) => {
    entries.push({
      kind: 'glyph', text, index: t1.words.length + i,
      rect: [i * (t1.glyphCellW + t1.gutter), row2Y, t1.glyphCellW, t1.rowH],
    });
  });
  return { entries };
}

/** Tier1 资产的图集精灵名（图集内唯一名；`tier1_` 前缀便于识别与排查） */
function tier1SpriteName(entry) {
  return `tier1_${entry.kind}_${entry.index}`;
}

/**
 * Tier1 字形缩放因子（R3.8/B13）——**全部格共用同一因子**。
 *
 * 口径：用基准 CJK 全高字形（tier1.refGlyph，缺省「国」）在当前 DPI 下的
 * ink 高度，反推「字高 = 格高 × glyphScale」所需的缩放因子。所有词条/字形
 * 都用这一个因子等比缩放 ⇒ 视觉相对大小自然正确（数字/字母/标点按各自
 * ink 尺寸参与）。若改为各自 trim 后拉满格，'-' 这类扁平字形会被放大成巨块。
 *
 * @returns {Promise<number>} 缩放因子（> 0）
 */
async function tier1GlyphScale(layout) {
  const t1 = layout.tier1;
  if (!t1.fontFile || !fs.existsSync(t1.fontFile)) {
    throw new Error(
      `Tier1 字体缺失: ${t1.fontFile}——构建期字形光栅化需要系统 CJK 粗体` +
      `（Windows: C:/Windows/Fonts/msyhbd.ttc）`
    );
  }
  const refRaw = await sharp({
    text: { text: t1.refGlyph, fontfile: t1.fontFile, dpi: t1.fontDpi, rgba: true },
  }).png().toBuffer();
  const refMeta = await sharp(refRaw).metadata();
  if (!refMeta.height || refMeta.height <= 0) {
    throw new Error(`Tier1 基准字形 "${t1.refGlyph}" 光栅化高度非法: ${refMeta.height}`);
  }
  const scale = (t1.rowH * t1.glyphScale) / refMeta.height;
  if (!Number.isFinite(scale) || scale <= 0) {
    throw new Error(`Tier1 字形缩放因子非法: ${scale}`);
  }
  return scale;
}

/**
 * 光栅化一个 Tier1 单元格（R3.8/B13）：字号由 DPI 定 → 按共用因子等比缩放
 * → 居中入固定格（透明背景）。
 *
 * 输出恒为 `wordCellW × rowH`（词条）或 `glyphCellW × rowH`（单字形），
 * RGBA 四通道（含抗锯齿 alpha），与 `tier1Entries` 的 rect 尺寸严格一致
 * （供图集按 rect 贴入）。
 *
 * @returns {Promise<Buffer>} PNG 缓冲（格尺寸）
 */
async function rasterizeTier1Cell(layout, entry, scale) {
  const t1 = layout.tier1;
  const cellW = entry.kind === 'word' ? t1.wordCellW : t1.glyphCellW;
  const cellH = t1.rowH;
  const text = entry.text;
  if (typeof text !== 'string' || text.length === 0) {
    throw new Error(`Tier1 非法文本条目: ${JSON.stringify(text)}`);
  }
  const raw = await sharp({
    text: { text, fontfile: t1.fontFile, dpi: t1.fontDpi, rgba: true },
  }).png().toBuffer();
  const m = await sharp(raw).metadata();
  if (!m.width || !m.height) {
    throw new Error(`Tier1 字形光栅化尺寸非法: "${text}" -> ${m.width}x${m.height}`);
  }
  const tw = Math.max(1, Math.min(Math.round(m.width * scale), cellW - 4));
  const th = Math.max(1, Math.min(Math.round(m.height * scale), cellH - 4));
  const fitted = await sharp(raw)
    .resize({
      width: tw, height: th, fit: 'inside',
      background: { r: 0, g: 0, b: 0, alpha: 0 }, kernel: sharp.kernel.lanczos3,
    })
    .png()
    .toBuffer();
  return sharp({
    create: { width: cellW, height: cellH, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
  })
    .composite([{ input: fitted, gravity: 'centre' }])
    .png()
    .toBuffer();
}

/**
 * 构建 C++ MAP_SPRITES 条目序列（单一数据源 = LAYOUT，禁止另写一份精灵表）。
 *
 * 顺序即精灵索引序（C++ getRegion 按名查、瓦片按 index 索引 UV 表）：
 * 瓦片（LAYOUT.tiles 中带 cppName 者，序 = TileType 序）→ 作物 → 建筑 →
 * 固定结构 → 云层 → 道路 → 浮空岛边缘。
 *
 * @returns {Array<{name: string, rect: number[]}>} MAP_SPRITES 条目
 */
function buildMapSprites() {
  const sprites = [];
  for (const t of LAYOUT.tiles) {
    if (t.cppName) sprites.push({ name: t.cppName, rect: t.rect });
  }
  for (const c of LAYOUT.crops) sprites.push({ name: c.cppName, rect: c.rect });
  LAYOUT.buildingNames.forEach((name, i) => {
    const r = buildingRectOf(LAYOUT.buildingColsPerRow, i);
    sprites.push({ name, rect: [r.x, r.y, r.w, r.h] });
  });
  for (const s of LAYOUT.structures) sprites.push({ name: s.key, rect: s.rect });
  for (const c of LAYOUT.clouds) sprites.push({ name: c.name, rect: c.rect });
  for (const r of LAYOUT.roads) sprites.push({ name: r.name, rect: r.rect });
  // 瓦片 index 必须等于其精灵索引（渲染器以瓦片值直取 UV 表）——缺 cppName 的占位瓦片
  //（TILE_BUILDING）不在图集内，其 index 由 TILE_UV_MAP 自身覆盖，不参与本断言
  for (const t of LAYOUT.tiles) {
    if (!t.cppName) continue;
    const spriteIdx = sprites.findIndex((s) => s.name === t.cppName);
    if (spriteIdx !== t.index) {
      throw new Error(`瓦片 ${t.name} index=${t.index} 与精灵索引 ${spriteIdx} 不一致（LAYOUT.tiles 顺序被打乱）`);
    }
  }
  return sprites;
}

// ── 内容 hash 增量 ──

/**
 * 内容 hash：源数据 JSON 序列化后的 sha256 前 16 位（非 mtime，内容不变即跳过重生成）。
 * 生成器函数源码纳入 hash——模板代码变更同样触发重新生成（防止改模板后残留旧产物）。
 */
function contentHash(obj) {
  return crypto.createHash('sha256').update(JSON.stringify(obj)).digest('hex').slice(0, 16);
}

/** 生成器函数源码（hash 因子：模板代码变更 → 内容变更 → 强制重生成）。
 *  含生成器用到的浮点字面量/UV 表达式助手——助手变更同样触发重生成 */
function codegenSource() {
  return [
    generateSpriteAtlasDef.toString(),
    generateSpriteRegistryData.toString(),
    generateTextureAtlasH.toString(),
    generateSceneUvTablesH.toString(),
    tier1CppLines.toString(),
    tier1KotlinLines.toString(),
    tier1Entries.toString(),
    cppFloatLiteral.toString(),
    cppUvExpr.toString(),
  ].join('\n');
}

/** hash 文件比对：无记录或内容变化时返回 true（需要重新生成） */
function shouldRegenerate(hashFile, hash) {
  if (!fs.existsSync(hashFile)) return true;
  return fs.readFileSync(hashFile, 'utf8').trim() !== hash;
}

/**
 * 剔除 manifest 的构建时间戳（generatedAt）后再入 hash——
 * 否则时间戳每次构建必变 → hash 恒失配 → 增量跳过永久失效。
 */
function manifestForHash(manifest) {
  if (!manifest || !Array.isArray(manifest.entries)) return manifest;
  return {
    version: manifest.version,
    entryCount: manifest.entryCount,
    entries: manifest.entries,
  };
}

// ── 显示尺寸保真校验（构建期快速失败） ──

/** 建筑配置源（与渲染运行期同源：BuildingConfigService 读同一文件） */
const BUILDINGS_CONFIG_FILE = path.resolve(ANDROID_DIR, 'app/src/main/assets/config/buildings.json');

/**
 * 素材纵横比容差：显示矩形取整数格后的残差上界。
 * 依据：整数格下最坏残差 = 0.5 ÷ 显示高格（现役最大 6.7%，为 4×5 / 6×5 档），
 * 超过 7% 即为肉眼可辨的压扁/拉宽。
 */
const DISPLAY_ASPECT_TOLERANCE = 0.07;

/** 双模块 drawable 文件解析（feature/game 优先，与双模块放置规则一致） */
function findDrawableFile(drawable) {
  for (const dir of [GAME_DRAWABLE_DIR, APP_DRAWABLE_DIR]) {
    for (const ext of ['.webp', '.png', '.jpg', '.jpeg']) {
      const file = path.join(dir, drawable + ext);
      if (fs.existsSync(file)) return file;
    }
  }
  return null;
}

/** 立体素材期望显示高（格）：H = W × 素材高 ÷ (0.75 × 素材宽) */
function billboardHeightCells(w, srcW, srcH) {
  return (w * srcH) / (LAYOUT.topdownYScale * srcW);
}

/** 屏上纵横比与素材纵横比的相对误差（0 = 完全不变形） */
function displayAspectError(displayW, displayH, srcW, srcH) {
  const display = displayW / displayH;
  const art = srcW / srcH;
  return Math.abs(display - art) / art;
}

/**
 * C++ 浮点字面量：整数值补 `.0f`（C++ 的 `1f` 非法、`{1.157}` 会因
 * double→float 收窄初始化报错），小数直接补 `f`。
 */
function cppFloatLiteral(v) {
  return Number.isInteger(v) ? `${v}.0f` : `${v}f`;
}

/** Kotlin 浮点字面量（与 cppFloatLiteral 同形态，整数值补 .0 保持可读性） */
function kotlinFloatLiteral(v) {
  return Number.isInteger(v) ? `${v}.0f` : `${v}f`;
}

/**
 * 叠加层常量的双端生成行（LAYOUT.overlay 单一数据源，R3.3/B11）。
 *
 * 同值性论证：两侧字面量文本相同（`${v}f`），Kotlin 与 C++ 编译器各自按
 * IEEE-754 最近舍入解析 ⇒ 逐位一致（与既有 SHADOW_ALPHA 等同机制）。
 */
function overlayKotlinLines(layout) {
  return layout.overlay.map((e) => `    const val ${e.kotlin} = ${kotlinFloatLiteral(e.v)}`);
}

function overlayCppLines(layout) {
  const out = [];
  for (const e of layout.overlay) {
    out.push(`// ${e.note}`);
    out.push(`inline constexpr float ${e.cpp} = ${cppFloatLiteral(e.v)};`);
  }
  return out;
}

/**
 * Tier1 文本资产的 Kotlin 常量行（R3.8/B13）——与 C++ `kFloat*` 同源同值。
 *
 * UV 表用**表达式**形态（`x.toFloat() / ATLAS_W`）与既有 UV 表同式：
 * 图集尺寸为 2 的幂 ⇒ 除法精确，双端逐位一致（`SceneUvTablesMirrorGuardTest`
 * 逐位对照锁定）。
 */
function tier1KotlinLines(layout) {
  const { entries } = tier1Entries(layout);
  const t1 = layout.tier1;
  const out = [];
  out.push(`    const val FLOAT_WORD_COUNT = ${t1.words.length}`);
  out.push(`    const val FLOAT_GLYPH_COUNT = ${t1.glyphs.length}`);
  out.push(`    const val FLOAT_ASSET_COUNT = ${entries.length}`);
  out.push(`    const val FLOAT_GLYPH_BASE_INDEX = ${t1.words.length}`);
  out.push('    /** 资产索引 → UV（[u0,v0,u1,v1] 展平；索引序 = 词条段 → 单字形段） */');
  out.push('    val FLOAT_UV: FloatArray = floatArrayOf(');
  for (const e of entries) {
    out.push(`        ${e.rect[0]}.toFloat() / ATLAS_W, ${e.rect[1]}.toFloat() / ATLAS_H, ` +
      `${e.rect[0] + e.rect[2]}.toFloat() / ATLAS_W, ${e.rect[1] + e.rect[3]}.toFloat() / ATLAS_H,`);
  }
  out.push('    )');
  out.push('    /** Tier1 冻结词条清单（索引 0..FLOAT_WORD_COUNT-1；本表即冻结清单） */');
  out.push(`    val FLOAT_WORD_TEXT: List<String> = listOf(${t1.words.map((s) => JSON.stringify(s)).join(', ')})`);
  out.push('    /** Tier1 冻结单字形清单（索引 FLOAT_GLYPH_BASE_INDEX..） */');
  out.push(`    val FLOAT_GLYPH_TEXT: List<String> = listOf(${t1.glyphs.map((s) => JSON.stringify(s)).join(', ')})`);
  out.push('    /** 词条字数（渲染宽度换算 = 字数 × 字宽；本批恒为 2） */');
  out.push(`    val FLOAT_WORD_LENGTH: List<Int> = listOf(${t1.words.map((s) => s.length).join(', ')})`);
  out.push(`    const val FLOAT_CELL_W = ${t1.wordCellW}`);
  out.push(`    const val FLOAT_CELL_H = ${t1.rowH}`);
  out.push(`    const val FLOAT_GLYPH_SCALE = ${kotlinFloatLiteral(t1.glyphScale)}`);
  out.push(`    const val FLOAT_STYLE_COUNT = ${t1.styleCount}`);
  for (const s of t1.styles) {
    out.push(`    /** ${s.note} */`);
    out.push(`    const val ${s.kotlin} = ${s.v}`);
  }
  return out;
}

/** 建筑配置内容（纳入 codegen hash：配置改动同样触发保真校验与重生成） */
function buildingsConfigForHash() {
  return JSON.parse(fs.readFileSync(BUILDINGS_CONFIG_FILE, 'utf8'));
}

/**
 * 显示尺寸保真校验：逐栋建筑 + 逐个装饰断言「显示矩形在屏幕上的纵横比 == 素材纵横比」。
 *
 * - 立体类（立绘建筑 / 草石树）：显示宽 ÷ (0.75 × 显示高) == 素材宽 ÷ 素材高
 *   （0.75 = TOPDOWN_Y_SCALE：地面做透视压缩、竖向物体不压缩）
 * - 贴地网格类（灵田等 gridAlignedBuildings / 草皮）：显示宽 ÷ 显示高 == 素材宽 ÷ 素材高
 *
 * 同时锁定结构不变量：精灵宽 == 占地宽（左右不互相压盖）、精灵高 ≥ 占地深（精灵盖住底座）。
 * 失败即抛错并给出应填尺寸——换素材/改尺寸时立刻暴露"被压扁"回归，
 * 与 app 侧 SpriteSizingFidelityTest 构成构建期 + 测试期双层防线。
 */
async function validateDisplaySizing(layout) {
  const config = JSON.parse(fs.readFileSync(BUILDINGS_CONFIG_FILE, 'utf8'));
  const byDisplayName = new Map(Object.values(config.buildings).map((b) => [b.displayName, b]));
  const gridAligned = new Set(layout.gridAlignedBuildings ?? []);
  const aliases = layout.displayNameAliases ?? {};
  const yScale = layout.topdownYScale;
  const tolerancePct = `${(DISPLAY_ASPECT_TOLERANCE * 100).toFixed(0)}%`;
  const problems = [];

  for (const [i, name] of layout.buildingNames.entries()) {
    const drawable = BUILDING_DRAWABLE[name];
    if (!drawable) throw new Error(`LAYOUT.buildingNames 缺少素材映射: ${name}（BUILDING_DRAWABLE）`);
    const displayName = aliases[name] ?? name;
    const cfg = byDisplayName.get(displayName);
    if (!cfg) {
      throw new Error(`配置缺少建筑: ${displayName}（图集名 ${name}）——config/buildings.json 与 LAYOUT 必须一一对应`);
    }
    const file = findDrawableFile(drawable);
    if (!file) throw new Error(`素材缺失: ${drawable}（${name}）——双模块 drawable-nodpi 必须同时放置`);
    const meta = await sharp(file).metadata();
    const [fpW, fpH] = layout.footprints[i];
    if (cfg.spriteWidth !== fpW) {
      problems.push(`${name}: 精灵宽 ${cfg.spriteWidth} ≠ 占地宽 ${fpW}（相邻建筑精灵会左右压盖）`);
    }
    if (cfg.spriteHeight < fpH) {
      problems.push(`${name}: 精灵高 ${cfg.spriteHeight} < 占地深 ${fpH}（精灵盖不住底座，露出空地）`);
    }
    const isGridAligned = gridAligned.has(name);
    const err = isGridAligned
      ? displayAspectError(cfg.spriteWidth, cfg.spriteHeight, meta.width, meta.height)
      : displayAspectError(cfg.spriteWidth, cfg.spriteHeight * yScale, meta.width, meta.height);
    if (err > DISPLAY_ASPECT_TOLERANCE) {
      const suggestH = isGridAligned
        ? Math.round((cfg.spriteWidth * meta.height) / meta.width)
        : Math.round(billboardHeightCells(cfg.spriteWidth, meta.width, meta.height));
      problems.push(
        `${name}: 屏上纵横比与素材不符（偏差 ${(err * 100).toFixed(1)}% > ${tolerancePct}）——` +
        `素材 ${meta.width}×${meta.height}，当前精灵 ${cfg.spriteWidth}×${cfg.spriteHeight}，应为 ${cfg.spriteWidth}×${suggestH}`
      );
    }
  }

  const decorTiles = layout.tiles.filter((t) => TILE_DRAWABLE[t.name] && t.kind !== 'ground');
  for (const t of decorTiles) {
    const file = findDrawableFile(TILE_DRAWABLE[t.name]);
    if (!file) throw new Error(`素材缺失: ${TILE_DRAWABLE[t.name]}（装饰 ${t.name}）——双模块 drawable-nodpi 必须同时放置`);
    const meta = await sharp(file).metadata();
    const err = displayAspectError(t.sprite[0], t.sprite[1] * yScale, meta.width, meta.height);
    if (err > DISPLAY_ASPECT_TOLERANCE) {
      const suggestH = billboardHeightCells(t.sprite[0], meta.width, meta.height);
      problems.push(
        `装饰 ${t.name}: 屏上纵横比与素材不符（偏差 ${(err * 100).toFixed(1)}%）——` +
        `素材 ${meta.width}×${meta.height}，当前 ${t.sprite[0]}×${t.sprite[1]}，应为 ${t.sprite[0]}×${suggestH.toFixed(3)}`
      );
    }
  }

  if (problems.length > 0) {
    throw new Error(
      '显示尺寸保真校验失败（建筑/装饰会被压扁或左右压盖——改 LAYOUT.tiles / config/buildings.json）：\n  ' +
      problems.join('\n  ')
    );
  }
  console.log(
    `显示尺寸保真校验通过：${layout.buildingNames.length} 栋建筑 + ${decorTiles.length} 个装饰`
  );
}

// ── 生成器：SpriteAtlasDef.kt（core/engine 编译单元） ──

/** 生成 Kotlin 图集布局常量文件（public 成员签名与原手工版一致，不含死代码命令类） */
function generateSpriteAtlasDef(layout) {
  const si = semanticIndices(layout);
  const tileLines = layout.tiles
    .map((t) => `        ${t.name}(${t.index}, SpriteRect(${t.rect.join(', ')}))`)
    .join(',\n');
  // Kotlin 名称字符串字面量经 JSON.stringify 生成——引号/$ 转义防注入，
  // 名称来自仓库内源数据，防手改破坏生成代码
  const buildingNameLines = layout.buildingNames.map((n) => `        ${JSON.stringify(n)}`).join(',\n');
  const footprintLines = layout.footprints
    .map((fp, i) => `        ${fp[0]} to ${fp[1]},   // ${i}: ${layout.buildingNames[i]}`)
    .join('\n');
  // 建筑专属槽位覆盖（图集名 → [x,y,w,h]，大显示建筑用高清槽位）
  const buildingOverrideLines = Object.entries(layout.buildingRectOverrides ?? {})
    .map(([name, rect]) => {
      const i = layout.buildingNames.indexOf(name);
      if (i < 0) throw new Error(`buildingRectOverrides 引用未知建筑: "${name}"`);
      return `        ${i} to SpriteRect(${rect.join(', ')}),   // ${name}`;
    })
    .join('\n');
  const cropLines = layout.crops
    .map((c) => `        ${c.name}(SpriteRect(${c.rect.join(', ')}))`)
    .join(',\n');
  const structureDefLines = layout.structures
    .map((s) => `        StructureDef(${JSON.stringify(s.name)}, ${JSON.stringify(s.key)}, SpriteRect(${s.rect.join(', ')}), ${s.footprint[0]}, ${s.footprint[1]}, ${s.spriteSize[0]}, ${s.spriteSize[1]})`)
    .join(',\n');
  const cloudRectLines = layout.clouds
    .map((c) => `        ${JSON.stringify(c.name)} to SpriteRect(${c.rect.join(', ')})`)
    .join(',\n');
  const roadLines = layout.roads
    .map((r) => `        ${JSON.stringify(r.name)} to SpriteRect(${r.rect.join(', ')})`)
    .join(',\n');
  const groundVariantLines = si.groundVariants.join(', ');

  return [
    'package com.xianxia.sect.core.render',
    '',
    '// ============================================================',
    '// GENERATED FILE — 由 scripts/build-atlas.mjs 自动生成，禁止手改。',
    '// 布局源数据位于 build-atlas.mjs 的 LAYOUT 常量，运行',
    '//   cd android && node scripts/build-atlas.mjs --atlas-def-only',
    '// 重新生成。',
    '// ============================================================',
    '',
    '/**',
    ' * 精灵在图集中的像素矩形（Vulkan UV 计算和 Canvas rect 共用）。',
    ' */',
    'data class SpriteRect(val x: Int, val y: Int, val w: Int, val h: Int)',
    '',
    '/**',
    ' * 统一精灵图集定义。',
    ' *',
    ' * 这是地面瓦片、装饰和建筑在图集中位置的唯一来源。',
    ' * 布局与 C++ TextureAtlas.h 中的 MAP_SPRITES 定义一致。',
    ' * 新增建筑类型时只需在 build-atlas.mjs 的 LAYOUT 添加，Vulkan/Canvas 两路径自动同步。',
    ' */',
    'object SpriteAtlasDef {',
    `    const val ATLAS_W = ${layout.atlasW}`,
    `    const val ATLAS_H = ${layout.atlasH}`,
    `    const val TILE_SIZE = ${layout.tileSize}`,
    `    const val BUILDING_SIZE = ${layout.buildingSize}`,
    `    const val BUILDING_PITCH = ${layout.buildingPitch}`,
    `    const val BUILDING_GRID_ORIGIN_Y = ${layout.buildingGridOrigin[1]}`,
    '',
    '    // ============================================================',
    '    // 双端共享渲染常量（与 C++ TextureAtlas.h 同源生成）',
    '    // ============================================================',
    `    const val DECOR_QUALITY_THRESHOLD = ${layout.lodThreshold}f`,
    `    const val SHADOW_OFFSET_TILES = ${layout.shadowOffsetTiles}f`,
    `    const val SHADOW_ALPHA = ${layout.shadowAlpha}f`,
    `    const val TOPDOWN_Y_SCALE = ${layout.topdownYScale}f`,
    `    const val SPIRIT_MINE_NAME_INDEX = ${si.spiritMine}`,
    `    const val SPIRIT_FIELD_NAME_INDEX = ${si.spiritField}`,
    `    const val TILE_GROUND_INDEX = ${si.tileGround}`,
    `    const val TILE_BUILDING_INDEX = ${si.tileBuilding}`,
    '',
    '    // ============================================================',
    '    // 世界叠加层（overlay）视觉常量（R3.3/B11：C++ drawFrame 生成叠加层几何；',
    '    // B18-臂β 后消费者 = C++ 几何生成 + Canvas 兜底手绘，同值由构造保证）',
    '    // ============================================================',
    ...overlayKotlinLines(layout),
    '',
    '    // ============================================================',
    '    // Tier1 文本资产（R3.8/B13：浮动文字预烘焙 sprite——数字/拉丁/标点/',
    '    // 有限固定游戏词条；与 C++ scene_uv_tables.h kFloat* 同源生成）',
    '    // ============================================================',
    ...tier1KotlinLines(layout),
    '',
    '    // ============================================================',
    '    // 瓦片类型定义',
    '    // ============================================================',
    '',
    '    /** 瓦片类型（与 C++ TextureAtlas.h MAP_SPRITES 索引一致） */',
    '    enum class TileType(',
    '        val index: Int,',
    '        val rect: SpriteRect',
    '    ) {',
    tileLines + ';',
    '',
    '        companion object {',
    '            private val BY_INDEX = values().associateBy { it.index }',
    '            fun fromIndex(index: Int): TileType = BY_INDEX[index] ?: GROUND',
    '        }',
    '    }',
    '',
    '    /**',
    '     * 瓦片 UV 映射（归一化 0-1，用于 Vulkan 纹理采样）。',
    '     * 与 C++ TextureAtlas.h 的 UV 计算一致。',
    '     */',
    '    val TILE_UV_MAP: FloatArray by lazy {',
    '        val uv = FloatArray(TileType.values().size * 4)',
    '        for (tile in TileType.values()) {',
    '            val r = tile.rect',
    '            val i = tile.index * 4',
    '            uv[i] = r.x.toFloat() / ATLAS_W',
    '            uv[i + 1] = r.y.toFloat() / ATLAS_H',
    '            uv[i + 2] = (r.x + r.w).toFloat() / ATLAS_W',
    '            uv[i + 3] = (r.y + r.h).toFloat() / ATLAS_H',
    '        }',
    '        uv',
    '    }',
    '',
    '    // ============================================================',
    '    // 宗门入口固定结构（门楼/阶梯）——渲染走建筑层',
    '    // 须在 BUILDING_UV_MAP 之前声明（BUILDING_UV_MAP 尾部追加结构 UV）',
    '    // ============================================================',
    '',
    '    /** 固定结构定义（渲染走建筑层，nameIdx = BUILDING_NAMES.size + index） */',
    '    data class StructureDef(',
    '        val name: String,',
    '        val key: String,',
    '        val rect: SpriteRect,',
    '        val footprintW: Int,',
    '        val footprintH: Int,',
    '        val spriteW: Int,',
    '        val spriteH: Int',
    '    )',
    '',
    '    val STRUCTURES = listOf(',
    structureDefLines,
    '    )',
    '',
    '    /** 结构 UV 映射（归一化 0-1，追加于 BUILDING_UV_MAP 尾部） */',
    '    val STRUCTURE_UV_MAP: FloatArray by lazy {',
    '        val uv = FloatArray(STRUCTURES.size * 4)',
    '        for ((i, s) in STRUCTURES.withIndex()) {',
    '            val r = s.rect',
    '            val j = i * 4',
    '            uv[j] = r.x.toFloat() / ATLAS_W',
    '            uv[j + 1] = r.y.toFloat() / ATLAS_H',
    '            uv[j + 2] = (r.x + r.w).toFloat() / ATLAS_W',
    '            uv[j + 3] = (r.y + r.h).toFloat() / ATLAS_H',
    '        }',
    '        uv',
    '    }',
    '',
    '    /** 地面草皮变体瓦片索引（渲染器按此把变体格映射到自身地面纹理） */',
    `    val GROUND_VARIANT_INDICES = intArrayOf(${groundVariantLines})`,
    '',
    '    // ============================================================',
    '    // 瓦片分类（装饰叠加层 / 显示尺寸 / 绘制层 / 实体障碍——渲染器与 autotile 的',
    '    // 唯一判据，由 LAYOUT.tiles 的 kind/sprite/layer/solid 生成，禁止在消费侧硬编码瓦片序号）',
    '    // ============================================================',
    '',
    '    /** 装饰瓦片 index 区间（草/石/树；区间外的瓦片不绘制装饰叠加层） */',
    `    const val DECOR_TILE_MIN_INDEX = ${si.decorMin}`,
    `    const val DECOR_TILE_MAX_INDEX = ${si.decorMax}`,
    '',
    '    /**',
    '     * 瓦片显示尺寸（格，小数格精度，按 index）。',
    '     *',
    '     * 锚点 = **格底边居中**：绘制矩形 x = 格左 + (1−W)/2 格、y = 格上 + (1−H) 格。',
    '     * 立体素材（草/石/树）按"屏上不变形"取 H = W × 素材高 ÷ (0.75 × 素材宽)，',
    '     * 故对象站在自己格子上、树冠向上伸出（构建期由 validateDisplaySizing 校验）。',
    '     */',
    `    val TILE_SPRITE_W = floatArrayOf(${si.tileSpriteW.map((v) => `${v}f`).join(', ')})`,
    `    val TILE_SPRITE_H = floatArrayOf(${si.tileSpriteH.map((v) => `${v}f`).join(', ')})`,
    '',
    '    /** 瓦片绘制层（0=地面层，跟随地面逐格绘制；1=立体层，与建筑同序 Y 归并绘制） */',
    `    val TILE_OBJECT_LAYER = intArrayOf(${si.objectLayer.join(', ')})`,
    '',
    '    /** 装饰最大显示尺寸（格）——越界余量推导源 */',
    `    const val DECOR_MAX_SPRITE_W = ${si.decorMaxW}f`,
    `    const val DECOR_MAX_SPRITE_H = ${si.decorMaxH}f`,
    '',
    '    /** 装饰越界余量（格）：左右各 (maxW−1)/2 上取整、向上 maxH−1 上取整 */',
    `    const val DECOR_MARGIN_COLS = ${si.decorMarginCols}`,
    `    const val DECOR_MARGIN_ROWS = ${si.decorMarginRows}`,
    '',
    '    /** 实体瓦片索引（石/树/建筑占位——autotile 过渡跳过，草参与过渡） */',
    `    val SOLID_TILE_INDICES = intArrayOf(${si.solidIndices.join(', ')})`,
    '',
    '    /** 是否为装饰叠加层瓦片（草/石/树） */',
    '    fun isDecorTile(index: Int): Boolean = index in DECOR_TILE_MIN_INDEX..DECOR_TILE_MAX_INDEX',
    '',
    '    /** 瓦片显示宽度（格；越界回退 1，防数组越界读） */',
    '    fun tileSpriteWidth(index: Int): Float = TILE_SPRITE_W.getOrElse(index) { 1f }',
    '',
    '    /** 瓦片显示高度（格；越界回退 1，防数组越界读） */',
    '    fun tileSpriteHeight(index: Int): Float = TILE_SPRITE_H.getOrElse(index) { 1f }',
    '',
    '    /** 是否为立体层瓦片（树——与建筑同序绘制，两后端消费同一判定） */',
    '    fun isObjectDecorTile(index: Int): Boolean = TILE_OBJECT_LAYER.getOrElse(index) { 0 } == 1',
    '',
    '    /** 是否为实体障碍瓦片（不参与 autotile 过渡） */',
    '    fun isSolidTile(index: Int): Boolean = SOLID_TILE_INDICES.contains(index)',
    '',
    '    // ============================================================',
    '    // 建筑定义',
    '    // ============================================================',
    '',
    '    /** 建筑名称（按图集排列顺序，与 C++ TextureAtlas.h MAP_SPRITES 一致） */',
    '    val BUILDING_NAMES = listOf(',
    buildingNameLines,
    '    )',
    '',
    '    /** 每行建筑数（图集行分布） */',
    `    private val BUILDING_COLS_PER_ROW = intArrayOf(${layout.buildingColsPerRow.join(', ')})`,
    '',
    '    /** 建筑名称 → 索引 */',
    '    val BUILDING_NAME_INDEX: Map<String, Int> by lazy {',
    '        BUILDING_NAMES.withIndex().associate { it.value to it.index }',
    '    }',
    '',
    '    /** 建筑专属槽位覆盖（索引 → 自定义图集 rect；大显示建筑用高清槽位） */',
    '    private val BUILDING_RECT_OVERRIDES: Map<Int, SpriteRect> = mapOf(',
    buildingOverrideLines,
    '    )',
    '',
    '    /**',
    '     * 占地尺寸（按 BUILDING_NAMES 索引，供渲染器查找占地面积用于地砖选择）。',
    '     * 建筑数据数组中传递的是精灵比例尺寸（spriteWidth/spriteHeight），',
    '     * 渲染器需通过此表获取占地尺寸来计算地砖索引。',
    '     */',
    '    val FOOTPRINT_BY_NAME_INDEX: Array<Pair<Int, Int>> = arrayOf(',
    footprintLines,
    '    )',
    '',
    '    /**',
    '     * 建筑 UV 映射（归一化 0-1，与 C++ NativeBridge drawAllTiles 的',
    '     * buildingUVMap 参数一致）。',
    '     */',
    '    val BUILDING_UV_MAP: FloatArray by lazy {',
    '        val uvs = FloatArray((BUILDING_NAMES.size + STRUCTURES.size) * 4)',
    '        var idx = 0',
    '        for (rowIndex in BUILDING_COLS_PER_ROW.indices) {',
    '            for (col in 0 until BUILDING_COLS_PER_ROW[rowIndex]) {',
    '                // 专属槽位覆盖优先，否则行公式槽位（节距 BUILDING_PITCH 含 gutter）',
    '                val r = BUILDING_RECT_OVERRIDES[idx]',
    '                    ?: SpriteRect(col * BUILDING_PITCH, BUILDING_GRID_ORIGIN_Y + rowIndex * BUILDING_PITCH, BUILDING_SIZE, BUILDING_SIZE)',
    '                val i = idx * 4',
    '                uvs[i] = r.x.toFloat() / ATLAS_W',
    '                uvs[i + 1] = r.y.toFloat() / ATLAS_H',
    '                uvs[i + 2] = (r.x + r.w).toFloat() / ATLAS_W',
    '                uvs[i + 3] = (r.y + r.h).toFloat() / ATLAS_H',
    '                idx++',
    '            }',
    '        }',
    '        // 固定结构 UV 追加在建筑 UV 尾部（nameIdx = BUILDING_NAMES.size + index）',
    '        for ((si, s) in STRUCTURES.withIndex()) {',
    '            val r = s.rect',
    '            val i = (BUILDING_NAMES.size + si) * 4',
    '            uvs[i] = r.x.toFloat() / ATLAS_W',
    '            uvs[i + 1] = r.y.toFloat() / ATLAS_H',
    '            uvs[i + 2] = (r.x + r.w).toFloat() / ATLAS_W',
    '            uvs[i + 3] = (r.y + r.h).toFloat() / ATLAS_H',
    '        }',
    '        uvs',
    '    }',
    '',
    '    /**',
    '     * 获取建筑在图集中的像素矩形（供 Canvas 渲染器使用）。',
    '     * @param nameIndex 建筑索引',
    '     */',
    '    fun buildingRect(nameIndex: Int): SpriteRect {',
    '        BUILDING_RECT_OVERRIDES[nameIndex]?.let { return it }',
    '        var idx = 0',
    '        for (rowIndex in BUILDING_COLS_PER_ROW.indices) {',
    '            for (col in 0 until BUILDING_COLS_PER_ROW[rowIndex]) {',
    '                if (idx == nameIndex) {',
    '                    return SpriteRect(',
    '                        col * BUILDING_PITCH,',
    '                        BUILDING_GRID_ORIGIN_Y + rowIndex * BUILDING_PITCH,',
    '                        BUILDING_SIZE,',
    '                        BUILDING_SIZE',
    '                    )',
    '                }',
    '                idx++',
    '            }',
    '        }',
    '        // 越界回退',
    '        return SpriteRect(0, BUILDING_GRID_ORIGIN_Y, BUILDING_SIZE, BUILDING_SIZE)',
    '    }',
    '',
    '    // ============================================================',
    '    // 灵田作物生长阶段（图集 y=0 行空闲区——与 C++ TextureAtlas.h',
    '    // MAP_SPRITES crop_seedling/crop_growing/crop_mature 同步）',
    '    // ============================================================',
    '',
    '    /** 灵田作物三阶段精灵（64×64，y=0 行 x=832/896/960 空槽） */',
    '    enum class CropStage(val rect: SpriteRect) {',
    cropLines,
    '    }',
    '',
    '    /**',
    '     * 作物 UV 映射（归一化 0-1，按阶段索引，供 Vulkan 纹理采样）。',
    '     * 与 C++ TextureAtlas.h UV 计算一致。',
    '     */',
    '    val CROP_UV_MAP: FloatArray by lazy {',
    '        val uv = FloatArray(CropStage.values().size * 4)',
    '        for (stage in CropStage.values()) {',
    '            val r = stage.rect',
    '            val i = stage.ordinal * 4',
    '            uv[i] = r.x.toFloat() / ATLAS_W',
    '            uv[i + 1] = r.y.toFloat() / ATLAS_H',
    '            uv[i + 2] = (r.x + r.w).toFloat() / ATLAS_W',
    '            uv[i + 3] = (r.y + r.h).toFloat() / ATLAS_H',
    '        }',
    '        uv',
    '    }',
    '',
    '    // ============================================================',
    '    // 云层精灵（世界顶部动态云朵——图集槽位，位置/运动由',
    '    // CloudLayerAnimator 逐帧驱动，渲染端只按槽位取 UV/源矩形）',
    '    // ============================================================',
    '',
    '    /** 云层精灵图集 rect（按 LAYOUT.clouds 声明顺序，Canvas 渲染取源矩形） */',
    '    val CLOUD_RECTS: List<Pair<String, SpriteRect>> = listOf(',
    cloudRectLines,
    '    )',
    '',
    '    /**',
    '     * 云层 UV 映射（归一化 0-1，按云层索引，供 Vulkan 纹理采样）。',
    '     * 与 C++ TextureAtlas.h MAP_SPRITES cloud_* 同源。',
    '     */',
    '    val CLOUD_UV_MAP: FloatArray by lazy {',
    '        val uv = FloatArray(CLOUD_RECTS.size * 4)',
    '        for ((i, entry) in CLOUD_RECTS.withIndex()) {',
    '            val r = entry.second',
    '            val j = i * 4',
    '            uv[j] = r.x.toFloat() / ATLAS_W',
    '            uv[j + 1] = r.y.toFloat() / ATLAS_H',
    '            uv[j + 2] = (r.x + r.w).toFloat() / ATLAS_W',
    '            uv[j + 3] = (r.y + r.h).toFloat() / ATLAS_H',
    '        }',
    '        uv',
    '    }',
    '',
    '    // ============================================================',
    '    // 石板道路系统（RoadSprite：主体/路口/边缘条/转角/十字装饰）',
    '    // 渲染叠加层按位掩码合成：直路用 base，转角/T/十字用 junction，',
    '    // 外缘描边条用 edge_*，外角用 corner_*，十字中心装饰用 cross_center。',
    '    // 与 C++ TextureAtlas.h MAP_SPRITES 的 road_* 同源。',
    '    // ============================================================',
    '',
    '    /** 道路精灵图集 rect（按 LAYOUT.roads 声明顺序；Canvas 渲染取源矩形） */',
    '    val ROAD_RECTS: List<Pair<String, SpriteRect>> = listOf(',
    roadLines,
    '    )',
    '',
    '    /** 道路精灵名称 → rect（渲染器按 bitMask 合成时查询） */',
    '    val ROAD_RECT_BY_KEY: Map<String, SpriteRect> by lazy {',
    '        ROAD_RECTS.associate { it.first to it.second }',
    '    }',
    '',
    '    /**',
    '     * 道路 UV 映射（归一化 0-1，按 ROAD_RECTS 声明顺序，供 Vulkan 纹理采样）。',
    '     * 与 C++ TextureAtlas.h 的 road UV 计算一致。',
    '     */',
    '    val ROAD_UV_MAP: FloatArray by lazy {',
    '        val uv = FloatArray(ROAD_RECTS.size * 4)',
    '        for ((i, entry) in ROAD_RECTS.withIndex()) {',
    '            val r = entry.second',
    '            val j = i * 4',
    '            uv[j] = r.x.toFloat() / ATLAS_W',
    '            uv[j + 1] = r.y.toFloat() / ATLAS_H',
    '            uv[j + 2] = (r.x + r.w).toFloat() / ATLAS_W',
    '            uv[j + 3] = (r.y + r.h).toFloat() / ATLAS_H',
    '        }',
    '        uv',
    '    }',
    '',
    '}',   // 闭合 object SpriteAtlasDef
    '',
  ].join('\n');
}

/**
 * 生成精灵注册数据文件（与原有手工 SpriteRegistryData.kt 功能等价：
 * registerAllSprites() 签名与注册语义不变，调用方 XianxiaApplication 无感）。
 * R 引用规则：资源在 app 模块有副本 → 用 app 的 R；仅 feature/game 有 → 用 FeatureGameR。
 */
function generateSpriteRegistryData(registry, manifest) {
  const byName = new Map(manifest.entries.map((e) => [e.name, e]));
  const lines = [];

  lines.push(
    'package com.xianxia.sect',
    '',
    'import com.xianxia.sect.ui.components.SpriteCategory',
    'import com.xianxia.sect.ui.components.SpriteResRegistry',
    '',
    '// ============================================================',
    '// GENERATED FILE — 由 scripts/build-atlas.mjs 自动生成，禁止手改。',
    '// 源数据：scripts/resource-registry.json（注册映射）+ atlas-manifest.json（资源清单）。',
    '// 运行 cd android && node scripts/build-atlas.mjs --codegen 重新生成。',
    '// ============================================================',
    ''
  );

  let needGameR = false;
  const registerLines = [];
  for (const cat of registry.categories) {
    const categoryName = cat.category;
    const valName = `SPRITES_${categoryName}`;
    const entryLines = [];
    for (const entry of cat.entries) {
      const resEntry = byName.get(entry.res);
      if (!resEntry) {
        throw new Error(`注册源数据引用了清单中不存在的资源: "${entry.res}"（${categoryName}/${entry.name}）——检查 resource-registry.json 或 drawable-nodpi 目录`);
      }
      const usesGameR = !manifest.entries.some((e) => e.name === entry.res && e.module === 'app');
      if (usesGameR) needGameR = true;
      const ref = usesGameR ? `FeatureGameR.drawable.${entry.res}` : `R.drawable.${entry.res}`;
      // JSON.stringify 转义
      entryLines.push(`        ${JSON.stringify(entry.name)} to ${ref},`);
    }
    lines.push(`/** ${categoryName} — 精灵图资源映射（由 resource-registry.json 生成） */`);
    lines.push(`internal val ${valName}: Map<String, Int> = mapOf(`);
    lines.push(entryLines.join('\n'));
    lines.push(')');
    lines.push('');
    registerLines.push(`    SpriteResRegistry.register(SpriteCategory.${categoryName}, ${valName})`);
  }

  lines.push('/** 全部装备资源 ID（按注册顺序，供预加载与兜底查询使用） */');
  lines.push('internal val ALLEQUIPMENTRESIDS: List<Int> = SPRITES_EQUIPMENT.values.toList()');
  lines.push('');
  lines.push('/** 统一精灵图注册入口（onCreate 调用，签名与原手工版一致） */');
  lines.push('internal fun registerAllSprites() {');
  lines.push(registerLines.join('\n'));
  lines.push('}');
  lines.push('');

  const body = lines.join('\n');
  if (needGameR) {
    return 'import com.xianxia.sect.feature.game.R as FeatureGameR\n\n' + body;
  }
  return body;
}

// ── 生成器：TextureAtlas.h（app native 编译单元） ──

/** 生成 C++ 图集头文件（MAP_SPRITES 与 Kotlin 侧同源） */
function generateTextureAtlasH(layout) {
  const si = semanticIndices(layout);
  const namePad = Math.max(...layout.mapSprites.map((s) => s.name.length));
  const spriteLines = layout.mapSprites
    .map((s) => `    { "${s.name}",${' '.repeat(namePad - s.name.length + 1)}${s.rect[0]}, ${s.rect[1]}, ${s.rect[2]}, ${s.rect[3]} },`)
    .join('\n');

  return [
    '// ============================================================',
    '// GENERATED FILE — 由 scripts/build-atlas.mjs 自动生成，禁止手改。',
    '// 图集布局源数据位于 build-atlas.mjs 的 LAYOUT 常量，运行',
    '//   cd android && node scripts/build-atlas.mjs --codegen',
    '// 重新生成。',
    '// ============================================================',
    '#pragma once',
    '',
    '#include "Rhi.h"',
    '#include <unordered_map>',
    '#include <string>',
    '',
    '// ============================================================',
    '// TextureAtlas — 精灵图集',
    '// 将所有精灵打包到一张 ASTC 纹理中，通过名称查询 UV 坐标',
    '// ============================================================',
    '',
    'struct AtlasRegion {',
    '    float u0, v0, u1, v1;  // 归一化 UV 坐标 [0,1]',
    '    int pixelW, pixelH;     // 原始像素尺寸',
    '};',
    '',
    'struct SpriteDef {',
    '    const char* name;',
    '    int x, y, w, h;         // 图集中的像素位置（原始图集坐标）',
    '};',
    '',
    'class TextureAtlas {',
    'public:',
    '    TextureAtlas() = default;',
    '    ~TextureAtlas() = default;',
    '',
    '    // 定义图集布局（描述每张精灵在图集中的像素位置）',
    '    void defineAtlas(int totalWidth, int totalHeight,',
    '                     const SpriteDef* sprites, int count);',
    '',
    '    // 查询 UV 坐标',
    '    const AtlasRegion* getRegion(const char* name) const;',
    '',
    '    // 获取图集尺寸',
    '    int width() const { return m_width; }',
    '    int height() const { return m_height; }',
    '',
    'private:',
    '    int m_width = 0;',
    '    int m_height = 0;',
    '    std::unordered_map<std::string, AtlasRegion> m_regions;',
    '};',
    '',
    '// ============================================================',
    '// 地图精灵图集布局定义',
    '// 单张 2048×2048 ASTC 图集，包含所有地图精灵',
    '// ============================================================',
    '',
    '// 地面（64×64）',
    `#define TILE_SIZE ${layout.tileSize}`,
    `#define TREE_SIZE ${layout.tileSize * 2}`,
    '',
    '// 图集总尺寸',
    `#define ATLAS_W ${layout.atlasW}`,
    `#define ATLAS_H ${layout.atlasH}`,
    '',
    '// 建筑精灵尺寸（所有建筑统一为 128×128）',
    `#define BUILDING_W ${layout.buildingSize}`,
    `#define BUILDING_H ${layout.buildingSize}`,
    '',
    '// ============================================================',
    '// 双端共享渲染常量（与 SpriteAtlasDef.kt 同源生成，禁止手改）',
    '// ============================================================',
    `#define DECOR_QUALITY_THRESHOLD ${layout.lodThreshold}f`,
    `#define SHADOW_OFFSET_TILES ${layout.shadowOffsetTiles}f`,
    `#define SHADOW_ALPHA ${layout.shadowAlpha}f`,
    `#define TOPDOWN_Y_SCALE ${layout.topdownYScale}f`,
    '',
    '// 语义索引（由 LAYOUT 名称推导生成，防建筑/地砖列表调整后索引漂移）',
    `#define SPIRIT_MINE_NAME_INDEX ${si.spiritMine}`,
    `#define SPIRIT_FIELD_NAME_INDEX ${si.spiritField}`,
    '',
    '// 瓦片类型索引（与 SpriteAtlasDef.TileType.index 同源）',
    `#define TILE_GROUND ${si.tileGround}`,
    `#define TILE_BUILDING ${si.tileBuilding}`,
    `#define TILE_TYPE_COUNT ${si.tileTypeCount}`,
    '',
    '// 瓦片分类（与 SpriteAtlasDef 的 DECOR_TILE_*_INDEX / TILE_SPRITE_W/H / TILE_OBJECT_LAYER 同源生成）——',
    '// 装饰叠加层判定 + 显示尺寸（格，小数格；锚点 = 格底边居中）+ 绘制层',
    '//（0=地面层随地面逐格绘制，1=立体层与建筑同序 Y 归并绘制），',
    '// 禁止在渲染代码里硬编码瓦片序号（新增装饰种类只改 LAYOUT.tiles）',
    `#define DECOR_TILE_MIN ${si.decorMin}`,
    `#define DECOR_TILE_MAX ${si.decorMax}`,
    `#define DECOR_MAX_SPRITE_W ${cppFloatLiteral(si.decorMaxW)}`,
    `#define DECOR_MAX_SPRITE_H ${cppFloatLiteral(si.decorMaxH)}`,
    '// 装饰越界余量（格）：左右各 (maxW−1)/2 上取整、向上 maxH−1 上取整——',
    '// 可见性遍历范围必须按此扩大，否则越界精灵（树冠）会被整块漏绘',
    `#define DECOR_MARGIN_COLS ${si.decorMarginCols}`,
    `#define DECOR_MARGIN_ROWS ${si.decorMarginRows}`,
    `static const float TILE_SPRITE_W[] = {${si.tileSpriteW.map(cppFloatLiteral).join(', ')}};`,
    `static const float TILE_SPRITE_H[] = {${si.tileSpriteH.map(cppFloatLiteral).join(', ')}};`,
    `static const int TILE_OBJECT_LAYER[] = {${si.objectLayer.join(', ')}};`,
    '',
    '// 固定结构（渲染走建筑层；nameIdx = STRUCTURE_NAME_BASE + index；占地表供底部对齐）',
    `#define STRUCTURE_NAME_BASE ${si.structureNameBase}`,
    `static const int STRUCTURE_FP_W[] = {${layout.structures.map((s) => s.footprint[0]).join(', ')}};`,
    `static const int STRUCTURE_FP_H[] = {${layout.structures.map((s) => s.footprint[1]).join(', ')}};`,
    '// 地面草皮变体索引（渲染器把变体格映射到自身地面纹理）',
    `static const int GROUND_VARIANT_COUNT = ${si.groundVariants.length};`,
    `static const int GROUND_VARIANTS[] = {${si.groundVariants.join(', ')}};`,
    '',
    'static const SpriteDef MAP_SPRITES[] = {',
    spriteLines,
    '};',
    '',
    'static constexpr int MAP_SPRITE_COUNT =',
    '    sizeof(MAP_SPRITES) / sizeof(MAP_SPRITES[0]);',
    '',
  ].join('\n');
}

// ── 生成器：scene_uv_tables.h（cpp/scene/，R3.2/B10 场景常量进 C++） ──

/**
 * C++ 浮点除法表达式（UV 归一化）：`x.0f / 4096.0f` 形态。
 *
 * 生成**表达式**而非预计算字面量——图集尺寸为 2 的幂，除法在二进制浮点下
 * 精确（指数移位无舍入），Kotlin `r.x.toFloat() / ATLAS_W` 与 C++ 编译期
 * 常量折叠逐位一致（constexpr 浮点按抽象机器语义求值，禁止收缩）。
 */
function cppUvExpr(pixel, atlasDim) {
  return `${cppFloatLiteral(pixel)} / ${cppFloatLiteral(atlasDim)}`;
}

/** UV 四元组行（u0, v0, u1, v1 = rect 归一化，与 SpriteAtlasDef 同式） */
function cppUvQuadLine(rect, atlasW, atlasH, indent) {
  return `${indent}${cppUvExpr(rect[0], atlasW)}, ${cppUvExpr(rect[1], atlasH)}, ` +
    `${cppUvExpr(rect[0] + rect[2], atlasW)}, ${cppUvExpr(rect[1] + rect[3], atlasH)},`;
}

/**
 * Tier1 文本资产的 C++ 常量段（R3.8/B13）——UV 表 + 索引常量 + 样式档。
 *
 * 与既有 UV 表同式：`kUvExpr` 除法表达式（图集尺寸 2 的幂 ⇒ 双端逐位一致）。
 * 词条文本以 **C++ 字符串字面量数组** 生成（UTF-8 源文件字面量）——供调试
 * 打印与守卫断言使用；运行期**不**消费文本（按索引直取 UV，零字符串比较）。
 */
function tier1CppLines(layout) {
  const { entries } = tier1Entries(layout);
  const t1 = layout.tier1;
  const w = layout.atlasW;
  const h = layout.atlasH;
  const out = [];

  out.push('// ── Tier1 文本资产（R3.8/B13：浮动文字预烘焙 sprite——数字/拉丁/标点/');
  out.push('//    有限固定游戏词条；运行期按资产索引直取 UV，零动态字形光栅化、零每帧 JNI）──');
  out.push('// 资产索引序 = 词条段（LAYOUT.tier1.words 声明序）→ 单字形段（glyphs 声明序）。');
  out.push('// ★ 本表即「Tier1 词表冻结清单」：新增词条须在 LAYOUT.tier1 显式加一行并重跑');
  out.push('//   node scripts/build-atlas.mjs --codegen（Tier2 动态 CJK 字形明确不在本批）。');
  out.push(`inline constexpr int kFloatWordCount = ${t1.words.length};`);
  out.push(`inline constexpr int kFloatGlyphCount = ${t1.glyphs.length};`);
  out.push(`inline constexpr int kFloatAssetCount = ${entries.length};`);
  out.push(`inline constexpr int kFloatGlyphBaseIndex = ${t1.words.length};`);
  // 词条/字形文本（UTF-8 字面量数组；索引 = 资产索引）
  out.push('// 词条文本（索引 0..kFloatWordCount-1；仅调试/守卫用，运行期按索引取 UV）');
  out.push(`inline constexpr const char* kFloatWordText[] = {${t1.words.map((s) => `"${s}"`).join(', ')}};`);
  out.push('// 单字形文本（索引 kFloatGlyphBaseIndex..；如上）');
  out.push(`inline constexpr const char* kFloatGlyphText[] = {${t1.glyphs.map((s) => `"${s}"`).join(', ')}};`);
  // 字数表（词条渲染时按字数取格子——本批词条均为 2 字，表仍逐条列出以便扩展）
  out.push('// 词条字数（渲染宽度换算：宽 = 字数 × 字宽；本批恒为 2）');
  out.push(`inline constexpr int kFloatWordLength[] = {${t1.words.map((s) => s.length).join(', ')}};`);
  // 几何常量（格高/字形缩比——供 C++ 动画与尺寸换算消费）
  out.push('// 几何：格高（世界单位换算基准）+ 字形 ink 高 / 格高 的缩比');
  out.push(`inline constexpr int kFloatCellW = ${t1.wordCellW};`);
  out.push(`inline constexpr int kFloatCellH = ${t1.rowH};`);
  out.push(`inline constexpr float kFloatGlyphScale = ${cppFloatLiteral(t1.glyphScale)};`);
  // UV 表（资产索引序）
  out.push('');
  out.push('// Tier1 资产 UV 表（[u0,v0,u1,v1] × kFloatAssetCount，索引 = 资产索引序）');
  out.push('inline constexpr float kFloatUv[] = {');
  for (const e of entries) out.push(cppUvQuadLine(e.rect, w, h, '    '));
  out.push('};');
  // 样式档
  out.push('');
  out.push('// Tier1 浮字样式档位索引（R3.8/B13：颜色/透明度在 C++ 浮字池内按档位查表，');
  out.push('//    字形素材为白色——避免为每种颜色各烘焙一份。双端共享，防索引漂移）');
  out.push(`inline constexpr int kFloatStyleCount = ${t1.styleCount};`);
  for (const s of t1.styles) {
    out.push(`// ${s.note}`);
    out.push(`inline constexpr int ${s.cpp} = ${s.v};`);
  }
  return out;
}

/**
 * 生成 C++ 场景常量表（native-renderer SceneStore 路径的 UV/占地/渲染常量
 * 单一来源——Kotlin 不再每帧传 SpriteAtlasDef 数组）。
 *
 * 命名用 k-前缀（namespace scene 成员）：与 TextureAtlas.h 的同名宏
 * （DECOR_QUALITY_THRESHOLD 等）-token 不冲突——NativeBridge.cpp 同时包含
 * 两个头时宏替换不会污染 scene:: 限定名。
 *
 * 布局源数据 = LAYOUT（与 SpriteAtlasDef.kt / TextureAtlas.h 同一权威）：
 *   - kTileUv/kBuildingUv/kCropUv/kCloudUv/kRoadUv：五张 UV 表，
 *     与 Kotlin SpriteAtlasDef.TILE_UV_MAP/BUILDING_UV_MAP/CROP_UV_MAP/
 *     CLOUD_UV_MAP/ROAD_UV_MAP 同式同序（建筑表尾部追加固定结构，同 Kotlin）；
 *   - kFootprintW/H + kStructureFpW/H：占地尺寸表（footprint_table.h 同源）；
 *   - 其余：双端共享渲染常量 + 瓦片分类表（TextureAtlas.h 同源）。
 */
function generateSceneUvTablesH(layout) {
  const si = semanticIndices(layout);
  const w = layout.atlasW;
  const h = layout.atlasH;

  const tableBlock = (name, count, rows, comment) => {
    const lines = rows.map((r) => cppUvQuadLine(r, w, h, '    '));
    return [
      comment,
      `inline constexpr float ${name}[] = {`,
      ...lines,
      '};',
      `inline constexpr int ${name.replace(/Uv$/, 'UvCount')} = ${count};`,
    ];
  };

  const tileRows = [...layout.tiles]
    .sort((a, b) => a.index - b.index)
    .map((t) => t.rect);
  const buildingRows = layout.buildingNames.map((_, i) => {
    const r = buildingRectOf(layout.buildingColsPerRow, i);
    return [r.x, r.y, r.w, r.h];
  });
  const structureRows = layout.structures.map((s) => s.rect);
  const cropRows = layout.crops.map((c) => c.rect);
  const cloudRows = layout.clouds.map((c) => c.rect);
  const roadRows = layout.roads.map((r) => r.rect);

  return [
    '// ============================================================',
    '// GENERATED FILE — 由 scripts/build-atlas.mjs 自动生成，禁止手改。',
    '// 图集布局源数据位于 build-atlas.mjs 的 LAYOUT 常量，运行',
    '//   cd android && node scripts/build-atlas.mjs --codegen',
    '// 重新生成。仓库内生成物（与 shaders.h 同策略）：桌面 GTest 与',
    '// Android NDK 构建无需先跑 codegen 即可编译。',
    '// ============================================================',
    '#pragma once',
    '',
    '#include <cstdint>',
    '',
    '// ============================================================',
    '// SceneStore 路径场景常量（重构方案 2026-09-17 R3.2/B10）——',
    '// Kotlin 不再每帧传 SpriteAtlasDef 数组，UV 表由本头同源生成进 C++。',
    '// 值与 Kotlin SpriteAtlasDef（TILE_UV_MAP 等）逐位一致：图集尺寸为',
    '// 2 的幂，UV 除法在二进制浮点下精确（无舍入），双端同式同值。',
    '// ============================================================',
    'namespace scene {',
    '',
    `inline constexpr int kAtlasW = ${w};`,
    `inline constexpr int kAtlasH = ${h};`,
    '',
    '// UV 向内收缩 0.5 texel（防 CLAMP_TO_EDGE + NEAREST 邻居渗色；',
    '// 与旧 NativeBridge.cpp UV_EPSILON 同式同值）',
    `inline constexpr float kUvEpsilon = ${cppUvExpr(0.5, w)};`,
    '',
    '// ── UV 表（归一化；[u0,v0,u1,v1] × N，与 SpriteAtlasDef 同源同序）──',
    ...tableBlock('kTileUv', si.tileTypeCount, tileRows,
      '// 瓦片 UV（按 TileType.index 直取；TILE_BUILDING 占位复刻 GROUND rect，同 Kotlin）'),
    ...tableBlock('kBuildingUv', layout.buildingNames.length + layout.structures.length,
      [...buildingRows, ...structureRows],
      '// 建筑 UV + 固定结构尾部（nameIdx = 建筑序；结构 nameIdx = kStructureNameBase + 序，同 Kotlin BUILDING_UV_MAP）'),
    ...tableBlock('kCropUv', layout.crops.length, cropRows,
      '// 灵田作物三阶段 UV（按阶段序直取）'),
    ...tableBlock('kCloudUv', layout.clouds.length, cloudRows,
      '// 云层精灵 UV（按 LAYOUT.clouds 声明序直取）'),
    ...tableBlock('kRoadUv', layout.roads.length, roadRows,
      '// 石板道路 UV（按 LAYOUT.roads 声明序直取；emitRoadDrawOps 的 sprite 下标直取）'),
    '',
    '// ── 双端共享渲染常量（与 SpriteAtlasDef.kt / TextureAtlas.h 同源）──',
    `inline constexpr float kDecorQualityThreshold = ${cppFloatLiteral(layout.lodThreshold)};`,
    `inline constexpr float kShadowOffsetTiles = ${cppFloatLiteral(layout.shadowOffsetTiles)};`,
    `inline constexpr float kShadowAlpha = ${cppFloatLiteral(layout.shadowAlpha)};`,
    `inline constexpr float kTopdownYScale = ${cppFloatLiteral(layout.topdownYScale)};`,
    '',
    '// ── 世界叠加层（overlay）视觉常量（R3.3/B11：网格线/预览框/选中/拆除高亮的',
    '//    颜色、不透明度、线宽——C++ buildOverlayLayers 生成**四类**几何消费本表；',
    '//    Kotlin 侧 Canvas 兜底手绘消费同源常量，两路同值由构造保证）──',
    ...overlayCppLines(layout),
    '',
    '// ── 瓦片分类（装饰区间/显示尺寸/绘制层/越界余量——绘制核心按表直取，',
    '//    禁止在渲染代码硬编码瓦片序号）──',
    `inline constexpr int kTileGround = ${si.tileGround};`,
    `inline constexpr int kTileTypeCount = ${si.tileTypeCount};`,
    `inline constexpr int kDecorTileMin = ${si.decorMin};`,
    `inline constexpr int kDecorTileMax = ${si.decorMax};`,
    '// 装饰越界余量（格）：左右各 (maxW−1)/2 上取整、向上 maxH−1 上取整',
    `inline constexpr int kDecorMarginCols = ${si.decorMarginCols};`,
    `inline constexpr int kDecorMarginRows = ${si.decorMarginRows};`,
    `inline constexpr float kTileSpriteW[] = {${si.tileSpriteW.map(cppFloatLiteral).join(', ')}};`,
    `inline constexpr float kTileSpriteH[] = {${si.tileSpriteH.map(cppFloatLiteral).join(', ')}};`,
    `inline constexpr int kTileObjectLayer[] = {${si.objectLayer.join(', ')}};`,
    '// 地面草皮变体索引（渲染器把变体格映射到自身地面纹理）',
    `inline constexpr int kGroundVariantCount = ${si.groundVariants.length};`,
    `inline constexpr int kGroundVariants[] = {${si.groundVariants.join(', ')}};`,
    '',
    '// ── 占地尺寸表（footprint_table.h 同源：建筑按 nameIdx；固定结构单列）──',
    `inline constexpr int kFootprintW[] = {${layout.footprints.map((f) => f[0]).join(', ')}};`,
    `inline constexpr int kFootprintH[] = {${layout.footprints.map((f) => f[1]).join(', ')}};`,
    `inline constexpr int kStructureNameBase = ${si.structureNameBase};`,
    `inline constexpr int kStructureFpW[] = {${layout.structures.map((s) => s.footprint[0]).join(', ')}};`,
    `inline constexpr int kStructureFpH[] = {${layout.structures.map((s) => s.footprint[1]).join(', ')}};`,
    '',
    ...tier1CppLines(layout),
    '',
    '}  // namespace scene',
    '',
  ].join('\n');
}

// ── 注册源数据加载 ──

/** 读取 resource-registry.json（校验结构完整性） */
function loadRegistry() {
  const raw = JSON.parse(fs.readFileSync(REGISTRY_FILE, 'utf8'));
  if (raw.version !== 1 || !Array.isArray(raw.categories) || raw.categories.length === 0) {
    throw new Error(`resource-registry.json 结构异常（version=${raw.version}）`);
  }
  return raw;
}

// ── 精灵清单构建（图集拼装用，顺序 = manifest 顺序 = 守卫测试复现顺序） ──

/** 从 LAYOUT 源数据构建精灵清单：[{ name, x, y, w, h, drawable|null }] */
function buildSpriteList() {
  const sprites = [];

  // 瓦片（TileType 声明顺序；TILE_BUILDING 为 GROUND 同 rect 占位，drawable=null）
  for (const tile of LAYOUT.tiles) {
    sprites.push({
      name: tile.name, x: tile.rect[0], y: tile.rect[1], w: tile.rect[2], h: tile.rect[3],
      drawable: TILE_DRAWABLE[tile.name] ?? null,
    });
  }

  // 建筑（BUILDING_NAMES 顺序，rect 由行分布公式计算；drawable 必须为资源文件名——
  // 为 null 时 KTX/ASTC 图集建筑槽位全空，Vulkan+ASTC 设备建筑不显示）
  for (let i = 0; i < LAYOUT.buildingNames.length; i++) {
    const rect = buildingRectOf(LAYOUT.buildingColsPerRow, i);
    sprites.push({
      name: LAYOUT.buildingNames[i], x: rect.x, y: rect.y, w: rect.w, h: rect.h,
      drawable: BUILDING_DRAWABLE[LAYOUT.buildingNames[i]] ?? null,
    });
  }

  // 作物（CropStage 声明顺序；CROP_DRAWABLE 按 ordinal）
  LAYOUT.crops.forEach((crop, idx) => {
    sprites.push({
      name: crop.name, x: crop.rect[0], y: crop.rect[1], w: crop.rect[2], h: crop.rect[3],
      drawable: CROP_DRAWABLE[idx] ?? null,
    });
  });

  // 固定结构（宗门入口：门楼/阶梯；drawable = key）
  LAYOUT.structures.forEach((st) => {
    sprites.push({
      name: st.key, x: st.rect[0], y: st.rect[1], w: st.rect[2], h: st.rect[3],
      drawable: st.key,
    });
  });

  // 云层精灵（LAYOUT.clouds 声明顺序；drawable = key = 资源文件名）
  LAYOUT.clouds.forEach((c) => {
    sprites.push({
      name: c.name, x: c.rect[0], y: c.rect[1], w: c.rect[2], h: c.rect[3],
      drawable: c.name,
    });
  });

  // 石板道路（LAYOUT.roads 声明顺序；drawable = ROAD_DRAWABLE 资源名）
  LAYOUT.roads.forEach((r) => {
    sprites.push({
      name: r.name, x: r.rect[0], y: r.rect[1], w: r.rect[2], h: r.rect[3],
      drawable: ROAD_DRAWABLE[r.name] ?? null,
    });
  });

  // Tier1 文本资产（R3.8/B13）：词条段 → 单字形段（索引序 = tier1Entries）
  // drawable 为**合成名**（tier1TextDrawable 前缀），图集拼装时由
  // loadSpriteContents 内的 tier1 光栅化钩子产出（不落 drawable-nodpi 资源）。
  for (const e of tier1Entries(LAYOUT).entries) {
    sprites.push({
      name: tier1SpriteName(e),
      x: e.rect[0], y: e.rect[1], w: e.rect[2], h: e.rect[3],
      drawable: tier1SpriteName(e),
    });
  }


  return sprites;
}

// ── 图集拼装（per-sprite 独立 mip + pad 环）──

/** 按名称在 manifest 中查找资源文件绝对路径（feature/game 优先，与双模块放置规则一致） */
function resolveDrawablePath(manifest, name) {
  const matches = manifest.entries.filter((e) => e.name === name);
  if (matches.length === 0) return null;
  const gameEntry = matches.find((e) => e.module === 'feature/game');
  const entry = gameEntry ?? matches[0];
  return path.join(ANDROID_DIR, entry.relPath);
}

// B15 / R6.1：原 `premultiplyRawRgba(data)` 已删除——其唯一消费点是
//   `loadSpriteContents` 的槽位加载内联分支，现已抽为共享实现
//   `lib/atlas-offline-rgba-lib.mjs::loadPremultipliedRgba`（ASTC 图集与离线
//   RGBA 产物共用，保证两侧精灵内容同源）。

/**
 * 解预乘 alpha（预乘 → 直通）：RGB ← RGB × 255/α（α=0 保持 0）。
 *
 * 交付 ASTC 的位图必须是直通 alpha（运行期按 SRC_ALPHA / ONE_MINUS_SRC_ALPHA 混合）。
 * 逐精灵缓冲调用（图集级整图解预乘要遍历 4096² 像素，无谓开销）。
 *
 * @param {Buffer} pngBuffer 预乘 PNG 缓冲
 * @returns {Promise<Buffer>} 直通 alpha PNG 缓冲
 */
async function unpremultiplyAlpha(pngBuffer) {
  const { data, info } = await sharp(pngBuffer)
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  const out = Buffer.allocUnsafe(data.length);
  for (let i = 0; i < data.length; i += 4) {
    const a = data[i + 3];
    if (a === 0 || a === 255) {
      out[i] = data[i];
      out[i + 1] = data[i + 1];
      out[i + 2] = data[i + 2];
    } else {
      out[i] = Math.min(255, Math.round((data[i] * 255) / a));
      out[i + 1] = Math.min(255, Math.round((data[i + 1] * 255) / a));
      out[i + 2] = Math.min(255, Math.round((data[i + 2] * 255) / a));
    }
    out[i + 3] = a;
  }
  return sharp(out, {
    raw: { width: info.width, height: info.height, channels: 4 },
  }).png().toBuffer();
}

/**
 * 逐精灵加载并 lanczos3 下采样到槽位尺寸（mip0 内容缓冲）。
 * 独立 mip 管线第 1 步：每张精灵按目标槽位尺寸单独重采样（mip0），
 * 后续各级 mip 均从该内容重新采样——消灭"整图逐级下采样"的邻居渗色。
 *
 * ★ **预乘 alpha 重采样**（管线不变量，见文件头"预乘 alpha 约定"）：含 alpha 的源图
 *   在**原始分辨率**先预乘、再重采样——直通 alpha 重采样会把透明区的残留 RGB 按普通
 *   RGB 混入邻近可见像素，在硬 alpha 边（岛边缘羽化边等）产生灰白毛边（实测 mip2
 *   边界 texel 由真实覆盖率 ~25% 被抬到 α≈216 / RGB≈204,212,199）。无 alpha 通道的
 *   源图（无缝草皮/道路等）不存在该问题，直接重采样并标记为不透明。
 *
 * @returns {Promise<{contents: Map<string, Buffer>, transparent: Set<string>, srcDims: Map<string, {w: number, h: number}>}>}
 *   图集名 → 槽位尺寸**预乘** PNG 缓冲；transparent = 含半透明像素的精灵名
 *（drawable=null 的占位条目两者皆不含）；srcDims = 图集名 → 素材原始像素尺寸
 *（写入 atlas-manifest.json 的 srcW/srcH，供 SpriteSizingFidelityTest 校验显示比例）
 */
async function loadSpriteContents(sprites, manifest) {
  const contents = new Map();
  const transparent = new Set();
  const srcDims = new Map();
  let loaded = 0;
  // Tier1 文本资产：构建期光栅化（不进 drawable-nodpi，故不走 resolveDrawablePath）
  const tier1BySpriteName = new Map(
    tier1Entries(LAYOUT).entries.map((e) => [tier1SpriteName(e), e])
  );
  let tier1Scale = null;   // 基准字形缩放因子（惰性计算一次，全部格共用）
  for (const s of sprites) {
    if (!s.drawable) continue;
    // ── Tier1 文本资产分支（R3.8/B13）──
    const tier1Entry = tier1BySpriteName.get(s.name);
    if (tier1Entry) {
      if (tier1Scale === null) tier1Scale = await tier1GlyphScale(LAYOUT);
      const slotPng = await rasterizeTier1Cell(LAYOUT, tier1Entry, tier1Scale);
      contents.set(s.name, slotPng);
      // 字形素材含大量半透明抗锯齿边 → 标记透明（走预乘路径，避免灰白毛边）
      transparent.add(s.name);
      srcDims.set(s.name, { w: LAYOUT.tier1.wordCellW, h: LAYOUT.tier1.rowH });
      loaded++;
      continue;
    }
    const file = resolveDrawablePath(manifest, s.drawable);
    if (!file || !fs.existsSync(file)) {
      // fail-fast：预期精灵缺失 = 构建失败，
      // 禁止静默产出缺精灵图集（线上地图透明/建筑隐形且无运行时错误）。
      // 允许缺省的条目（TILE_BUILDING 占位）已由上层 `if (!s.drawable) continue` 跳过
      throw new Error(
        `资源缺失: ${s.drawable} (${s.name})——精灵图必须双模块放置（rules/static-resources.md），缺失即构建失败`
      );
    }
    // B15：槽位加载口径抽到 lib/atlas-offline-rgba-lib.mjs 的 loadPremultipliedRgba
    //   ——离线 RGBA 产物与本 ASTC 图集**共用同一加载实现**，「两条图集路径的
    //   精灵内容同源」成为结构事实（不再依赖两侧逐次比对）。语义与原先内联实现
    //   逐字等价：无 alpha 源免预乘往返；有 alpha 源先预乘再 lanczos3 缩放。
    const { pm, width, height, transparent: hasAlpha } = await loadPremultipliedRgba(file);
    srcDims.set(s.name, { w: width, h: height });
    if (hasAlpha) transparent.add(s.name);
    const slotPng = await sharp(pm, {
      raw: { width, height, channels: 4 },
    })
      .resize(s.w, s.h, { fit: 'fill', kernel: sharp.kernel.lanczos3 })
      .png()
      .toBuffer();
    contents.set(s.name, slotPng);
    loaded++;
  }
  console.log(`  精灵加载: ${loaded}/${sprites.length}（含半透明 ${transparent.size}）`);
  return { contents, transparent, srcDims };
}

/**
 * 构建单精灵 pad 环缓冲（独立 mip 管线第 2 步）：内容四周外扩 mipPad（mip0 尺度 4px），
 * 环内容 = 该精灵**边缘像素外扩**（1px 边条复制 + 角部 1×1 像素复制，replicate 语义）——
 * 非透明/纯色填充，保证 mip 采样边界渐变连续（Unity SpriteAtlas paddingPower 同款意图）。
 *
 * ★ 输入与输出均为**预乘**缓冲（预乘空间做复制/重采样，语义一致）。
 * ★ 拉伸核必须 nearest（精确复制）：默认 lanczos3 在 1→4 上采样时仍做低通
 * （kernel 支撑窗覆盖邻行），会把邻行颜色/alpha 混入边条，破坏"环 ≡ 边缘"的
 * 逐像素等价（验收脚本 pad-vs-edge 会假阳性）。
 *
 * @param {Buffer} content 槽位尺寸**预乘**内容缓冲
 * @param {number} w 内容宽（= 槽位宽）
 * @param {number} h 内容高
 * @param {number} pad 环厚（mip0 尺度，= LAYOUT.mipPad）
 * @returns {Promise<Buffer>} (w+2pad)×(h+2pad) **预乘** PNG 缓冲
 */
async function buildPaddedTile(content, w, h, pad) {
  const strip = (rect, tw, th) =>
    sharp(content).extract(rect).resize(tw, th, { fit: 'fill', kernel: 'nearest' }).png().toBuffer();
  const [leftCol, rightCol, topRow, bottomRow, tl, tr, bl, br] = await Promise.all([
    strip({ left: 0, top: 0, width: 1, height: h }, pad, h),
    strip({ left: w - 1, top: 0, width: 1, height: h }, pad, h),
    strip({ left: 0, top: 0, width: w, height: 1 }, w, pad),
    strip({ left: 0, top: h - 1, width: w, height: 1 }, w, pad),
    strip({ left: 0, top: 0, width: 1, height: 1 }, pad, pad),
    strip({ left: w - 1, top: 0, width: 1, height: 1 }, pad, pad),
    strip({ left: 0, top: h - 1, width: 1, height: 1 }, pad, pad),
    strip({ left: w - 1, top: h - 1, width: 1, height: 1 }, pad, pad),
  ]);
  return sharp({
    create: { width: w + pad * 2, height: h + pad * 2, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
  }).composite([
    { input: topRow, left: pad, top: 0 },
    { input: bottomRow, left: pad, top: h + pad },
    { input: leftCol, left: 0, top: pad },
    { input: rightCol, left: w + pad, top: pad },
    { input: tl, left: 0, top: 0 },
    { input: tr, left: w + pad, top: 0 },
    { input: bl, left: 0, top: h + pad },
    { input: br, left: w + pad, top: h + pad },
    { input: content, left: pad, top: pad },
  ]).png().toBuffer();
}

/**
 * 合成单级 mip 位图（独立 mip 管线第 3 步）：逐精灵把 pad 环缓冲重采样到该级尺寸
 * （从 mip0 pad 环缓冲**重新采样**，避免级联失真——与现状"每级从 mip0 重采样"同一理由），
 * 再按缩小后槽位拼入该级位图。
 *
 * pad 语义：环在 mip0 尺度为 mipPad(4px)，随精灵等比缩放（第 k 级 pad =
 * max(1, round(4/2^k))）——环与内容一起重采样保证边缘梯度连续；槽位间距
 * mipGutter(8) ≥ 相邻精灵环厚之和（4+4=8），环只占 gutter 不压内容。
 *
 * 构建器自检（fail-fast）：该级精灵数必须与精灵清单一致、槽位几何 (w>>k,h>>k) 合法。
 *
 * ★ **预乘 alpha 约定**：入参 pad 环缓冲在预乘空间（重采样数学正确——透明像素的残留
 *   RGB 不参与混色）；逐精灵缓冲在拼入位图前 `unpremultiplyAlpha()` 解回直通 alpha，
 *   整级位图与最终 ASTC 均为直通 alpha（与运行期 SRC_ALPHA 混合一致）。
 *
 * @param {Map<string, Buffer>} padded 图集名 → mip0 pad 环缓冲（buildPaddedTile 产物，预乘）
 * @param {Set<string>} transparent 含半透明像素的精灵名（其余全不透明，免解预乘）
 * @param {Array} sprites 精灵清单（buildSpriteList 产物）
 * @param {number} level mip 级（0 = mip0）
 * @param {number} atlasSize 该级位图边长（= atlasW >> level）
 * @param {string} outPng 输出 PNG 路径（直通 alpha）
 */
async function composeMipLevel(padded, transparent, sprites, level, atlasSize, outPng) {
  const padK = Math.max(1, Math.round(LAYOUT.mipPad / 2 ** level));
  const overlays = [];
  for (const s of sprites) {
    const tile = padded.get(s.name);
    if (!tile) continue; // drawable=null 占位条目
    // 槽位几何：内容 = slot>>k（<1 钳到 1——ASTC 4×4 块下限沿用 max() 逻辑，
    // 深级 mip 亚像素精灵仍贡献均值色，与"该级精灵数 == 上一级"守恒一致）
    const w = Math.max(1, s.w >> level);
    const h = Math.max(1, s.h >> level);
    if (w < 1 || h < 1) throw new Error(`精灵 ${s.name} mip${level} 下采样尺寸非法: ${w}×${h}`);
    const tw = w + padK * 2;
    const th = h + padK * 2;
    const tileBuf = level === 0 ? tile
      : await sharp(tile).resize(tw, th, { fit: 'fill', kernel: sharp.kernel.lanczos3 }).png().toBuffer();
    // 位置：内容槽位 (x>>k, y>>k)，环外扩 padK；图集边缘精灵的越界环部分裁剪
    //（CLAMP_TO_EDGE 采样器下边界外无采样需求）
    let left = (s.x >> level) - padK;
    let top = (s.y >> level) - padK;
    const cutL = Math.max(0, -left);
    const cutT = Math.max(0, -top);
    const cutR = Math.max(0, left + tw - atlasSize);
    const cutB = Math.max(0, top + th - atlasSize);
    let buf = tileBuf;
    if (cutL || cutT || cutR || cutB) {
      if (tw - cutL - cutR < 1 || th - cutT - cutB < 1) {
        throw new Error(`精灵 ${s.name} mip${level} pad 环裁剪后为空（槽位越界）`);
      }
      buf = await sharp(tileBuf)
        .extract({ left: cutL, top: cutT, width: tw - cutL - cutR, height: th - cutT - cutB })
        .png().toBuffer();
      left += cutL;
      top += cutT;
    }
    // 拼入位图前解预乘：位图与最终 ASTC 都是直通 alpha（全不透明精灵免此步）
    if (transparent.has(s.name)) buf = await unpremultiplyAlpha(buf);
    overlays.push({ input: buf, left, top });
  }
  // fail-fast：该级精灵数 == 精灵清单可绘制数（上一级同源枚举，恒等即守恒）
  const expected = sprites.filter((s) => s.drawable).length;
  if (overlays.length !== expected) {
    throw new Error(`mip${level} 精灵数守恒失败: ${overlays.length} != ${expected}`);
  }
  // 位图为直通 alpha（各精灵已在上方解预乘；画布透明，'over' 合成即值透传）
  await sharp({
    create: { width: atlasSize, height: atlasSize, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
  })
    .composite(overlays)
    .png()
    .toFile(outPng);
}

// ── astcenc 压缩 ──

/** 探测可用 astcenc 可执行文件（avx2 → sse4.1 → sse2） */
function findAstcenc() {
  if (!fs.existsSync(ASTCENC_DIR)) return null;
  for (const name of ['astcenc-avx2.exe', 'astcenc-sse4.1.exe', 'astcenc-sse2.exe']) {
    const p = path.join(ASTCENC_DIR, name);
    if (fs.existsSync(p)) return p;
  }
  return null;
}

/**
 * 运行 astcenc（-cl <in> <out> 4x4 -medium，LDR——5.x 位置式参数）。
 * @param {string} inPng 输入 PNG 路径
 * @param {string} outAstc 输出 .astc 路径
 */
function compressAstc(astcenc, inPng, outAstc) {
  execFileSync(astcenc, ['-cl', inPng, outAstc, '4x4', '-medium'], { stdio: 'inherit' });
}

/**
 * 生成图集 mip 链（**per-sprite 独立 mip + pad 环**）。
 *
 * 注意：astcenc 5.7.0 **无 mipmap 生成开关**（设计稿"astcenc -m"假设不成立），
 * 故 mip 链在构建期逐级合成：
 *   1. 每张精灵单独 lanczos3 下采样到槽位尺寸（mip0 内容）；
 *   2. 每张精灵内容四周外扩 mipPad 环（边缘像素复制，replicate）；
 *   3. 每级 mip k：逐精灵 pad 环缓冲独立重采样至 (slot>>k + 2·pad_k)，按缩小后
 *      槽位拼入 (4096>>k) 级位图——**每级从 mip0 重新采样，避免级联失真**；
 *   4. 逐级 astcenc 压缩到 4×4（ASTC 4×4 块下限），KTX1 容器结构不变。
 *
 * 该方式消灭整图 mip 的邻居渗色：mip ≥1 级的精灵边界像素全部来自自身内容外扩
 * （pad 环），与 Unity SpriteAtlas paddingPower / Godot use_texture_padding 同语义。
 *
 * @param {Function} astcenc astcenc 可执行路径
 * @param {Array} sprites 精灵清单
 * @param {Map<string, Buffer>} padded 图集名 → mip0 pad 环缓冲（buildPaddedTile 产物）
 * @returns {Promise<Array<{w:number,h:number,data:Buffer}>>} 每级 mip 的 ASTC 数据
 */
async function generateMips(astcenc, transparent, sprites, padded) {
  const mips = [];
  let size = LAYOUT.atlasW;
  let level = 0;
  // 4096 → 4（ASTC 4×4 块下限）：每级最长边 / 2，到 4×4 为止
  while (size >= ASTC_BLOCK) {
    const mipPng = TMP_PNG.replace('atlas_tmp.png', `atlas_mip_${level}.png`);
    const mipAstc = TMP_ASTC.replace('atlas_tmp.astc', `atlas_mip_${level}.astc`);
    console.log(`  mip ${level}: ${size}×${size}（per-sprite 独立合成）...`);
    await composeMipLevel(padded, transparent, sprites, level, size, mipPng);
    compressAstc(astcenc, mipPng, mipAstc);
    mips.push({ w: size, h: size, data: fs.readFileSync(mipAstc) });
    fs.unlinkSync(mipAstc);
    // ATLAS_KEEP_TMP=1 保留各级 PNG（调试验收脚本用，默认清理）
    if (process.env.ATLAS_KEEP_TMP !== '1') fs.unlinkSync(mipPng);
    level++;
    const next = size >> 1;
    if (next < ASTC_BLOCK) break;  // 到 4×4 块下限即止
    size = next;
  }
  console.log(`  mip 链: ${mips.length} 级（per-sprite + pad 环，mip0 ${LAYOUT.atlasW}→ 最小 ${mips[mips.length - 1].w}）`);
  return mips;
}

// ── KTX1 封装 ──

/**
 * KTX1 多 mip 压缩纹理封装（ASTC 4×4；输入为各级 mip 的 .astc 数据）。
 * B.1：numberOfMipmapLevels = mips.length；数据区 = 逐级 [imageSize 4 字节][数据]。
 * .astc 文件头布局因 astcenc 版本有差异（16/20 字节），但数据区恒为
 * 文件尾部的几何计算尺寸（w/4 × h/4 × 16）——按尾部截取数据，头大小
 * 校验在合理范围（≤32 字节）即可，避免解析头字段的版本兼容问题。
 *
 * @param {Array<{w:number,h:number,data:Buffer}>} mips 每级 mip 数据（mip0 最大在前）
 */
function wrapKtx1(mips) {
  if (!Array.isArray(mips) || mips.length === 0) throw new Error('wrapKtx1: mips 为空');
  const widths = [];  // 各级宽度（KtxLoader 复现几何校验用——头仅存 mip0 宽高）

  const perLevel = mips.map((m, i) => {
    const expected = Math.floor(m.w / ASTC_BLOCK) * Math.floor(m.h / ASTC_BLOCK) * ASTC_BLOCK_BYTES;
    if (m.data.length < expected + 16) throw new Error(`ASTC 文件过短 (mip ${i})`);
    const headerSize = m.data.length - expected;
    if (headerSize > 32) throw new Error(`ASTC 头尺寸异常: ${headerSize} (mip ${i})`);
    const data = m.data.subarray(headerSize);
    if (data.length !== expected) throw new Error(`ASTC 数据尺寸不符: ${data.length} != ${expected} (mip ${i})`);
    widths.push(m.w);
    return { size: expected, data };
  });

  const header = Buffer.alloc(64);
  header[0] = 0xAB; header[1] = 0x4B; header[2] = 0x54; header[3] = 0x58; // "«KTX"
  header[4] = 0x20; header[5] = 0x31; header[6] = 0x31; header[7] = 0xBB; // " 11»"
  header.writeUInt32LE(0x04030201, 8); // endianness（小端）
  header.writeUInt32LE(0, 12); // glType（压缩纹理 = 0）
  header.writeUInt32LE(1, 16); // glTypeSize
  header.writeUInt32LE(0, 20); // glFormat（压缩纹理 = 0）
  header.writeUInt32LE(GL_COMPRESSED_RGBA_ASTC_4x4_KHR, 24); // glInternalFormat
  header.writeUInt32LE(GL_RGBA, 28); // glBaseInternalFormat
  header.writeUInt32LE(mips[0].w, 32); // pixelWidth（mip0）
  header.writeUInt32LE(mips[0].h, 36); // pixelHeight（mip0）
  header.writeUInt32LE(0, 40); // pixelDepth
  header.writeUInt32LE(0, 44); // numberOfArrayElements
  header.writeUInt32LE(1, 48); // numberOfFaces
  header.writeUInt32LE(mips.length, 52); // numberOfMipmapLevels（B.1）
  header.writeUInt32LE(0, 56); // bytesOfKeyValueData

  const bodies = perLevel.map((pl) => {
    const sizeField = Buffer.alloc(4);
    sizeField.writeUInt32LE(pl.size, 0);
    return Buffer.concat([sizeField, pl.data]);
  });
  return Buffer.concat([header, ...bodies]);
}

// ── 图集布局 manifest ──

/** 布局哈希：sprites 清单的 sha256 前缀（守卫测试用 SpriteAtlasDef 数据复现比对） */
function layoutHashOf(sprites) {
  const canonical = sprites
    .map((s) => `${s.name}:${s.x},${s.y},${s.w},${s.h}`)
    .join('|');
  return crypto.createHash('sha256').update(canonical).digest('hex').slice(0, 16);
}

// ── 模式：codegen ──

/** --atlas-def-only：生成 SpriteAtlasDef.kt 到 core/engine 编译单元（hash 增量跳过） */
async function runAtlasDefCodegen() {
  await validateDisplaySizing(LAYOUT);
  const hash = contentHash({ layout: LAYOUT, buildings: buildingsConfigForHash(), codegen: codegenSource() });
  const hashFile = path.join(ATLAS_DEF_OUT_DIR, '.atlas-def.hash');
  if (!shouldRegenerate(hashFile, hash)) {
    console.log('SpriteAtlasDef.kt 内容未变化，跳过生成');
    return;
  }
  const outFile = path.join(ATLAS_DEF_OUT_DIR, 'com/xianxia/sect/core/render/SpriteAtlasDef.kt');
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, generateSpriteAtlasDef(LAYOUT));
  fs.writeFileSync(hashFile, hash);
  console.log(`生成 SpriteAtlasDef.kt -> ${outFile}`);
}

/** --codegen：生成 SpriteRegistryData.kt + TextureAtlas.h 到 app 编译单元（hash 增量跳过） */
async function runSpriteCodegen() {
  await validateDisplaySizing(LAYOUT);
  const registry = loadRegistry();
  const manifest = ensureManifest(APP_DRAWABLE_DIR, GAME_DRAWABLE_DIR, MANIFEST_OUT);
  const hash = contentHash({
    layout: LAYOUT, buildings: buildingsConfigForHash(), registry,
    manifest: manifestForHash(manifest), codegen: codegenSource(),
  });
  const hashFile = path.join(SPRITE_CODE_OUT_DIR, '.sprite-code.hash');
  if (!shouldRegenerate(hashFile, hash)) {
    console.log('SpriteRegistryData.kt / TextureAtlas.h 内容未变化，跳过生成');
    return;
  }
  fs.mkdirSync(SPRITE_CODE_OUT_DIR, { recursive: true });

  const ktOut = path.join(SPRITE_CODE_OUT_DIR, 'com/xianxia/sect/SpriteRegistryData.kt');
  fs.mkdirSync(path.dirname(ktOut), { recursive: true });
  fs.writeFileSync(ktOut, generateSpriteRegistryData(registry, manifest));
  console.log(`生成 SpriteRegistryData.kt -> ${ktOut}`);

  const hOut = path.join(SPRITE_CODE_OUT_DIR, 'TextureAtlas.h');
  fs.writeFileSync(hOut, generateTextureAtlasH(LAYOUT));
  console.log(`生成 TextureAtlas.h -> ${hOut}`);

  // R3.2/B10：场景 UV 常量表进 cpp/scene/（仓库内生成物，随版本库提交）
  fs.writeFileSync(SCENE_UV_TABLES_OUT, generateSceneUvTablesH(LAYOUT));
  console.log(`生成 scene_uv_tables.h -> ${SCENE_UV_TABLES_OUT}`);

  fs.writeFileSync(hashFile, hash);
}

// ── main ──

async function main() {
  const args = process.argv.slice(2);

  // codegen 模式（不依赖 astcenc/sharp 拼装）
  if (args.includes('--atlas-def-only')) {
    await runAtlasDefCodegen();
    return;
  }
  if (args.includes('--codegen')) {
    await runSpriteCodegen();
    return;
  }

  // 图集模式：拼装 KTX + 布局 manifest（保留原行为）
  console.log('build-atlas: 构建精灵清单（LAYOUT 源数据）...');
  await validateDisplaySizing(LAYOUT);
  const sprites = buildSpriteList();
  const manifest = ensureManifest(APP_DRAWABLE_DIR, GAME_DRAWABLE_DIR, MANIFEST_OUT);

  const astcenc = findAstcenc();
  if (!astcenc) {
    throw new Error('astcenc 未找到（scripts/tools/astcenc/bin/）——请从 https://github.com/ARM-software/astc-encoder/releases 下载 windows-x64 版本');
  }

  console.log(`拼装 ${LAYOUT.atlasW}×${LAYOUT.atlasH} 图集（${sprites.length} 精灵，per-sprite 独立 mip + pad 环）...`);
  // 独立 mip 管线：逐精灵 mip0 内容 → pad 环缓冲（一次构建，各级复用）
  const { contents, transparent, srcDims } = await loadSpriteContents(sprites, manifest);
  const padded = new Map();
  for (const s of sprites) {
    if (!s.drawable) continue;
    padded.set(s.name, await buildPaddedTile(contents.get(s.name), s.w, s.h, LAYOUT.mipPad));
  }

  // per-sprite 多 mip；--no-mip 兜底（单 mip，保持旧结构，供回退/调试——mip0 仍带 pad 环）
  const noMip = args.includes('--no-mip');
  let mips;
  if (noMip) {
    console.log('  --no-mip：单 mip（回退）...');
    await composeMipLevel(padded, transparent, sprites, 0, LAYOUT.atlasW, TMP_PNG);
    compressAstc(astcenc, TMP_PNG, TMP_ASTC);
    mips = [{ w: LAYOUT.atlasW, h: LAYOUT.atlasH, data: fs.readFileSync(TMP_ASTC) }];
    fs.unlinkSync(TMP_ASTC);
  } else {
    console.log('压缩 ASTC + 生成 per-sprite mip 链 ...');
    mips = await generateMips(astcenc, transparent, sprites, padded);
  }

  // B15 / R6.1：同源产出离线 RGBA 降采样产物（Canvas 软渲染 + RGBA 回退臂像素源）
  //   —— 复用 contents（与 ASTC 图集同一份 premultiply + lanczos3 槽位内容），
  //   故两条图集路径的精灵内容同源是结构事实。产物入库，运行时零拼装。
  console.log('产出离线 RGBA 降采样产物（B15：Canvas/回退臂像素源）...');
  const offlineManifest = await writeOfflineRgbaArtifacts(contents, transparent, sprites, OUT_DIR);
  console.log(
    `      离线 RGBA: ${offlineManifest.width}² ${(offlineManifest.rawBytes / 1024 / 1024).toFixed(2)}MB` +
      ` + mip${offlineManifest.mipLevels}级 ${(offlineManifest.mipBytes / 1024 / 1024).toFixed(2)}MB` +
      ` (${offlineManifest.spriteCount} 精灵, frameSha256=${offlineManifest.frameSha256.slice(0, 16)})`
  );

  console.log('封装 KTX1（多 mip）...');
  const ktx = wrapKtx1(mips);
  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.writeFileSync(path.join(OUT_DIR, 'atlas_astc.ktx'), ktx);

  const outManifest = {
    version: 1,
    format: 'ASTC_4x4_LDR',
    width: LAYOUT.atlasW,
    height: LAYOUT.atlasH,
    mipLevels: mips.length,
    // 契约锚点：mip 内容生成方式（per-sprite = 逐精灵独立下采样 +
    //   pad 环；none = --no-mip 单级回退）。AtlasManifestSyncTest 断言防"整图 mip"回退。
    mipMode: noMip ? 'none' : 'per-sprite',
    layoutHash: layoutHashOf(sprites),
    generatedAt: new Date().toISOString(),
    spriteCount: sprites.length,
    sprites: sprites.map((s) => ({
      name: s.name, x: s.x, y: s.y, w: s.w, h: s.h,
      drawable: s.drawable ?? null,
      // 素材原始像素尺寸（槽位 fill 缩放的源）——SpriteSizingFidelityTest 据此校验
      // 显示矩形在屏上的纵横比与素材一致（换图未同步尺寸即变红）
      srcW: srcDims.get(s.name)?.w ?? null,
      srcH: srcDims.get(s.name)?.h ?? null,
    })),
  };
  fs.writeFileSync(path.join(OUT_DIR, 'atlas-manifest.json'),
    JSON.stringify(outManifest, null, 2) + '\n');

  // 清理临时文件
  for (const f of [TMP_PNG, TMP_ASTC]) {
    if (fs.existsSync(f)) fs.unlinkSync(f);
  }

  console.log(`完成: atlas_astc.ktx ${(ktx.length / 1024 / 1024).toFixed(2)}MB, sprites=${sprites.length}`);
  console.log(`      layoutHash=${outManifest.layoutHash}`);
}

main().catch((e) => {
  console.error('build-atlas 失败:', e.message);
  process.exit(1);
});
