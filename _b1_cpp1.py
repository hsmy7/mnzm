# -*- coding: utf-8 -*-
"""B1 C++ 脚本 1：models.h 全结构单列化"""
import os
os.chdir(r'C:\Mnzm\XianxiaSectNative-equipment\android\app\src\main\cpp\gamecore')

def rd(p):
    return open(p, encoding='utf-8').read()

def wr(p, s):
    open(p, 'w', encoding='utf-8', newline='\n').write(s)

def patch(path, subs):
    s = rd(path)
    for old, new in subs:
        if old not in s:
            raise SystemExit('NOT FOUND in %s:\n%s' % (path, old[:200]))
        s = s.replace(old, new)
    wr(path, s)

p = 'include/gamecore/state/models.h'
patch(p, [
    # ── DiscipleData CombatAttributes ──
    ('''    // ── CombatAttributes（@Embedded 平铺；字段名与 Kotlin 序列化一致） ──
    int32_t baseHp = 120;
    int32_t baseMp = 60;
    int32_t basePhysicalAttack = 12;
    int32_t baseMagicAttack = 12;
    int32_t basePhysicalDefense = 10;
    int32_t baseMagicDefense = 8;
    int32_t baseSpeed = 15;
    int32_t hpVariance = 0;
    int32_t mpVariance = 0;
    int32_t physicalAttackVariance = 0;
    int32_t magicAttackVariance = 0;
    int32_t physicalDefenseVariance = 0;
    int32_t magicDefenseVariance = 0;
    int32_t speedVariance = 0;''',
     '''    // ── CombatAttributes（@Embedded 平铺；单列口径 B1，方案 §15）──
    // 旧档 JSON 的物法双列键由 json_codec 读档归一化（基值取和/方差均值），
    // 结构体只承载单列；innateDamageType 空 = 存量旧弟子按模板/灵根派生。
    int32_t baseHp = 120;
    int32_t baseMp = 60;
    int32_t baseAttack = 24;
    int32_t baseDefense = 18;
    int32_t baseSpeed = 15;
    int32_t hpVariance = 0;
    int32_t mpVariance = 0;
    int32_t attackVariance = 0;
    int32_t defenseVariance = 0;
    int32_t speedVariance = 0;
    std::string innateDamageType;           // DamageType.name；空串 = 未派生'''),
    # ── PillEffects（DiscipleData 段） ──
    ('''    // ── PillEffects（@Embedded 平铺） ──
    int32_t pillPhysicalAttackBonus = 0;
    int32_t pillMagicAttackBonus = 0;
    int32_t pillPhysicalDefenseBonus = 0;
    int32_t pillMagicDefenseBonus = 0;
    int32_t pillHpBonus = 0;''',
     '''    // ── PillEffects（@Embedded 平铺；单列口径 B1，旧四列由 json_codec 归一化） ──
    int32_t pillAttackBonus = 0;
    int32_t pillDefenseBonus = 0;
    int32_t pillHpBonus = 0;'''),
    # ── ItemEffect：旧四字段保留 + 新单列字段 ──
    ('''    int32_t extendLife = 0;
    int32_t physicalAttackAdd = 0;
    int32_t magicAttackAdd = 0;
    int32_t physicalDefenseAdd = 0;
    int32_t magicDefenseAdd = 0;
    int32_t speedAdd = 0;
    double critRateAdd = 0.0;
    double critEffectAdd = 0.0;
    int32_t intelligenceAdd = 0;
    int32_t charmAdd = 0;''',
     '''    int32_t extendLife = 0;
    // 单列口径（B1）：旧物法四字段保留（旧档 JSON 归一化读取源），
    // 消费点一律用 AttackAddTotal/DefenseAddTotal；新档写入只写新键
    int32_t physicalAttackAdd = 0;
    int32_t magicAttackAdd = 0;
    int32_t physicalDefenseAdd = 0;
    int32_t magicDefenseAdd = 0;
    int32_t attackAdd = 0;
    int32_t defenseAdd = 0;
    int32_t speedAdd = 0;
    double critRateAdd = 0.0;
    double critEffectAdd = 0.0;
    int32_t intelligenceAdd = 0;
    int32_t charmAdd = 0;'''),
    # ── PillEffect（仓库丹药嵌套）：同 ItemEffect 口径 ──
    ('''    int32_t duration = 3;
    bool cannotStack = true;
    int32_t physicalAttackAdd = 0;
    int32_t magicAttackAdd = 0;
    int32_t physicalDefenseAdd = 0;
    int32_t magicDefenseAdd = 0;
    int32_t hpAdd = 0;''',
     '''    int32_t duration = 3;
    bool cannotStack = true;
    // 单列口径（B1）：旧物法四字段保留（归一化读取源），消费点用 AttackAddTotal
    int32_t physicalAttackAdd = 0;
    int32_t magicAttackAdd = 0;
    int32_t physicalDefenseAdd = 0;
    int32_t magicDefenseAdd = 0;
    int32_t attackAdd = 0;
    int32_t defenseAdd = 0;
    int32_t hpAdd = 0;'''),
    # ── WorldLevel beast 四列 → 单列 ──
    ('''    int32_t beastMaxHp = 0;
    int32_t beastMaxMp = 0;
    int32_t beastPhysicalAttack = 0;
    int32_t beastMagicAttack = 0;
    int32_t beastPhysicalDefense = 0;
    int32_t beastMagicDefense = 0;
    int32_t beastSpeed = 0;
};''',
     '''    int32_t beastMaxHp = 0;
    int32_t beastMaxMp = 0;
    // 单列口径（B1）：旧 JSON 物法四键由 data_json/json_codec 归一化取和
    int32_t beastAttack = 0;
    int32_t beastDefense = 0;
    int32_t beastSpeed = 0;
};'''),
])

s = rd(p)
# ItemEffect/PillEffect 的有效值辅助函数（结构体尾部统一追加）
anchor = '''    std::string pillCategory;
    std::string pillType;
};'''
assert anchor in s
s = s.replace(anchor, '''    std::string pillCategory;
    std::string pillType;

    /// 有效攻击加成：新单列值 + 旧物法两列归一化（旧档 14/15 有值、新档恒 0）
    int32_t AttackAddTotal() const { return attackAdd + physicalAttackAdd + magicAttackAdd; }
    /// 有效防御加成：口径同 AttackAddTotal
    int32_t DefenseAddTotal() const { return defenseAdd + physicalDefenseAdd + magicDefenseAdd; }
};''')
anchor2 = '''    bool cannotStack = true;
    // 单列口径（B1）：旧物法四字段保留（归一化读取源），消费点用 AttackAddTotal
    int32_t physicalAttackAdd = 0;'''
assert anchor2 in s
wr(p, s)
print('models.h ok')
