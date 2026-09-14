// 由 scripts/gen-beast-material-db.mjs 生成 — 禁止手改（与 Kotlin BeastMaterialDatabase 同源）
#pragma once

#include <cstdint>
#include <string>
#include <vector>

// ============================================================
// 妖兽材料静态表（与 Kotlin BeastMaterialDatabase 同源）
// 字段与 BeastMaterial 构造参数一致；price/materialCategory 为派生值
// ============================================================
namespace gamecore::data {

struct BeastMaterialTemplate {
    std::string id;
    std::string name;
    int32_t tier = 1;
    int32_t rarity = 1;
    std::string category;
    std::string description;
    std::string icon;
    double dropWeight = 1.0;
    int32_t price = 0;
    std::string materialCategory;
};

/// 全部妖兽材料模板
inline const std::vector<BeastMaterialTemplate>& beastMaterialTemplates() {
    static const std::vector<BeastMaterialTemplate> kTemplates = {
        {"tigerHide0", "凡虎皮", 1, 1, "hide", "凡品虎妖的皮毛，蕴含狂暴之力", "🟧", 1, 400, "BEAST_HIDE"},
        {"tigerBlood0", "凡虎血", 1, 1, "blood", "凡品虎妖的精血，蕴含狂暴之力", "🩸", 1, 400, "BEAST_BLOOD"},
        {"tigerTooth0", "凡虎牙", 1, 1, "tooth", "凡品虎妖的利齿，锋利异常", "🦷", 0.8, 400, "BEAST_TOOTH"},
        {"tigerCore0", "凡虎内丹", 1, 1, "core", "凡品虎妖的内丹，蕴含狂暴灵力", "🔮", 0.5, 400, "BEAST_CORE"},
        {"tigerHide1", "灵虎皮", 2, 2, "hide", "灵品虎妖的皮毛，蕴含灵气与狂暴", "🟧", 0.8, 1600, "BEAST_HIDE"},
        {"tigerBlood1", "灵虎血", 2, 2, "blood", "灵品虎妖的精血，蕴含灵力与狂暴", "🩸", 0.8, 1600, "BEAST_BLOOD"},
        {"tigerTooth1", "灵虎牙", 2, 2, "tooth", "灵品虎妖的利齿，削铁如泥", "🦷", 0.6, 1600, "BEAST_TOOTH"},
        {"tigerCore1", "灵虎内丹", 2, 2, "core", "灵品虎妖的内丹，蕴含浓郁狂暴灵力", "🔮", 0.4, 1600, "BEAST_CORE"},
        {"tigerHide2", "宝虎皮", 3, 3, "hide", "宝品虎妖的皮毛，珍贵且蕴含强大力量", "🟧", 0.6, 8000, "BEAST_HIDE"},
        {"tigerBlood2", "宝虎血", 3, 3, "blood", "宝品虎妖的精血，蕴含强大狂暴之力", "🩸", 0.6, 8000, "BEAST_BLOOD"},
        {"tigerTooth2", "宝虎牙", 3, 3, "tooth", "宝品虎妖的利齿，可碎金石", "🦷", 0.5, 8000, "BEAST_TOOTH"},
        {"tigerCore2", "宝虎内丹", 3, 3, "core", "宝品虎妖的内丹，蕴含强大狂暴之力", "🔮", 0.3, 8000, "BEAST_CORE"},
        {"tigerHide3", "玄虎皮", 4, 4, "hide", "玄品虎妖的皮毛，蕴含玄妙狂暴之力", "🟧", 0.4, 48000, "BEAST_HIDE"},
        {"tigerBlood3", "玄虎血", 4, 4, "blood", "玄品虎妖的精血，蕴含玄妙狂暴", "🩸", 0.4, 48000, "BEAST_BLOOD"},
        {"tigerTooth3", "玄虎牙", 4, 4, "tooth", "玄品虎妖的利齿，蕴含玄妙锋芒", "🦷", 0.3, 48000, "BEAST_TOOTH"},
        {"tigerCore3", "玄虎内丹", 4, 4, "core", "玄品虎妖的内丹，蕴含玄妙狂暴灵力", "🔮", 0.2, 48000, "BEAST_CORE"},
        {"tigerHide4", "地虎皮", 5, 5, "hide", "地品虎妖的皮毛，蕴含大地狂暴之力", "🟧", 0.25, 336000, "BEAST_HIDE"},
        {"tigerBlood4", "地虎血", 5, 5, "blood", "地品虎妖的精血，蕴含大地狂暴", "🩸", 0.25, 336000, "BEAST_BLOOD"},
        {"tigerTooth4", "地虎牙", 5, 5, "tooth", "地品虎妖的利齿，蕴含大地锋芒", "🦷", 0.2, 336000, "BEAST_TOOTH"},
        {"tigerCore4", "地虎内丹", 5, 5, "core", "地品虎妖的内丹，蕴含大地狂暴灵力", "🔮", 0.12, 336000, "BEAST_CORE"},
        {"tigerHide5", "天虎皮", 6, 6, "hide", "天品虎妖的皮毛，蕴含天道狂暴之力", "🟧", 0.12, 2688000, "BEAST_HIDE"},
        {"tigerBlood5", "天虎血", 6, 6, "blood", "天品虎妖的精血，蕴含天道狂暴", "🩸", 0.12, 2688000, "BEAST_BLOOD"},
        {"tigerTooth5", "天虎牙", 6, 6, "tooth", "天品虎妖的利齿，蕴含天道锋芒", "🦷", 0.1, 2688000, "BEAST_TOOTH"},
        {"tigerCore5", "天虎内丹", 6, 6, "core", "天品虎妖的内丹，蕴含天道狂暴灵力", "🔮", 0.06, 2688000, "BEAST_CORE"},
        {"wolfHide0", "凡狼皮", 1, 1, "hide", "凡品狼妖的皮毛，轻便迅捷", "🟫", 1, 400, "BEAST_HIDE"},
        {"wolfBone0", "凡狼骨", 1, 1, "bone", "凡品狼妖的骨骼，轻而坚韧", "🦴", 1, 400, "BEAST_BONE"},
        {"wolfTooth0", "凡狼牙", 1, 1, "tooth", "凡品狼妖的利齿，锋利迅捷", "🦷", 0.8, 400, "BEAST_TOOTH"},
        {"wolfCore0", "凡狼内丹", 1, 1, "core", "凡品狼妖的内丹，蕴含迅捷灵力", "🔮", 0.5, 400, "BEAST_CORE"},
        {"wolfHide1", "灵狼皮", 2, 2, "hide", "灵品狼妖的皮毛，蕴含灵气与迅捷", "🟫", 0.8, 1600, "BEAST_HIDE"},
        {"wolfBone1", "灵狼骨", 2, 2, "bone", "灵品狼妖的骨骼，轻盈坚韧", "🦴", 0.8, 1600, "BEAST_BONE"},
        {"wolfTooth1", "灵狼牙", 2, 2, "tooth", "灵品狼妖的利齿，快如闪电", "🦷", 0.6, 1600, "BEAST_TOOTH"},
        {"wolfCore1", "灵狼内丹", 2, 2, "core", "灵品狼妖的内丹，蕴含浓郁迅捷灵力", "🔮", 0.4, 1600, "BEAST_CORE"},
        {"wolfHide2", "宝狼皮", 3, 3, "hide", "宝品狼妖的皮毛，珍贵且蕴含速度之力", "🟫", 0.6, 8000, "BEAST_HIDE"},
        {"wolfBone2", "宝狼骨", 3, 3, "bone", "宝品狼妖的骨骼，轻若鸿毛", "🦴", 0.6, 8000, "BEAST_BONE"},
        {"wolfTooth2", "宝狼牙", 3, 3, "tooth", "宝品狼妖的利齿，迅疾如风", "🦷", 0.5, 8000, "BEAST_TOOTH"},
        {"wolfCore2", "宝狼内丹", 3, 3, "core", "宝品狼妖的内丹，蕴含强大迅捷之力", "🔮", 0.3, 8000, "BEAST_CORE"},
        {"wolfHide3", "玄狼皮", 4, 4, "hide", "玄品狼妖的皮毛，蕴含玄妙迅捷之力", "🟫", 0.4, 48000, "BEAST_HIDE"},
        {"wolfBone3", "玄狼骨", 4, 4, "bone", "玄品狼妖的骨骼，蕴含玄妙轻盈", "🦴", 0.4, 48000, "BEAST_BONE"},
        {"wolfTooth3", "玄狼牙", 4, 4, "tooth", "玄品狼妖的利齿，蕴含玄妙速度", "🦷", 0.3, 48000, "BEAST_TOOTH"},
        {"wolfCore3", "玄狼内丹", 4, 4, "core", "玄品狼妖的内丹，蕴含玄妙迅捷灵力", "🔮", 0.2, 48000, "BEAST_CORE"},
        {"wolfHide4", "地狼皮", 5, 5, "hide", "地品狼妖的皮毛，蕴含大地迅捷之力", "🟫", 0.25, 336000, "BEAST_HIDE"},
        {"wolfBone4", "地狼骨", 5, 5, "bone", "地品狼妖的骨骼，蕴含大地轻盈", "🦴", 0.25, 336000, "BEAST_BONE"},
        {"wolfTooth4", "地狼牙", 5, 5, "tooth", "地品狼妖的利齿，蕴含大地速度", "🦷", 0.2, 336000, "BEAST_TOOTH"},
        {"wolfCore4", "地狼内丹", 5, 5, "core", "地品狼妖的内丹，蕴含大地迅捷灵力", "🔮", 0.12, 336000, "BEAST_CORE"},
        {"wolfHide5", "天狼皮", 6, 6, "hide", "天品狼妖的皮毛，蕴含天道迅捷之力", "🟫", 0.12, 2688000, "BEAST_HIDE"},
        {"wolfBone5", "天狼骨", 6, 6, "bone", "天品狼妖的骨骼，蕴含天道轻盈", "🦴", 0.12, 2688000, "BEAST_BONE"},
        {"wolfTooth5", "天狼牙", 6, 6, "tooth", "天品狼妖的利齿，蕴含天道速度", "🦷", 0.1, 2688000, "BEAST_TOOTH"},
        {"wolfCore5", "天狼内丹", 6, 6, "core", "天品狼妖的内丹，蕴含天道迅捷灵力", "🔮", 0.06, 2688000, "BEAST_CORE"},
        {"snakeScale0", "凡蛇鳞", 1, 1, "scale", "凡品蛇妖的鳞片，蕴含剧毒", "🟩", 1, 400, "BEAST_SCALE"},
        {"snakeBlood0", "凡蛇血", 1, 1, "blood", "凡品蛇妖的精血，蕴含剧毒之力", "🩸", 1, 400, "BEAST_BLOOD"},
        {"snakeTooth0", "凡蛇牙", 1, 1, "tooth", "凡品蛇妖的毒牙，蕴含剧毒", "🦷", 0.8, 400, "BEAST_TOOTH"},
        {"snakeCore0", "凡蛇内丹", 1, 1, "core", "凡品蛇妖的内丹，蕴含剧毒灵力", "🔮", 0.5, 400, "BEAST_CORE"},
        {"snakeScale1", "灵蛇鳞", 2, 2, "scale", "灵品蛇妖的鳞片，蕴含灵气与剧毒", "🟩", 0.8, 1600, "BEAST_SCALE"},
        {"snakeBlood1", "灵蛇血", 2, 2, "blood", "灵品蛇妖的精血，蕴含灵力与剧毒", "🩸", 0.8, 1600, "BEAST_BLOOD"},
        {"snakeTooth1", "灵蛇牙", 2, 2, "tooth", "灵品蛇妖的毒牙，毒性强烈", "🦷", 0.6, 1600, "BEAST_TOOTH"},
        {"snakeCore1", "灵蛇内丹", 2, 2, "core", "灵品蛇妖的内丹，蕴含浓郁剧毒灵力", "🔮", 0.4, 1600, "BEAST_CORE"},
        {"snakeScale2", "宝蛇鳞", 3, 3, "scale", "宝品蛇妖的鳞片，珍贵且蕴含强大毒性", "🟩", 0.6, 8000, "BEAST_SCALE"},
        {"snakeBlood2", "宝蛇血", 3, 3, "blood", "宝品蛇妖的精血，毒性猛烈", "🩸", 0.6, 8000, "BEAST_BLOOD"},
        {"snakeTooth2", "宝蛇牙", 3, 3, "tooth", "宝品蛇妖的毒牙，毒性猛烈", "🦷", 0.5, 8000, "BEAST_TOOTH"},
        {"snakeCore2", "宝蛇内丹", 3, 3, "core", "宝品蛇妖的内丹，蕴含强大剧毒之力", "🔮", 0.3, 8000, "BEAST_CORE"},
        {"snakeScale3", "玄蛇鳞", 4, 4, "scale", "玄品蛇妖的鳞片，蕴含玄妙剧毒之力", "🟩", 0.4, 48000, "BEAST_SCALE"},
        {"snakeBlood3", "玄蛇血", 4, 4, "blood", "玄品蛇妖的精血，蕴含玄妙剧毒", "🩸", 0.4, 48000, "BEAST_BLOOD"},
        {"snakeTooth3", "玄蛇牙", 4, 4, "tooth", "玄品蛇妖的毒牙，蕴含玄妙毒性", "🦷", 0.3, 48000, "BEAST_TOOTH"},
        {"snakeCore3", "玄蛇内丹", 4, 4, "core", "玄品蛇妖的内丹，蕴含玄妙剧毒灵力", "🔮", 0.2, 48000, "BEAST_CORE"},
        {"snakeScale4", "地蛇鳞", 5, 5, "scale", "地品蛇妖的鳞片，蕴含大地剧毒之力", "🟩", 0.25, 336000, "BEAST_SCALE"},
        {"snakeBlood4", "地蛇血", 5, 5, "blood", "地品蛇妖的精血，蕴含大地剧毒", "🩸", 0.25, 336000, "BEAST_BLOOD"},
        {"snakeTooth4", "地蛇牙", 5, 5, "tooth", "地品蛇妖的毒牙，蕴含大地毒性", "🦷", 0.2, 336000, "BEAST_TOOTH"},
        {"snakeCore4", "地蛇内丹", 5, 5, "core", "地品蛇妖的内丹，蕴含大地剧毒灵力", "🔮", 0.12, 336000, "BEAST_CORE"},
        {"snakeScale5", "天蛇鳞", 6, 6, "scale", "天品蛇妖的鳞片，蕴含天道剧毒之力", "🟩", 0.12, 2688000, "BEAST_SCALE"},
        {"snakeBlood5", "天蛇血", 6, 6, "blood", "天品蛇妖的精血，蕴含天道剧毒", "🩸", 0.12, 2688000, "BEAST_BLOOD"},
        {"snakeTooth5", "天蛇牙", 6, 6, "tooth", "天品蛇妖的毒牙，蕴含天道毒性", "🦷", 0.1, 2688000, "BEAST_TOOTH"},
        {"snakeCore5", "天蛇内丹", 6, 6, "core", "天品蛇妖的内丹，蕴含天道剧毒灵力", "🔮", 0.06, 2688000, "BEAST_CORE"},
        {"bearHide0", "凡熊皮", 1, 1, "hide", "凡品熊妖的皮毛，厚实坚韧", "🟫", 1, 400, "BEAST_HIDE"},
        {"bearBone0", "凡熊骨", 1, 1, "bone", "凡品熊妖的骨骼，粗壮坚硬", "🦴", 1, 400, "BEAST_BONE"},
        {"bearClaw0", "凡熊掌", 1, 1, "claw", "凡品熊妖的熊掌，力大无穷", "🐾", 0.8, 400, "BEAST_CLAW"},
        {"bearCore0", "凡熊内丹", 1, 1, "core", "凡品熊妖的内丹，蕴含坚韧灵力", "🔮", 0.5, 400, "BEAST_CORE"},
        {"bearHide1", "灵熊皮", 2, 2, "hide", "灵品熊妖的皮毛，蕴含灵气与坚韧", "🟫", 0.8, 1600, "BEAST_HIDE"},
        {"bearBone1", "灵熊骨", 2, 2, "bone", "灵品熊妖的骨骼，坚如磐石", "🦴", 0.8, 1600, "BEAST_BONE"},
        {"bearClaw1", "灵熊掌", 2, 2, "claw", "灵品熊妖的熊掌，威力惊人", "🐾", 0.6, 1600, "BEAST_CLAW"},
        {"bearCore1", "灵熊内丹", 2, 2, "core", "灵品熊妖的内丹，蕴含浓郁坚韧灵力", "🔮", 0.4, 1600, "BEAST_CORE"},
        {"bearHide2", "宝熊皮", 3, 3, "hide", "宝品熊妖的皮毛，珍贵且蕴含强大防御", "🟫", 0.6, 8000, "BEAST_HIDE"},
        {"bearBone2", "宝熊骨", 3, 3, "bone", "宝品熊妖的骨骼，坚不可摧", "🦴", 0.6, 8000, "BEAST_BONE"},
        {"bearClaw2", "宝熊掌", 3, 3, "claw", "宝品熊妖的熊掌，力能碎山", "🐾", 0.5, 8000, "BEAST_CLAW"},
        {"bearCore2", "宝熊内丹", 3, 3, "core", "宝品熊妖的内丹，蕴含强大坚韧之力", "🔮", 0.3, 8000, "BEAST_CORE"},
        {"bearHide3", "玄熊皮", 4, 4, "hide", "玄品熊妖的皮毛，蕴含玄妙坚韧之力", "🟫", 0.4, 48000, "BEAST_HIDE"},
        {"bearBone3", "玄熊骨", 4, 4, "bone", "玄品熊妖的骨骼，蕴含玄妙坚固", "🦴", 0.4, 48000, "BEAST_BONE"},
        {"bearClaw3", "玄熊掌", 4, 4, "claw", "玄品熊妖的熊掌，蕴含玄妙力量", "🐾", 0.3, 48000, "BEAST_CLAW"},
        {"bearCore3", "玄熊内丹", 4, 4, "core", "玄品熊妖的内丹，蕴含玄妙坚韧灵力", "🔮", 0.2, 48000, "BEAST_CORE"},
        {"bearHide4", "地熊皮", 5, 5, "hide", "地品熊妖的皮毛，蕴含大地坚韧之力", "🟫", 0.25, 336000, "BEAST_HIDE"},
        {"bearBone4", "地熊骨", 5, 5, "bone", "地品熊妖的骨骼，蕴含大地坚固", "🦴", 0.25, 336000, "BEAST_BONE"},
        {"bearClaw4", "地熊掌", 5, 5, "claw", "地品熊妖的熊掌，蕴含大地力量", "🐾", 0.2, 336000, "BEAST_CLAW"},
        {"bearCore4", "地熊内丹", 5, 5, "core", "地品熊妖的内丹，蕴含大地坚韧灵力", "🔮", 0.12, 336000, "BEAST_CORE"},
        {"bearHide5", "天熊皮", 6, 6, "hide", "天品熊妖的皮毛，蕴含天道坚韧之力", "🟫", 0.12, 2688000, "BEAST_HIDE"},
        {"bearBone5", "天熊骨", 6, 6, "bone", "天品熊妖的骨骼，蕴含天道坚固", "🦴", 0.12, 2688000, "BEAST_BONE"},
        {"bearClaw5", "天熊掌", 6, 6, "claw", "天品熊妖的熊掌，蕴含天道力量", "🐾", 0.1, 2688000, "BEAST_CLAW"},
        {"bearCore5", "天熊内丹", 6, 6, "core", "天品熊妖的内丹，蕴含天道坚韧灵力", "🔮", 0.06, 2688000, "BEAST_CORE"},
        {"eagleFeather0", "凡鹰羽", 1, 1, "feather", "凡品鹰妖的羽毛，轻盈锐利", "🪶", 1, 400, "BEAST_FEATHER"},
        {"eagleBone0", "凡鹰骨", 1, 1, "bone", "凡品鹰妖的骨骼，轻而坚固", "🦴", 1, 400, "BEAST_BONE"},
        {"eagleClaw0", "凡鹰爪", 1, 1, "claw", "凡品鹰妖的利爪，锋利如钩", "🐾", 0.8, 400, "BEAST_CLAW"},
        {"eagleCore0", "凡鹰内丹", 1, 1, "core", "凡品鹰妖的内丹，蕴含锐利灵力", "🔮", 0.5, 400, "BEAST_CORE"},
        {"eagleFeather1", "灵鹰羽", 2, 2, "feather", "灵品鹰妖的羽毛，蕴含灵气与锐利", "🪶", 0.8, 1600, "BEAST_FEATHER"},
        {"eagleBone1", "灵鹰骨", 2, 2, "bone", "灵品鹰妖的骨骼，轻盈坚固", "🦴", 0.8, 1600, "BEAST_BONE"},
        {"eagleClaw1", "灵鹰爪", 2, 2, "claw", "灵品鹰妖的利爪，快如闪电", "🐾", 0.6, 1600, "BEAST_CLAW"},
        {"eagleCore1", "灵鹰内丹", 2, 2, "core", "灵品鹰妖的内丹，蕴含浓郁锐利灵力", "🔮", 0.4, 1600, "BEAST_CORE"},
        {"eagleFeather2", "宝鹰羽", 3, 3, "feather", "宝品鹰妖的羽毛，珍贵且蕴含强大锐利", "🪶", 0.6, 8000, "BEAST_FEATHER"},
        {"eagleBone2", "宝鹰骨", 3, 3, "bone", "宝品鹰妖的骨骼，轻若鸿毛", "🦴", 0.6, 8000, "BEAST_BONE"},
        {"eagleClaw2", "宝鹰爪", 3, 3, "claw", "宝品鹰妖的利爪，迅疾如风", "🐾", 0.5, 8000, "BEAST_CLAW"},
        {"eagleCore2", "宝鹰内丹", 3, 3, "core", "宝品鹰妖的内丹，蕴含强大锐利之力", "🔮", 0.3, 8000, "BEAST_CORE"},
        {"eagleFeather3", "玄鹰羽", 4, 4, "feather", "玄品鹰妖的羽毛，蕴含玄妙锐利之力", "🪶", 0.4, 48000, "BEAST_FEATHER"},
        {"eagleBone3", "玄鹰骨", 4, 4, "bone", "玄品鹰妖的骨骼，蕴含玄妙轻盈", "🦴", 0.4, 48000, "BEAST_BONE"},
        {"eagleClaw3", "玄鹰爪", 4, 4, "claw", "玄品鹰妖的利爪，蕴含玄妙速度", "🐾", 0.3, 48000, "BEAST_CLAW"},
        {"eagleCore3", "玄鹰内丹", 4, 4, "core", "玄品鹰妖的内丹，蕴含玄妙锐利灵力", "🔮", 0.2, 48000, "BEAST_CORE"},
        {"eagleFeather4", "地鹰羽", 5, 5, "feather", "地品鹰妖的羽毛，蕴含大地锐利之力", "🪶", 0.25, 336000, "BEAST_FEATHER"},
        {"eagleBone4", "地鹰骨", 5, 5, "bone", "地品鹰妖的骨骼，蕴含大地轻盈", "🦴", 0.25, 336000, "BEAST_BONE"},
        {"eagleClaw4", "地鹰爪", 5, 5, "claw", "地品鹰妖的利爪，蕴含大地速度", "🐾", 0.2, 336000, "BEAST_CLAW"},
        {"eagleCore4", "地鹰内丹", 5, 5, "core", "地品鹰妖的内丹，蕴含大地锐利灵力", "🔮", 0.12, 336000, "BEAST_CORE"},
        {"eagleFeather5", "天鹰羽", 6, 6, "feather", "天品鹰妖的羽毛，蕴含天道锐利之力", "🪶", 0.12, 2688000, "BEAST_FEATHER"},
        {"eagleBone5", "天鹰骨", 6, 6, "bone", "天品鹰妖的骨骼，蕴含天道轻盈", "🦴", 0.12, 2688000, "BEAST_BONE"},
        {"eagleClaw5", "天鹰爪", 6, 6, "claw", "天品鹰妖的利爪，蕴含天道速度", "🐾", 0.1, 2688000, "BEAST_CLAW"},
        {"eagleCore5", "天鹰内丹", 6, 6, "core", "天品鹰妖的内丹，蕴含天道锐利灵力", "🔮", 0.06, 2688000, "BEAST_CORE"},
        {"foxHide0", "凡狐皮", 1, 1, "hide", "凡品狐妖的皮毛，柔软光滑", "🟥", 1, 400, "BEAST_HIDE"},
        {"foxBone0", "凡狐骨", 1, 1, "bone", "凡品狐妖的骨骼，轻盈灵动", "🦴", 1, 400, "BEAST_BONE"},
        {"foxTail0", "凡狐尾", 1, 1, "tail", "凡品狐妖的尾巴，柔软迷人", "🦊", 0.8, 400, "BEAST_TAIL"},
        {"foxCore0", "凡狐内丹", 1, 1, "core", "凡品狐妖的内丹，蕴含幻魅灵力", "🔮", 0.5, 400, "BEAST_CORE"},
        {"foxHide1", "灵狐皮", 2, 2, "hide", "灵品狐妖的皮毛，蕴含灵气与幻魅", "🟥", 0.8, 1600, "BEAST_HIDE"},
        {"foxBone1", "灵狐骨", 2, 2, "bone", "灵品狐妖的骨骼，轻盈灵动", "🦴", 0.8, 1600, "BEAST_BONE"},
        {"foxTail1", "灵狐尾", 2, 2, "tail", "灵品狐妖的尾巴，幻魅迷人", "🦊", 0.6, 1600, "BEAST_TAIL"},
        {"foxCore1", "灵狐内丹", 2, 2, "core", "灵品狐妖的内丹，蕴含浓郁幻魅灵力", "🔮", 0.4, 1600, "BEAST_CORE"},
        {"foxHide2", "宝狐皮", 3, 3, "hide", "宝品狐妖的皮毛，珍贵且蕴含强大幻魅", "🟥", 0.6, 8000, "BEAST_HIDE"},
        {"foxBone2", "宝狐骨", 3, 3, "bone", "宝品狐妖的骨骼，轻若鸿毛", "🦴", 0.6, 8000, "BEAST_BONE"},
        {"foxTail2", "宝狐尾", 3, 3, "tail", "宝品狐妖的尾巴，幻魅万千", "🦊", 0.5, 8000, "BEAST_TAIL"},
        {"foxCore2", "宝狐内丹", 3, 3, "core", "宝品狐妖的内丹，蕴含强大幻魅之力", "🔮", 0.3, 8000, "BEAST_CORE"},
        {"foxHide3", "玄狐皮", 4, 4, "hide", "玄品狐妖的皮毛，蕴含玄妙幻魅之力", "🟥", 0.4, 48000, "BEAST_HIDE"},
        {"foxBone3", "玄狐骨", 4, 4, "bone", "玄品狐妖的骨骼，蕴含玄妙灵动", "🦴", 0.4, 48000, "BEAST_BONE"},
        {"foxTail3", "玄狐尾", 4, 4, "tail", "玄品狐妖的尾巴，蕴含玄妙幻魅", "🦊", 0.3, 48000, "BEAST_TAIL"},
        {"foxCore3", "玄狐内丹", 4, 4, "core", "玄品狐妖的内丹，蕴含玄妙幻魅灵力", "🔮", 0.2, 48000, "BEAST_CORE"},
        {"foxHide4", "地狐皮", 5, 5, "hide", "地品狐妖的皮毛，蕴含大地幻魅之力", "🟥", 0.25, 336000, "BEAST_HIDE"},
        {"foxBone4", "地狐骨", 5, 5, "bone", "地品狐妖的骨骼，蕴含大地灵动", "🦴", 0.25, 336000, "BEAST_BONE"},
        {"foxTail4", "地狐尾", 5, 5, "tail", "地品狐妖的尾巴，蕴含大地幻魅", "🦊", 0.2, 336000, "BEAST_TAIL"},
        {"foxCore4", "地狐内丹", 5, 5, "core", "地品狐妖的内丹，蕴含大地幻魅灵力", "🔮", 0.12, 336000, "BEAST_CORE"},
        {"foxHide5", "天狐皮", 6, 6, "hide", "天品狐妖的皮毛，蕴含天道幻魅之力", "🟥", 0.12, 2688000, "BEAST_HIDE"},
        {"foxBone5", "天狐骨", 6, 6, "bone", "天品狐妖的骨骼，蕴含天道灵动", "🦴", 0.12, 2688000, "BEAST_BONE"},
        {"foxTail5", "天狐尾", 6, 6, "tail", "天品狐妖的尾巴，蕴含天道幻魅", "🦊", 0.1, 2688000, "BEAST_TAIL"},
        {"foxCore5", "天狐内丹", 6, 6, "core", "天品狐妖的内丹，蕴含天道幻魅灵力", "🔮", 0.06, 2688000, "BEAST_CORE"},
        {"dragonScale0", "凡龙鳞", 1, 1, "scale", "凡品龙妖的鳞片，坚硬无比", "🐲", 1, 400, "BEAST_SCALE"},
        {"dragonClaw0", "凡龙爪", 1, 1, "claw", "凡品龙妖的利爪，蕴含龙力", "🐾", 1, 400, "BEAST_CLAW"},
        {"dragonHorn0", "凡龙角", 1, 1, "horn", "凡品龙妖的龙角，蕴含龙力", "🦄", 0.8, 400, "BEAST_HORN"},
        {"dragonCore0", "凡龙内丹", 1, 1, "core", "凡品龙妖的内丹，蕴含龙族灵力", "🔮", 0.5, 400, "BEAST_CORE"},
        {"dragonScale1", "灵龙鳞", 2, 2, "scale", "灵品龙妖的鳞片，蕴含灵气与龙威", "🐲", 0.8, 1600, "BEAST_SCALE"},
        {"dragonClaw1", "灵龙爪", 2, 2, "claw", "灵品龙妖的利爪，龙威凛凛", "🐾", 0.8, 1600, "BEAST_CLAW"},
        {"dragonHorn1", "灵龙角", 2, 2, "horn", "灵品龙妖的龙角，蕴含强大龙力", "🦄", 0.6, 1600, "BEAST_HORN"},
        {"dragonCore1", "灵龙内丹", 2, 2, "core", "灵品龙妖的内丹，蕴含浓郁龙族灵力", "🔮", 0.4, 1600, "BEAST_CORE"},
        {"dragonScale2", "宝龙鳞", 3, 3, "scale", "宝品龙妖的鳞片，珍贵且蕴含强大龙威", "🐲", 0.6, 8000, "BEAST_SCALE"},
        {"dragonClaw2", "宝龙爪", 3, 3, "claw", "宝品龙妖的利爪，蕴含磅礴龙力", "🐾", 0.6, 8000, "BEAST_CLAW"},
        {"dragonHorn2", "宝龙角", 3, 3, "horn", "宝品龙妖的龙角，蕴含磅礴龙力", "🦄", 0.5, 8000, "BEAST_HORN"},
        {"dragonCore2", "宝龙内丹", 3, 3, "core", "宝品龙妖的内丹，蕴含强大龙族之力", "🔮", 0.3, 8000, "BEAST_CORE"},
        {"dragonScale3", "玄龙鳞", 4, 4, "scale", "玄品龙妖的鳞片，蕴含玄妙龙威", "🐲", 0.4, 48000, "BEAST_SCALE"},
        {"dragonClaw3", "玄龙爪", 4, 4, "claw", "玄品龙妖的利爪，蕴含玄妙龙力", "🐾", 0.4, 48000, "BEAST_CLAW"},
        {"dragonHorn3", "玄龙角", 4, 4, "horn", "玄品龙妖的龙角，蕴含玄妙龙威", "🦄", 0.3, 48000, "BEAST_HORN"},
        {"dragonCore3", "玄龙内丹", 4, 4, "core", "玄品龙妖的内丹，蕴含玄妙龙族灵力", "🔮", 0.2, 48000, "BEAST_CORE"},
        {"dragonScale4", "地龙鳞", 5, 5, "scale", "地品龙妖的鳞片，蕴含大地龙威", "🐲", 0.25, 336000, "BEAST_SCALE"},
        {"dragonClaw4", "地龙爪", 5, 5, "claw", "地品龙妖的利爪，蕴含大地龙力", "🐾", 0.25, 336000, "BEAST_CLAW"},
        {"dragonHorn4", "地龙角", 5, 5, "horn", "地品龙妖的龙角，蕴含大地龙威", "🦄", 0.2, 336000, "BEAST_HORN"},
        {"dragonCore4", "地龙内丹", 5, 5, "core", "地品龙妖的内丹，蕴含大地龙族灵力", "🔮", 0.12, 336000, "BEAST_CORE"},
        {"dragonScale5", "天龙鳞", 6, 6, "scale", "天品龙妖的鳞片，蕴含天道龙威", "🐲", 0.12, 2688000, "BEAST_SCALE"},
        {"dragonClaw5", "天龙爪", 6, 6, "claw", "天品龙妖的利爪，蕴含天道龙力", "🐾", 0.12, 2688000, "BEAST_CLAW"},
        {"dragonHorn5", "天龙角", 6, 6, "horn", "天品龙妖的龙角，蕴含天道龙威", "🦄", 0.1, 2688000, "BEAST_HORN"},
        {"dragonCore5", "天龙内丹", 6, 6, "core", "天品龙妖的内丹，蕴含天道龙族灵力", "🔮", 0.06, 2688000, "BEAST_CORE"},
        {"turtleShell0", "凡龟壳", 1, 1, "shell", "凡品龟妖的龟壳，坚硬厚重", "🐢", 1, 400, "BEAST_SHELL"},
        {"turtleBone0", "凡龟骨", 1, 1, "bone", "凡品龟妖的骨骼，厚重坚固", "🦴", 1, 400, "BEAST_BONE"},
        {"turtleBlood0", "凡龟血", 1, 1, "blood", "凡品龟妖的精血，蕴含厚重之力", "🩸", 0.8, 400, "BEAST_BLOOD"},
        {"turtleCore0", "凡龟内丹", 1, 1, "core", "凡品龟妖的内丹，蕴含厚重灵力", "🔮", 0.5, 400, "BEAST_CORE"},
        {"turtleShell1", "灵龟壳", 2, 2, "shell", "灵品龟妖的龟壳，蕴含灵气与厚重", "🐢", 0.8, 1600, "BEAST_SHELL"},
        {"turtleBone1", "灵龟骨", 2, 2, "bone", "灵品龟妖的骨骼，坚如磐石", "🦴", 0.8, 1600, "BEAST_BONE"},
        {"turtleBlood1", "灵龟血", 2, 2, "blood", "灵品龟妖的精血，蕴含灵力与厚重", "🩸", 0.6, 1600, "BEAST_BLOOD"},
        {"turtleCore1", "灵龟内丹", 2, 2, "core", "灵品龟妖的内丹，蕴含浓郁厚重灵力", "🔮", 0.4, 1600, "BEAST_CORE"},
        {"turtleShell2", "宝龟壳", 3, 3, "shell", "宝品龟妖的龟壳，珍贵且蕴含强大防御", "🐢", 0.6, 8000, "BEAST_SHELL"},
        {"turtleBone2", "宝龟骨", 3, 3, "bone", "宝品龟妖的骨骼，坚不可摧", "🦴", 0.6, 8000, "BEAST_BONE"},
        {"turtleBlood2", "宝龟血", 3, 3, "blood", "宝品龟妖的精血，蕴含强大厚重之力", "🩸", 0.5, 8000, "BEAST_BLOOD"},
        {"turtleCore2", "宝龟内丹", 3, 3, "core", "宝品龟妖的内丹，蕴含强大厚重之力", "🔮", 0.3, 8000, "BEAST_CORE"},
        {"turtleShell3", "玄龟壳", 4, 4, "shell", "玄品龟妖的龟壳，蕴含玄妙厚重之力", "🐢", 0.4, 48000, "BEAST_SHELL"},
        {"turtleBone3", "玄龟骨", 4, 4, "bone", "玄品龟妖的骨骼，蕴含玄妙坚固", "🦴", 0.4, 48000, "BEAST_BONE"},
        {"turtleBlood3", "玄龟血", 4, 4, "blood", "玄品龟妖的精血，蕴含玄妙厚重", "🩸", 0.3, 48000, "BEAST_BLOOD"},
        {"turtleCore3", "玄龟内丹", 4, 4, "core", "玄品龟妖的内丹，蕴含玄妙厚重灵力", "🔮", 0.2, 48000, "BEAST_CORE"},
        {"turtleShell4", "地龟壳", 5, 5, "shell", "地品龟妖的龟壳，蕴含大地厚重之力", "🐢", 0.25, 336000, "BEAST_SHELL"},
        {"turtleBone4", "地龟骨", 5, 5, "bone", "地品龟妖的骨骼，蕴含大地坚固", "🦴", 0.25, 336000, "BEAST_BONE"},
        {"turtleBlood4", "地龟血", 5, 5, "blood", "地品龟妖的精血，蕴含大地厚重", "🩸", 0.2, 336000, "BEAST_BLOOD"},
        {"turtleCore4", "地龟内丹", 5, 5, "core", "地品龟妖的内丹，蕴含大地厚重灵力", "🔮", 0.12, 336000, "BEAST_CORE"},
        {"turtleShell5", "天龟壳", 6, 6, "shell", "天品龟妖的龟壳，蕴含天道厚重之力", "🐢", 0.12, 2688000, "BEAST_SHELL"},
        {"turtleBone5", "天龟骨", 6, 6, "bone", "天品龟妖的骨骼，蕴含天道坚固", "🦴", 0.12, 2688000, "BEAST_BONE"},
        {"turtleBlood5", "天龟血", 6, 6, "blood", "天品龟妖的精血，蕴含天道厚重", "🩸", 0.1, 2688000, "BEAST_BLOOD"},
        {"turtleCore5", "天龟内丹", 6, 6, "core", "天品龟妖的内丹，蕴含天道厚重灵力", "🔮", 0.06, 2688000, "BEAST_CORE"},
    };
    return kTemplates;
}

/// 按 id 查询妖兽材料
inline const BeastMaterialTemplate* beastMaterialById(const std::string& id) {
    for (const auto& m : beastMaterialTemplates()) {
        if (m.id == id) return &m;
    }
    return nullptr;
}

/// 按妖兽类型查询（Kotlin getMaterialsByBeastType：接受中文妖兽名如"虎妖"，
/// 也接受英文前缀如 "tiger"；按 id 前缀匹配，如 tigerHide/tigerBlood/tigerTooth/tigerCore）
inline std::vector<const BeastMaterialTemplate*> beastMaterialsByBeastType(
    const std::string& beastType) {
    std::string prefix;
    if (beastType == "虎妖") prefix = "tiger";
    else if (beastType == "狼妖") prefix = "wolf";
    else if (beastType == "蛇妖") prefix = "snake";
    else if (beastType == "熊妖") prefix = "bear";
    else if (beastType == "鹰妖") prefix = "eagle";
    else if (beastType == "狐妖") prefix = "fox";
    else if (beastType == "龙妖") prefix = "dragon";
    else if (beastType == "龟妖") prefix = "turtle";
    else prefix = beastType;  // 已传英文前缀
    std::vector<const BeastMaterialTemplate*> out;
    for (const auto& m : beastMaterialTemplates()) {
        if (m.id.rfind(prefix, 0) == 0) {
            out.push_back(&m);
        }
    }
    return out;
}

}  // namespace gamecore::data
