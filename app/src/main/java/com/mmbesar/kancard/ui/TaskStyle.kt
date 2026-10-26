package com.mmbesar.kancard.ui

import androidx.compose.ui.graphics.Color

/**
 * Kanboard's task colours. The ids are the names Kanboard understands.
 * The shades are stronger than Kanboard's pastel ones so they stay visible as a stripe
 * on both light and dark themes.
 */
val kanboardColors: List<Pair<String, Color>> = listOf(
    "yellow" to Color(0xFFF2C94C),
    "blue" to Color(0xFF4A90E2),
    "green" to Color(0xFF4CAF50),
    "purple" to Color(0xFF9C27B0),
    "red" to Color(0xFFE53935),
    "orange" to Color(0xFFFB8C00),
    "grey" to Color(0xFF9E9E9E),
    "brown" to Color(0xFF8D6E63),
    "deep_orange" to Color(0xFFFF5722),
    "dark_grey" to Color(0xFF607D8B),
    "pink" to Color(0xFFEC407A),
    "teal" to Color(0xFF009688),
    "cyan" to Color(0xFF00BCD4),
    "lime" to Color(0xFFCDDC39),
    "light_green" to Color(0xFF8BC34A),
    "amber" to Color(0xFFFFC107),
)

/** The stripe colour for a Kanboard colour id, or null when the id is empty or unknown. */
fun taskColor(colorId: String): Color? = kanboardColors.firstOrNull { it.first == colorId }?.second
