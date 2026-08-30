#pragma once

// ============================================================
// name_service.h — 中文名生成（批 13-4a：Kotlin NameService.inheritName/
// extractSurname 等价移植）
//
// 背景：Kotlin NameService 原用 JVM 全局 Random（非确定性、不入 rngStates，
// 跨语言不可对拍）——批 13-4a 分区化（S-19 同族确定性修正）：inheritName
// 接受分区 PRNG（SYSTEM 分区），C++ 本文件等价移植（数据表与 Kotlin 逐项
// 一致——名字表为静态数据，双端守卫防漂移）。
//
// RNG 语义（对齐 Kotlin asKotlinRandom 适配器）：
//   rng.nextDouble() → DeterministicRng.nextDouble()
//   rng.nextInt(n)   → DeterministicRng.nextInt(n)
// ============================================================

#include <cstdint>
#include <set>
#include <string>
#include <vector>

#include "gamecore/rng/pcg_xsh_rr.h"

namespace gamecore::system {

/// 复姓表（Kotlin NameService.compoundSurnames——extractSurname 用）
inline const std::vector<std::string>& compoundSurnames() {
    static const std::vector<std::string> k = {
        "慕容", "上官", "欧阳", "司徒", "南宫", "诸葛", "东方", "西门",
        "独孤", "令狐", "皇甫", "公孙", "轩辕", "太史", "端木", "百里",
    };
    return k;
}

/// 男性双字名（Kotlin maleDoubleNames）
inline const std::vector<std::string>& maleDoubleNames() {
    static const std::vector<std::string> k = {
        "逍遥", "无忌", "长生", "问道", "清风", "明月", "玄真", "道尘",
        "云飞", "天行", "凌霄", "御风", "踏云", "破晓", "逐月", "追星",
        "悟道", "通玄", "归真", "化神", "凝神",
        "剑心", "剑尘", "剑歌", "剑影", "剑魄",
        "丹辰", "丹华", "丹心", "丹青", "丹枫",
        "子轩", "子涵", "子墨", "子瑜", "子琪",
        "怀瑾", "景行", "承宇", "君浩", "亦尘", "云深",
        "晏清", "知远", "修远", "秉文", "若谷", "临渊",
        "望舒", "归鸿", "寒山", "听雨", "忘机", "抱朴",
        "乐天", "安然", "致远", "明轩", "文渊", "廷玉",
        "浩然", "瑾瑜", "星野", "澄之", "衡之",
        "器宇", "器灵", "器心", "器魂",
        "阵玄", "阵灵", "阵心", "阵尘",
        "符玄", "符灵", "符心", "符尘",
    };
    return k;
}

/// 女性双字名（Kotlin femaleDoubleNames）
inline const std::vector<std::string>& femaleDoubleNames() {
    static const std::vector<std::string> k = {
        "月华", "紫烟", "灵芸", "清音", "玉瑶", "雪晴", "碧云", "青鸾",
        "紫霞", "晨曦", "幽兰", "寒梅", "翠竹", "青松",
        "月影", "花颜", "梦璃", "霜华", "冰心", "凝露",
        "瑶光", "璇玑", "灵犀", "素心", "清浅", "如烟",
        "芷若", "沐云", "晓霜", "凌波", "听澜", "念真",
        "疏影", "流萤", "惜音", "惊鸿", "采薇", "青衣",
        "婉清", "静姝", "素锦", "芳菲", "梵音", "墨染",
        "笙箫", "洛神", "湘君", "素问", "兰若",
        "含烟", "弄影", "踏歌", "凝霜", "映雪", "初雪",
    };
    return k;
}

/// 男性单字名（Kotlin maleSingleNames）
inline const std::vector<std::string>& maleSingleNames() {
    static const std::vector<std::string> k = {
        "风", "云", "雷", "电", "剑", "明", "华", "天", "玄", "宇",
        "龙", "虎", "鹤", "鹰", "轩", "尘", "渊", "峰", "辰", "墨",
    };
    return k;
}

/// 女性单字名（Kotlin femaleSingleNames）
inline const std::vector<std::string>& femaleSingleNames() {
    static const std::vector<std::string> k = {
        "月", "雪", "花", "梅", "兰", "竹", "菊", "莲", "芸", "芳",
        "玉", "珠", "翠", "霞", "虹", "露", "霜", "雨", "烟", "鸾",
    };
    return k;
}

/// 名字结果（Kotlin NameService.NameResult）
struct NameResult {
    std::string surname;
    std::string fullName;
};

/// 提取姓氏（Kotlin NameService.extractSurname：复姓前缀匹配，否则首个字符）
inline std::string extractSurname(const std::string& fullName) {
    for (const auto& c : compoundSurnames()) {
        if (fullName.rfind(c, 0) == 0) return c;
    }
    if (fullName.empty()) return "";
    // 首个 UTF-8 字符（中文 BMP = 3 字节；Kotlin firstOrNull 首个代码点）
    const auto b0 = static_cast<unsigned char>(fullName[0]);
    std::size_t len = 1;
    if (b0 >= 0xF0) len = 4;
    else if (b0 >= 0xE0) len = 3;
    else if (b0 >= 0xC0) len = 2;
    return fullName.substr(0, len);
}

/// 继承姓氏生成名字（Kotlin NameService.inheritName——分区 rng 确定性版）
inline NameResult inheritName(const std::string& parentSurname,
                              const std::string& gender,
                              const std::set<std::string>& existingNames,
                              rng::DeterministicRng& rng) {
    const auto pickGivenName = [&](const std::string& g) -> const std::string& {
        const bool useDouble = rng.nextDouble() < 0.75;
        if (useDouble) {
            if (g == "male") {
                return maleDoubleNames()[rng.nextInt(
                    static_cast<int32_t>(maleDoubleNames().size()))];
            }
            return femaleDoubleNames()[rng.nextInt(
                static_cast<int32_t>(femaleDoubleNames().size()))];
        } else {
            if (g == "male") {
                return maleSingleNames()[rng.nextInt(
                    static_cast<int32_t>(maleSingleNames().size()))];
            }
            return femaleSingleNames()[rng.nextInt(
                static_cast<int32_t>(femaleSingleNames().size()))];
        }
    };

    for (int attempts = 0; attempts < 50; ++attempts) {
        const auto& given = pickGivenName(gender);
        const std::string full = parentSurname + given;
        if (!existingNames.count(full)) return {parentSurname, full};
    }
    const auto& given = pickGivenName(gender);
    const std::string base = parentSurname + given;
    if (!existingNames.count(base)) return {parentSurname, base};
    int32_t suffix = 2;
    std::string unique;
    do {
        unique = base + std::to_string(suffix);
        ++suffix;
    } while (existingNames.count(unique) && suffix < 100);
    return {parentSurname, unique};
}

}  // namespace gamecore::system
