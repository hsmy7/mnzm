package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.model.CombatSkill
import kotlin.random.Random

/**
 * 战斗描述生成器（从 BattleSystem.kt 提取）
 *
 * 为战斗日志生成中文战斗描述文本，根据武器类型、伤害类型、技能等
 * 选择不同的描述模板和随机措辞。
 */
object BattleDescriptionGenerator {

    private val unarmedAttackVerbs = listOf(
        "挥拳攻向", "猛力一拳打向", "拳风呼啸，攻向", "一记重拳轰向",
        "拳劲爆发，攻向", "双拳齐出，攻向", "一记掌击拍向", "拳影重重，攻向"
    )

    private val swordVerbs = listOf(
        "一剑刺向", "挥剑斩向", "剑光闪烁，攻向", "剑气纵横，斩向", "长剑横空，刺向"
    )
    private val bladeVerbs = listOf(
        "一刀劈向", "挥刀砍向", "刀光一闪，斩向", "利刃破空，劈向", "刀气凛然，砍向"
    )
    private val staffVerbs = listOf(
        "一杖击向", "舞杖砸向", "杖影重重，攻向", "法杖挥动，击向", "杖风呼啸，砸向"
    )
    private val orbVerbs = listOf(
        "灵珠旋转，轰向", "宝珠发光，攻向", "珠光闪耀，击向", "灵珠飞旋，轰向", "宝珠璀璨，攻向"
    )
    private val bowVerbs = listOf(
        "弯弓搭箭，射向", "一箭破空，射向", "弓弦响处，箭射", "利箭疾射", "弓如满月，射向"
    )
    private val fanVerbs = listOf(
        "折扇一挥，攻向", "扇影翻飞，击向", "折扇轻挥，攻向", "扇风卷起，击向", "折扇展开，攻向"
    )
    private val genericWeaponVerbs = listOf(
        "挥动武器攻向", "猛然一击", "挥舞兵器攻向", "奋力一击", "猛力攻击"
    )

    private fun getWeaponVerbs(weaponName: String?): List<String> {
        if (weaponName == null) return unarmedAttackVerbs
        return when {
            weaponName.contains("剑") -> swordVerbs
            weaponName.contains("刀") -> bladeVerbs
            weaponName.contains("杖") -> staffVerbs
            weaponName.contains("珠") || weaponName.contains("球") -> orbVerbs
            weaponName.contains("弓") -> bowVerbs
            weaponName.contains("扇") -> fanVerbs
            else -> genericWeaponVerbs
        }
    }

    private val magicAttackVerbs = listOf(
        "凝聚灵力攻向", "施法轰向", "灵力涌动，攻向", "法力凝聚，轰向", "一记法术攻向",
        "灵光闪烁，攻向", "法力流转，轰向", "凝聚真元攻向", "施放法术轰向", "灵力爆发攻向"
    )

    private val beastPhysicalAttackVerbs = listOf(
        "猛扑向", "利爪抓向", "獠牙咬向", "尾巴横扫", "猛烈撞击",
        "咆哮着扑向", "凶狠攻击", "利爪撕裂", "獠牙撕咬", "野蛮冲撞"
    )

    private val beastMagicAttackVerbs = listOf(
        "凝聚妖力攻向", "喷吐妖气轰向", "妖力涌动，攻向", "释放妖术轰向", "一记妖法攻向",
        "妖光闪烁，攻向", "妖气弥漫，轰向", "凝聚妖元攻向", "施放妖术轰向", "妖力爆发攻向"
    )

    private val skillCastPhrases = listOf(
        "运转", "施展", "催动", "发动", "运转"
    )

    private val critDescriptions = listOf(
        "致命一击！", "击中要害！", "暴击命中！", "一击破防！", "势大力沉！"
    )

    private val dodgeDescriptions = listOf(
        "身形一闪，躲过了攻击", "侧身闪避，堪堪躲过", "身法灵动，避开了攻击",
        "脚下生风，闪身躲过", "反应敏捷，躲开了攻击"
    )

    private val killDescriptions = listOf(
        "轰杀", "击杀", "斩杀", "击溃", "击倒"
    )

    fun generateAttackDescription(
        attacker: Combatant,
        target: Combatant,
        result: AttackResult,
        isKill: Boolean
    ): String {
        val sb = StringBuilder()

        if (result.isDodged) {
            val dodgeDesc = dodgeDescriptions.random()
            return "${target.name}${dodgeDesc}${attacker.name}的攻击！"
        }

        val isPhysical = result.isPhysical
        val isBeast = attacker.isBeast

        val attackVerb = if (isBeast) {
            if (isPhysical) beastPhysicalAttackVerbs.random() else beastMagicAttackVerbs.random()
        } else {
            if (isPhysical) getWeaponVerbs(attacker.weaponName).random() else magicAttackVerbs.random()
        }

        val damageType = if (isPhysical) "物理" else "法术"

        sb.append("${attacker.name}${attackVerb}${target.name}")

        if (result.isCrit) {
            sb.append("，${critDescriptions.random()}")
        }

        sb.append("，造成${result.damage}点${damageType}伤害")

        if (result.hits > 1) {
            sb.append("（${result.hits}连击）")
        }

        if (isKill) {
            val killDesc = killDescriptions.random()
            sb.append("，${killDesc}了${target.name}！")
        }

        return sb.toString()
    }

    fun generateSkillDescription(
        attacker: Combatant,
        target: Combatant,
        skill: CombatSkill,
        result: AttackResult,
        isKill: Boolean
    ): String {
        val sb = StringBuilder()

        if (result.isDodged) {
            val dodgeDesc = dodgeDescriptions.random()
            return "${target.name}${dodgeDesc}${attacker.name}的[${skill.name}]！"
        }

        val castPhrase = skillCastPhrases.random()
        if (skill.manualName.isNotEmpty()) {
            sb.append("${attacker.name}${castPhrase}【${skill.manualName}】，使出[${skill.name}]")
        } else {
            sb.append("${attacker.name}使出[${skill.name}]")
        }

        if (skill.skillDescription.isNotEmpty()) {
            sb.append("（${skill.skillDescription}）")
        }

        sb.append("攻向${target.name}")

        if (result.isCrit) {
            sb.append("，${critDescriptions.random()}")
        }

        val damageType = if (result.isPhysical) "物理" else "法术"
        sb.append("，造成${result.damage}点${damageType}伤害")

        if (skill.damageMultiplier > 1.0) {
            sb.append("（威力${(skill.damageMultiplier * 100).toInt()}%）")
        }

        if (result.hits > 1) {
            sb.append("（${result.hits}连击）")
        }

        if (isKill) {
            val killDesc = killDescriptions.random()
            sb.append("，${killDesc}了${target.name}！")
        }

        return sb.toString()
    }

    fun generateSupportSkillDescription(
        caster: Combatant,
        skill: CombatSkill,
        healAmount: Int,
        healType: HealType,
        buffs: List<Triple<BuffType, Double, Int>>
    ): String {
        val sb = StringBuilder()

        val castPhrase = skillCastPhrases.random()
        if (skill.manualName.isNotEmpty()) {
            sb.append("${caster.name}${castPhrase}【${skill.manualName}】，使出[${skill.name}]")
        } else {
            sb.append("${caster.name}使出[${skill.name}]")
        }

        if (skill.skillDescription.isNotEmpty()) {
            sb.append("（${skill.skillDescription}）")
        }

        val effects = mutableListOf<String>()

        if (healAmount > 0) {
            val healTypeName = if (healType == HealType.HP) "生命值" else "灵力"
            effects.add("为全队恢复${healTypeName}${healAmount}点")
        }

        buffs.forEach { (buffType, buffValue, duration) ->
            val percentValue = (buffValue * 100).toInt()
            effects.add("全队获得${buffType.displayName}${percentValue}%持续${duration}回合")
        }

        if (effects.isNotEmpty()) {
            sb.append("，${effects.joinToString("，")}")
        }

        return sb.toString()
    }

    fun generateAoeSkillDescription(
        attacker: Combatant,
        skill: CombatSkill,
        results: List<AttackResult>,
        isKill: Boolean
    ): String {
        val sb = StringBuilder()

        val castPhrase = skillCastPhrases.random()
        if (skill.manualName.isNotEmpty()) {
            sb.append("${attacker.name}${castPhrase}【${skill.manualName}】，使出[${skill.name}]")
        } else {
            sb.append("${attacker.name}使出[${skill.name}]")
        }

        if (skill.skillDescription.isNotEmpty()) {
            sb.append("（${skill.skillDescription}）")
        }

        val damageType = if (results.first().isPhysical) "物理" else "法术"

        sb.append("，对全体敌人造成伤害：")

        val targetDescriptions = results.map { result ->
            if (result.isDodged) {
                "${result.target.name}闪避了攻击"
            } else {
                "${result.target.name}受到${result.damage}点${damageType}伤害"
            }
        }
        sb.append(targetDescriptions.joinToString("，"))

        if (skill.damageMultiplier > 1.0) {
            sb.append("（威力${(skill.damageMultiplier * 100).toInt()}%）")
        }

        if (skill.hits > 1) {
            val hitCount = results.count { !it.isDodged }
            sb.append("（${skill.hits}连击×${hitCount}目标）")
        }

        if (isKill) {
            val killDesc = killDescriptions.random()
            sb.append("，${killDesc}了敌人！")
        }

        return sb.toString()
    }
}
