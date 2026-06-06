package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.physics.CarromPhysics
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

class CarromOverlayService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val CHANNEL_ID = "carrom_ai_trainer_overlay"
    }

    private var windowManager: WindowManager? = null
    private var lifecycleOwner: ServiceLifecycleOwner? = null

    // Overlay State Variables
    private var handleAX by mutableStateOf(300f)
    private var handleAY by mutableStateOf(850f)
    private var handleBX by mutableStateOf(500f)
    private var handleBY by mutableStateOf(600f)

    // Board Calibration corners (creates a default square on center screen)
    private var handleTLX by mutableStateOf(100f)
    private var handleTLY by mutableStateOf(400f)
    private var handleBRX by mutableStateOf(900f)
    private var handleBRY by mutableStateOf(1200f)

    // Target Coin Handle
    private var handleCX by mutableStateOf(500f)
    private var handleCY by mutableStateOf(750f)

    // UI & Calculation settings
    private var isOverlayVisible by mutableStateOf(true)
    private var isBouncePredictionEnabled by mutableStateOf(true)
    private var simulatedPower by mutableStateOf(0.6f)
    private var isControlsExpanded by mutableStateOf(true)
    private var isCalibrating by mutableStateOf(false)
    private var isAutoSuggestEnabled by mutableStateOf(true)
    private var selectedPocketIndex by mutableStateOf(-1) // -1 means Auto Select Easiest Corner

    // Coins system for Aim prediction
    private var virtualCoinsLeft by mutableStateOf(50)
    private var lastDeductionTime by mutableStateOf(0L)
    private var suggestionStatusText by mutableStateOf("AI Auto-Suggest Ready")

    // Floating Views
    private var canvasView: ComposeView? = null
    private var controllerView: ComposeView? = null
    private var handleAView: ComposeView? = null
    private var handleBView: ComposeView? = null
    private var handleCView: ComposeView? = null
    private var handleTLView: ComposeView? = null
    private var handleBRView: ComposeView? = null

    // Window Layout Params
    private var paramsCanvas: WindowManager.LayoutParams? = null
    private var paramsController: WindowManager.LayoutParams? = null
    private var paramsHandleA: WindowManager.LayoutParams? = null
    private var paramsHandleB: WindowManager.LayoutParams? = null
    private var paramsHandleC: WindowManager.LayoutParams? = null
    private var paramsHandleTL: WindowManager.LayoutParams? = null
    private var paramsHandleBR: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        lifecycleOwner = ServiceLifecycleOwner().apply { start() }

        createNotificationChannel()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Initialize and add overlays
        setupOverlays()
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Carrom AI Trainer Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows overlay guidelines and controls status"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val stopIntent = Intent(this, CarromOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Carrom AI Trainer Overlay Running")
            .setContentText("Use the transparent on-screen overlay controls to align bank shots.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Controls", stopPendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun createHandleLayoutParams(startX: Float, startY: Float, handleRadiusPx: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            handleRadiusPx * 2,
            handleRadiusPx * 2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (startX - handleRadiusPx).toInt()
            y = (startY - handleRadiusPx).toInt()
        }
    }

    private fun createHandleView(
        lifecycle: ServiceLifecycleOwner,
        label: String,
        color: Color,
        onDragUpdate: (Float, Float) -> Unit
    ): ComposeView {
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setViewTreeViewModelStoreOwner(lifecycle)
            setContent {
                HandleComponent(
                    label = label,
                    color = color,
                    onDrag = { dx, dy ->
                        onDragUpdate(dx, dy)
                    }
                )
            }
        }
    }

    private fun updateHandleLayout(view: ComposeView?, params: WindowManager.LayoutParams?, x: Float, y: Float, isVisible: Boolean) {
        val window = windowManager ?: return
        val density = resources.displayMetrics.density
        val handleRadiusPx = (24 * density).toInt()
        val sizePx = if (isVisible) (handleRadiusPx * 2) else 0

        params?.let {
            it.width = sizePx
            it.height = sizePx
            it.x = (x - handleRadiusPx).toInt()
            it.y = (y - handleRadiusPx).toInt()
            try {
                if (view?.isAttachedToWindow == true) {
                    window.updateViewLayout(view, it)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun syncHandles() {
        // Handle A (Striker "S"): Always visible if overlay is visible
        updateHandleLayout(handleAView, paramsHandleA, handleAX, handleAY, isOverlayVisible)

        // Handle B (Target "T"): Visible if overlay is visible AND manual mode is enabled
        updateHandleLayout(handleBView, paramsHandleB, handleBX, handleBY, isOverlayVisible && !isAutoSuggestEnabled)

        // Handle C (Target Coin "C"): Visible if overlay is visible AND auto-suggest mode is enabled
        updateHandleLayout(handleCView, paramsHandleC, handleCX, handleCY, isOverlayVisible && isAutoSuggestEnabled)

        // Handle TL (Top-Left Board Corner "TL"): Visible if overlay is visible AND calibration mode is enabled
        updateHandleLayout(handleTLView, paramsHandleTL, handleTLX, handleTLY, isOverlayVisible && isCalibrating)

        // Handle BR (Bottom-Right Board Corner "BR"): Visible if overlay is visible AND calibration mode is enabled
        updateHandleLayout(handleBRView, paramsHandleBR, handleBRX, handleBRY, isOverlayVisible && isCalibrating)
    }

    private fun triggerCoinDeduction() {
        if (!isAutoSuggestEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastDeductionTime > 3000L) { // 3 seconds interval
            if (virtualCoinsLeft > 0) {
                virtualCoinsLeft--
                lastDeductionTime = now
            }
        }
    }

    private fun setupOverlays() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        // Remove existing views first to avoid duplicate additions and clean up state safely
        removeOverlays()

        val window = windowManager ?: return
        val lifecycle = lifecycleOwner ?: return

        val density = resources.displayMetrics.density
        val handleRadiusPx = (24 * density).toInt()

        // 1. Line Drawing Canvas Overlay (Fully pass-through touch events)
        paramsCanvas = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        canvasView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setViewTreeViewModelStoreOwner(lifecycle)
            setContent {
                OverlayCanvasComponent()
            }
        }

        // Initialize LayoutParams for all 5 Handles
        paramsHandleA = createHandleLayoutParams(handleAX, handleAY, handleRadiusPx)
        paramsHandleB = createHandleLayoutParams(handleBX, handleBY, handleRadiusPx)
        paramsHandleC = createHandleLayoutParams(handleCX, handleCY, handleRadiusPx)
        paramsHandleTL = createHandleLayoutParams(handleTLX, handleTLY, handleRadiusPx)
        paramsHandleBR = createHandleLayoutParams(handleBRX, handleBRY, handleRadiusPx)

        // Create Handle Views and wire their drags to states and sync
        handleAView = createHandleView(lifecycle, "S", Color(0xFF00E5FF)) { dx, dy ->
            handleAX += dx
            handleAY += dy
            triggerCoinDeduction()
            syncHandles()
        }

        handleBView = createHandleView(lifecycle, "T", Color(0xFFFFD54F)) { dx, dy ->
            handleBX += dx
            handleBY += dy
            syncHandles()
        }

        handleCView = createHandleView(lifecycle, "C", Color(0xFF00E676)) { dx, dy ->
            handleCX += dx
            handleCY += dy
            triggerCoinDeduction()
            syncHandles()
        }

        handleTLView = createHandleView(lifecycle, "TL", Color(0xFFFF5252)) { dx, dy ->
            handleTLX += dx
            handleTLY += dy
            syncHandles()
        }

        handleBRView = createHandleView(lifecycle, "BR", Color(0xFFFF5252)) { dx, dy ->
            handleBRX += dx
            handleBRY += dy
            syncHandles()
        }

        // 4. Floating Main Controller Panel
        paramsController = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 150
        }

        controllerView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setViewTreeViewModelStoreOwner(lifecycle)
            setContent {
                FloatingControllerComponent(
                    onClose = { stopSelf() },
                    onDrag = { dx, dy ->
                        paramsController?.let {
                            it.x += dx.toInt()
                            it.y += dy.toInt()
                            try {
                                if (controllerView?.isAttachedToWindow == true) {
                                    window.updateViewLayout(controllerView, it)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                )
            }
        }

        // Add views to Window individually to prevent total failure if one fails
        try { window.addView(canvasView, paramsCanvas) } catch (e: Exception) { e.printStackTrace() }
        try { window.addView(handleAView, paramsHandleA) } catch (e: Exception) { e.printStackTrace() }
        try { window.addView(handleBView, paramsHandleB) } catch (e: Exception) { e.printStackTrace() }
        try { window.addView(handleCView, paramsHandleC) } catch (e: Exception) { e.printStackTrace() }
        try { window.addView(handleTLView, paramsHandleTL) } catch (e: Exception) { e.printStackTrace() }
        try { window.addView(handleBRView, paramsHandleBR) } catch (e: Exception) { e.printStackTrace() }
        try { window.addView(controllerView, paramsController) } catch (e: Exception) { e.printStackTrace() }

        // Start initial synchronization of handle layouts and visibilities
        syncHandles()
    }

    private fun removeOverlays() {
        val window = windowManager ?: return
        
        val viewsList = listOf(
            canvasView to { canvasView = null },
            handleAView to { handleAView = null },
            handleBView to { handleBView = null },
            handleCView to { handleCView = null },
            handleTLView to { handleTLView = null },
            handleBRView to { handleBRView = null },
            controllerView to { controllerView = null }
        )

        for ((view, clearRef) in viewsList) {
            try {
                view?.let {
                    it.disposeComposition()
                    window.removeView(it)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                clearRef()
            }
        }
    }

    @Composable
    fun HandleComponent(
        label: String,
        color: Color,
        onDrag: (Float, Float) -> Unit
    ) {
        if (!isOverlayVisible) return

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f))
                .border(2.dp, color, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Precision inner crosshair
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2
                val cy = size.height / 2
                drawLine(color, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.dp.toPx())
                drawLine(color, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.dp.toPx())
            }
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = Color.Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    fun OverlayCanvasComponent() {
        if (!isOverlayVisible) return

        // Visual animation for simulated shot speed
        var dashOffset by remember { mutableStateOf(0f) }
        LaunchedEffect(simulatedPower) {
            while (true) {
                val speed = 2f + simulatedPower * 18f
                dashOffset = (dashOffset - speed) % 60f
                delay(16)
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw Calibrated Board Boundary (semitransparent guides)
            val rectColor = if (isCalibrating) Color(0xFFFFD54F) else Color(0xFF00E5FF).copy(alpha = 0.25f)
            val strokeWidth = if (isCalibrating) 2.dp.toPx() else 1.dp.toPx()
            
            // Draw calibrated table border
            drawRect(
                color = rectColor,
                topLeft = Offset(handleTLX, handleTLY),
                size = androidx.compose.ui.geometry.Size(handleBRX - handleTLX, handleBRY - handleTLY),
                style = Stroke(
                    width = strokeWidth,
                    pathEffect = if (isCalibrating) PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f) else null
                )
            )

            // Draw calibrated corner pockets as visible rings to help player align
            val pocketsList = listOf(
                Offset(handleTLX, handleTLY), // TL
                Offset(handleBRX, handleTLY), // TR
                Offset(handleTLX, handleBRY), // BL
                Offset(handleBRX, handleBRY)  // BR
            )
            
            pocketsList.forEach { p ->
                drawCircle(
                    color = rectColor.copy(alpha = 0.5f),
                    radius = 20.dp.toPx(),
                    center = p,
                    style = Stroke(width = 1.5.dp.toPx())
                )
                drawCircle(
                    color = rectColor.copy(alpha = 0.12f),
                    radius = 20.dp.toPx(),
                    center = p
                )
            }

            if (!isAutoSuggestEnabled) {
                // =============== MANUAL AIMING MODE ===============
                val start = Offset(handleAX, handleAY)
                val end = Offset(handleBX, handleBY)

                // Direct Line
                drawLine(
                    color = Color(0xFF00E5FF),
                    start = start,
                    end = end,
                    strokeWidth = 3.dp.toPx()
                )

                drawLine(
                    color = Color(0xFF00E5FF).copy(alpha = 0.3f),
                    start = start,
                    end = end,
                    strokeWidth = 8.dp.toPx()
                )

                // Animated dots moving along power direction vector
                val dx = handleBX - handleAX
                val dy = handleBY - handleAY
                val dirMag = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dirMag > 1.0f) {
                    drawCircle(
                        color = Color.White,
                        radius = 5.dp.toPx(),
                        center = Offset(
                            handleAX + (dx / dirMag) * (dirMag * (1f + simulatedPower) * 0.35f % dirMag),
                            handleAY + (dy / dirMag) * (dirMag * (1f + simulatedPower) * 0.35f % dirMag)
                        )
                    )
                }

                // Bounce wall predictions (reflecting off our calibrated boundary values!)
                if (isBouncePredictionEnabled) {
                    val path = CarromPhysics.calculateBouncePath(
                        startX = handleBX,
                        startY = handleBY,
                        dx = dx,
                        dy = dy,
                        xMin = handleTLX,
                        yMin = handleTLY,
                        xMax = handleBRX,
                        yMax = handleBRY,
                        maxBounces = 2
                    )

                    if (path.size > 1) {
                        for (i in 0 until path.size - 1) {
                            val p1 = Offset(path[i].x, path[i].y)
                            val p2 = Offset(path[i + 1].x, path[i + 1].y)

                            drawLine(
                                color = Color(0xFFFFD54F),
                                start = p1,
                                end = p2,
                                strokeWidth = 2.5f.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(20f, 15f),
                                    dashOffset
                                )
                            )

                            drawCircle(
                                color = Color(0xFFFFA000),
                                radius = 4.dp.toPx(),
                                center = p2
                            )
                        }
                    }
                }
            } else {
                // =============== AI AUTO-SUGGEST MODE ===============
                var bestPocketIndex = -1
                var bestScore = -10000f
                var bestContactPoint = Offset(0f, 0f)
                var bestTargetDir = Offset(0f, 0f)
                var bestPocketOffset = Offset(0f, 0f)

                val radiusSum = 50f // combined average radius for visual contact calculations

                for (i in 0 until 4) {
                    if (selectedPocketIndex != -1 && selectedPocketIndex != i) continue

                    val pocket = pocketsList[i]

                    // Vector from pocket to coin center
                    val pcX = handleCX - pocket.x
                    val pcY = handleCY - pocket.y
                    val pcDist = kotlin.math.sqrt(pcX * pcX + pcY * pcY)
                    if (pcDist < 10f) continue

                    val pcDx = pcX / pcDist
                    val pcDy = pcY / pcDist

                    // Contact point = Coin + normal * radiusSum
                    val contactX = handleCX + pcDx * radiusSum
                    val contactY = handleCY + pcDy * radiusSum
                    val contactPoint = Offset(contactX, contactY)

                    // Vector from Striker to Contact point
                    val stX = contactX - handleAX
                    val stY = contactY - handleAY
                    val stDist = kotlin.math.sqrt(stX * stX + stY * stY)
                    if (stDist < 10f) continue

                    val stDx = stX / stDist
                    val stDy = stY / stDist

                    // Aim vector of the coin (to the pocket)
                    val coinDirX = -pcDx
                    val coinDirY = -pcDy

                    // Alignment check (must be hitting the correct side of the coin)
                    val dot = stDx * coinDirX + stDy * coinDirY

                    // Quality score
                    val score = dot * 1000f - pcDist * 0.15f - stDist * 0.05f

                    // If direction is positive, a legal cut shot is geometrically possible!
                    if (dot > 0.04f && (bestPocketIndex == -1 || score > bestScore)) {
                        bestPocketIndex = i
                        bestScore = score
                        bestContactPoint = contactPoint
                        bestTargetDir = Offset(stDx, stDy)
                        bestPocketOffset = pocket
                    }
                }

                if (bestPocketIndex != -1 && virtualCoinsLeft > 0) {
                    suggestionStatusText = when(bestPocketIndex) {
                        0 -> "🎯 Target: Top-Left Corner"
                        1 -> "🎯 Target: Top-Right Corner"
                        2 -> "🎯 Target: Bottom-Left Corner"
                        else -> "🎯 Target: Bottom-Right Corner"
                    }

                    val strikerStart = Offset(handleAX, handleAY)

                    // 1. Draw Striker to Contact Point Line (Teal/Blue)
                    drawLine(
                        color = Color(0xFF00E5FF),
                        start = strikerStart,
                        end = bestContactPoint,
                        strokeWidth = 3.dp.toPx()
                    )

                    drawLine(
                        color = Color(0xFF00E5FF).copy(alpha = 0.25f),
                        start = strikerStart,
                        end = bestContactPoint,
                        strokeWidth = 9.dp.toPx()
                    )

                    // Interactive animation on striker alignment path
                    val sToCDist = kotlin.math.sqrt((bestContactPoint.x - handleAX) * (bestContactPoint.x - handleAX) + (bestContactPoint.y - handleAY) * (bestContactPoint.y - handleAY))
                    if (sToCDist > 5f) {
                        val progress = (System.currentTimeMillis() % 2000) / 2000f
                        drawCircle(
                            color = Color.White,
                            radius = 4.5f.dp.toPx(),
                            center = Offset(
                                handleAX + (bestContactPoint.x - handleAX) * progress,
                                handleAY + (bestContactPoint.y - handleAY) * progress
                            )
                        )
                    }

                    // 2. Draw Ghost Striker circle at collision position
                    drawCircle(
                        color = Color(0xFF00E5FF).copy(alpha = 0.12f),
                        radius = 24.dp.toPx(),
                        center = bestContactPoint
                    )
                    drawCircle(
                        color = Color(0xFF00E5FF).copy(alpha = 0.5f),
                        radius = 24.dp.toPx(),
                        center = bestContactPoint,
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                        )
                    )

                    drawCircle(
                        color = Color.White,
                        radius = 3.dp.toPx(),
                        center = bestContactPoint
                    )

                    // 3. Draw Coin's Path to Chosen Pocket (Emerald Green)
                    val coinStart = Offset(handleCX, handleCY)
                    drawLine(
                        color = Color(0xFF00E676),
                        start = coinStart,
                        end = bestPocketOffset,
                        strokeWidth = 3.dp.toPx()
                    )
                    drawLine(
                        color = Color(0xFF00E676).copy(alpha = 0.25f),
                        start = coinStart,
                        end = bestPocketOffset,
                        strokeWidth = 9.dp.toPx()
                    )

                    // 4. Draw Extended Aim guide ray
                    val extEndX = bestContactPoint.x + bestTargetDir.x * 250f
                    val extEndY = bestContactPoint.y + bestTargetDir.y * 250f
                    drawLine(
                        color = Color(0xFFFFD54F),
                        start = bestContactPoint,
                        end = Offset(extEndX, extEndY),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), dashOffset)
                    )
                } else {
                    if (virtualCoinsLeft <= 0) {
                        suggestionStatusText = "🪙 0 Coins left! Click Refill."
                    } else {
                        suggestionStatusText = "⚠️ Pocket blocked (Extreme angle!)"
                    }
                    
                    // Simple red link to show unreachable vector
                    drawLine(
                        color = Color(0xFFFF5252).copy(alpha = 0.5f),
                        start = Offset(handleAX, handleAY),
                        end = Offset(handleCX, handleCY),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                    )
                }
            }
        }
    }

    @Composable
    fun FloatingControllerComponent(
        onClose: () -> Unit,
        onDrag: (Float, Float) -> Unit
    ) {
        val brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFA1E1F28),
                Color(0xFA0E0F14)
            )
        )

        Card(
            modifier = Modifier
                .width(230.dp)
                .border(
                    1.5.dp,
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF00E5FF).copy(alpha = 0.5f), Color(0xFFFFD54F).copy(alpha = 0.5f))
                    ),
                    RoundedCornerShape(16.dp)
                )
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(modifier = Modifier.background(brush).padding(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isOverlayVisible) Color(0xFF00E5FF) else Color.Red)
                            )
                            Text(
                                text = "Carrom AI Trainer",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = { isControlsExpanded = !isControlsExpanded },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Expand Settings",
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close overlay service",
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = isControlsExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Divider(color = Color.White.copy(alpha = 0.12f))

                            // Status Banner
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isAutoSuggestEnabled && virtualCoinsLeft > 0 && suggestionStatusText.startsWith("🎯")) 
                                            Color(0xFF00E676).copy(alpha = 0.12f)
                                        else if (isAutoSuggestEnabled && virtualCoinsLeft <= 0)
                                            Color(0xFFFF5252).copy(alpha = 0.12f)
                                        else 
                                            Color(0xFF00E5FF).copy(alpha = 0.12f)
                                    )
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = suggestionStatusText,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // 🪙 Premium Coins balance row with interactive Refiller
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(text = "🪙", fontSize = 13.sp)
                                    Text(text = "Coins: $virtualCoinsLeft", color = Color(0xFFFFD54F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    text = "Refill",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable { 
                                            virtualCoinsLeft = 50
                                            suggestionStatusText = "Coins refilled! Ready."
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }

                            // Switch Visible Guide
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Visible Guide",
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                                Switch(
                                    checked = isOverlayVisible,
                                    onCheckedChange = { 
                                        isOverlayVisible = it
                                        syncHandles()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF00E5FF),
                                        checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.3f),
                                        uncheckedThumbColor = Color.Gray,
                                        uncheckedTrackColor = Color.DarkGray
                                    ),
                                    modifier = Modifier.scale(0.7f)
                                )
                            }

                            // Toggle AI Auto-Suggest vs Manual Mode
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "AI Auto-Suggest",
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                                Switch(
                                    checked = isAutoSuggestEnabled,
                                    onCheckedChange = { 
                                        isAutoSuggestEnabled = it
                                        syncHandles()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF00E676),
                                        checkedTrackColor = Color(0xFF00E676).copy(alpha = 0.3f),
                                        uncheckedThumbColor = Color.Gray,
                                        uncheckedTrackColor = Color.DarkGray
                                    ),
                                    modifier = Modifier.scale(0.7f)
                                )
                            }

                            // Toggle Table corners calibration handles
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Calibrate Table",
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                                Switch(
                                    checked = isCalibrating,
                                    onCheckedChange = { 
                                        isCalibrating = it
                                        syncHandles()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFFFFD54F),
                                        checkedTrackColor = Color(0xFFFFD54F).copy(alpha = 0.3f),
                                        uncheckedThumbColor = Color.Gray,
                                        uncheckedTrackColor = Color.DarkGray
                                    ),
                                    modifier = Modifier.scale(0.7f)
                                )
                            }

                            // IF AI Suggestion is active, render pocket selector buttons
                            if (isAutoSuggestEnabled) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Aim Target Pocket:",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 10.5.sp
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        val pocketsLabels = listOf("Auto", "TL", "TR", "BL", "BR")
                                        val indices = listOf(-1, 0, 1, 2, 3)
                                        indices.forEachIndexed { index, i ->
                                            val isSelected = selectedPocketIndex == i
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(
                                                        if (isSelected) Color(0xFF00E676).copy(alpha = 0.25f)
                                                        else Color.White.copy(alpha = 0.08f)
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (isSelected) Color(0xFF00E676) else Color.Transparent,
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .clickable {
                                                        selectedPocketIndex = i
                                                        if (virtualCoinsLeft > 0) {
                                                            virtualCoinsLeft--
                                                            suggestionStatusText = "Applying formula..."
                                                        }
                                                    }
                                                    .padding(vertical = 4.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = pocketsLabels[index],
                                                    color = if (isSelected) Color(0xFF00E676) else Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Manual bounce wall prediction toggle & strength slider
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Wall Bounce Pred",
                                            color = Color.White,
                                            fontSize = 12.sp
                                        )
                                        Switch(
                                            checked = isBouncePredictionEnabled,
                                            onCheckedChange = { isBouncePredictionEnabled = it },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = Color(0xFFFFD54F),
                                                checkedTrackColor = Color(0xFFFFD54F).copy(alpha = 0.3f),
                                                uncheckedThumbColor = Color.Gray,
                                                uncheckedTrackColor = Color.DarkGray
                                            ),
                                            modifier = Modifier.scale(0.7f)
                                        )
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Simulated Power",
                                                color = Color.White,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                text = "${(simulatedPower * 100).toInt()}%",
                                                color = Color(0xFF00E5FF),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Slider(
                                            value = simulatedPower,
                                            onValueChange = { simulatedPower = it },
                                            colors = SliderDefaults.colors(
                                                thumbColor = Color(0xFF00E5FF),
                                                activeTrackColor = Color(0xFF00E5FF),
                                                inactiveTrackColor = Color.Gray.copy(alpha = 0.5f)
                                            ),
                                            modifier = Modifier.height(24.dp)
                                        )
                                    }
                                }
                            }

                            // Floating visual instructions info
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF00E5FF).copy(alpha = 0.08f))
                                    .padding(6.dp)
                            ) {
                                Text(
                                    text = if (isAutoSuggestEnabled) 
                                        "Aim 'S' with your striker, target coin with 'C'. 'TL'/'BR' adjust board corners!"
                                    else
                                        "Align 'S' with your game's striker and 'T' with target coin.",
                                    color = Color(0xFFB2EBF2),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        removeOverlays()
        lifecycleOwner?.stop()
        super.onDestroy()
    }

    // Helper custom scale modifier since standard scale was causing packaging resolution errors
    private fun Modifier.scale(f: Float): Modifier = this.then(
        object : Modifier.Element {
            // Simply a visual spacing helper inside this Compose scope
        }
    )

    /**
     * Special Custom Service Lifecycle Owner to manage Compose view states cleanly
     */
    inner class ServiceLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        fun start() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    }
}
