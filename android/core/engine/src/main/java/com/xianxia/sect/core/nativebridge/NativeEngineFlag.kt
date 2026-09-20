package com.xianxia.sect.core.nativebridge

/**
 * NativeEngineFlag — C++ 引擎集成 feature flag（双态：OFF / AUTHORITATIVE）。
 *
 * 双态语义：
 * - [Mode.OFF]：转发禁用——已接线动作（库存家族等）回退 Kotlin 原实现；
 *   引擎循环帧计划与看门狗判据走 Kotlin 侧。tick 结算
 *   恒走 native（单引擎终态无 Kotlin 路径），OFF 不影响 tick
 *  （仅逐动作/循环集成降级）
 * - [Mode.AUTHORITATIVE]（**生产默认**）：
 *   每旬时间推进 + C++ 核心结算（步骤 1-5 零 RNG 批量）走标量通道
 *   nativeSettlePhase；自动装备/丹药/突破/月变/年变由 Kotlin 残留执行器
 *   处理（行为零丢失）；Kotlin RNG 抽取经分区标量通道委托 C++ 单一真相源
 *   （跨语言序列逐位统一）
 *
 * 逐动作降级契约：native 链路不可用（.so 加载失败/初始化失败）时各转发
 * 分支自动回退 Kotlin 原实现（见 GameEngineCoreAuthoritativeOps /
 * InventoryNativeForward 降级契约）；tick 结算层 native 不可用则该旬跳过
 * 结算，由看门狗判据 → 紧急重启路径自愈。
 *
 * 测试可经 [withMode] 临时设置（自动恢复）。
 */
object NativeEngineFlag {

    /** 引擎集成模式 */
    enum class Mode {
        /** 转发禁用（已接线动作回退 Kotlin 原实现；非引擎级回退——tick 无 Kotlin 路径） */
        OFF,
        /**
         * 真相源切换（C++ 时间推进+核心结算为真相源，Kotlin 残留执行器 +
         * 委托式 RNG；**生产默认**）
         */
        AUTHORITATIVE,
    }

    @Volatile
    var mode: Mode = Mode.AUTHORITATIVE

    /** 是否开启 C++ 引擎转发/集成（非 OFF 即开启） */
    val enabled: Boolean get() = mode != Mode.OFF

    /** 是否 AUTHORITATIVE 过渡模式（tick 路径分支依据） */
    val authoritative: Boolean get() = mode == Mode.AUTHORITATIVE


    /**
     * 镜像瘦身灰度开关（重构方案 R2.3 第二波：GameViewStore 投影态 + 全量重建退场）。
     *
     * - **true（本批生产默认）**：镜像消费面走第二波形态——
     *   ① [com.xianxia.sect.core.gameview.GameDataFieldPatch] 字段级应用 gameData
     *   变更（替代整份 GameData JSON 往返的每旬级全量重建）；
     *   ② [com.xianxia.sect.core.gameview.GameViewStore] 投影态承载已迁 UI 消费块
     *   （资源头部/配置回声/事件流），[com.xianxia.sect.core.engine.GameEngine]
     *   对应转发面以投影为入口；
     *   ③ 弟子行走 typed 直读（`DiscipleRow → Disciple`，去每行 JSON 树重建）。
     * - **false（回滚臂，共存一个版本周期）**：第一波形态——整份 GameData JSON
     *   往返 + DiscipleRow→JsonObject→kotlinx 重建 + UI 只读 GameStateStore 全量流。
     *
     * 语义边界：仅改**消费侧应用形状**，不改协议、不改镜像内容、不改存档；
     * 两臂逐值等价由 `GameDataFieldPatchEquivalenceTest` /
     * `DiscipleRowTypedProjectionTest` / `GameViewProjectionFeedEquivalenceTest` 守卫。
     */
    @Volatile
    var gameViewProjection: Boolean = true

    /**
     * 列级增量导出灰度开关（重构方案 R2.4/B09：R1.4 列级写屏障接生产）。
     *
     * - **true（生产默认）**：`nativeExportDirty`（protobuf 臂）优先走
     *   ColumnDirtyTracker 整树导出——弟子域仅脏行×脏列（结算热路径写点
     *   markCol 精确标脏；月/年边界按审计列集粗粒度标脏），gameData/实体集合
     *   域与全量 diff 共享同一比对段（构造等价）；
     * - false（回滚臂，共存一个版本周期）：全量树 diff（B06 前口径，
     *   对拍显式依赖的全量模式开关，零漂移）。
     *
     * 由 native 初始化后经 [GameCoreBridge.nativeSetDirtyExportColumn] 推送
     * C++（引擎控制端口族，登记豁免沿 nativeSetDirtyExportProtobuf 先例）。
     * **仅 protobuf 臂生效**（B18 传输臂退役后生产通道恒 protobuf）
     * 恒全量树 diff，且 C++ 侧异构写入路径（业务事务/战斗/招募）自动锁存
     * 回退全量导出一封。列级↔全量语义等价由 ColumnExportEquivalenceTest
     * （C++ GTest，真实结算双臂对照）+ Kotlin 侧部分行合并守卫锁定。
     */
    @Volatile
    var dirtyColumnExport: Boolean = true

    /**
     * 场景渲染路径灰度开关（重构方案 R3.2/B10：drawAllTiles 17 参数全量数组
     * → SceneStore 场景真相 + drawFrame(相机, 覆盖标志)；
     * R3.3/B11 起本旗标同时决定**叠加层几何由谁生成**）。
     *
     * - **true（生产默认）**：Vulkan/GLES 渲染走新路径——场景数据（地形/道路/
     *   建筑/作物/云/崖壁）与叠加层状态（选中索引/逐建筑拆除标记/预览几何）
     *   变化驱动导入 C++ [SceneStore]
     *   （sceneSetTerrain / sceneUpdateBuildings / sceneUpdateCrops /
     *   sceneUpdateRoads / sceneUpdateClouds / sceneSetCliffLayout /
     *   sceneSetAtlasTexture），每帧只剩 [NativeBridge.drawFrame]
     *   （相机 5 标量 + overlayFlags 7 位 + 淡入/插值 alpha，G3 <200B/帧）；
     *   网格线/占地框/选中/拆除高亮的几何与顶点也在 C++ 生成
     *   （scene_draw.h 单份绘制核心），Kotlin 侧不再每帧逐 rect 跨线；
     * - **false（回滚臂，共存一个版本周期）**：旧路径——setCamera +
     *   drawAllTiles(17 参数全量数组) + drawSprite/drawRect 逐条叠加层
     *   每帧全量跨线（旧行为不删除，即时回退）。
     *
     * 语义边界：仅切**渲染数据通道 + 叠加层几何归属**，渲染线程模型/后端降级链/Canvas 兜底路径
     * （R3.6 红线：SoftwareCanvasBackend 直接消费 RenderFrame，不经本旗标）零变更；
     * 两臂像素等价由 C++ 单份绘制核心（scene_draw.h）构造性保证 +
     * SceneEquivalenceTest（地图/崖壁层）与 SceneOverlayEquivalenceTest（叠加层）
     * 顶点流逐位对照锁定。回滚 = 旗标默认值改 false
     * 重编/重启；进程级开关（首次访问前确定）。
     */
    @Volatile
    var sceneStoreRender: Boolean = true

    /**
     * 远景观看容量路径灰度开关（重构方案 R3.5）。
     *
     * - **false（生产默认，回滚臂）**：地面层恒走逐格绘制（R3.5 前现状逐位一致）；
     * - true：允许在**整岛缩小观看档**把地面层改走「整图 REPEAT quad」
     *   （1 个 draw call 替代最坏 ~16384 sprite/帧），缓解 SpriteBatcher 容量悬崖。
     *
     * ## 为什么默认关
     * 整图 REPEAT 采样在部分 **Adreno 驱动**上异常（黑屏）——须带**设备白名单**
     * 验证后逐条放行；白名单（[com.xianxia.sect.core.render.FarViewGroundPolicy.ALLOWED_DEVICES]）
     * 当前为空 ⇒ 即便本旗标置 true，[com.xianxia.sect.core.render.FarViewGroundPolicy]
     * 的合取判定仍返回 false（四重门之一不满足）。
     *
     * ## 与 [sceneStoreRender] 的关系
     * **正交**：本旗标只决定地面层**绘制形态**（整图 quad / 逐格），
     * 无论新旧路径都生效；[sceneStoreRender] 决定场景数据通道与叠加层几何归属。
     * 因此两旗标可任意组合，回退本旗标不影响新路径其余部分。
     *
     * 语义边界：仅地面层；Canvas 兜底路径不经本旗标（R3.6 红线）。
     * 回退 = 旗标置 false；C++ 侧开关随 surface 纪元复位。
     */
    @Volatile
    var farViewGroundQuad: Boolean = false

    /**
     * 在 [block] 执行期间临时设置模式（对拍/转发测试用，自动恢复）。
     */
    inline fun <T> withMode(mode: Mode, block: () -> T): T {
        val previous = this.mode
        this.mode = mode
        try {
            return block()
        } finally {
            this.mode = previous
        }
    }
}
