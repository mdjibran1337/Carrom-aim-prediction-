package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalContext
import com.example.physics.AIShotSuggestion
import com.example.physics.CarromCoin
import com.example.physics.CarromPhysics
import com.example.physics.CarromStriker
import com.example.physics.Pocket
import com.example.ui.theme.MyApplicationTheme
import kotlin.math.*
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var hasOverlayPermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF0F1014) // Clean obsidian theme background
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        MainScreenContent(
                            hasOverlayPermission = hasOverlayPermission,
                            onRequestPermission = { launchOverlaySettings() },
                            onStartOverlay = { startOverlayHelper() },
                            onStopOverlay = { stopOverlayHelper() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasOverlayPermission = Settings.canDrawOverlays(this)
    }

    private fun launchOverlaySettings() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Enable Draw Over Other Apps for Carrom AI", Toast.LENGTH_LONG).show()
        }
    }

    private fun startOverlayHelper() {
        if (!Settings.canDrawOverlays(this)) {
            launchOverlaySettings()
            return
        }
        val intent = Intent(this, CarromOverlayService::class.java).apply {
            action = CarromOverlayService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Carrom Overlay Service Started!", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlayHelper() {
        val intent = Intent(this, CarromOverlayService::class.java).apply {
            action = CarromOverlayService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "Carrom Overlay Stopped.", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    hasOverlayPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Title Header Banner
        AppHeaderBanner()

        // Overlay Manager Card
        OverlayManagerCard(
            hasPermission = hasOverlayPermission,
            onRequestPermission = onRequestPermission,
            onStartOverlay = onStartOverlay,
            onStopOverlay = onStopOverlay
        )

        // Sandbox Board Arena Card
        CarromSandboxArena()

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun AppHeaderBanner() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "CARROM AI TRAINER",
            color = Color(0xFFFFD54F),
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.5.sp
        )
        Text(
            text = "Holographic Cushion Reflection & Collision Engine",
            color = Color(0xFFA7AAB2),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun OverlayManagerCard(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E202A))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Floating Aim Overlay Helper",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                // Status Indicator
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (hasPermission) Color(0xFF00E5FF).copy(alpha = 0.12f) else Color(0xFFFF5252).copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (hasPermission) "ACTIVE READY" else "NEEDS PERMISSION",
                        color = if (hasPermission) Color(0xFF00E5FF) else Color(0xFFFF5252),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = "Launches an persistent aiming HUD containing interactive calibration handles. Can be manually aligned over other applications.",
                color = Color(0xFFA0A5B5),
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )

            Divider(color = Color.White.copy(alpha = 0.08f))

            if (!hasPermission) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Grant Draw Over Other Apps", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onStartOverlay,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Launch Overlays", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onStopOverlay,
                        border = BorderStroke(1.dp, Color(0xFFFF5252)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Stop Overlays", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CarromSandboxArena() {
    val context = LocalContext.current
    // Sandbox board parameters: virtualized 1000f x 1000f coordinates for calculations
    val coinsList = remember { mutableStateListOf<CarromCoin>() }
    val strikerState = remember { mutableStateOf(CarromStriker(500f, 820f)) }

    // Screen Arena Variables
    var strikerX by remember { mutableStateOf(500f) } // horizontal position on baseline [200, 800]
    var aimTargetX by remember { mutableStateOf(500f) } // aim crosshairs coordinates
    var aimTargetY by remember { mutableStateOf(350f) }
    var selectedPower by remember { mutableStateOf(0.72f) }

    var isSimulating by remember { mutableStateOf(false) }
    var isLiveAssistEnabled by remember { mutableStateOf(false) }
    var lastSuggestedShot by remember { mutableStateOf<AIShotSuggestion?>(null) }
    var showAIHudAlert by remember { mutableStateOf(false) }

    val pockets = remember {
        listOf(
            Pocket(80f, 80f),
            Pocket(920f, 80f),
            Pocket(80f, 920f),
            Pocket(920f, 920f)
        )
    }

    // Function to initialize board
    fun initializeSandbox() {
        coinsList.clear()

        // 1. Red Queen: Exactly in the center
        coinsList.add(CarromCoin(id = 0, x = 500f, y = 500f, isRed = true))

        // 2. White and Black pieces arranged around the center
        val placements = listOf(
            Pair(0f, true), Pair(60f, false), Pair(120f, true),
            Pair(180f, false), Pair(240f, true), Pair(300f, false)
        )
        val ringRadius = 75f
        for (i in placements.indices) {
            val angle = placements[i].first
            val isWhite = placements[i].second
            val rad = Math.toRadians(angle.toDouble())
            coinsList.add(
                CarromCoin(
                    id = i + 1,
                    x = 500f + (ringRadius * cos(rad)).toFloat(),
                    y = 500f + (ringRadius * sin(rad)).toFloat(),
                    isWhite = isWhite
                )
            )
        }

        // 3. Extra scattered practice layout
        coinsList.add(CarromCoin(id = 7, x = 300f, y = 350f, isWhite = true))
        coinsList.add(CarromCoin(id = 8, x = 700f, y = 350f, isWhite = false))
        coinsList.add(CarromCoin(id = 9, x = 450f, y = 280f, isWhite = false))

        // Reset striker baseline snap
        strikerX = 500f
        strikerState.value = CarromStriker(strikerX, 820f)
        isSimulating = false
        isLiveAssistEnabled = false
        lastSuggestedShot = null
        showAIHudAlert = false
    }

    // Run board initialization
    LaunchedEffect(Unit) {
        initializeSandbox()
    }

    // Real-time Physics Update loop
    LaunchedEffect(isSimulating) {
        if (isSimulating) {
            while (isSimulating) {
                CarromPhysics.updateSimulationTick(
                    striker = strikerState.value,
                    coins = coinsList,
                    pockets = pockets,
                    width = 1000f,
                    height = 1000f,
                    xMin = 50f, yMin = 50f, xMax = 950f, yMax = 950f
                )

                // Check standard stopping threshold
                val stopSpeed = 0.2f
                val sSpeed = sqrt(strikerState.value.vx * strikerState.value.vx + strikerState.value.vy * strikerState.value.vy)
                val anyMoving = coinsList.any { !it.isPocketed && (sqrt(it.vx * it.vx + it.vy * it.vy) > stopSpeed) }

                if (sSpeed < stopSpeed && !anyMoving) {
                    isSimulating = false

                    // If striker pocketed, respawn
                    if (strikerState.value.isPocketed) {
                        strikerState.value = CarromStriker(500f, 820f, isPocketed = false)
                        strikerX = 500f
                    } else {
                        // Reset striker securely back onto the baseline
                        strikerState.value = CarromStriker(strikerX, 820f)
                    }
                }
                delay(16) // ~60fps step
            }
        }
    }

    // Watch slider repositioning of striker
    LaunchedEffect(strikerX) {
        if (!isSimulating) {
            strikerState.value = CarromStriker(strikerX, 820f)
        }
    }

    // Real-time AI recommendation helper
    LaunchedEffect(isLiveAssistEnabled, strikerX, coinsList.map { it.isPocketed }, isSimulating) {
        if (isLiveAssistEnabled && !isSimulating) {
            val suggestion = CarromPhysics.calculateAISuggestion(
                coins = coinsList,
                strikerRadius = strikerState.value.radius,
                baselineY = 820f,
                pockets = pockets,
                xMin = 50f, yMin = 50f, xMax = 950f, yMax = 950f,
                forcedStrikerX = strikerX
            )
            if (suggestion != null) {
                lastSuggestedShot = suggestion
                val rad = Math.toRadians(suggestion.targetAngle.toDouble())
                // Project visual target reticle a standard distance in front of striker
                aimTargetX = suggestion.strikerX + 150f * cos(rad).toFloat()
                aimTargetY = 820f + 150f * sin(rad).toFloat()
                selectedPower = suggestion.power
                showAIHudAlert = true
            } else {
                lastSuggestedShot = null
                showAIHudAlert = false
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF15161D))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Title & Reset Action Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Practice Sim Area",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Interactive board training sandbox",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }

                IconButton(
                    onClick = { initializeSandbox() },
                    modifier = Modifier.background(Color.White.copy(alpha = 0.06f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset Board Arena Layout",
                        tint = Color(0xFFFFD54F)
                    )
                }
            }

            // Simple Notification AI Alert
            AnimatedVisibility(
                visible = showAIHudAlert,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                lastSuggestedShot?.let { suggestion ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF00E5FF).copy(alpha = 0.08f))
                            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(18.dp))
                            Column {
                                val shotType = when {
                                    suggestion.expectedStrikerPath.size > 2 -> "Striker Cushion Rebound 🦘"
                                    suggestion.expectedCoinPath.size > 2 -> "Coin Bank Rebound 📐"
                                    else -> "Direct Cut Shot 🎯"
                                }
                                Text(
                                    text = "AI Suggested: $shotType",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Position striker. Target pocket ${suggestion.pocketIndex + 1} using ${(suggestion.power * 100).toInt()}% fire strength.",
                                    color = Color(0xFFB2EBF2),
                                    fontSize = 10.5.sp
                                )
                            }
                        }
                    }
                }
            }

            // Real Canvas Rendering Board Layout!
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f) // Ensure wood frame is square
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF3E2723)) // Rich Mahogany border color
                    .border(6.dp, Color(0xFF2D1510), RoundedCornerShape(12.dp))
                    .padding(8.dp) // Cushion thickness offset
                    .background(Color(0xFFFFF1C5)) // Bright Natural Maple wood table color
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            if (!isSimulating) {
                                val scale = size.width / 1000f
                                if (scale > 0f) {
                                    val mappedX = offset.x / scale
                                    val mappedY = offset.y / scale
                                    // Make sure we tap inside playable cushion limits
                                    if (mappedX in 50f..950f && mappedY in 50f..950f) {
                                        aimTargetX = mappedX
                                        aimTargetY = mappedY
                                        showAIHudAlert = false // dismiss override
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            if (!isSimulating) {
                                change.consume()
                                val scale = size.width / 1000f
                                if (scale > 0f) {
                                    aimTargetX = (aimTargetX + dragAmount.x / scale).coerceIn(60f, 940f)
                                    aimTargetY = (aimTargetY + dragAmount.y / scale).coerceIn(60f, 940f)
                                    showAIHudAlert = false // dismiss override
                                }
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val scaleX = size.width / 1000f
                    val scaleY = size.height / 1000f

                    // Function convert virtual to offset
                    fun pt(vx: Float, vy: Float) = Offset(vx * scaleX, vy * scaleY)

                    // 1. Cushion Outer Line Borders
                    drawRect(
                        color = Color(0xFF4E342E),
                        topLeft = pt(50f, 50f),
                        size = Size(900f * scaleX, 900f * scaleY),
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // 2. Baselines for Striker Placements
                    // Bottom baseline
                    drawLine(Color(0xFF5D4037), pt(200f, 820f), pt(800f, 820f), strokeWidth = 1.dp.toPx())
                    drawLine(Color(0xFF5D4037), pt(200f, 800f), pt(800f, 800f), strokeWidth = 1.dp.toPx())
                    drawCircle(Color(0xFFD32F2F), radius = 10f * scaleX, center = pt(200f, 810f))
                    drawCircle(Color(0xFFD32F2F), radius = 10f * scaleX, center = pt(800f, 810f))

                    // Top baseline
                    drawLine(Color(0xFF5D4037), pt(200f, 180f), pt(800f, 180f), strokeWidth = 1.dp.toPx())
                    drawLine(Color(0xFF5D4037), pt(200f, 200f), pt(800f, 200f), strokeWidth = 1.dp.toPx())
                    drawCircle(Color(0xFFD32F2F), radius = 10f * scaleX, center = pt(200f, 190f))
                    drawCircle(Color(0xFFD32F2F), radius = 10f * scaleX, center = pt(800f, 190f))

                    // Left baseline
                    drawLine(Color(0xFF5D4037), pt(180f, 200f), pt(180f, 800f), strokeWidth = 1.dp.toPx())
                    drawLine(Color(0xFF5D4037), pt(200f, 200f), pt(200f, 800f), strokeWidth = 1.dp.toPx())
                    drawCircle(Color(0xFFD32F2F), radius = 10f * scaleX, center = pt(190f, 200f))
                    drawCircle(Color(0xFFD32F2F), radius = 10f * scaleX, center = pt(190f, 800f))

                    // Right baseline
                    drawLine(Color(0xFF5D4037), pt(820f, 200f), pt(820f, 800f), strokeWidth = 1.dp.toPx())
                    drawLine(Color(0xFF5D4037), pt(800f, 200f), pt(800f, 800f), strokeWidth = 1.dp.toPx())
                    drawCircle(Color(0xFFD32F2F), radius = 10f * scaleX, center = pt(810f, 200f))
                    drawCircle(Color(0xFFD32F2F), radius = 10f * scaleX, center = pt(810f, 800f))

                    // 3. Center Circles
                    drawCircle(
                        color = Color(0xFFD32F2F),
                        radius = 80f * scaleX,
                        center = pt(500f, 500f),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    drawCircle(
                        color = Color(0xFF5D4037),
                        radius = 30f * scaleX,
                        center = pt(500f, 500f),
                        style = Stroke(width = 1.dp.toPx())
                    )

                    // Diagonal lines representing board corner alignment guidelines
                    drawLine(Color(0x555D4037), pt(180f, 180f), pt(400f, 400f), strokeWidth = 0.8f.dp.toPx())
                    drawLine(Color(0x555D4037), pt(820f, 180f), pt(600f, 400f), strokeWidth = 0.8f.dp.toPx())
                    drawLine(Color(0x555D4037), pt(180f, 820f), pt(400f, 600f), strokeWidth = 0.8f.dp.toPx())
                    drawLine(Color(0x555D4037), pt(820f, 820f), pt(600f, 600f), strokeWidth = 0.8f.dp.toPx())

                    // 4. Drawing pockets (4 Corner Holes)
                    for (pocket in pockets) {
                        drawCircle(
                            color = Color(0xFF15100F),
                            radius = pocket.radius * scaleX,
                            center = pt(pocket.x, pocket.y)
                        )
                        // Pocket Golden metallic inner border rim
                        drawCircle(
                            color = Color(0xFFFFB300),
                            radius = pocket.radius * scaleX,
                            center = pt(pocket.x, pocket.y),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    // 5. Drawing predicted trainer laser guides (if NOT simulating)
                    if (!isSimulating) {
                        val str = strikerState.value
                        val aimVecX = aimTargetX - str.x
                        val aimVecY = aimTargetY - str.y

                        // Draw Striker Alignment laser (Primary laser in light-blue)
                        drawLine(
                            color = Color(0xFF00B0FF),
                            start = pt(str.x, str.y),
                            end = pt(aimTargetX, aimTargetY),
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f)
                        )

                        // Draw reflected prediction laser (bounces off cushions)
                        val bouncePath = CarromPhysics.calculateBouncePath(
                            startX = str.x,
                            startY = str.y,
                            dx = aimVecX,
                            dy = aimVecY,
                            xMin = 50f, yMin = 50f, xMax = 950f, yMax = 950f,
                            maxBounces = 2
                        )
                        if (bouncePath.size > 2) {
                            for (i in 1 until bouncePath.size - 1) {
                                drawLine(
                                    color = Color(0xFFFF9100),
                                    start = pt(bouncePath[i].x, bouncePath[i].y),
                                    end = pt(bouncePath[i + 1].x, bouncePath[i + 1].y),
                                    strokeWidth = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                                )
                            }
                        }

                        // Drawing AI predicted shot vectors (if AI shot is active)
                        if (showAIHudAlert) {
                            lastSuggestedShot?.let { suggestion ->
                                // Draw predicted striker path (supports multi-segment reflections)
                                if (suggestion.expectedStrikerPath.size > 1) {
                                    for (i in 0 until suggestion.expectedStrikerPath.size - 1) {
                                        val sStart = suggestion.expectedStrikerPath[i]
                                        val sEnd = suggestion.expectedStrikerPath[i + 1]
                                        drawLine(
                                            color = Color(0xFF00E5FF),
                                            start = pt(sStart.x, sStart.y),
                                            end = pt(sEnd.x, sEnd.y),
                                            strokeWidth = 3.dp.toPx()
                                        )
                                    }
                                }

                                // Draw predicted coin path to pocket (supports multi-segment bank reflections)
                                if (suggestion.expectedCoinPath.size > 1) {
                                    for (i in 0 until suggestion.expectedCoinPath.size - 1) {
                                        val cStart = suggestion.expectedCoinPath[i]
                                        val cEnd = suggestion.expectedCoinPath[i + 1]
                                        drawLine(
                                            color = Color(0xFF00E676),
                                            start = pt(cStart.x, cStart.y),
                                            end = pt(cEnd.x, cEnd.y),
                                            strokeWidth = 3.dp.toPx()
                                        )
                                    }
                                    // Draw target ring at the final coin endpoint (pocket)
                                    val finalEndpoint = suggestion.expectedCoinPath.last()
                                    drawCircle(
                                        color = Color(0xFF00E676),
                                        radius = 12f * scaleX,
                                        center = pt(finalEndpoint.x, finalEndpoint.y),
                                        style = Stroke(width = 2.dp.toPx())
                                    )
                                }
                            }
                        }

                        // Target crosshair reticle
                        drawCircle(
                            color = Color(0xFFFFD54F),
                            radius = 16f * scaleX,
                            center = pt(aimTargetX, aimTargetY),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                        drawLine(
                            Color(0xFFFFD54F),
                            pt(aimTargetX - 25f, aimTargetY),
                            pt(aimTargetX + 25f, aimTargetY),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            Color(0xFFFFD54F),
                            pt(aimTargetX, aimTargetY - 25f),
                            pt(aimTargetX, aimTargetY + 25f),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    // 6. Draw active coins
                    for (coin in coinsList) {
                        if (coin.isPocketed) continue

                        // Smooth lighting shadow
                        drawCircle(
                            color = Color(0x3C000000),
                            radius = coin.radius * scaleX,
                            center = pt(coin.x + 3f, coin.y + 4f)
                        )

                        // Outer rim
                        drawCircle(
                            color = coin.color,
                            radius = coin.radius * scaleX,
                            center = pt(coin.x, coin.y)
                        )

                        // Inner concentric highlights for premium design
                        drawCircle(
                            color = if (coin.isWhite) Color.DarkGray.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.35f),
                            radius = (coin.radius * 0.5f) * scaleX,
                            center = pt(coin.x, coin.y),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }

                    // 7. Draw striker if NOT pocketed
                    val str = strikerState.value
                    if (!str.isPocketed) {
                        // Striker shadow
                        drawCircle(
                            color = Color(0x40000000),
                            radius = str.radius * scaleX,
                            center = pt(str.x + 4f, str.y + 5f)
                        )
                        // Striker Body (Elegant Glowing Crimson Red)
                        drawCircle(
                            color = Color(0xFFD50000),
                            radius = str.radius * scaleX,
                            center = pt(str.x, str.y)
                        )
                        // Striker Inner Silver Metallic ring
                        drawCircle(
                            color = Color(0xFFECEFF1),
                            radius = (str.radius * 0.7f) * scaleX,
                            center = pt(str.x, str.y),
                            style = Stroke(width = 2.dp.toPx())
                        )
                        // Center dot of striker
                        drawCircle(
                            color = Color(0xFF263238),
                            radius = (str.radius * 0.22f) * scaleX,
                            center = pt(str.x, str.y)
                        )
                    }
                }
            }

            // Striker repositioning bar slider (In-board slider)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Striker Position", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text("${strikerX.toInt()}px", color = Color(0xFF00E5FF), fontSize = 11.sp)
                }
                Slider(
                    value = strikerX,
                    onValueChange = { if (!isSimulating) strikerX = it },
                    valueRange = 220f..780f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF),
                        inactiveTrackColor = Color.White.copy(alpha = 0.08f)
                    ),
                    enabled = !isSimulating,
                    modifier = Modifier.height(24.dp)
                )
            }

            // Power Slider
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Shot Power Strength", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text("${(selectedPower * 100).toInt()}%", color = Color(0xFFFF9100), fontSize = 11.sp)
                }
                Slider(
                    value = selectedPower,
                    onValueChange = { selectedPower = it },
                    valueRange = 0.1f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFF9100),
                        activeTrackColor = Color(0xFFFF9100),
                        inactiveTrackColor = Color.White.copy(alpha = 0.08f)
                    ),
                    enabled = !isSimulating,
                    modifier = Modifier.height(24.dp)
                )
            }

            // Live Assist Toggle Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = if (isLiveAssistEnabled) Color(0xFF00E5FF) else Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Real-time AI Assist",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Auto-calculate on striker drag",
                            color = Color.Gray,
                            fontSize = 9.5.sp
                        )
                    }
                }
                Switch(
                    checked = isLiveAssistEnabled,
                    onCheckedChange = {
                        isLiveAssistEnabled = it
                        if (!it) {
                            showAIHudAlert = false
                            lastSuggestedShot = null
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00E5FF),
                        checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.4f),
                        uncheckedThumbColor = Color.LightGray,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.08f)
                    )
                )
            }

            // Action Row: AI SUGGESTION & FIRE!
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // AI Auto Shot Suggestions Button
                Button(
                    onClick = {
                        val suggestion = CarromPhysics.calculateAISuggestion(
                            coins = coinsList,
                            strikerRadius = strikerState.value.radius,
                            baselineY = 820f,
                            pockets = pockets,
                            xMin = 50f, yMin = 50f, xMax = 950f, yMax = 950f
                        )
                        if (suggestion != null) {
                            lastSuggestedShot = suggestion
                            strikerX = suggestion.strikerX
                            val rad = Math.toRadians(suggestion.targetAngle.toDouble())
                            // Set target aim along that target angle from striker
                            aimTargetX = suggestion.strikerX + 150f * cos(rad).toFloat()
                            aimTargetY = 820f + 150f * sin(rad).toFloat()
                            selectedPower = suggestion.power
                            showAIHudAlert = true
                        } else {
                            Toast.makeText(
                                context,
                                "No clear AI shots found of optimal cut angle! Clear other obstacles.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF).copy(alpha = 0.12f), contentColor = Color(0xFF00E5FF)),
                    enabled = !isSimulating,
                    modifier = Modifier
                        .weight(1.1f)
                        .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("AI Suggest Shot", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                // Fire striker button
                Button(
                    onClick = {
                        if (isSimulating) return@Button
                        val s = strikerState.value
                        val dx = aimTargetX - s.x
                        val dy = aimTargetY - s.y
                        val mag = sqrt(dx * dx + dy * dy)
                        if (mag > 0.01f) {
                            val maxVelocity = 40f
                            val velScale = selectedPower * maxVelocity
                            strikerState.value.vx = (dx / mag) * velScale
                            strikerState.value.vy = (dy / mag) * velScale
                            isSimulating = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000)),
                    enabled = !isSimulating,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Fire Striker", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
