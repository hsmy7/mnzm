// 由 scripts/gen-templates.mjs 生成 — 禁止手改（与 Kotlin EquipmentDatabase 同源）
#pragma once

#include <cstdint>
#include <string>
#include <vector>

// ============================================================
// 装备模板静态表（与 Kotlin EquipmentDatabase 同源）
// 字段与 EquipmentTemplate 构造参数一致；slot 为 EquipmentSlot.name
// ============================================================
namespace gamecore::data {

struct EquipmentTemplate {
    std::string id;
    std::string name;
    std::string slot;
    int32_t rarity = 0;
    int32_t physicalAttack = 0;
    int32_t magicAttack = 0;
    int32_t physicalDefense = 0;
    int32_t magicDefense = 0;
    int32_t speed = 0;
    int32_t hp = 0;
    int32_t mp = 0;
    double critChance = 0.0;
    std::string description;
    int32_t price = 0;
};

/// B16/R6.2：数值等价守卫用（逐字段默认比较；C++20 自动派生 !=）
inline bool operator==(const EquipmentTemplate& a, const EquipmentTemplate& b) {
    return a.id == b.id && a.name == b.name && a.slot == b.slot && a.rarity == b.rarity && a.physicalAttack == b.physicalAttack && a.magicAttack == b.magicAttack && a.physicalDefense == b.physicalDefense && a.magicDefense == b.magicDefense && a.speed == b.speed && a.hp == b.hp && a.mp == b.mp && a.critChance == b.critChance && a.description == b.description && a.price == b.price;
}

/// 全部装备模板（weapons + armors + boots + accessories）
///
/// B16/R6.2 数值外置：本表为**内联默认值兜底**，与数据文件
/// `assets/data/game-data.json` 的 `db.equipment` 段**同源**
/// （均由 scripts/gen-templates.mjs 从 scripts/data/equipment_db_sample.json
/// 产出）。运行时由 `gamecore/data/data_inject.h` 在引擎初始化期一次性注入；
/// 注入前/失败时此处即为权威值（与数据文件默认值逐字段相等，守卫锁定）。
inline std::vector<EquipmentTemplate>& equipmentTemplatesMutable() {
    static std::vector<EquipmentTemplate> kTemplates = {
        {
            "ironSword", "精铁剑", "WEAPON", 1,
            15, 0,
            0, 0,
            0, 0, 0,
            0.03, "普通铁匠打造的精铁剑，物理攻击力出众", 4000,
        },
        {
            "bronzeDagger", "精铁刀", "WEAPON", 1,
            21, 0,
            0, 0,
            0, 0, 0,
            0.06, "精铁锻造的宝刀，锋利易暴击", 4000,
        },
        {
            "woodenStaff", "桃木杖", "WEAPON", 1,
            0, 15,
            0, 0,
            0, 0, 0,
            0.03, "百年桃木制成的法杖，法术攻击力强大", 4000,
        },
        {
            "crystalOrb", "碧木扇", "WEAPON", 1,
            0, 21,
            0, 0,
            0, 0, 0,
            0.06, "碧木炼制的灵扇，蕴含微量灵气，易触发暴击", 4000,
        },
        {
            "spiritSword", "灵锋剑", "WEAPON", 2,
            45, 0,
            0, 0,
            0, 0, 0,
            0.06, "注入灵气的锋利长剑，物理攻击力强大", 16000,
        },
        {
            "battleAxe", "凌华刀", "WEAPON", 2,
            66, 0,
            0, 0,
            0, 0, 0,
            0.09, "刀光凌厉如华，威力巨大且易暴击", 16000,
        },
        {
            "jadeStaff", "碧玉杖", "WEAPON", 2,
            0, 45,
            0, 0,
            0, 0, 0,
            0.06, "碧玉雕刻的法杖，法术攻击力出众", 16000,
        },
        {
            "spiritFan", "灵风扇", "WEAPON", 2,
            0, 66,
            0, 0,
            0, 0, 0,
            0.09, "可扇出灵风的法器，攻击易暴击", 16000,
        },
        {
            "frostBlade", "青碧刃", "WEAPON", 3,
            138, 0,
            0, 0,
            0, 0, 0,
            0.09, "蕴含青碧灵力的宝刀，物理攻击力出众", 80000,
        },
        {
            "flameSword", "烈焰剑", "WEAPON", 3,
            204, 0,
            0, 0,
            0, 0, 0,
            0.135, "燃烧着火焰的灵剑，攻击易暴击", 80000,
        },
        {
            "thunderStaff", "玄雷杖", "WEAPON", 3,
            0, 138,
            0, 0,
            0, 0, 0,
            0.09, "可召唤雷电的法杖，法术攻击力强大", 80000,
        },
        {
            "frostOrb", "玄冰扇", "WEAPON", 3,
            0, 204,
            0, 0,
            0, 0, 0,
            0.135, "蕴含玄冰之力的宝扇，攻击易暴击", 80000,
        },
        {
            "thunderSword", "雷霆剑", "WEAPON", 4,
            428, 0,
            0, 0,
            0, 0, 0,
            0.12, "引动天雷的玄妙飞剑，物理攻击力惊人", 480000,
        },
        {
            "shadowBlade", "暗影刃", "WEAPON", 4,
            630, 0,
            0, 0,
            0, 0, 0,
            0.18, "来无影去无踪的暗杀之刃，暴击率极高", 480000,
        },
        {
            "voidStaff", "虚华杖", "WEAPON", 4,
            0, 428,
            0, 0,
            0, 0, 0,
            0.12, "虚华流转的玄妙法杖，法术攻击力出众", 480000,
        },
        {
            "phoenixFan", "凰焰扇", "WEAPON", 4,
            0, 630,
            0, 0,
            0, 0, 0,
            0.18, "凰焰淬炼的神扇，攻击易暴击", 480000,
        },
        {
            "dragonSlayer", "凤炎刃", "WEAPON", 5,
            1320, 0,
            0, 0,
            0, 0, 0,
            0.15000000000000002, "蕴含凤炎之力的绝世神兵，物理攻击力无双", 3360000,
        },
        {
            "godSlayer", "青莲剑", "WEAPON", 5,
            1920, 0,
            0, 0,
            0, 0, 0,
            0.22499999999999998, "传说中自青莲中诞生的神剑，剑气纵横天地间", 3360000,
        },
        {
            "phoenixWing", "阴阳扇", "WEAPON", 5,
            0, 1320,
            0, 0,
            0, 0, 0,
            0.15000000000000002, "蕴含阴阳之力的神扇，法术攻击力出众", 3360000,
        },
        {
            "celestialOrb", "天玄杖", "WEAPON", 5,
            0, 1920,
            0, 0,
            0, 0, 0,
            0.22499999999999998, "蕴含天玄之力的神杖，攻击易暴击", 3360000,
        },
        {
            "immortalSword", "诛仙剑", "WEAPON", 6,
            4050, 0,
            0, 0,
            0, 0, 0,
            0.195, "上古仙人遗留的仙器，物理攻击力毁天灭地", 26880000,
        },
        {
            "chaosBlade", "玄玉刃", "WEAPON", 6,
            5970, 0,
            0, 0,
            0, 0, 0,
            0.27, "玄玉淬炼的神刃，暴击率超凡入圣", 26880000,
        },
        {
            "primordialStaff", "天星杖", "WEAPON", 6,
            0, 4050,
            0, 0,
            0, 0, 0,
            0.195, "凝聚天星之力的法杖，法术攻击力无双", 26880000,
        },
        {
            "yinYangOrb", "天玄扇", "WEAPON", 6,
            0, 5970,
            0, 0,
            0, 0, 0,
            0.27, "蕴含天玄道韵的至宝，攻击易暴击", 26880000,
        },
        {
            "leatherArmor", "皮甲", "ARMOR", 1,
            0, 0,
            11, 0,
            0, 126, 0,
            0, "野兽皮革制成的护甲，物理防御出众", 4000,
        },
        {
            "chainMail", "锁子甲", "ARMOR", 1,
            0, 0,
            17, 0,
            0, 189, 0,
            0, "铁环编织的护甲，增强生命力", 4000,
        },
        {
            "bronzePlate", "精铁甲", "ARMOR", 1,
            0, 0,
            0, 11,
            0, 126, 0,
            0, "精铁铸造的铠甲，坚固且增强生命", 4000,
        },
        {
            "clothRobe", "灵竹衣", "ARMOR", 1,
            0, 0,
            0, 17,
            0, 189, 0,
            0, "灵竹纤维制成的衣物，法术防御出众", 4000,
        },
        {
            "ironPlate", "碧叶甲", "ARMOR", 2,
            0, 0,
            33, 0,
            0, 396, 0,
            0, "碧玉叶片打造的护甲，物理防御力强大", 16000,
        },
        {
            "steelArmor", "丹羽衣", "ARMOR", 2,
            0, 0,
            51, 0,
            0, 594, 0,
            0, "丹砂羽线织成的法衣，增强生命力", 16000,
        },
        {
            "spiritRobe", "灵丝袍", "ARMOR", 2,
            0, 0,
            0, 33,
            0, 396, 0,
            0, "灵蚕丝织成的法袍，法术防御出众", 16000,
        },
        {
            "cloudRobe", "云纹袍", "ARMOR", 2,
            0, 0,
            0, 51,
            0, 594, 0,
            0, "绣有云纹的法袍，增强生命力", 16000,
        },
        {
            "scaleArmor", "青鳞铠", "ARMOR", 3,
            0, 0,
            102, 0,
            0, 1269, 0,
            0, "妖兽青鳞打造的铠甲，物理防御力惊人", 80000,
        },
        {
            "plateArmor", "银板铠", "ARMOR", 3,
            0, 0,
            153, 0,
            0, 1905, 0,
            0, "整块银钢锻造的铠甲，增强生命力", 80000,
        },
        {
            "mysticRobe", "汐流衣", "ARMOR", 3,
            0, 0,
            0, 102,
            0, 1269, 0,
            0, "蕴含汐流之力的法衣，法术防御出众", 80000,
        },
        {
            "starRobe", "星辰袍", "ARMOR", 3,
            0, 0,
            0, 153,
            0, 1905, 0,
            0, "绣有星辰图案的法袍，增强生命力", 80000,
        },
        {
            "dragonScale", "龙鳞铠", "ARMOR", 4,
            0, 0,
            315, 0,
            0, 4032, 0,
            0, "真龙鳞片锻造的铠甲，物理防御无双", 480000,
        },
        {
            "titanArmor", "渊岩铠", "ARMOR", 4,
            0, 0,
            473, 0,
            0, 6048, 0,
            0, "深渊岩铁铸造的铠甲，增强生命力", 480000,
        },
        {
            "voidRobe", "瑶光袍", "ARMOR", 4,
            0, 0,
            0, 315,
            0, 4032, 0,
            0, "蕴含瑶光之力的法袍，法术防御出众", 480000,
        },
        {
            "moonRobe", "月华袍", "ARMOR", 4,
            0, 0,
            0, 473,
            0, 6048, 0,
            0, "吸收月华之力织成的法袍，增强生命力", 480000,
        },
        {
            "earthArmor", "玄幽袍", "ARMOR", 5,
            0, 0,
            975, 0,
            0, 12750, 0,
            0, "承载玄幽之力的法袍，物理防御如幽渊般坚固", 3360000,
        },
        {
            "divinePlate", "墨幽铠", "ARMOR", 5,
            0, 0,
            1467, 0,
            0, 19140, 0,
            0, "墨幽玄铁铸造的铠甲，增强生命力", 3360000,
        },
        {
            "celestialRobe", "凌星袍", "ARMOR", 5,
            0, 0,
            0, 975,
            0, 12750, 0,
            0, "凌驾星辰之力的法袍，法术防御超凡入圣", 3360000,
        },
        {
            "voidShadowRobe", "定海铠", "ARMOR", 5,
            0, 0,
            0, 1467,
            0, 19140, 0,
            0, "定海之力凝聚的铠甲，增强生命力", 3360000,
        },
        {
            "immortalArmor", "不朽铠", "ARMOR", 6,
            0, 0,
            3000, 0,
            0, 39300, 0,
            0, "仙界神甲，物理防御不朽不灭", 26880000,
        },
        {
            "primordialArmor", "苍罡铠", "ARMOR", 6,
            0, 0,
            4500, 0,
            0, 59100, 0,
            0, "苍罡之力凝聚的神甲，承载创世生命之力", 26880000,
        },
        {
            "immortalRobe", "曦光铠", "ARMOR", 6,
            0, 0,
            0, 3000,
            0, 39300, 0,
            0, "蕴含曦光之力的铠甲，法术防御超凡入圣", 26880000,
        },
        {
            "chaosRobe", "云影袍", "ARMOR", 6,
            0, 0,
            0, 4500,
            0, 59100, 0,
            0, "云影交织的法袍，蕴含无尽生命之力", 26880000,
        },
        {
            "clothBoots", "青澜靴", "BOOTS", 1,
            0, 0,
            0, 0,
            11, 126, 0,
            0, "青澜丝线织就的轻靴，提升移动速度", 4000,
        },
        {
            "leatherBoots", "兽皮靴", "BOOTS", 1,
            0, 0,
            0, 0,
            15, 189, 0,
            0, "兽皮鞣制的厚靴，增强生命力", 4000,
        },
        {
            "swiftBoots", "疾风靴", "BOOTS", 2,
            0, 0,
            0, 0,
            33, 396, 0,
            0, "穿上可大幅提升移动速度", 16000,
        },
        {
            "lightBoots", "轻羽靴", "BOOTS", 2,
            0, 0,
            0, 0,
            50, 594, 0,
            0, "如羽毛般轻盈，蕴含生命之力", 16000,
        },
        {
            "windBoots", "追风靴", "BOOTS", 3,
            0, 0,
            0, 0,
            104, 1269, 0,
            0, "追逐风的速度，极速无双", 80000,
        },
        {
            "mistBoots", "云栖靴", "BOOTS", 3,
            0, 0,
            0, 0,
            155, 1905, 0,
            0, "云栖之处步履轻盈，蕴含浓郁生命气息", 80000,
        },
        {
            "cloudBoots", "踏云履", "BOOTS", 4,
            0, 0,
            0, 0,
            323, 4032, 0,
            0, "踏云而行的仙家法宝，速度惊人", 480000,
        },
        {
            "thunderBoots", "奔雷靴", "BOOTS", 4,
            0, 0,
            0, 0,
            480, 6048, 0,
            0, "如雷电般厚重，蕴含磅礴生命力", 480000,
        },
        {
            "voidBoots", "溯光靴", "BOOTS", 5,
            0, 0,
            0, 0,
            996, 12750, 0,
            0, "溯光逐影穿梭虚空，速度无双", 3360000,
        },
        {
            "shadowStepBoots", "赤煞靴", "BOOTS", 5,
            0, 0,
            0, 0,
            1488, 19140, 0,
            0, "赤煞之气凝聚的战靴，蕴含浩瀚生命力", 3360000,
        },
        {
            "immortalBoots", "鸾羽履", "BOOTS", 6,
            0, 0,
            0, 0,
            3072, 39300, 0,
            0, "鸾鸟仙羽织就的灵履，速度超凡入圣", 26880000,
        },
        {
            "chaosStepBoots", "鹤岚靴", "BOOTS", 6,
            0, 0,
            0, 0,
            4575, 59100, 0,
            0, "鹤翔岚雾而行，蕴含无尽生命之力", 26880000,
        },
        {
            "jadeRing", "玉戒指", "ACCESSORY", 1,
            0, 0,
            0, 0,
            11, 0, 63,
            0, "蕴含微量灵气的玉戒指，灵力出众", 4000,
        },
        {
            "copperNecklace", "铜项链", "ACCESSORY", 1,
            0, 0,
            0, 0,
            15, 0, 95,
            0, "铜制项链，可提升身法速度", 4000,
        },
        {
            "spiritPendant", "灵玉佩", "ACCESSORY", 2,
            0, 0,
            0, 0,
            33, 0, 204,
            0, "蕴含灵气的玉佩，灵力出众", 16000,
        },
        {
            "healthRing", "蕴灵戒", "ACCESSORY", 2,
            0, 0,
            0, 0,
            50, 0, 308,
            0, "可蕴养灵力的戒指，灵力出众", 16000,
        },
        {
            "storageRing", "灵泉戒", "ACCESSORY", 3,
            0, 0,
            0, 0,
            104, 0, 645,
            0, "蕴含灵泉之力的戒指，灵力出众", 80000,
        },
        {
            "wisdomOrb", "迅捷珠", "ACCESSORY", 3,
            0, 0,
            0, 0,
            155, 0, 968,
            0, "可提升身法速度的宝珠", 80000,
        },
        {
            "dragonEye", "龙灵珠", "ACCESSORY", 4,
            0, 0,
            0, 0,
            323, 0, 2040,
            0, "真龙之灵凝聚的宝珠，灵力出众", 480000,
        },
        {
            "phoenixHeart", "凤羽坠", "ACCESSORY", 4,
            0, 0,
            0, 0,
            480, 0, 3060,
            0, "凤凰羽翼炼制的坠饰，可提升身法速度", 480000,
        },
        {
            "earthCore", "渡厄佩", "ACCESSORY", 5,
            0, 0,
            0, 0,
            996, 0, 6420,
            0, "可渡厄解难的灵佩，灵力出众", 3360000,
        },
        {
            "dragonEyePendant", "隐云佩", "ACCESSORY", 5,
            0, 0,
            0, 0,
            1488, 0, 9630,
            0, "隐于云端的灵佩，可大幅提升身法速度", 3360000,
        },
        {
            "chaosBead", "幽朔珠", "ACCESSORY", 6,
            0, 0,
            0, 0,
            3072, 0, 20100,
            0, "蕴含幽朔之力的灵珠，灵力超凡入圣", 26880000,
        },
        {
            "heavenRing", "长明坠", "ACCESSORY", 6,
            0, 0,
            0, 0,
            4575, 0, 30150,
            0, "长明不灭的灵坠，身法速度无双", 26880000,
        },
    };
    return kTemplates;
}

/// 只读消费入口（52 个消费点的唯一通道；B16 在外置后保持签名零变更）
inline const std::vector<EquipmentTemplate>& equipmentTemplates() {
    return equipmentTemplatesMutable();
}

}  // namespace gamecore::data
