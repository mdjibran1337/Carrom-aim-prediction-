package com.example.physics

import androidx.compose.ui.graphics.Color

data class CarromCoin(
    val id: Int,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    val isRed: Boolean = false,
    val isWhite: Boolean = false,
    var isPocketed: Boolean = false,
    val radius: Float = 22f,
    val weight: Float = 1.0f // for collision math
) {
    val color: Color
        get() = when {
            isRed -> Color(0xFFFF3D00) // Vibrant Red Queen
            isWhite -> Color(0xFFECEFF1) // Premium Off-White
            else -> Color(0xFF263238) // Deep Charcoal / Black
        }
}

data class CarromStriker(
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    val radius: Float = 32f,
    val weight: Float = 3.0f, // Striker is heavier
    var isPocketed: Boolean = false
)

data class Pocket(
    val x: Float,
    val y: Float,
    val radius: Float = 40f
)

data class AimGuidePoint(
    val x: Float,
    val y: Float
)

data class AIShotSuggestion(
    val strikerX: Float,
    val targetAngle: Float,
    val power: Float,
    val coinId: Int,
    val pocketIndex: Int,
    val expectedCoinPath: List<AimGuidePoint>,
    val expectedStrikerPath: List<AimGuidePoint>,
    val score: Float
)
