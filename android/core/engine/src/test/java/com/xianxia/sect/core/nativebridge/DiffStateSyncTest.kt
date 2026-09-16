package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DiffStateSyncTest — StateSyncService 镜像同步测试。
 *
 * 守护目标：
 *   1. buildNativeState：从 GameStateStore 构建快照（全字段收集）
 *   2. applySnapshot：快照镜像写入（单事务原子 + 宽松合并——未覆盖字段保留）
 *   3. buildNativeState → applySnapshot 往返保真
 *
 * 用共用 [FakeGameStateStore]（手写 Fake 而非 Mock——CLAUDE.md 9.4）隔离
 * 真实 store，聚焦 StateSyncService 自身逻辑。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class DiffStateSyncTest {

    // ── 测试用例 ─────────────────────────────────────────────────

    @Test
    fun `buildNativeState collects all fields`() {
        val store = FakeGameStateStore()
        store.gameDataValue = GameData().apply { spiritStones = 999; gameYear = 5 }
        store.disciplesValue = listOf(
            Disciple().apply { id = "1"; name = "张三"; realm = 7 }
        )
        store.equipmentStacksValue = listOf(
            EquipmentStack(id = "eq-1", name = "木剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 5)
        )
        store.pillsValue = listOf(
            Pill(id = "pill-1", name = "聚气丹", rarity = 2, quantity = 10)
        )
        store.materialsValue = listOf(
            Material(id = "mat-1", name = "兽皮", rarity = 1, quantity = 3)
        )
        store.herbsValue = listOf(Herb(id = "herb-1", name = "灵草", rarity = 1, quantity = 8, category = "普通"))
        store.seedsValue = listOf(Seed(id = "seed-1", name = "灵草种", rarity = 1, quantity = 2, growTime = 3, yield = 1))

        val service = StateSyncService(store)
        val native = service.buildNativeState()

        assertEquals(999L, native.gameData.spiritStones)
        assertEquals(5, native.gameData.gameYear)
        assertEquals(1, native.disciples.size)
        assertEquals("张三", native.disciples[0].name)
        assertEquals(5, native.equipmentStacks[0].quantity)
        assertEquals(10, native.pills[0].quantity)
        assertEquals(3, native.materials[0].quantity)
        assertEquals(8, native.herbs[0].quantity)
        assertEquals(2, native.seeds[0].quantity)
    }

    @Test
    fun `applySnapshot writes all fields in single transaction`() {
        val store = FakeGameStateStore()
        val service = StateSyncService(store)

        val snapshot = NativeGameState(
            gameData = GameData().apply { spiritStones = 500; gameMonth = 7 },
            disciples = listOf(Disciple().apply { id = "9"; name = "李四" }),
            equipmentStacks = listOf(
                EquipmentStack(id = "eq-9", name = "铁剑", rarity = 2, slot = EquipmentSlot.WEAPON, quantity = 3)
            ),
            pills = listOf(Pill(id = "pill-9", name = "回气丹", rarity = 1, quantity = 20)),
            materials = listOf(Material(id = "mat-9", name = "兽骨", rarity = 2, quantity = 7)),
            herbs = listOf(Herb(id = "herb-9", name = "寒霜草", rarity = 2, quantity = 4, category = "冰")),
            seeds = listOf(Seed(id = "seed-9", name = "寒霜草种", rarity = 2, quantity = 1, growTime = 6, yield = 2))
        )

        service.applySnapshot(snapshot)

        // 单事务
        assertEquals(1, store.updateCallCount)
        // 全字段写入
        assertEquals(500L, store.gameDataValue.spiritStones)
        assertEquals(7, store.gameDataValue.gameMonth)
        assertEquals(1, store.disciplesValue.size)
        assertEquals("李四", store.disciplesValue[0].name)
        assertEquals(3, store.equipmentStacksValue[0].quantity)
        assertEquals(20, store.pillsValue[0].quantity)
        assertEquals(7, store.materialsValue[0].quantity)
        assertEquals(4, store.herbsValue[0].quantity)
        assertEquals(1, store.seedsValue[0].quantity)
    }

    @Test
    fun `round trip build then apply preserves state`() {
        val store = FakeGameStateStore()
        store.gameDataValue = GameData().apply { spiritStones = 12345; gameYear = 12; gameMonth = 3 }
        store.disciplesValue = listOf(Disciple().apply { id = "1"; name = "王五"; realm = 5; realmLayer = 3 })
        store.equipmentStacksValue = listOf(
            EquipmentStack(id = "eq-1", name = "玄铁剑", rarity = 4, slot = EquipmentSlot.WEAPON, quantity = 1)
        )
        store.materialsValue = listOf(
            Material(id = "mat-1", name = "龙鳞", rarity = 5, quantity = 2)
        )

        val service = StateSyncService(store)
        val native = service.buildNativeState()
        // 清空后镜像恢复
        store.gameDataValue = GameData()
        store.disciplesValue = emptyList()
        store.equipmentStacksValue = emptyList()
        store.materialsValue = emptyList()

        service.applySnapshot(native)

        assertEquals(12345L, store.gameDataValue.spiritStones)
        assertEquals(12, store.gameDataValue.gameYear)
        assertEquals(1, store.disciplesValue.size)
        assertEquals("王五", store.disciplesValue[0].name)
        assertEquals(1, store.equipmentStacksValue[0].quantity)
        assertEquals(2, store.materialsValue[0].quantity)
    }

    @Test
    fun `empty snapshot disciples keeps existing list`() {
        // 宽松合并语义：C++ 未覆盖字段保留 Kotlin 侧值
        val store = FakeGameStateStore()
        store.disciplesValue = listOf(Disciple().apply { id = "100"; name = "幸存者" })
        val service = StateSyncService(store)

        val snapshot = NativeGameState(gameData = GameData().apply { spiritStones = 100 })
        service.applySnapshot(snapshot)

        assertEquals(100L, store.gameDataValue.spiritStones)
        // disciples 快照为空 → 保留既有列表（宽松合并）
        assertEquals(1, store.disciplesValue.size)
        assertEquals("幸存者", store.disciplesValue[0].name)
    }

    @Test
    fun `importToNative returns false when native unavailable`() {
        // 未加载 native 库 → importToNative 静默降级 false（不崩溃）
        val store = FakeGameStateStore()
        val service = StateSyncService(store)
        // 仅验证不抛异常（真实 native 路径由 Diff 测试覆盖）
        val result = service.importToNative()
        assertTrue("未初始化时应返回 false", !result)
    }

    @Test
    fun `mergeGameData keeps unmigrated fields`() {
        // C++ 只导出已迁移字段——白名单外字段保留 Kotlin 值（宽松合并）
        val store = FakeGameStateStore()
        store.gameDataValue = GameData().apply {
            spiritStones = 100
            gameYear = 3
            sectName = "青云宗"          // C++ 未迁移字段
            jadeSymbols = 42              // C++ 未迁移字段
        }
        val service = StateSyncService(store)

        val snapshotGameData = GameData().apply {
            spiritStones = 500            // C++ 已迁移：覆盖
            gameYear = 4                  // C++ 已迁移：覆盖
        }
        val merged = service.mergeGameData(store.gameDataValue, snapshotGameData,
            exportedKeys = setOf("spiritStones", "gameYear"))

        assertEquals(500L, merged.spiritStones)
        assertEquals(4, merged.gameYear)
        // 白名单外字段保留 Kotlin 值
        assertEquals("青云宗", merged.sectName)
        assertEquals(42, merged.jadeSymbols)
    }

    @Test
    fun `applySnapshot with exported keys merges not replaces`() {
        val store = FakeGameStateStore()
        store.gameDataValue = GameData().apply {
            spiritStones = 100
            sectName = "青云宗"
            jadeSymbols = 42
        }
        val service = StateSyncService(store)
        val snapshot = NativeGameState(
            gameData = GameData().apply { spiritStones = 500 }
        )
        service.applySnapshot(snapshot, exportedGameDataKeys = setOf("spiritStones"))

        assertEquals(500L, store.gameDataValue.spiritStones)
        assertEquals("青云宗", store.gameDataValue.sectName)
        assertEquals(42, store.gameDataValue.jadeSymbols)
    }

    // ── @Transient aiSectDisciples 镜像/反向通道 ──

    @Test
    fun `mergeGameData keeps transient aiSectDisciples`() {
        // @Transient 字段永不进 gameData JSON——解码必然丢失，按"未迁移字段
        // 保留既有值"语义回填（镜像永不因解码丢失清空 AI 弟子池）
        val store = FakeGameStateStore()
        store.gameDataValue = GameData().apply {
            spiritStones = 100
            aiSectDisciples = mapOf("ai-1" to listOf(Disciple().apply {
                id = "90"; name = "玄水弟子"; realm = 7
            }))
        }
        val service = StateSyncService(store)

        val snapshotGameData = GameData().apply { spiritStones = 500 }
        val merged = service.mergeGameData(store.gameDataValue, snapshotGameData,
            exportedKeys = setOf("spiritStones"))

        assertEquals(500L, merged.spiritStones)
        assertEquals("AI 弟子池应保留", 1, merged.aiSectDisciples.size)
        assertEquals("90", merged.aiSectDisciples["ai-1"]?.first()?.id)
    }

    @Test
    fun `applySnapshot full replace keeps aiSectDisciples when not carried`() {
        // C++ 导出未携带 aiSectDisciples（空表不导出键）→ 全量替换分支也保留
        // 事务内既有值，镜像永不主动清空该域
        val store = FakeGameStateStore()
        store.gameDataValue = GameData().apply {
            spiritStones = 100
            aiSectDisciples = mapOf("ai-1" to listOf(Disciple().apply {
                id = "90"; name = "玄水弟子"; realm = 7
            }))
        }
        val service = StateSyncService(store)
        service.applySnapshot(
            NativeGameState(gameData = GameData().apply { spiritStones = 500 })
        )

        assertEquals(500L, store.gameDataValue.spiritStones)
        assertEquals("全量替换不应清空 AI 弟子池", 1, store.gameDataValue.aiSectDisciples.size)
        assertEquals("90", store.gameDataValue.aiSectDisciples["ai-1"]?.first()?.id)
    }

    @Test
    fun `applySnapshot overrides aiSectDisciples when carried by native`() {
        // C++ 顶层导出携带新值（非 null）→ 覆盖 Kotlin 既有值
        val store = FakeGameStateStore()
        store.gameDataValue = GameData().apply {
            aiSectDisciples = mapOf("ai-1" to listOf(Disciple().apply {
                id = "90"; name = "玄水弟子"; realm = 7
            }))
        }
        val service = StateSyncService(store)
        val carried = mapOf("ai-9" to listOf(Disciple().apply {
            id = "99"; name = "玄水新"; realm = 9
        }))
        service.applySnapshot(
            NativeGameState(
                gameData = GameData().apply { spiritStones = 500 },
                aiSectDisciples = carried
            ),
            exportedGameDataKeys = setOf("spiritStones")
        )

        assertEquals("C++ 携带新值应覆盖", setOf("ai-9"),
            store.gameDataValue.aiSectDisciples.keys)
        assertEquals("99", store.gameDataValue.aiSectDisciples["ai-9"]?.first()?.id)
    }
}
