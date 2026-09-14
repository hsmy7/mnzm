package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.model.CombatSkill
/**
 * 战斗描述生成器（从 BattleSystem.kt 提取）
 *
 * 为战斗日志生成中文战斗描述文本，根据武器类型、伤害类型、技能等
 * 选择不同的描述模板和随机措辞。
 *
 * ## 措辞选源（W4-C 随机源治理·战斗侧收敛）
 *
 * 战斗措辞经 `BattleSystem回合Ops2 → BattleLog.rounds` 落 Room `battle_logs`
 * 实体（决策类），但 BATTLE 分区的抽取序被跨语言对拍**逐位锁定**
 * （DiffSectBattleTest 等）——措辞抽取既**不得**插入 BATTLE 分区（会平移
 * 后续战斗结果的抽取序），也不得新增分区（`RngSourceGuardTest` 的分区登记面
 * 归 W4-A 独占）。故对齐 `GameEngineWorldBattleOps.applyDeterministicWinAttr`
 * 的既有口径：**以战斗上下文散列代替随机抽取**（攻击者 id / 目标 id / 回合号
 * 混合散列选词）——零抽取、零分区影响，存档→读档→重放措辞逐位可复现。
 */
object BattleDescriptionGenerator {

    /**
     * 上下文散列（与 [com.xianxia.sect.core.model.Disciple] id 等稳定输入混合；
     * String.hashCode 为 JVM 规范固定算法，跨进程逐位一致）。
     */
    private fun saltOf(vararg parts: Any?): Int {
        var h = 17
        for (part in parts) h = h * 31 + (part?.hashCode() ?: 0)
        return h xor (h ushr 15)
    }

    /** 按上下文散列确定性选词（代替原 `.random()`；见类 KDoc） */
    private fun <T> List<T>.pick(salt: Int): T = this[Math.floorMod(salt, size)]

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
        isKill: Boolean,
        turn: Int
    ): String {
        val sb = StringBuilder()

        if (result.isDodged) {
            val dodgeDesc = dodgeDescriptions.pick(saltOf("dodge", attacker.id, target.id, turn))
            return "${target.name}${dodgeDesc}${attacker.name}的攻击！"
        }

        val isPhysical = result.isPhysical
        val isBeast = attacker.isBeast

        val attackVerb = if (isBeast) {
            if (isPhysical) {
                beastPhysicalAttackVerbs.pick(saltOf("bPhys", attacker.id, target.id, turn))
            } else {
                beastMagicAttackVerbs.pick(saltOf("bMag", attacker.id, target.id, turn))
            }
        } else {
            if (isPhysical) {
                getWeaponVerbs(attacker.weaponName).pick(saltOf("w", attacker.id, target.id, turn))
            } else {
                magicAttackVerbs.pick(saltOf("mag", attacker.id, target.id, turn))
            }
        }

        val damageType = if (isPhysical) "物理" else "法术"

        sb.append("${attacker.name}${attackVerb}${target.name}")

        if (result.isCrit) {
            sb.append("，${critDescriptions.pick(saltOf("crit", attacker.id, target.id, turn, result.damage))}")
        }

        sb.append("，造成${result.damage}点${damageType}伤害")

        if (result.hits > 1) {
            sb.append("（${result.hits}连击）")
        }

        if (isKill) {
            val killDesc = killDescriptions.pick(saltOf("kill", attacker.id, target.id, turn))
            sb.append("，${killDesc}了${target.name}！")
        }

        return sb.toString()
    }

    fun generateSkillDescription(
        attacker: Combatant,
        target: Combatant,
        skill: CombatSkill,
        result: AttackResult,
        isKill: Boolean,
        turn: Int
    ): String {
        val sb = StringBuilder()

        if (result.isDodged) {
            val dodgeDesc = dodgeDescriptions.pick(saltOf("dodge", attacker.id, target.id, turn))
            return "${target.name}${dodgeDesc}${attacker.name}的[${skill.name}]！"
        }

        val castPhrase = skillCastPhrases.pick(saltOf("cast", attacker.id, skill.name, turn))
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
            sb.append("，${critDescriptions.pick(saltOf("crit", attacker.id, target.id, turn, result.damage))}")
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
            val killDesc = killDescriptions.pick(saltOf("kill", attacker.id, target.id, turn))
            sb.append("，${killDesc}了${target.name}！")
        }

        return sb.toString()
    }

    fun generateSupportSkillDescription(
        caster: Combatant,
        skill: CombatSkill,
        healAmount: Int,
        healType: HealType,
        buffs: List<Triple<BuffType, Double, Int>>,
        turn: Int
    ): String {
        val sb = StringBuilder()

        val castPhrase = skillCastPhrases.pick(saltOf("cast", caster.id, skill.name, turn))
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
        isKill: Boolean,
        turn: Int
    ): String {
        val sb = StringBuilder()

        val castPhrase = skillCastPhrases.pick(saltOf("cast", attacker.id, skill.name, turn))
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
            val killDesc = killDescriptions.pick(saltOf("kill", attacker.id, turn))
            sb.append("，${killDesc}了敌人！")
        }

        return sb.toString()
    }
}
