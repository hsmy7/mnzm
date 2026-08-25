// 由 scripts/gen-templates.mjs 生成 — 禁止手改（与 Kotlin HerbDatabase 同源）
#pragma once

#include <cstdint>
#include <string>
#include <vector>

// ============================================================
// 灵草/种子静态表（Kotlin HerbDatabase 提取，批次 4c）
// 字段与 HerbDatabase.Herb/Seed 构造参数一致
// ============================================================
namespace gamecore::data {

struct HerbTemplate {
    std::string id;
    std::string name;
    int32_t tier = 1;
    int32_t rarity = 1;
    std::string category;
    std::string description;
};

struct SeedTemplate {
    std::string id;
    std::string name;
    int32_t tier = 1;
    int32_t rarity = 1;
    int32_t growTime = 36;
    int32_t yield = 1;
    std::string description;
};

/// 全部灵草模板
inline const std::vector<HerbTemplate>& herbTemplates() {
    static const std::vector<HerbTemplate> kHerbs = {
        {"spiritGrass1", "聚灵草", 1, 1, "grass", "吸收天地灵气而生的灵草，炼丹基础材料"},
        {"spiritGrass2", "清心草", 1, 1, "grass", "叶片清凉，可清心明目，安神定志"},
        {"spiritGrass3", "凝气草", 1, 1, "grass", "凝聚灵气的灵草，辅助修炼佳品"},
        {"spiritFlower1", "云雾花", 1, 1, "flower", "生于云雾间的灵花，清热解毒"},
        {"spiritFlower2", "白莲", 1, 1, "flower", "洁白无瑕的莲花，清心降火"},
        {"spiritFlower3", "晨露花", 1, 1, "flower", "清晨露水滋养的灵花，润肺生津"},
        {"spiritFruit1", "精气果", 1, 1, "fruit", "蕴含精气的灵果，明目养肾"},
        {"spiritFruit2", "赤心果", 1, 1, "fruit", "形似红心的灵果，补气养血"},
        {"spiritFruit3", "灵韵果", 1, 1, "fruit", "蕴含灵韵的灵果，滋养经脉"},
        {"spiritGrass4", "寒霜草", 2, 2, "grass", "冰寒之地生长的灵草，蕴含寒气"},
        {"spiritGrass5", "烈焰草", 2, 2, "grass", "火山附近生长的灵草，蕴含火力"},
        {"spiritGrass6", "金灵草", 2, 2, "grass", "吸收金精之气而生，泻火解毒"},
        {"spiritFlower4", "冰魄莲", 2, 2, "flower", "生于极寒之地的冰属性灵花，花瓣如冰晶"},
        {"spiritFlower5", "双生花", 2, 2, "flower", "金银双色并蒂而生，可解百毒"},
        {"spiritFlower6", "紫霄花", 2, 2, "flower", "紫气缭绕的灵花，安神定魄"},
        {"spiritFruit4", "通灵果", 2, 2, "fruit", "可通经脉的灵果，消肿散结"},
        {"spiritFruit5", "玄灵果", 2, 2, "fruit", "色如墨玉的灵果，滋补肝肾"},
        {"spiritFruit6", "五行果", 2, 2, "fruit", "蕴含五行之气的奇果，调和阴阳"},
        {"spiritGrass7", "龙血草", 3, 3, "grass", "沾染真龙之血而生的灵草，蕴含龙威"},
        {"spiritGrass8", "风铃草", 3, 3, "grass", "随风摇曳的灵草，祛风除湿"},
        {"spiritGrass9", "九转灵草", 3, 3, "grass", "九转轮回方可成熟的灵草，蕴含轮回之力"},
        {"spiritFlower7", "九转仙兰", 3, 3, "flower", "九转轮回方可盛开的仙界灵花"},
        {"spiritFlower8", "凤凰花", 3, 3, "flower", "凤凰栖息之地生长的灵花，蕴含涅槃之力"},
        {"spiritFlower9", "青龙花", 3, 3, "flower", "龙气滋养的灵花，强筋健骨"},
        {"spiritFruit7", "赤阳果", 3, 3, "fruit", "吸收日精月华而生的赤红灵果"},
        {"spiritFruit8", "玄灵莓", 3, 3, "fruit", "色如墨玉的灵果，滋补肝肾"},
        {"spiritFruit9", "天元果", 3, 3, "fruit", "天元之力凝结的灵果，补气固本"},
        {"spiritGrass10", "玄冰草", 4, 4, "grass", "万年玄冰中孕育的冰魄精华"},
        {"spiritGrass11", "风暴草", 4, 4, "grass", "飓风区域生长的灵草，化风定惊"},
        {"spiritGrass12", "神命草", 4, 4, "grass", "神山之巅的灵草，起死回生"},
        {"spiritFlower10", "日月同辉花", 4, 4, "flower", "日月同辉之时方可绽放的神花"},
        {"spiritFlower11", "紫云花", 4, 4, "flower", "生于紫云深处的灵花，补气安神"},
        {"spiritFlower12", "玄武花", 4, 4, "flower", "玄武守护之地生长的灵花，蕴含大地之力"},
        {"spiritFruit10", "长生果", 4, 4, "fruit", "三千年一开花，三千年一结果的仙果"},
        {"spiritFruit11", "仙灵果", 4, 4, "fruit", "仙界遗落的灵果，蕴含仙气"},
        {"spiritFruit12", "天灵果", 4, 4, "fruit", "吸收天界灵气而生的神果"},
        {"spiritGrass13", "仙灵草", 5, 5, "grass", "仙界遗落的灵草，蕴含仙气"},
        {"spiritGrass14", "天灵草", 5, 5, "grass", "吸收天界灵气而生的神草"},
        {"spiritGrass15", "混沌草", 5, 5, "grass", "混沌初开时诞生的神草，蕴含混沌本源之力"},
        {"spiritFlower13", "涅槃凤仙花", 5, 5, "flower", "凤凰涅槃之地孕育的仙花，花瓣如凤羽般绚烂"},
        {"spiritFlower14", "龙鳞仙莲", 5, 5, "flower", "真龙栖息之池生长的仙莲，莲瓣如龙鳞般坚韧"},
        {"spiritFlower15", "白虎幽兰", 5, 5, "flower", "白虎栖息之谷生长的幽兰，花香蕴含杀伐之气"},
        {"spiritFruit13", "九叶还魂果", 5, 5, "fruit", "九叶齐生，有还魂续命之效的仙果"},
        {"spiritFruit14", "玄天灵果", 5, 5, "fruit", "玄天之上孕育的灵果，通体玄光流转"},
        {"spiritFruit15", "星陨神果", 5, 5, "fruit", "星辰陨落后凝结的神果，蕴含星力"},
        {"spiritGrass16", "鸿蒙草", 6, 6, "grass", "鸿蒙初开时诞生的神草，蕴含鸿蒙本源"},
        {"spiritGrass17", "太初草", 6, 6, "grass", "太古时期诞生的神草，蕴含太初之力"},
        {"spiritGrass18", "永恒草", 6, 6, "grass", "永恒不灭的神草，时间法则的化身"},
        {"spiritFlower16", "永恒花", 6, 6, "flower", "永恒不谢的仙花，时间法则的化身"},
        {"spiritFlower17", "混沌仙莲", 6, 6, "flower", "混沌中诞生的仙莲，蕴含混沌本源"},
        {"spiritFlower18", "造化神花", 6, 6, "flower", "天地造化孕育的神花，蕴含造化之力"},
        {"spiritFruit16", "瑞麟仙果", 6, 6, "fruit", "瑞兽麒麟守护万年的仙果，已生灵智"},
        {"spiritFruit17", "玄武帝果", 6, 6, "fruit", "玄武守护之地生长的天品灵果，蕴含大地精华"},
        {"spiritFruit18", "混沌神果", 6, 6, "fruit", "混沌初开时诞生的神果，蕴含混沌本源"},
    };
    return kHerbs;
}

/// 全部种子模板
inline const std::vector<SeedTemplate>& seedTemplates() {
    static const std::vector<SeedTemplate> kSeeds = {
        {"spiritGrass1Seed", "聚灵草种", 1, 1, 36, 5, "种植后可收获聚灵草"},
        {"spiritGrass2Seed", "清心草种", 1, 1, 36, 5, "种植后可收获清心草"},
        {"spiritGrass3Seed", "凝气草种", 1, 1, 36, 4, "种植后可收获凝气草"},
        {"spiritFlower1Seed", "云雾花种", 1, 1, 36, 5, "种植后可收获云雾花"},
        {"spiritFlower2Seed", "白莲种", 1, 1, 36, 4, "种植后可收获白莲"},
        {"spiritFlower3Seed", "晨露花种", 1, 1, 36, 5, "种植后可收获晨露花"},
        {"spiritFruit1Seed", "精气果核", 1, 1, 36, 5, "种植后可收获精气果"},
        {"spiritFruit2Seed", "赤心果核", 1, 1, 36, 4, "种植后可收获赤心果"},
        {"spiritFruit3Seed", "灵韵果核", 1, 1, 36, 5, "种植后可收获灵韵果"},
        {"spiritGrass4Seed", "寒霜草种", 2, 2, 72, 4, "种植后可收获寒霜草"},
        {"spiritGrass5Seed", "烈焰草种", 2, 2, 72, 4, "种植后可收获烈焰草"},
        {"spiritGrass6Seed", "金灵草种", 2, 2, 72, 4, "种植后可收获金灵草"},
        {"spiritFlower4Seed", "冰魄莲种", 2, 2, 72, 3, "种植后可收获冰魄莲"},
        {"spiritFlower5Seed", "双生花种", 2, 2, 72, 4, "种植后可收获双生花"},
        {"spiritFlower6Seed", "紫霄花种", 2, 2, 72, 4, "种植后可收获紫霄花"},
        {"spiritFruit4Seed", "通灵果核", 2, 2, 72, 4, "种植后可收获通灵果"},
        {"spiritFruit5Seed", "玄灵果核", 2, 2, 72, 4, "种植后可收获玄灵果"},
        {"spiritFruit6Seed", "五行果核", 2, 2, 72, 3, "种植后可收获五行果"},
        {"spiritGrass7Seed", "龙血草种", 3, 3, 240, 2, "种植后可收获龙血草"},
        {"spiritGrass8Seed", "风铃草种", 3, 3, 240, 2, "种植后可收获风铃草"},
        {"spiritGrass9Seed", "九转灵草种", 3, 3, 240, 2, "种植后可收获九转灵草"},
        {"spiritFlower7Seed", "九转仙兰种", 3, 3, 240, 2, "种植后可收获九转仙兰"},
        {"spiritFlower8Seed", "凤凰花种", 3, 3, 240, 2, "种植后可收获凤凰花"},
        {"spiritFlower9Seed", "青龙花种", 3, 3, 240, 2, "种植后可收获青龙花"},
        {"spiritFruit7Seed", "赤阳果核", 3, 3, 240, 2, "种植后可收获赤阳果"},
        {"spiritFruit8Seed", "玄灵莓种", 3, 3, 240, 2, "种植后可收获玄灵莓"},
        {"spiritFruit9Seed", "天元果核", 3, 3, 240, 2, "种植后可收获天元果"},
        {"spiritGrass10Seed", "玄冰草种", 4, 4, 540, 1, "种植后可收获玄冰草"},
        {"spiritGrass11Seed", "风暴草种", 4, 4, 540, 1, "种植后可收获风暴草"},
        {"spiritGrass12Seed", "神命草种", 4, 4, 540, 1, "种植后可收获神命草"},
        {"spiritFlower10Seed", "日月同辉种", 4, 4, 540, 1, "种植后可收获日月同辉花"},
        {"spiritFlower11Seed", "紫云花种", 4, 4, 540, 1, "种植后可收获紫云花"},
        {"spiritFlower12Seed", "玄武花种", 4, 4, 540, 1, "种植后可收获玄武花"},
        {"spiritFruit10Seed", "长生果核", 4, 4, 540, 1, "种植后可收获长生果"},
        {"spiritFruit11Seed", "仙灵果核", 4, 4, 540, 1, "种植后可收获仙灵果"},
        {"spiritFruit12Seed", "天灵果核", 4, 4, 540, 1, "种植后可收获天灵果"},
        {"spiritGrass13Seed", "仙灵草种", 5, 5, 840, 1, "种植后可收获仙灵草"},
        {"spiritGrass14Seed", "天灵草种", 5, 5, 840, 1, "种植后可收获天灵草"},
        {"spiritGrass15Seed", "混沌草种", 5, 5, 840, 1, "种植后可收获混沌草"},
        {"spiritFlower13Seed", "凤仙花种", 5, 5, 840, 1, "种植后可收获涅槃凤仙花"},
        {"spiritFlower14Seed", "龙鳞莲种", 5, 5, 840, 1, "种植后可收获龙鳞仙莲"},
        {"spiritFlower15Seed", "白虎幽兰种", 5, 5, 840, 1, "种植后可收获白虎幽兰"},
        {"spiritFruit13Seed", "还魂果核", 5, 5, 840, 1, "种植后可收获九叶还魂果"},
        {"spiritFruit14Seed", "玄天灵果核", 5, 5, 840, 1, "种植后可收获玄天灵果"},
        {"spiritFruit15Seed", "星陨神果核", 5, 5, 840, 1, "种植后可收获星陨神果"},
        {"spiritGrass16Seed", "鸿蒙草种", 6, 6, 1440, 1, "种植后可收获鸿蒙草"},
        {"spiritGrass17Seed", "太初草种", 6, 6, 1440, 1, "种植后可收获太初草"},
        {"spiritGrass18Seed", "永恒草种", 6, 6, 1440, 1, "种植后可收获永恒草"},
        {"spiritFlower16Seed", "永恒花种", 6, 6, 1440, 1, "种植后可收获永恒花"},
        {"spiritFlower17Seed", "混沌仙莲种", 6, 6, 1440, 1, "种植后可收获混沌仙莲"},
        {"spiritFlower18Seed", "造化神花种", 6, 6, 1440, 1, "种植后可收获造化神花"},
        {"spiritFruit16Seed", "瑞麟仙果核", 6, 6, 1440, 1, "种植后可收获瑞麟仙果"},
        {"spiritFruit17Seed", "玄武帝果核", 6, 6, 1440, 1, "种植后可收获玄武帝果"},
        {"spiritFruit18Seed", "混沌神果核", 6, 6, 1440, 1, "种植后可收获混沌神果"},
    };
    return kSeeds;
}

/// 种子 id → 灵草 id（Kotlin seedToHerbMap：seed.id 去 "Seed" 后缀）
inline std::string herbIdFromSeedId(const std::string& seedId) {
    if (seedId.size() > 4 && seedId.compare(seedId.size() - 4, 4, "Seed") == 0) {
        return seedId.substr(0, seedId.size() - 4);
    }
    return {};
}

}  // namespace gamecore::data
