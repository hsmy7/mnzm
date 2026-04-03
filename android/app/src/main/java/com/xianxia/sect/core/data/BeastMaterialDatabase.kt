package com.xianxia.sect.core.data

import com.xianxia.sect.core.model.MaterialCategory

object BeastMaterialDatabase {
    
    data class BeastMaterial(
        val id: String,
        val name: String,
        val tier: Int,
        val rarity: Int,
        val category: String,
        val description: String,
        val icon: String,
        val price: Int,
        val dropWeight: Double = 1.0
    ) {
        val materialCategory: MaterialCategory get() = when (category) {
            "hide" -> MaterialCategory.BEAST_HIDE
            "bone" -> MaterialCategory.BEAST_BONE
            "tooth" -> MaterialCategory.BEAST_TOOTH
            "core" -> MaterialCategory.BEAST_CORE
            "claw" -> MaterialCategory.BEAST_CLAW
            "feather" -> MaterialCategory.BEAST_FEATHER
            "tail" -> MaterialCategory.BEAST_TAIL
            "scale" -> MaterialCategory.BEAST_SCALE
            "horn" -> MaterialCategory.BEAST_HORN
            "shell" -> MaterialCategory.BEAST_SHELL
            "plastron" -> MaterialCategory.BEAST_PLASTRON
            else -> MaterialCategory.BEAST_HIDE
        }
    }
    
    private val allMaterials: List<BeastMaterial> = listOf(
        // ========== 虎妖材料（高攻击型）==========
        // 凡品
        BeastMaterial("tigerHide0", "凡虎皮", 1, 1, "hide", "凡品虎妖的皮毛，蕴含狂暴之力", "🟧", 1200, 1.0),
        BeastMaterial("tigerBone0", "凡虎骨", 1, 1, "bone", "凡品虎妖的骨骼，坚硬有力", "🦴", 1200, 1.0),
        BeastMaterial("tigerTooth0", "凡虎牙", 1, 1, "tooth", "凡品虎妖的利齿，锋利异常", "🦷", 1200, 0.8),
        BeastMaterial("tigerCore0", "凡虎内丹", 1, 1, "core", "凡品虎妖的内丹，蕴含狂暴灵力", "🔮", 1200, 0.5),
        // 灵品
        BeastMaterial("tigerHide1", "灵虎皮", 2, 2, "hide", "灵品虎妖的皮毛，蕴含灵气与狂暴", "🟧", 24000, 0.8),
        BeastMaterial("tigerBone1", "灵虎骨", 2, 2, "bone", "灵品虎妖的骨骼，坚韧有力", "🦴", 24000, 0.8),
        BeastMaterial("tigerTooth1", "灵虎牙", 2, 2, "tooth", "灵品虎妖的利齿，削铁如泥", "🦷", 24000, 0.6),
        BeastMaterial("tigerCore1", "灵虎内丹", 2, 2, "core", "灵品虎妖的内丹，蕴含浓郁狂暴灵力", "🔮", 24000, 0.4),
        // 宝品
        BeastMaterial("tigerHide2", "宝虎皮", 3, 3, "hide", "宝品虎妖的皮毛，珍贵且蕴含强大力量", "🟧", 88000, 0.6),
        BeastMaterial("tigerBone2", "宝虎骨", 3, 3, "bone", "宝品虎妖的骨骼，坚如精钢", "🦴", 88000, 0.6),
        BeastMaterial("tigerTooth2", "宝虎牙", 3, 3, "tooth", "宝品虎妖的利齿，可碎金石", "🦷", 88000, 0.5),
        BeastMaterial("tigerCore2", "宝虎内丹", 3, 3, "core", "宝品虎妖的内丹，蕴含强大狂暴之力", "🔮", 88000, 0.3),
        // 玄品
        BeastMaterial("tigerHide3", "玄虎皮", 4, 4, "hide", "玄品虎妖的皮毛，蕴含玄妙狂暴之力", "🟧", 320000, 0.4),
        BeastMaterial("tigerBone3", "玄虎骨", 4, 4, "bone", "玄品虎妖的骨骼，蕴含玄妙之力", "🦴", 320000, 0.4),
        BeastMaterial("tigerTooth3", "玄虎牙", 4, 4, "tooth", "玄品虎妖的利齿，蕴含玄妙锋芒", "🦷", 320000, 0.3),
        BeastMaterial("tigerCore3", "玄虎内丹", 4, 4, "core", "玄品虎妖的内丹，蕴含玄妙狂暴灵力", "🔮", 320000, 0.2),
        // 地品
        BeastMaterial("tigerHide4", "地虎皮", 5, 5, "hide", "地品虎妖的皮毛，蕴含大地狂暴之力", "🟧", 1600000, 0.25),
        BeastMaterial("tigerBone4", "地虎骨", 5, 5, "bone", "地品虎妖的骨骼，蕴含大地之力", "🦴", 1600000, 0.25),
        BeastMaterial("tigerTooth4", "地虎牙", 5, 5, "tooth", "地品虎妖的利齿，蕴含大地锋芒", "🦷", 1600000, 0.2),
        BeastMaterial("tigerCore4", "地虎内丹", 5, 5, "core", "地品虎妖的内丹，蕴含大地狂暴灵力", "🔮", 1600000, 0.12),
        // 天品
        BeastMaterial("tigerHide5", "天虎皮", 6, 6, "hide", "天品虎妖的皮毛，蕴含天道狂暴之力", "🟧", 4800000, 0.12),
        BeastMaterial("tigerBone5", "天虎骨", 6, 6, "bone", "天品虎妖的骨骼，蕴含天道之力", "🦴", 4800000, 0.12),
        BeastMaterial("tigerTooth5", "天虎牙", 6, 6, "tooth", "天品虎妖的利齿，蕴含天道锋芒", "🦷", 4800000, 0.1),
        BeastMaterial("tigerCore5", "天虎内丹", 6, 6, "core", "天品虎妖的内丹，蕴含天道狂暴灵力", "🔮", 4800000, 0.06),

        // ========== 狼妖材料（高速度型）==========
        // 凡品
        BeastMaterial("wolfHide0", "凡狼皮", 1, 1, "hide", "凡品狼妖的皮毛，轻便迅捷", "🟫", 1200, 1.0),
        BeastMaterial("wolfBone0", "凡狼骨", 1, 1, "bone", "凡品狼妖的骨骼，轻而坚韧", "🦴", 1200, 1.0),
        BeastMaterial("wolfTooth0", "凡狼牙", 1, 1, "tooth", "凡品狼妖的利齿，锋利迅捷", "🦷", 1200, 0.8),
        BeastMaterial("wolfCore0", "凡狼内丹", 1, 1, "core", "凡品狼妖的内丹，蕴含迅捷灵力", "🔮", 1200, 0.5),
        // 灵品
        BeastMaterial("wolfHide1", "灵狼皮", 2, 2, "hide", "灵品狼妖的皮毛，蕴含灵气与迅捷", "🟫", 24000, 0.8),
        BeastMaterial("wolfBone1", "灵狼骨", 2, 2, "bone", "灵品狼妖的骨骼，轻盈坚韧", "🦴", 24000, 0.8),
        BeastMaterial("wolfTooth1", "灵狼牙", 2, 2, "tooth", "灵品狼妖的利齿，快如闪电", "🦷", 24000, 0.6),
        BeastMaterial("wolfCore1", "灵狼内丹", 2, 2, "core", "灵品狼妖的内丹，蕴含浓郁迅捷灵力", "🔮", 24000, 0.4),
        // 宝品
        BeastMaterial("wolfHide2", "宝狼皮", 3, 3, "hide", "宝品狼妖的皮毛，珍贵且蕴含速度之力", "🟫", 88000, 0.6),
        BeastMaterial("wolfBone2", "宝狼骨", 3, 3, "bone", "宝品狼妖的骨骼，轻若鸿毛", "🦴", 88000, 0.6),
        BeastMaterial("wolfTooth2", "宝狼牙", 3, 3, "tooth", "宝品狼妖的利齿，迅疾如风", "🦷", 88000, 0.5),
        BeastMaterial("wolfCore2", "宝狼内丹", 3, 3, "core", "宝品狼妖的内丹，蕴含强大迅捷之力", "🔮", 88000, 0.3),
        // 玄品
        BeastMaterial("wolfHide3", "玄狼皮", 4, 4, "hide", "玄品狼妖的皮毛，蕴含玄妙迅捷之力", "🟫", 320000, 0.4),
        BeastMaterial("wolfBone3", "玄狼骨", 4, 4, "bone", "玄品狼妖的骨骼，蕴含玄妙轻盈", "🦴", 320000, 0.4),
        BeastMaterial("wolfTooth3", "玄狼牙", 4, 4, "tooth", "玄品狼妖的利齿，蕴含玄妙速度", "🦷", 320000, 0.3),
        BeastMaterial("wolfCore3", "玄狼内丹", 4, 4, "core", "玄品狼妖的内丹，蕴含玄妙迅捷灵力", "🔮", 320000, 0.2),
        // 地品
        BeastMaterial("wolfHide4", "地狼皮", 5, 5, "hide", "地品狼妖的皮毛，蕴含大地迅捷之力", "🟫", 1600000, 0.25),
        BeastMaterial("wolfBone4", "地狼骨", 5, 5, "bone", "地品狼妖的骨骼，蕴含大地轻盈", "🦴", 1600000, 0.25),
        BeastMaterial("wolfTooth4", "地狼牙", 5, 5, "tooth", "地品狼妖的利齿，蕴含大地速度", "🦷", 1600000, 0.2),
        BeastMaterial("wolfCore4", "地狼内丹", 5, 5, "core", "地品狼妖的内丹，蕴含大地迅捷灵力", "🔮", 1600000, 0.12),
        // 天品
        BeastMaterial("wolfHide5", "天狼皮", 6, 6, "hide", "天品狼妖的皮毛，蕴含天道迅捷之力", "🟫", 4800000, 0.12),
        BeastMaterial("wolfBone5", "天狼骨", 6, 6, "bone", "天品狼妖的骨骼，蕴含天道轻盈", "🦴", 4800000, 0.12),
        BeastMaterial("wolfTooth5", "天狼牙", 6, 6, "tooth", "天品狼妖的利齿，蕴含天道速度", "🦷", 4800000, 0.1),
        BeastMaterial("wolfCore5", "天狼内丹", 6, 6, "core", "天品狼妖的内丹，蕴含天道迅捷灵力", "🔮", 4800000, 0.06),

        // ========== 蛇妖材料（法术/毒属性型）==========
        // 凡品
        BeastMaterial("snakeHide0", "凡蛇皮", 1, 1, "hide", "凡品蛇妖的鳞片，蕴含剧毒", "🟩", 1200, 1.0),
        BeastMaterial("snakeBone0", "凡蛇骨", 1, 1, "bone", "凡品蛇妖的骨骼，柔韧有毒", "🦴", 1200, 1.0),
        BeastMaterial("snakeTooth0", "凡毒牙", 1, 1, "tooth", "凡品蛇妖的毒牙，蕴含剧毒", "🦷", 1200, 0.8),
        BeastMaterial("snakeCore0", "凡蛇内丹", 1, 1, "core", "凡品蛇妖的内丹，蕴含剧毒灵力", "🔮", 1200, 0.5),
        // 灵品
        BeastMaterial("snakeHide1", "灵蛇皮", 2, 2, "hide", "灵品蛇妖的鳞片，蕴含灵气与剧毒", "🟩", 24000, 0.8),
        BeastMaterial("snakeBone1", "灵蛇骨", 2, 2, "bone", "灵品蛇妖的骨骼，柔韧且蕴含灵力", "🦴", 24000, 0.8),
        BeastMaterial("snakeTooth1", "灵毒牙", 2, 2, "tooth", "灵品蛇妖的毒牙，毒性强烈", "🦷", 24000, 0.6),
        BeastMaterial("snakeCore1", "灵蛇内丹", 2, 2, "core", "灵品蛇妖的内丹，蕴含浓郁剧毒灵力", "🔮", 24000, 0.4),
        // 宝品
        BeastMaterial("snakeHide2", "宝蛇皮", 3, 3, "hide", "宝品蛇妖的鳞片，珍贵且蕴含强大毒性", "🟩", 88000, 0.6),
        BeastMaterial("snakeBone2", "宝蛇骨", 3, 3, "bone", "宝品蛇妖的骨骼，柔韧如丝", "🦴", 88000, 0.6),
        BeastMaterial("snakeTooth2", "宝毒牙", 3, 3, "tooth", "宝品蛇妖的毒牙，毒性猛烈", "🦷", 88000, 0.5),
        BeastMaterial("snakeCore2", "宝蛇内丹", 3, 3, "core", "宝品蛇妖的内丹，蕴含强大剧毒之力", "🔮", 88000, 0.3),
        // 玄品
        BeastMaterial("snakeHide3", "玄蛇皮", 4, 4, "hide", "玄品蛇妖的鳞片，蕴含玄妙剧毒之力", "🟩", 320000, 0.4),
        BeastMaterial("snakeBone3", "玄蛇骨", 4, 4, "bone", "玄品蛇妖的骨骼，蕴含玄妙柔韧", "🦴", 320000, 0.4),
        BeastMaterial("snakeTooth3", "玄毒牙", 4, 4, "tooth", "玄品蛇妖的毒牙，蕴含玄妙毒性", "🦷", 320000, 0.3),
        BeastMaterial("snakeCore3", "玄蛇内丹", 4, 4, "core", "玄品蛇妖的内丹，蕴含玄妙剧毒灵力", "🔮", 320000, 0.2),
        // 地品
        BeastMaterial("snakeHide4", "地蛇皮", 5, 5, "hide", "地品蛇妖的鳞片，蕴含大地剧毒之力", "🟩", 1600000, 0.25),
        BeastMaterial("snakeBone4", "地蛇骨", 5, 5, "bone", "地品蛇妖的骨骼，蕴含大地柔韧", "🦴", 1600000, 0.25),
        BeastMaterial("snakeTooth4", "地毒牙", 5, 5, "tooth", "地品蛇妖的毒牙，蕴含大地毒性", "🦷", 1600000, 0.2),
        BeastMaterial("snakeCore4", "地蛇内丹", 5, 5, "core", "地品蛇妖的内丹，蕴含大地剧毒灵力", "🔮", 1600000, 0.12),
        // 天品
        BeastMaterial("snakeHide5", "天蛇皮", 6, 6, "hide", "天品蛇妖的鳞片，蕴含天道剧毒之力", "🟩", 4800000, 0.12),
        BeastMaterial("snakeBone5", "天蛇骨", 6, 6, "bone", "天品蛇妖的骨骼，蕴含天道柔韧", "🦴", 4800000, 0.12),
        BeastMaterial("snakeTooth5", "天毒牙", 6, 6, "tooth", "天品蛇妖的毒牙，蕴含天道毒性", "🦷", 4800000, 0.1),
        BeastMaterial("snakeCore5", "天蛇内丹", 6, 6, "core", "天品蛇妖的内丹，蕴含天道剧毒灵力", "🔮", 4800000, 0.06),

        // ========== 熊妖材料（高防御型）==========
        // 凡品
        BeastMaterial("bearHide0", "凡熊皮", 1, 1, "hide", "凡品熊妖的皮毛，厚实坚韧", "🟫", 1200, 1.0),
        BeastMaterial("bearBone0", "凡熊骨", 1, 1, "bone", "凡品熊妖的骨骼，粗壮坚硬", "🦴", 1200, 1.0),
        BeastMaterial("bearClaw0", "凡熊掌", 1, 1, "claw", "凡品熊妖的熊掌，力大无穷", "🐾", 1200, 0.8),
        BeastMaterial("bearCore0", "凡熊内丹", 1, 1, "core", "凡品熊妖的内丹，蕴含坚韧灵力", "🔮", 1200, 0.5),
        // 灵品
        BeastMaterial("bearHide1", "灵熊皮", 2, 2, "hide", "灵品熊妖的皮毛，蕴含灵气与坚韧", "🟫", 24000, 0.8),
        BeastMaterial("bearBone1", "灵熊骨", 2, 2, "bone", "灵品熊妖的骨骼，坚如磐石", "🦴", 24000, 0.8),
        BeastMaterial("bearClaw1", "灵熊掌", 2, 2, "claw", "灵品熊妖的熊掌，威力惊人", "🐾", 24000, 0.6),
        BeastMaterial("bearCore1", "灵熊内丹", 2, 2, "core", "灵品熊妖的内丹，蕴含浓郁坚韧灵力", "🔮", 24000, 0.4),
        // 宝品
        BeastMaterial("bearHide2", "宝熊皮", 3, 3, "hide", "宝品熊妖的皮毛，珍贵且蕴含强大防御", "🟫", 88000, 0.6),
        BeastMaterial("bearBone2", "宝熊骨", 3, 3, "bone", "宝品熊妖的骨骼，坚不可摧", "🦴", 88000, 0.6),
        BeastMaterial("bearClaw2", "宝熊掌", 3, 3, "claw", "宝品熊妖的熊掌，力能碎山", "🐾", 88000, 0.5),
        BeastMaterial("bearCore2", "宝熊内丹", 3, 3, "core", "宝品熊妖的内丹，蕴含强大坚韧之力", "🔮", 88000, 0.3),
        // 玄品
        BeastMaterial("bearHide3", "玄熊皮", 4, 4, "hide", "玄品熊妖的皮毛，蕴含玄妙坚韧之力", "🟫", 320000, 0.4),
        BeastMaterial("bearBone3", "玄熊骨", 4, 4, "bone", "玄品熊妖的骨骼，蕴含玄妙坚固", "🦴", 320000, 0.4),
        BeastMaterial("bearClaw3", "玄熊掌", 4, 4, "claw", "玄品熊妖的熊掌，蕴含玄妙力量", "🐾", 320000, 0.3),
        BeastMaterial("bearCore3", "玄熊内丹", 4, 4, "core", "玄品熊妖的内丹，蕴含玄妙坚韧灵力", "🔮", 320000, 0.2),
        // 地品
        BeastMaterial("bearHide4", "地熊皮", 5, 5, "hide", "地品熊妖的皮毛，蕴含大地坚韧之力", "🟫", 1600000, 0.25),
        BeastMaterial("bearBone4", "地熊骨", 5, 5, "bone", "地品熊妖的骨骼，蕴含大地坚固", "🦴", 1600000, 0.25),
        BeastMaterial("bearClaw4", "地熊掌", 5, 5, "claw", "地品熊妖的熊掌，蕴含大地力量", "🐾", 1600000, 0.2),
        BeastMaterial("bearCore4", "地熊内丹", 5, 5, "core", "地品熊妖的内丹，蕴含大地坚韧灵力", "🔮", 1600000, 0.12),
        // 天品
        BeastMaterial("bearHide5", "天熊皮", 6, 6, "hide", "天品熊妖的皮毛，蕴含天道坚韧之力", "🟫", 4800000, 0.12),
        BeastMaterial("bearBone5", "天熊骨", 6, 6, "bone", "天品熊妖的骨骼，蕴含天道坚固", "🦴", 4800000, 0.12),
        BeastMaterial("bearClaw5", "天熊掌", 6, 6, "claw", "天品熊妖的熊掌，蕴含天道力量", "🐾", 4800000, 0.1),
        BeastMaterial("bearCore5", "天熊内丹", 6, 6, "core", "天品熊妖的内丹，蕴含天道坚韧灵力", "🔮", 4800000, 0.06),

        // ========== 鹰妖材料（暴击/速度型）==========
        // 凡品
        BeastMaterial("eagleFeather0", "凡鹰羽", 1, 1, "feather", "凡品鹰妖的羽毛，轻盈锐利", "🪶", 1200, 1.0),
        BeastMaterial("eagleBone0", "凡鹰骨", 1, 1, "bone", "凡品鹰妖的骨骼，轻而坚固", "🦴", 1200, 1.0),
        BeastMaterial("eagleClaw0", "凡鹰爪", 1, 1, "claw", "凡品鹰妖的利爪，锋利如钩", "🐾", 1200, 0.8),
        BeastMaterial("eagleCore0", "凡鹰内丹", 1, 1, "core", "凡品鹰妖的内丹，蕴含锐利灵力", "🔮", 1200, 0.5),
        // 灵品
        BeastMaterial("eagleFeather1", "灵鹰羽", 2, 2, "feather", "灵品鹰妖的羽毛，蕴含灵气与锐利", "🪶", 24000, 0.8),
        BeastMaterial("eagleBone1", "灵鹰骨", 2, 2, "bone", "灵品鹰妖的骨骼，轻盈坚固", "🦴", 24000, 0.8),
        BeastMaterial("eagleClaw1", "灵鹰爪", 2, 2, "claw", "灵品鹰妖的利爪，快如闪电", "🐾", 24000, 0.6),
        BeastMaterial("eagleCore1", "灵鹰内丹", 2, 2, "core", "灵品鹰妖的内丹，蕴含浓郁锐利灵力", "🔮", 24000, 0.4),
        // 宝品
        BeastMaterial("eagleFeather2", "宝鹰羽", 3, 3, "feather", "宝品鹰妖的羽毛，珍贵且蕴含强大锐利", "🪶", 88000, 0.6),
        BeastMaterial("eagleBone2", "宝鹰骨", 3, 3, "bone", "宝品鹰妖的骨骼，轻若鸿毛", "🦴", 88000, 0.6),
        BeastMaterial("eagleClaw2", "宝鹰爪", 3, 3, "claw", "宝品鹰妖的利爪，迅疾如风", "🐾", 88000, 0.5),
        BeastMaterial("eagleCore2", "宝鹰内丹", 3, 3, "core", "宝品鹰妖的内丹，蕴含强大锐利之力", "🔮", 88000, 0.3),
        // 玄品
        BeastMaterial("eagleFeather3", "玄鹰羽", 4, 4, "feather", "玄品鹰妖的羽毛，蕴含玄妙锐利之力", "🪶", 320000, 0.4),
        BeastMaterial("eagleBone3", "玄鹰骨", 4, 4, "bone", "玄品鹰妖的骨骼，蕴含玄妙轻盈", "🦴", 320000, 0.4),
        BeastMaterial("eagleClaw3", "玄鹰爪", 4, 4, "claw", "玄品鹰妖的利爪，蕴含玄妙速度", "🐾", 320000, 0.3),
        BeastMaterial("eagleCore3", "玄鹰内丹", 4, 4, "core", "玄品鹰妖的内丹，蕴含玄妙锐利灵力", "🔮", 320000, 0.2),
        // 地品
        BeastMaterial("eagleFeather4", "地鹰羽", 5, 5, "feather", "地品鹰妖的羽毛，蕴含大地锐利之力", "🪶", 1600000, 0.25),
        BeastMaterial("eagleBone4", "地鹰骨", 5, 5, "bone", "地品鹰妖的骨骼，蕴含大地轻盈", "🦴", 1600000, 0.25),
        BeastMaterial("eagleClaw4", "地鹰爪", 5, 5, "claw", "地品鹰妖的利爪，蕴含大地速度", "🐾", 1600000, 0.2),
        BeastMaterial("eagleCore4", "地鹰内丹", 5, 5, "core", "地品鹰妖的内丹，蕴含大地锐利灵力", "🔮", 1600000, 0.12),
        // 天品
        BeastMaterial("eagleFeather5", "天鹰羽", 6, 6, "feather", "天品鹰妖的羽毛，蕴含天道锐利之力", "🪶", 4800000, 0.12),
        BeastMaterial("eagleBone5", "天鹰骨", 6, 6, "bone", "天品鹰妖的骨骼，蕴含天道轻盈", "🦴", 4800000, 0.12),
        BeastMaterial("eagleClaw5", "天鹰爪", 6, 6, "claw", "天品鹰妖的利爪，蕴含天道速度", "🐾", 4800000, 0.1),
        BeastMaterial("eagleCore5", "天鹰内丹", 6, 6, "core", "天品鹰妖的内丹，蕴含天道锐利灵力", "🔮", 4800000, 0.06),

        // ========== 狐妖材料（幻魅型）==========
        // 凡品
        BeastMaterial("foxHide0", "凡狐皮", 1, 1, "hide", "凡品狐妖的皮毛，柔软光滑", "🟥", 1200, 1.0),
        BeastMaterial("foxBone0", "凡狐骨", 1, 1, "bone", "凡品狐妖的骨骼，轻盈灵动", "🦴", 1200, 1.0),
        BeastMaterial("foxTail0", "凡狐尾", 1, 1, "tail", "凡品狐妖的尾巴，柔软迷人", "🦊", 1200, 0.8),
        BeastMaterial("foxCore0", "凡狐内丹", 1, 1, "core", "凡品狐妖的内丹，蕴含幻魅灵力", "🔮", 1200, 0.5),
        // 灵品
        BeastMaterial("foxHide1", "灵狐皮", 2, 2, "hide", "灵品狐妖的皮毛，蕴含灵气与幻魅", "🟥", 24000, 0.8),
        BeastMaterial("foxBone1", "灵狐骨", 2, 2, "bone", "灵品狐妖的骨骼，轻盈灵动", "🦴", 24000, 0.8),
        BeastMaterial("foxTail1", "灵狐尾", 2, 2, "tail", "灵品狐妖的尾巴，幻魅迷人", "🦊", 24000, 0.6),
        BeastMaterial("foxCore1", "灵狐内丹", 2, 2, "core", "灵品狐妖的内丹，蕴含浓郁幻魅灵力", "🔮", 24000, 0.4),
        // 宝品
        BeastMaterial("foxHide2", "宝狐皮", 3, 3, "hide", "宝品狐妖的皮毛，珍贵且蕴含强大幻魅", "🟥", 88000, 0.6),
        BeastMaterial("foxBone2", "宝狐骨", 3, 3, "bone", "宝品狐妖的骨骼，轻若鸿毛", "🦴", 88000, 0.6),
        BeastMaterial("foxTail2", "宝狐尾", 3, 3, "tail", "宝品狐妖的尾巴，幻魅万千", "🦊", 88000, 0.5),
        BeastMaterial("foxCore2", "宝狐内丹", 3, 3, "core", "宝品狐妖的内丹，蕴含强大幻魅之力", "🔮", 88000, 0.3),
        // 玄品
        BeastMaterial("foxHide3", "玄狐皮", 4, 4, "hide", "玄品狐妖的皮毛，蕴含玄妙幻魅之力", "🟥", 320000, 0.4),
        BeastMaterial("foxBone3", "玄狐骨", 4, 4, "bone", "玄品狐妖的骨骼，蕴含玄妙灵动", "🦴", 320000, 0.4),
        BeastMaterial("foxTail3", "玄狐尾", 4, 4, "tail", "玄品狐妖的尾巴，蕴含玄妙幻魅", "🦊", 320000, 0.3),
        BeastMaterial("foxCore3", "玄狐内丹", 4, 4, "core", "玄品狐妖的内丹，蕴含玄妙幻魅灵力", "🔮", 320000, 0.2),
        // 地品
        BeastMaterial("foxHide4", "地狐皮", 5, 5, "hide", "地品狐妖的皮毛，蕴含大地幻魅之力", "🟥", 1600000, 0.25),
        BeastMaterial("foxBone4", "地狐骨", 5, 5, "bone", "地品狐妖的骨骼，蕴含大地灵动", "🦴", 1600000, 0.25),
        BeastMaterial("foxTail4", "地狐尾", 5, 5, "tail", "地品狐妖的尾巴，蕴含大地幻魅", "🦊", 1600000, 0.2),
        BeastMaterial("foxCore4", "地狐内丹", 5, 5, "core", "地品狐妖的内丹，蕴含大地幻魅灵力", "🔮", 1600000, 0.12),
        // 天品
        BeastMaterial("foxHide5", "天狐皮", 6, 6, "hide", "天品狐妖的皮毛，蕴含天道幻魅之力", "🟥", 4800000, 0.12),
        BeastMaterial("foxBone5", "天狐骨", 6, 6, "bone", "天品狐妖的骨骼，蕴含天道灵动", "🦴", 4800000, 0.12),
        BeastMaterial("foxTail5", "天狐尾", 6, 6, "tail", "天品狐妖的尾巴，蕴含天道幻魅", "🦊", 4800000, 0.1),
        BeastMaterial("foxCore5", "天狐内丹", 6, 6, "core", "天品狐妖的内丹，蕴含天道幻魅灵力", "🔮", 4800000, 0.06),

        // ========== 龙妖材料（全能顶级型）==========
        // 凡品
        BeastMaterial("dragonScale0", "凡龙鳞", 1, 1, "scale", "凡品龙妖的鳞片，坚硬无比", "🐲", 1200, 1.0),
        BeastMaterial("dragonBone0", "凡龙骨", 1, 1, "bone", "凡品龙妖的骨骼，蕴含龙威", "🦴", 1200, 1.0),
        BeastMaterial("dragonHorn0", "凡龙角", 1, 1, "horn", "凡品龙妖的龙角，蕴含龙力", "🦄", 1200, 0.8),
        BeastMaterial("dragonCore0", "凡龙内丹", 1, 1, "core", "凡品龙妖的内丹，蕴含龙族灵力", "🔮", 1200, 0.5),
        // 灵品
        BeastMaterial("dragonScale1", "灵龙鳞", 2, 2, "scale", "灵品龙妖的鳞片，蕴含灵气与龙威", "🐲", 24000, 0.8),
        BeastMaterial("dragonBone1", "灵龙骨", 2, 2, "bone", "灵品龙妖的骨骼，龙威凛凛", "🦴", 24000, 0.8),
        BeastMaterial("dragonHorn1", "灵龙角", 2, 2, "horn", "灵品龙妖的龙角，蕴含强大龙力", "🦄", 24000, 0.6),
        BeastMaterial("dragonCore1", "灵龙内丹", 2, 2, "core", "灵品龙妖的内丹，蕴含浓郁龙族灵力", "🔮", 24000, 0.4),
        // 宝品
        BeastMaterial("dragonScale2", "宝龙鳞", 3, 3, "scale", "宝品龙妖的鳞片，珍贵且蕴含强大龙威", "🐲", 88000, 0.6),
        BeastMaterial("dragonBone2", "宝龙骨", 3, 3, "bone", "宝品龙妖的骨骼，坚不可摧", "🦴", 88000, 0.6),
        BeastMaterial("dragonHorn2", "宝龙角", 3, 3, "horn", "宝品龙妖的龙角，蕴含磅礴龙力", "🦄", 88000, 0.5),
        BeastMaterial("dragonCore2", "宝龙内丹", 3, 3, "core", "宝品龙妖的内丹，蕴含强大龙族之力", "🔮", 88000, 0.3),
        // 玄品
        BeastMaterial("dragonScale3", "玄龙鳞", 4, 4, "scale", "玄品龙妖的鳞片，蕴含玄妙龙威", "🐲", 320000, 0.4),
        BeastMaterial("dragonBone3", "玄龙骨", 4, 4, "bone", "玄品龙妖的骨骼，蕴含玄妙龙力", "🦴", 320000, 0.4),
        BeastMaterial("dragonHorn3", "玄龙角", 4, 4, "horn", "玄品龙妖的龙角，蕴含玄妙龙威", "🦄", 320000, 0.3),
        BeastMaterial("dragonCore3", "玄龙内丹", 4, 4, "core", "玄品龙妖的内丹，蕴含玄妙龙族灵力", "🔮", 320000, 0.2),
        // 地品
        BeastMaterial("dragonScale4", "地龙鳞", 5, 5, "scale", "地品龙妖的鳞片，蕴含大地龙威", "🐲", 1600000, 0.25),
        BeastMaterial("dragonBone4", "地龙骨", 5, 5, "bone", "地品龙妖的骨骼，蕴含大地龙力", "🦴", 1600000, 0.25),
        BeastMaterial("dragonHorn4", "地龙角", 5, 5, "horn", "地品龙妖的龙角，蕴含大地龙威", "🦄", 1600000, 0.2),
        BeastMaterial("dragonCore4", "地龙内丹", 5, 5, "core", "地品龙妖的内丹，蕴含大地龙族灵力", "🔮", 1600000, 0.12),
        // 天品
        BeastMaterial("dragonScale5", "天龙鳞", 6, 6, "scale", "天品龙妖的鳞片，蕴含天道龙威", "🐲", 4800000, 0.12),
        BeastMaterial("dragonBone5", "天龙骨", 6, 6, "bone", "天品龙妖的骨骼，蕴含天道龙力", "🦴", 4800000, 0.12),
        BeastMaterial("dragonHorn5", "天龙角", 6, 6, "horn", "天品龙妖的龙角，蕴含天道龙威", "🦄", 4800000, 0.1),
        BeastMaterial("dragonCore5", "天龙内丹", 6, 6, "core", "天品龙妖的内丹，蕴含天道龙族灵力", "🔮", 4800000, 0.06),

        // ========== 龟妖材料（高防御型）==========
        // 凡品
        BeastMaterial("turtleShell0", "凡龟壳", 1, 1, "shell", "凡品龟妖的龟壳，坚硬厚重", "🐢", 1200, 1.0),
        BeastMaterial("turtleBone0", "凡龟骨", 1, 1, "bone", "凡品龟妖的骨骼，厚重坚固", "🦴", 1200, 1.0),
        BeastMaterial("turtlePlastron0", "凡龟甲", 1, 1, "plastron", "凡品龟妖的腹甲，防御力惊人", "🛡️", 1200, 0.8),
        BeastMaterial("turtleCore0", "凡龟内丹", 1, 1, "core", "凡品龟妖的内丹，蕴含厚重灵力", "🔮", 1200, 0.5),
        // 灵品
        BeastMaterial("turtleShell1", "灵龟壳", 2, 2, "shell", "灵品龟妖的龟壳，蕴含灵气与厚重", "🐢", 24000, 0.8),
        BeastMaterial("turtleBone1", "灵龟骨", 2, 2, "bone", "灵品龟妖的骨骼，坚如磐石", "🦴", 24000, 0.8),
        BeastMaterial("turtlePlastron1", "灵龟甲", 2, 2, "plastron", "灵品龟妖的腹甲，防御惊人", "🛡️", 24000, 0.6),
        BeastMaterial("turtleCore1", "灵龟内丹", 2, 2, "core", "灵品龟妖的内丹，蕴含浓郁厚重灵力", "🔮", 24000, 0.4),
        // 宝品
        BeastMaterial("turtleShell2", "宝龟壳", 3, 3, "shell", "宝品龟妖的龟壳，珍贵且蕴含强大防御", "🐢", 88000, 0.6),
        BeastMaterial("turtleBone2", "宝龟骨", 3, 3, "bone", "宝品龟妖的骨骼，坚不可摧", "🦴", 88000, 0.6),
        BeastMaterial("turtlePlastron2", "宝龟甲", 3, 3, "plastron", "宝品龟妖的腹甲，防御无双", "🛡️", 88000, 0.5),
        BeastMaterial("turtleCore2", "宝龟内丹", 3, 3, "core", "宝品龟妖的内丹，蕴含强大厚重之力", "🔮", 88000, 0.3),
        // 玄品
        BeastMaterial("turtleShell3", "玄龟壳", 4, 4, "shell", "玄品龟妖的龟壳，蕴含玄妙厚重之力", "🐢", 320000, 0.4),
        BeastMaterial("turtleBone3", "玄龟骨", 4, 4, "bone", "玄品龟妖的骨骼，蕴含玄妙坚固", "🦴", 320000, 0.4),
        BeastMaterial("turtlePlastron3", "玄龟甲", 4, 4, "plastron", "玄品龟妖的腹甲，蕴含玄妙防御", "🛡️", 320000, 0.3),
        BeastMaterial("turtleCore3", "玄龟内丹", 4, 4, "core", "玄品龟妖的内丹，蕴含玄妙厚重灵力", "🔮", 320000, 0.2),
        // 地品
        BeastMaterial("turtleShell4", "地龟壳", 5, 5, "shell", "地品龟妖的龟壳，蕴含大地厚重之力", "🐢", 1600000, 0.25),
        BeastMaterial("turtleBone4", "地龟骨", 5, 5, "bone", "地品龟妖的骨骼，蕴含大地坚固", "🦴", 1600000, 0.25),
        BeastMaterial("turtlePlastron4", "地龟甲", 5, 5, "plastron", "地品龟妖的腹甲，蕴含大地防御", "🛡️", 1600000, 0.2),
        BeastMaterial("turtleCore4", "地龟内丹", 5, 5, "core", "地品龟妖的内丹，蕴含大地厚重灵力", "🔮", 1600000, 0.12),
        // 天品
        BeastMaterial("turtleShell5", "天龟壳", 6, 6, "shell", "天品龟妖的龟壳，蕴含天道厚重之力", "🐢", 4800000, 0.12),
        BeastMaterial("turtleBone5", "天龟骨", 6, 6, "bone", "天品龟妖的骨骼，蕴含天道坚固", "🦴", 4800000, 0.12),
        BeastMaterial("turtlePlastron5", "天龟甲", 6, 6, "plastron", "天品龟妖的腹甲，蕴含天道防御", "🛡️", 4800000, 0.1),
        BeastMaterial("turtleCore5", "天龟内丹", 6, 6, "core", "天品龟妖的内丹，蕴含天道厚重灵力", "🔮", 4800000, 0.06)
    )
    
    fun getAllMaterials(): List<BeastMaterial> = allMaterials
    
    fun getMaterialById(id: String): BeastMaterial? = allMaterials.find { it.id == id }
    
    fun getMaterialsByTier(tier: Int): List<BeastMaterial> = allMaterials.filter { it.tier == tier }
    
    fun getMaterialsByRarity(rarity: Int): List<BeastMaterial> = allMaterials.filter { it.rarity == rarity }
    
    fun getMaterialsByBeastType(beastType: String): List<BeastMaterial> {
        val prefix = when (beastType) {
            "虎妖" -> "tiger"
            "狼妖" -> "wolf"
            "蛇妖" -> "snake"
            "熊妖" -> "bear"
            "鹰妖" -> "eagle"
            "狐妖" -> "fox"
            "龙妖" -> "dragon"
            "龟妖" -> "turtle"
            else -> return emptyList()
        }
        return allMaterials.filter { it.id.startsWith(prefix) }
    }
    
    fun getDropMaterialsByRealm(realm: Int): List<BeastMaterial> {
        val tiers: List<Int> = when {
            realm >= 9 -> listOf(1)
            realm >= 8 -> listOf(1, 2)
            realm >= 7 -> listOf(1, 2, 3)
            realm >= 6 -> listOf(2, 3, 4)
            realm >= 5 -> listOf(3, 4, 5)
            realm >= 3 -> listOf(4, 5, 6)
            else -> listOf(5, 6)
        }
        return allMaterials.filter { it.tier in tiers }
    }
    
    fun getRandomMaterialByRealm(realm: Int, luck: Double = 1.0): BeastMaterial? {
        val candidates = getDropMaterialsByRealm(realm)
        if (candidates.isEmpty()) return null
        
        val totalWeight = candidates.sumOf { it.dropWeight * luck }
        var random = kotlin.random.Random.nextDouble() * totalWeight
        
        for (material in candidates) {
            random -= material.dropWeight * luck
            if (random <= 0) return material
        }
        
        return candidates.firstOrNull()
    }
    
    fun getRandomMaterialByBeastType(beastType: String, tier: Int, luck: Double = 1.0): BeastMaterial? {
        val materials = getMaterialsByBeastType(beastType).filter { it.tier == tier }
        if (materials.isEmpty()) return null
        
        val totalWeight = materials.sumOf { it.dropWeight * luck }
        var random = kotlin.random.Random.nextDouble() * totalWeight
        
        for (material in materials) {
            random -= material.dropWeight * luck
            if (random <= 0) return material
        }
        
        return materials.firstOrNull()
    }
    
    fun getMaterialByName(name: String): BeastMaterial? = allMaterials.find { it.name == name }
}
