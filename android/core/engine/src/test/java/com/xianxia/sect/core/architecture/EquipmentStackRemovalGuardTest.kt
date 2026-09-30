package com.xianxia.sect.core.architecture

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 装备堆叠轨移除守卫（R6/S12 符号面，方案 §3.15/§6.1）。
 *
 * B3 把装备从「EquipmentStack 堆叠轨」原子替换为「EquipmentInstance 实例轨」。
 * 堆叠符号必须从**状态面与 UI 消费面**归零（GameStateStore 四件套、GameEngine
 * 流/快照、GameViewModel 流）；**有意偏差白名单**（HANDOVER-B3 §五.1 登记，
 * 与方案字面 S12「符号归零」的差异）：[EquipmentStack] 以 @Deprecated 载体
 * 保留为 SaveData(53) 协议占位（旧字节按未知字段忽略，号禁复用）与已登记
 * 的过渡面（秘境旧背包轨待 B4、旧 ForgeRecipe DTO 待后续批）。
 *
 * 守卫口径：主源**剥注释后**扫描 `equipmentStacks|EquipmentStack`；
 * [MUST_BE_ZERO] 文件出现符号即红；[TOLERATED] 文件为登记白名单（文件被删
 * /改名时白名单失效即红，防名单腐化）。
 */
class EquipmentStackRemovalGuardTest {

    /** android/ 根（Gradle 测试工作目录 = android/core/engine） */
    private val androidRoot: File = File("..", "..")

    /** 必须归零的文件（相对 android/ 路径 → 违规时的处置指引） */
    private val mustBeZero: Map<String, String> = mapOf(
        "core/domain/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt"
            to "状态接口不得再有装备堆叠面（B3 四件套已删，禁复活）",
        "core/domain/src/main/java/com/xianxia/sect/core/state/MutableGameState.kt"
            to "可变状态不得再有 equipmentStacks 容器（实例表 equipmentInstances 单轨）",
        "core/engine/src/main/java/com/xianxia/sect/core/engine/GameEngine.kt"
            to "GameEngine 不得再暴露装备堆叠流/快照（镜像面已移除）",
        "feature/game/src/main/java/com/xianxia/sect/ui/game/GameViewModel.kt"
            to "UI 不得消费 equipmentStacks 流（改 equipmentInstances）",
        "feature/game/src/main/java/com/xianxia/sect/ui/game/components/dialog/DialogCommon.kt"
            to "对话框聚合面改实例轨",
        "feature/game/src/main/java/com/xianxia/sect/ui/game/components/detail/DetailPillSection.kt"
            to "丹药详情面板改实例轨",
        "feature/game/src/main/java/com/xianxia/sect/ui/game/components/ItemDetailDialog.kt"
            to "物品详情改实例轨",
        "feature/game/src/main/java/com/xianxia/sect/ui/game/components/ItemDetailEffects.kt"
            to "物品效果摘要改实例轨",
        "feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/MerchantDialog.kt"
            to "商人对话框改实例轨",
        "feature/game/src/main/java/com/xianxia/sect/ui/game/dialogs/MerchantInventoryDialog.kt"
            to "商人货架改实例轨",
        "feature/game/src/main/java/com/xianxia/sect/ui/game/tabs/WarehouseTab.kt"
            to "仓库页改实例轨（数量恒 1）",
        "feature/game/src/main/java/com/xianxia/sect/ui/game/tabs/WarehouseBulkSellDialog.kt"
            to "批量出售改实例轨（数量恒 1）",
        "feature/game/src/main/java/com/xianxia/sect/ui/game/tabs/DisciplesTab.kt"
            to "弟子列表改实例轨",
        "feature/game/src/main/java/com/xianxia/sect/ui/game/tabs/EquipmentSection.kt"
            to "装备段改实例轨",
        "app/src/main/java/com/xianxia/sect/di/AppModule.kt"
            to "EquipmentStackDao 已随 B3 摘除，DI 面不得再提供"
    )

    /**
     * 有意偏差白名单（登记理由 = 报告「旧用例处置表」同源；文件删除/改名即红，
     * 防止名单腐化成摆设）。
     */
    private val tolerated: Map<String, String> = mapOf(
        "core/domain/src/main/java/com/xianxia/sect/core/model/Items.kt"
            to "@Deprecated 载体 + toLegacyInstance 过渡桥（协议占位依赖，勿删）",
        "core/domain/src/main/java/com/xianxia/sect/core/model/SecretRealmModels.kt"
            to "SecretRealmBackpack.equipment 旧轨（C++ 已实例轨，跨端过渡待 B4）",
        "core/domain/src/main/java/com/xianxia/sect/core/model/AlchemySystem.kt"
            to "旧 ForgeRecipe DTO 装备字段（引擎 10+ 文件仍消费，登记后续批）",
        "core/domain/src/main/java/com/xianxia/sect/core/util/StorageBagMaterializer.kt"
            to "物化链输入面（SaveData 载体装配）",
        "core/domain/src/main/java/com/xianxia/sect/core/util/ItemSortUtils.kt"
            to "排序键载体兼容分支（watchKey 按名，随载体退役清）",
        "core/domain/src/main/java/com/xianxia/sect/core/repository/SaveStorage.kt"
            to "存档仓储兼容参数（SaveData(53) 载体链）",
        "core/data/src/main/java/com/xianxia/sect/data/model/SaveData.kt"
            to "SaveData.equipmentStacks(53) @Deprecated 载体（协议号占位）",
        "core/data/src/main/java/com/xianxia/sect/data/engine/StorageEngine.kt"
            to "存档体积估算兼容面",
        "core/data/src/main/java/com/xianxia/sect/data/engine/StorageEngineHeavyDataOps.kt"
            to "读档装配物化",
        "core/data/src/main/java/com/xianxia/sect/data/engine/StorageEngineLoadOps.kt"
            to "读档链载体装配",
        "core/data/src/main/java/com/xianxia/sect/data/GameStateRepository.kt"
            to "仓储读档 shim（空载体喂存档链）",
        "core/engine/src/main/java/com/xianxia/sect/core/engine/domain/exploration/SecretRealmRuinsResolver.kt"
            to "遗迹奖励旧背包轨（同 SecretRealmModels，待 B4）",
        "core/engine/src/main/java/com/xianxia/sect/core/nativebridge/NativeGameState.kt"
            to "native 桥兼容默认参（C++ 容器已删，桥面随 B4 清）"
    )

    private val symbol = Regex("equipmentStacks|EquipmentStack")

    /** 剥 /* */ 与 // 注释（字符串字面量内极少数误伤可接受——守卫口径宁严勿漏） */
    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")

    private fun hits(path: String): List<Pair<Int, String>> {
        val file = File(androidRoot, path)
        if (!file.isFile) return emptyList()
        return stripComments(file.readText()).lines()
            .withIndex()
            .filter { symbol.containsMatchIn(it.value) }
            .map { it.index + 1 to it.value.trim().take(120) }
    }

    @Test
    fun `状态面与UI消费面堆叠符号归零`() {
        val offenders = mutableListOf<String>()
        mustBeZero.forEach { (path, reason) ->
            val found = hits(path)
            if (found.isNotEmpty()) {
                offenders.add("$path\n    处置：$reason\n" + found.joinToString("\n") { "    :${it.first}: ${it.second}" })
            }
        }
        if (offenders.isNotEmpty()) {
            fail(
                "装备堆叠符号出现在必须归零的面（B3 原子替换残留，${offenders.size} 个文件）：\n" +
                    offenders.joinToString("\n")
            )
        }
    }

    @Test
    fun `白名单文件存在且未腐化`() {
        val missing = tolerated.keys.filterNot { File(androidRoot, it).isFile }
        assertTrue(
            "白名单文件被删除或改名（名单已失效，须随实际清理进度重写本守卫）：$missing",
            missing.isEmpty()
        )
    }
}
