package com.xianxia.sect.ui.game.components.messagebar

/**
 * 游戏事件文本格式化工具——统一旬名映射，避免重复定义。
 */
object GameEventFormat {

    /** 根据旬值返回显示文本 */
    fun phaseName(phase: Int): String = when (phase) {
        0 -> "上旬"; 1 -> "中旬"; 2 -> "下旬"; else -> ""
    }

    /** 格式化事件预览文本（收起态使用） */
    fun formatEventPreview(year: Int, month: Int, phase: Int, summary: String): String =
        "第${year}年${month}月${phaseName(phase)} ${summary}"
}
