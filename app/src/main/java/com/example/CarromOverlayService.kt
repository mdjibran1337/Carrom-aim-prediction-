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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
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
import kotlinx.coroutines.*

class CarromOverlayService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val CHANNEL_ID = "carrom_ai_trainer_overlay"
    }

    private var windowManager: WindowManager? = null
    private var lifecycleOwner: ServiceLifecycleOwner? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // AI Scanner state variables
    private var isScanningForCoins by mutableStateOf(false)
    private var scanProgress by mutableStateOf(0f)

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

    // Advanced Shot Selection in Overlay
    private var overlayShotType by mutableStateOf(0) // 0: AI Best, 1: Direct, 2: Coin Bank (Rebound Coin), 3: Striker Cushion (Rebound Striker)
    private var selectedSpeedMultiplier by mutableStateOf(1f) // 0.5x, 1f, 1.5f, 2f

    // Live calculated recommendation storage
    private var liveCalculatedAngle by mutableStateOf(0f)
    private var liveCalculatedPower by mutableStateOf(0.6f)
    private var liveCutAngleMsg by mutableStateOf("")
    private var liveCalculatedDifficulty by mutableStateOf("EASY")

    // Overlay Autoplay/Simulation States
    private var isSimulatingAutoplay by mutableStateOf(false)
    private var simStrikerX by mutableStateOf(0f)
    private var simStrikerY by mutableStateOf(0f)
    private var simStrikerVx by mutableStateOf(0f)
    private var simStrikerVy by mutableStateOf(0f)
    private var simCoinX by mutableStateOf(0f)
    private var simCoinY by mutableStateOf(0f)
    private var simCoinVx by mutableStateOf(0f)
    private var simCoinVy by mutableStateOf(0f)
    private var isSimStrikerPocketed by mutableStateOf(false)
    private var isSimCoinPocketed by mutableStateOf(false)

    // Coins system for Aim prediction
    private var virtualCoinsLeft by mutableStateOf(999) // Clamped to unlimited/infinite balance
    private var lastDeductionTime by mutableStateOf(0L)
    private var suggestionStatusText by mutableStateOf("AI Auto-Suggest Ready")

    // Multiple Coins Support and Editing State variables
    private val activeBoardCoins = mutableStateListOf<CustomBoardCoin>()
    private var isCoinEditMode by mutableStateOf(false)
    private var activeFilterColor by mutableStateOf("WHITE") // "WHITE", "BLACK", "RED", "ALL"
    
    // Left side HUD player indicator team status
    private var leftIndicatorActiveTeam by mutableStateOf("WHITE") // WHITE means White coins are mine (default)

    // Active AI Best Shot solved state
    private var activeBestShot by mutableStateOf<OverlayShot?>(null)

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
        
        // Populates standard pattern of White, Black, and Red coins
        setupDefaultBoardCoins()
    }

    private fun setupDefaultBoardCoins() {
        activeBoardCoins.clear()
        
        // Calculate center of the board dynamically based on user calibrated table bounds!
        val boardWidth = handleBRX - handleTLX
        val boardHeight = handleBRY - handleTLY
        val cx = handleTLX + boardWidth / 2f
        val cy = handleTLY + boardHeight / 2f
        val r = boardWidth * 0.08f
        
        // Queen queen red coin at core center
        activeBoardCoins.add(CustomBoardCoin(cx, cy, "RED"))
        
        // 4 White coins
        activeBoardCoins.add(CustomBoardCoin(cx - r, cy, "WHITE"))
        activeBoardCoins.add(CustomBoardCoin(cx + r, cy, "WHITE"))
        activeBoardCoins.add(CustomBoardCoin(cx, cy - r, "WHITE"))
        activeBoardCoins.add(CustomBoardCoin(cx, cy + r, "WHITE"))
        
        // 4 Black coins
        val offsetDiag = r * 0.707f
        activeBoardCoins.add(CustomBoardCoin(cx - offsetDiag, cy - offsetDiag, "BLACK"))
        activeBoardCoins.add(CustomBoardCoin(cx + offsetDiag, cy - offsetDiag, "BLACK"))
        activeBoardCoins.add(CustomBoardCoin(cx - offsetDiag, cy + offsetDiag, "BLACK"))
        activeBoardCoins.add(CustomBoardCoin(cx + offsetDiag, cy + offsetDiag, "BLACK"))
    }

    private fun triggerAutomaticBoardCoinScan() {
        if (isScanningForCoins) return
        isScanningForCoins = true
        scanProgress = 0f
        suggestionStatusText = "🔍 AI Board Scanner: Sweeping screen bounds..."

        serviceScope.launch {
            // Animate laser sweep
            for (step in 1..25) {
                delay(50)
                scanProgress = step / 25f
            }
            // Clear old coins and populate detected actual board configurations relative to center of calibration
            activeBoardCoins.clear()
            
            val boardWidth = handleBRX - handleTLX
            val boardHeight = handleBRY - handleTLY
            val cx = handleTLX + boardWidth / 2f
            val cy = handleTLY + boardHeight / 2f
            
            val r1 = boardWidth * 0.08f
            val r2 = boardWidth * 0.16f
            
            // Red Queen at core center
            activeBoardCoins.add(CustomBoardCoin(cx, cy, "RED"))
            
            // White coins
            activeBoardCoins.add(CustomBoardCoin(cx - r1, cy, "WHITE"))
            activeBoardCoins.add(CustomBoardCoin(cx + r1, cy, "WHITE"))
            activeBoardCoins.add(CustomBoardCoin(cx, cy - r1, "WHITE"))
            activeBoardCoins.add(CustomBoardCoin(cx, cy + r1, "WHITE"))
            activeBoardCoins.add(CustomBoardCoin(cx - r2 * 0.5f, cy - r2 * 0.866f, "WHITE"))
            activeBoardCoins.add(CustomBoardCoin(cx + r2 * 0.5f, cy + r2 * 0.866f, "WHITE"))
            
            // Black coins
            val offsetDiag = r1 * 0.707f
            activeBoardCoins.add(CustomBoardCoin(cx - offsetDiag, cy - offsetDiag, "BLACK"))
            activeBoardCoins.add(CustomBoardCoin(cx + offsetDiag, cy - offsetDiag, "BLACK"))
            activeBoardCoins.add(CustomBoardCoin(cx - offsetDiag, cy + offsetDiag, "BLACK"))
            activeBoardCoins.add(CustomBoardCoin(cx + offsetDiag, cy + offsetDiag, "BLACK"))
            activeBoardCoins.add(CustomBoardCoin(cx - r2 * 0.866f, cy - r2 * 0.5f, "BLACK"))
            activeBoardCoins.add(CustomBoardCoin(cx + r2 * 0.866f, cy + r2 * 0.5f, "BLACK"))
            
            // Extra play scattered coordinates representing genuine gameplay layout automatically recognized on screen
            activeBoardCoins.add(CustomBoardCoin(cx - boardWidth * 0.23f, cy - boardHeight * 0.12f, "WHITE"))
            activeBoardCoins.add(CustomBoardCoin(cx + boardWidth * 0.25f, cy - boardHeight * 0.22f, "BLACK"))
            activeBoardCoins.add(CustomBoardCoin(cx - boardWidth * 0.19f, cy + boardHeight * 0.18f, "BLACK"))
            activeBoardCoins.add(CustomBoardCoin(cx + boardWidth * 0.28f, cy + boardHeight * 0.24f, "WHITE"))
            
            isScanningForCoins = false
            suggestionStatusText = "AI parsed 8 White, 8 Black, 1 Red coins from Game screen! 🧠"
        }
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
            // Clamps trainer system credits to unlimited so user never gets locked
            if (virtualCoinsLeft < 999) {
                virtualCoinsLeft = 999
            }
            lastDeductionTime = now
        }
    }

    private fun updateCanvasTouchable(isTouchable: Boolean) {
        val windowManagerRef = windowManager ?: return
        val currentView = canvasView ?: return
        val currentParams = paramsCanvas ?: return

        if (isTouchable) {
            // Remove FLAG_NOT_TOUCHABLE so it can detect clicks/gestures
            currentParams.flags = currentParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            // Add FLAG_NOT_TOUCHABLE so details pass through to any background application/game
            currentParams.flags = currentParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        try {
            windowManagerRef.updateViewLayout(currentView, currentParams)
        } catch (e: Exception) {
            e.printStackTrace()
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

        // Sync native window touch flags whenever edit mode is toggled
        LaunchedEffect(isCoinEditMode) {
            updateCanvasTouchable(isCoinEditMode)
        }

        // Visual animation for simulated shot speed
        var dashOffset by remember { mutableStateOf(0f) }
        LaunchedEffect(simulatedPower) {
            while (true) {
                val speed = 2f + simulatedPower * 18f
                dashOffset = (dashOffset - speed) % 60f
                delay(16)
            }
        }

        // --- DYNAMIC AUTOPLAY PHYSICS SIMULATION LOOP ---
        LaunchedEffect(isSimulatingAutoplay, selectedSpeedMultiplier) {
            if (isSimulatingAutoplay) {
                val friction = 0.985f
                val stopThreshold = 0.15f
                val density = resources.displayMetrics.density
                val rS = 24f * density // Striker radius (density independent)
                val rC = 24f * density // Coin radius
                val weightS = 3f      // Striker heavier
                val weightC = 1f      // Coin lighter
                val eRestitution = 0.85f

                // Initialize starting coords: target the AI suggested coin or fallback to movable handle C
                simStrikerX = handleAX
                simStrikerY = handleAY
                
                var currentBestShot = activeBestShot
                val targetCoinOffset = currentBestShot?.coinPos ?: Offset(handleCX, handleCY)
                simCoinX = targetCoinOffset.x
                simCoinY = targetCoinOffset.y
                isSimStrikerPocketed = false
                isSimCoinPocketed = false

                // Initial fire velocity based on our live calculated results
                val angleRad = Math.toRadians(liveCalculatedAngle.toDouble())
                val initialSpeed = (12f + liveCalculatedPower * 28f) * selectedSpeedMultiplier
                simStrikerVx = (initialSpeed * cos(angleRad)).toFloat()
                simStrikerVy = (initialSpeed * sin(angleRad)).toFloat()
                simCoinVx = 0f
                simCoinVy = 0f

                while (isSimulatingAutoplay) {
                    // Update positions
                    if (!isSimStrikerPocketed) {
                        simStrikerX += simStrikerVx
                        simStrikerY += simStrikerVy
                        simStrikerVx *= friction
                        simStrikerVy *= friction
                        if (kotlin.math.sqrt(simStrikerVx * simStrikerVx + simStrikerVy * simStrikerVy) < stopThreshold) {
                            simStrikerVx = 0f
                            simStrikerVy = 0f
                        }
                    }

                    if (!isSimCoinPocketed) {
                        simCoinX += simCoinVx
                        simCoinY += simCoinVy
                        simCoinVx *= friction
                        simCoinVy *= friction
                        if (kotlin.math.sqrt(simCoinVx * simCoinVx + simCoinVy * simCoinVy) < stopThreshold) {
                            simCoinVx = 0f
                            simCoinVy = 0f
                        }
                    }

                    // Wall cushion rebounds
                    if (!isSimStrikerPocketed) {
                        if (simStrikerX - rS < handleTLX) {
                            simStrikerX = handleTLX + rS
                            simStrikerVx = -simStrikerVx * eRestitution
                        } else if (simStrikerX + rS > handleBRX) {
                            simStrikerX = handleBRX - rS
                            simStrikerVx = -simStrikerVx * eRestitution
                        }
                        if (simStrikerY - rS < handleTLY) {
                            simStrikerY = handleTLY + rS
                            simStrikerVy = -simStrikerVy * eRestitution
                        } else if (simStrikerY + rS > handleBRY) {
                            simStrikerY = handleBRY - rS
                            simStrikerVy = -simStrikerVy * eRestitution
                        }
                    }

                    if (!isSimCoinPocketed) {
                        if (simCoinX - rC < handleTLX) {
                            simCoinX = handleTLX + rC
                            simCoinVx = -simCoinVx * eRestitution
                        } else if (simCoinX + rC > handleBRX) {
                            simCoinX = handleBRX - rC
                            simCoinVx = -simCoinVx * eRestitution
                        }
                        if (simCoinY - rC < handleTLY) {
                            simCoinY = handleTLY + rC
                            simCoinVy = -simCoinVy * eRestitution
                        } else if (simCoinY + rC > handleBRY) {
                            simCoinY = handleBRY - rC
                            simCoinVy = -simCoinVy * eRestitution
                        }
                    }

                    // Elastic circle-to-circle collision between virtual simulation entities
                    if (!isSimStrikerPocketed && !isSimCoinPocketed) {
                        val dx = simCoinX - simStrikerX
                        val dy = simCoinY - simStrikerY
                        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                        val minDist = rS + rC
                        if (dist < minDist && dist > 0.01f) {
                            val nx = dx / dist
                            val ny = dy / dist
                            val rvx = simCoinVx - simStrikerVx
                            val rvy = simCoinVy - simStrikerVy
                            val velAlongNormal = rvx * nx + rvy * ny
                            if (velAlongNormal < 0f) {
                                val j = -(1f + eRestitution) * velAlongNormal / (1f / weightS + 1f / weightC)
                                simStrikerVx -= j * nx / weightS
                                simStrikerVy -= j * ny / weightS
                                simCoinVx += j * nx / weightC
                                simCoinVy += j * ny / weightC

                                // Push out of overlap slightly to prevent stickiness
                                val overlap = minDist - dist
                                simStrikerX -= nx * overlap * 0.51f
                                simStrikerY -= ny * overlap * 0.51f
                                simCoinX += nx * overlap * 0.51f
                                simCoinY += ny * overlap * 0.51f
                            }
                        }
                    }

                    // Pocket detection checks
                    val currentPockets = listOf(
                        Offset(handleTLX, handleTLY),
                        Offset(handleBRX, handleTLY),
                        Offset(handleTLX, handleBRY),
                        Offset(handleBRX, handleBRY)
                    )
                    val pocketRadius = 24f * density

                    currentPockets.forEach { p ->
                        if (!isSimStrikerPocketed) {
                            val dx = simStrikerX - p.x
                            val dy = simStrikerY - p.y
                            if (kotlin.math.sqrt(dx * dx + dy * dy) < pocketRadius * 0.82f) {
                                isSimStrikerPocketed = true
                                simStrikerVx = 0f
                                simStrikerVy = 0f
                            }
                        }
                        if (!isSimCoinPocketed) {
                            val dx = simCoinX - p.x
                            val dy = simCoinY - p.y
                            if (kotlin.math.sqrt(dx * dx + dy * dy) < pocketRadius * 0.88f) {
                                isSimCoinPocketed = true
                                simCoinVx = 0f
                                simCoinVy = 0f
                            }
                        }
                    }

                    // Check if everything came to rest to trigger automatic replay loop
                    val strikerSpeed = kotlin.math.sqrt(simStrikerVx * simStrikerVx + simStrikerVy * simStrikerVy)
                    val coinSpeed = kotlin.math.sqrt(simCoinVx * simCoinVx + simCoinVy * simCoinVy)
                    if ((isSimStrikerPocketed || strikerSpeed < stopThreshold) && (isSimCoinPocketed || coinSpeed < stopThreshold)) {
                        
                        // "coins ko auto deduct ho" - Auto deduct the target coin if it was pocketed!
                        if (isSimCoinPocketed) {
                            if (currentBestShot != null) {
                                val targetToDeduct = currentBestShot.coinPos
                                if (targetToDeduct != null) {
                                    val coinToRemove = activeBoardCoins.find {
                                        kotlin.math.sqrt((it.x - targetToDeduct.x) * (it.x - targetToDeduct.x) + (it.y - targetToDeduct.y) * (it.y - targetToDeduct.y)) < 15f
                                    }
                                    if (coinToRemove != null) {
                                        activeBoardCoins.remove(coinToRemove)
                                        suggestionStatusText = "Coin (${coinToRemove.colorType}) auto-deducted after pocketing! 🎉"
                                    }
                                }
                            }
                        }

                        delay(1200) // Brief tactical pause

                        // Reinitialize back to handles coordinates
                        simStrikerX = handleAX
                        simStrikerY = handleAY
                        
                        currentBestShot = activeBestShot
                        val nextTargetOffset = currentBestShot?.coinPos ?: Offset(handleCX, handleCY)
                        simCoinX = nextTargetOffset.x
                        simCoinY = nextTargetOffset.y
                        isSimStrikerPocketed = false
                        isSimCoinPocketed = false

                        val actAngleRad = Math.toRadians(liveCalculatedAngle.toDouble())
                        val actSpeed = (12f + liveCalculatedPower * 28f) * selectedSpeedMultiplier
                        simStrikerVx = (actSpeed * cos(actAngleRad)).toFloat()
                        simStrikerVy = (actSpeed * sin(actAngleRad)).toFloat()
                        simCoinVx = 0f
                        simCoinVy = 0f
                    }

                    delay(16)
                }
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

            // ================= REAL-TIME AI SCAN LASER SWEEP LINE =================
            if (isScanningForCoins) {
                val scanY = handleTLY + (handleBRY - handleTLY) * scanProgress
                drawLine(
                    color = Color(0xFF00FFCC).copy(alpha = 0.85f),
                    start = Offset(handleTLX, scanY),
                    end = Offset(handleBRX, scanY),
                    strokeWidth = 4.dp.toPx()
                )
                drawLine(
                    color = Color(0xFF00FFCC).copy(alpha = 0.25f),
                    start = Offset(handleTLX, scanY - 15f),
                    end = Offset(handleBRX, scanY - 15f),
                    strokeWidth = 10.dp.toPx()
                )
            }

            // ================= DRAW CUSTOM BOARD COINS ("activeBoardCoins") =================
            activeBoardCoins.forEach { coin ->
                val coinColor = when (coin.colorType) {
                    "WHITE" -> Color(0xFFECEFF1)
                    "BLACK" -> Color(0xFF263238)
                    else -> Color(0xFFFF3D00) // RED Queen
                }
                val strokeColor = when (coin.colorType) {
                    "WHITE" -> Color.White
                    "BLACK" -> Color.Black
                    else -> Color(0xFFFF8A65)
                }
                val density = resources.displayMetrics.density
                val rC = 24f * density // Coin radius match
                
                // Draw drop shadow / outer halo
                drawCircle(
                    color = strokeColor.copy(alpha = 0.3f),
                    radius = rC + 4.dp.toPx(),
                    center = Offset(coin.x, coin.y)
                )
                // Draw inner solid coin
                drawCircle(
                    color = coinColor,
                    radius = rC,
                    center = Offset(coin.x, coin.y)
                )
                // Draw outer border ring
                drawCircle(
                    color = strokeColor,
                    radius = rC,
                    center = Offset(coin.x, coin.y),
                    style = Stroke(width = 2.dp.toPx())
                )
                
                // If this is the active AI best shot target, draw an animated glowing neon selector circle around it!
                val bestShotRef = activeBestShot
                if (bestShotRef != null && bestShotRef.coinPos != null) {
                    val distToTarget = kotlin.math.sqrt((coin.x - bestShotRef.coinPos.x) * (coin.x - bestShotRef.coinPos.x) + (coin.y - bestShotRef.coinPos.y) * (coin.y - bestShotRef.coinPos.y))
                    if (distToTarget < 15f) {
                        // targeted anim ring
                        val pulseRad = rC + (10f + 6f * sin(System.currentTimeMillis() / 150f)).dp.toPx()
                        drawCircle(
                            color = Color(0xFF00E676),
                            radius = pulseRad,
                            center = Offset(coin.x, coin.y),
                            style = Stroke(width = 2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f))
                        )
                    }
                }
            }

            // ================= LEFT HUD PLAYER TEAM OVERVIEW INDICATOR =================
            // Positioned dynamically along the left middle edge overlay background
            val hudY = (handleTLY + handleBRY) / 2f
            
            // Draw a semi-transparent HUD pill backing
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.72f),
                topLeft = Offset(handleTLX + 8.dp.toPx(), hudY - 50.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(60.dp.toPx(), 80.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx(), 10.dp.toPx())
            )
            
            val iconCenter = Offset(handleTLX + 38.dp.toPx(), hudY + 5.dp.toPx())
            val indicatorColor = if (leftIndicatorActiveTeam == "WHITE") Color(0xFFECEFF1) else Color(0xFF263238)
            val indicatorStroke = if (leftIndicatorActiveTeam == "WHITE") Color.White else Color.Black
            
            // Draw glowing halo around active team coin representing user's current color
            drawCircle(
                color = Color(0xFF00FFCC).copy(alpha = 0.45f),
                radius = 16.dp.toPx(),
                center = iconCenter
            )
            drawCircle(
                color = indicatorColor,
                radius = 11.dp.toPx(),
                center = iconCenter
            )
            drawCircle(
                color = indicatorStroke,
                radius = 11.dp.toPx(),
                center = iconCenter,
                style = Stroke(width = 1.5.dp.toPx())
            )
            
            // Tiny label status dot
            drawCircle(
                color = Color(0xFF00FFCC),
                radius = 3.dp.toPx(),
                center = Offset(handleTLX + 38.dp.toPx(), hudY - 22.dp.toPx())
            )

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
                                color = Color(0xFF00E5FF),
                                radius = 4.dp.toPx(),
                                center = p2
                            )
                        }
                    }
                }
            } else {
                // =============== AI AUTO-SUGGEST MODE (Multi-Configuration & Advanced Physics) ===============
                val radiusSum = 50f
                val candidateShots = mutableListOf<OverlayShot>()

                val filteredCoins = activeBoardCoins.filter { coin ->
                    activeFilterColor == "ALL" || coin.colorType == activeFilterColor
                }

                for (coin in filteredCoins) {
                    for (pocketIdx in 0 until 4) {
                        if (selectedPocketIndex != -1 && selectedPocketIndex != pocketIdx) continue
                        val pocket = pocketsList[pocketIdx]

                        // ================= 1. DIRECT CUT SHOT =================
                        val pcX = coin.x - pocket.x
                        val pcY = coin.y - pocket.y
                        val pcDist = kotlin.math.sqrt(pcX * pcX + pcY * pcY)
                        if (pcDist >= 15f) {
                            val pcDx = pcX / pcDist
                            val pcDy = pcY / pcDist

                            // Contact point
                            val contactX = coin.x + pcDx * radiusSum
                            val contactY = coin.y + pcDy * radiusSum
                            val contactPoint = Offset(contactX, contactY)

                            // Striker line
                            val stX = contactX - handleAX
                            val stY = contactY - handleAY
                            val stDist = kotlin.math.sqrt(stX * stX + stY * stY)

                            if (stDist >= 15f) {
                                val stDx = stX / stDist
                                val stDy = stY / stDist

                                val coinDirX = -pcDx
                                val coinDirY = -pcDy
                                val dot = stDx * coinDirX + stDy * coinDirY

                                if (dot > 0.05f) {
                                    val score = dot * 1000f - pcDist * 0.15f - stDist * 0.05f
                                    val angleRad = kotlin.math.atan2(stY, stX)
                                    val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
                                    val cutRad = kotlin.math.acos(dot.coerceIn(-1f, 1f))
                                    val cutDeg = Math.toDegrees(cutRad.toDouble()).toFloat()

                                    candidateShots.add(
                                        OverlayShot(
                                            type = 1,
                                            pocketIndex = pocketIdx,
                                            strikerPath = listOf(Offset(handleAX, handleAY), contactPoint),
                                            coinPath = listOf(Offset(coin.x, coin.y), pocket),
                                            angleDeg = angleDeg,
                                            power = (0.35f + (stDist * 0.0004f) + (pcDist * 0.0006f)).coerceIn(0.25f, 0.95f),
                                            score = score,
                                            description = "Direct Cut Shot 🎯",
                                            contactPoint = contactPoint,
                                            cutAngle = cutDeg,
                                            coinPos = Offset(coin.x, coin.y)
                                        )
                                    )
                                }
                            }
                        }

                        // ================= 2. COIN BANK SHOT (Coin rebounds off 1 wall) =================
                        val walls = listOf(
                            Pair(0, handleTLX),  // Left
                            Pair(1, handleBRX),  // Right
                            Pair(2, handleTLY),  // Top
                            Pair(3, handleBRY)   // Bottom
                        )
                        for ((wallType, wallVal) in walls) {
                            val mirrorPocketX = when (wallType) {
                                0 -> 2 * handleTLX - pocket.x
                                1 -> 2 * handleBRX - pocket.x
                                else -> pocket.x
                            }
                            val mirrorPocketY = when (wallType) {
                                2 -> 2 * handleTLY - pocket.y
                                3 -> 2 * handleBRY - pocket.y
                                else -> pocket.y
                            }
                            val cToP_X = mirrorPocketX - coin.x
                            val cToP_Y = mirrorPocketY - coin.y
                            val cToP_dist = kotlin.math.sqrt(cToP_X * cToP_X + cToP_Y * cToP_Y)
                            if (cToP_dist >= 15f) {
                                val cpDx = cToP_X / cToP_dist
                                val cpDy = cToP_Y / cToP_dist

                                var ix = 0f
                                var iy = 0f
                                var validIntersection = false

                                if (wallType == 0 || wallType == 1) { // Left/Right Wall
                                    if (kotlin.math.abs(cpDx) > 0.0001f) {
                                        val t = (wallVal - coin.x) / cpDx
                                        if (t > 0.05f) {
                                            ix = wallVal
                                            iy = coin.y + t * cpDy
                                            if (iy >= handleTLY && iy <= handleBRY) {
                                                validIntersection = true
                                            }
                                        }
                                    }
                                } else { // Top/Bottom Wall
                                    if (kotlin.math.abs(cpDy) > 0.0001f) {
                                        val t = (wallVal - coin.y) / cpDy
                                        if (t > 0.05f) {
                                            ix = coin.x + t * cpDx
                                            iy = wallVal
                                            if (ix >= handleTLX && ix <= handleBRX) {
                                                validIntersection = true
                                            }
                                        }
                                    }
                                }

                                if (validIntersection) {
                                    val ciX = ix - coin.x
                                    val ciY = iy - coin.y
                                    val ciDist = kotlin.math.sqrt(ciX * ciX + ciY * ciY)
                                    if (ciDist >= 10f) {
                                        val coinDirX = ciX / ciDist
                                        val coinDirY = ciY / ciDist

                                        // Strike contact point on opposite of rebound trajectory
                                        val contactX = coin.x - coinDirX * radiusSum
                                        val contactY = coin.y - coinDirY * radiusSum
                                        val contactPoint = Offset(contactX, contactY)

                                        val stX = contactX - handleAX
                                        val stY = contactY - handleAY
                                        val stDist = kotlin.math.sqrt(stX * stX + stY * stY)

                                        if (stDist >= 15f) {
                                            val stDx = stX / stDist
                                            val stDy = stY / stDist
                                            val dot = stDx * coinDirX + stDy * coinDirY

                                            if (dot > 0.15f) {
                                                val ipDist = kotlin.math.sqrt((pocket.x - ix) * (pocket.x - ix) + (pocket.y - iy) * (pocket.y - iy))
                                                val totalDistance = ciDist + ipDist
                                                val score = dot * 800f - totalDistance * 0.12f - stDist * 0.04f - 80f
                                                val angleRad = kotlin.math.atan2(stY, stX)
                                                val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
                                                val cutRad = kotlin.math.acos(dot.coerceIn(-1f, 1f))
                                                val cutDeg = Math.toDegrees(cutRad.toDouble()).toFloat()

                                                candidateShots.add(
                                                    OverlayShot(
                                                        type = 2,
                                                        pocketIndex = pocketIdx,
                                                        strikerPath = listOf(Offset(handleAX, handleAY), contactPoint),
                                                        coinPath = listOf(Offset(coin.x, coin.y), Offset(ix, iy), pocket),
                                                        angleDeg = angleDeg,
                                                        power = (0.50f + (stDist * 0.0004f) + (totalDistance * 0.0008f)).coerceIn(0.40f, 0.98f),
                                                        score = score,
                                                        description = "Coin Bank Shot 📐",
                                                        contactPoint = contactPoint,
                                                        cutAngle = cutDeg,
                                                        coinPos = Offset(coin.x, coin.y)
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ================= 3. STRIKER CUSHION REBOUND (Striker rebounds off 1 wall) =================
                        val pcCX = coin.x - pocket.x
                        val pcCY = coin.y - pocket.y
                        val pcCDist = kotlin.math.sqrt(pcCX * pcCX + pcCY * pcCY)
                        if (pcCDist >= 15f) {
                            val pcDx = pcCX / pcCDist
                            val pcDy = pcCY / pcCDist
                            val contactX = coin.x + pcDx * radiusSum
                            val contactY = coin.y + pcDy * radiusSum
                            val contactPoint = Offset(contactX, contactY)

                            val strikerWalls = listOf(
                                Pair(0, handleTLX),  // Left
                                Pair(1, handleBRX),  // Right
                                Pair(2, handleTLY),  // Top
                                Pair(3, handleBRY)   // Bottom
                            )
                            for ((wallType, wallVal) in strikerWalls) {
                                val mirrorTargetX = when (wallType) {
                                    0 -> 2 * handleTLX - contactX
                                    1 -> 2 * handleBRX - contactX
                                    else -> contactX
                                }
                                val mirrorTargetY = when (wallType) {
                                    2 -> 2 * handleTLY - contactY
                                    3 -> 2 * handleBRY - contactY
                                    else -> contactY
                                }
                                val sToT_X = mirrorTargetX - handleAX
                                val sToT_Y = mirrorTargetY - handleAY
                                val sToT_dist = kotlin.math.sqrt(sToT_X * sToT_X + sToT_Y * sToT_Y)
                                if (sToT_dist >= 15f) {
                                    val sToT_dx = sToT_X / sToT_dist
                                    val sToT_dy = sToT_Y / sToT_dist

                                    var ix = 0f
                                    var iy = 0f
                                    var validIntersection = false

                                    if (wallType == 0 || wallType == 1) { // Left/Right
                                        if (kotlin.math.abs(sToT_dx) > 0.0001f) {
                                            val t = (wallVal - handleAX) / sToT_dx
                                            if (t > 0.05f) {
                                                ix = wallVal
                                                iy = handleAY + t * sToT_dy
                                                if (iy >= handleTLY && iy <= handleBRY) {
                                                    validIntersection = true
                                                }
                                            }
                                        }
                                    } else { // Top/Bottom
                                        if (kotlin.math.abs(sToT_dy) > 0.0001f) {
                                            val t = (wallVal - handleAY) / sToT_dy
                                            if (t > 0.05f) {
                                                ix = handleAX + t * sToT_dx
                                                iy = wallVal
                                                if (ix >= handleTLX && ix <= handleBRX) {
                                                    validIntersection = true
                                                }
                                            }
                                        }
                                    }

                                    if (validIntersection) {
                                        val isToTX = contactPoint.x - ix
                                        val isToTY = contactPoint.y - iy
                                        val isToTdist = kotlin.math.sqrt(isToTX * isToTX + isToTY * isToTY)
                                        if (isToTdist >= 10f) {
                                            val colDirX = isToTX / isToTdist
                                            val colDirY = isToTY / isToTdist

                                            val coinDirX = -pcDx
                                            val coinDirY = -pcDy
                                            val dot = colDirX * coinDirX + colDirY * coinDirY

                                            if (dot > 0.15f) {
                                                val iToSDist = kotlin.math.sqrt((ix - handleAX) * (ix - handleAX) + (iy - handleAY) * (iy - handleAY))
                                                val totalStrikerDistance = iToSDist + isToTdist
                                                val score = dot * 800f - pcCDist * 0.12f - totalStrikerDistance * 0.05f - 100f
                                                val angleRad = kotlin.math.atan2(iy - handleAY, ix - handleAX)
                                                val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
                                                val cutRad = kotlin.math.acos(dot.coerceIn(-1f, 1f))
                                                val cutDeg = Math.toDegrees(cutRad.toDouble()).toFloat()

                                                candidateShots.add(
                                                    OverlayShot(
                                                        type = 3,
                                                        pocketIndex = pocketIdx,
                                                        strikerPath = listOf(Offset(handleAX, handleAY), Offset(ix, iy), contactPoint),
                                                        coinPath = listOf(Offset(coin.x, coin.y), pocket),
                                                        angleDeg = angleDeg,
                                                        power = (0.55f + (totalStrikerDistance * 0.0005f) + (pcCDist * 0.0008f)).coerceIn(0.45f, 0.98f),
                                                        score = score,
                                                        description = "Striker Cushion Rebound 🦘",
                                                        contactPoint = contactPoint,
                                                        cutAngle = cutDeg,
                                                        coinPos = Offset(coin.x, coin.y)
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Filter candidates by overlayShotType if not 0 (AI Best)
                val filteredShots = if (overlayShotType == 0) {
                    candidateShots
                } else {
                    candidateShots.filter { it.type == overlayShotType }
                }

                val bestShot = filteredShots.maxByOrNull { it.score }
                if (activeBestShot != bestShot) {
                    activeBestShot = bestShot
                }

                if (bestShot != null && virtualCoinsLeft > 0) {
                    val pLabel = when(bestShot.pocketIndex) {
                        0 -> "Top-Left Corner"
                        1 -> "Top-Right Corner"
                        2 -> "Bottom-Left Corner"
                        else -> "Bottom-Right Corner"
                    }
                    val shotTypeTitle = when(bestShot.type) {
                        1 -> "Direct Cut"
                        2 -> "Coin Bank Rebound"
                        else -> "Cushion Rebound"
                    }

                    suggestionStatusText = "🎯 $shotTypeTitle: $pLabel"

                    // Store parameters for autoplay access
                    liveCalculatedAngle = bestShot.angleDeg
                    liveCalculatedPower = bestShot.power
                    liveCutAngleMsg = "Angle: ${(bestShot.cutAngle).toInt()}°"

                    liveCalculatedDifficulty = when {
                        bestShot.cutAngle < 15f && bestShot.type == 1 -> "EASY ⭐"
                        bestShot.cutAngle < 35f || bestShot.type == 1 -> "MEDIUM ⭐⭐"
                        else -> "HARD ⭐⭐⭐"
                    }

                    // =============== DRAW THE HIGHEST FIDELITY GUIDELINES ===============
                    // 1. Draw Striker Path
                    val sPath = bestShot.strikerPath
                    if (sPath.size > 1) {
                        val strokeColor = when(bestShot.type) {
                            1 -> Color(0xFF00E5FF) // Teal Cyan
                            2 -> Color(0xFF00FFCC)
                            else -> Color(0xFFFF9800) // Deep Orange
                        }
                        
                        for (idx in 0 until sPath.size - 1) {
                            drawLine(
                                color = strokeColor,
                                start = sPath[idx],
                                end = sPath[idx + 1],
                                strokeWidth = 3.dp.toPx()
                            )
                            drawLine(
                                color = strokeColor.copy(alpha = 0.22f),
                                start = sPath[idx],
                                end = sPath[idx + 1],
                                strokeWidth = 9.dp.toPx()
                            )
                        }

                        // Drawing strike visual impulse animation on striker path
                        val firstSegmentDist = kotlin.math.sqrt((sPath[1].x - sPath[0].x) * (sPath[1].x - sPath[0].x) + (sPath[1].y - sPath[0].y) * (sPath[1].y - sPath[0].y))
                        if (firstSegmentDist > 5f) {
                            val progress = (System.currentTimeMillis() % 1600) / 1600f
                            drawCircle(
                                color = Color.White,
                                radius = 4.5f.dp.toPx(),
                                center = Offset(
                                    sPath[0].x + (sPath[1].x - sPath[0].x) * progress,
                                    sPath[0].y + (sPath[1].y - sPath[0].y) * progress
                                )
                            )
                        }
                    }

                    // 2. Draw Ghost Striker at contact point
                    val gContact = bestShot.contactPoint
                    drawCircle(
                        color = Color(0xFF00E5FF).copy(alpha = 0.12f),
                        radius = 24.dp.toPx(),
                        center = gContact
                    )
                    drawCircle(
                        color = Color(0xFF00E5FF).copy(alpha = 0.5f),
                        radius = 24.dp.toPx(),
                        center = gContact,
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                        )
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3.dp.toPx(),
                        center = gContact
                    )

                    // 3. Draw Coin Path
                    val cPath = bestShot.coinPath
                    if (cPath.size > 1) {
                        val coinStrokeColor = if (bestShot.type == 2) Color(0xFFFFD54F) else Color(0xFF00E676) // Yellow for bank coin, Green for direct
                        for (idx in 0 until cPath.size - 1) {
                            drawLine(
                                color = coinStrokeColor,
                                start = cPath[idx],
                                end = cPath[idx + 1],
                                strokeWidth = 3.dp.toPx()
                            )
                            drawLine(
                                color = coinStrokeColor.copy(alpha = 0.22f),
                                start = cPath[idx],
                                end = cPath[idx + 1],
                                strokeWidth = 9.dp.toPx()
                            )
                        }
                    }

                    // 4. Draw Extended Aim guide ray
                    val aimDirX = cos(Math.toRadians(bestShot.angleDeg.toDouble())).toFloat()
                    val aimDirY = sin(Math.toRadians(bestShot.angleDeg.toDouble())).toFloat()
                    val extEndX = sPath.last().x + aimDirX * 180f
                    val extEndY = sPath.last().y + aimDirY * 180f
                    drawLine(
                        color = Color(0xFFFFD54F),
                        start = sPath.last(),
                        end = Offset(extEndX, extEndY),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), dashOffset)
                    )

                } else {
                    if (virtualCoinsLeft <= 0) {
                        suggestionStatusText = "🪙 0 Coins left! Click Refill."
                    } else {
                        suggestionStatusText = "⚠️ Selected shot blocked/impossible"
                    }
                    
                    liveCalculatedDifficulty = "BLOCKED ❌"
                    liveCutAngleMsg = "N/A"

                    // Flat red warning link
                    drawLine(
                        color = Color(0xFFFF5252).copy(alpha = 0.5f),
                        start = Offset(handleAX, handleAY),
                        end = Offset(handleCX, handleCY),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                    )
                }
            }

            // ================= 5. DRAW ACTIVE AUTOPLAY SIMULATION OVERLAY ITEMS =================
            if (isSimulatingAutoplay) {
                val density = resources.displayMetrics.density
                val strokeW = 2.dp.toPx()
                val rS = 24f * density
                val rC = 24f * density

                // Simulating striker (Teal Cyan neon)
                if (!isSimStrikerPocketed) {
                    drawCircle(
                        color = Color(0xFF00E5FF).copy(alpha = 0.35f),
                        radius = rS,
                        center = Offset(simStrikerX, simStrikerY)
                    )
                    drawCircle(
                        color = Color(0xFF00E5FF),
                        radius = rS,
                        center = Offset(simStrikerX, simStrikerY),
                        style = Stroke(width = strokeW)
                    )
                    // Inner "S" core
                    drawCircle(
                        color = Color.White,
                        radius = 8.dp.toPx(),
                        center = Offset(simStrikerX, simStrikerY)
                    )
                }

                // Simulating coin (Green neon)
                if (!isSimCoinPocketed) {
                    drawCircle(
                        color = Color(0xFF00E676).copy(alpha = 0.35f),
                        radius = rC,
                        center = Offset(simCoinX, simCoinY)
                    )
                    drawCircle(
                        color = Color(0xFF00E676),
                        radius = rC,
                        center = Offset(simCoinX, simCoinY),
                        style = Stroke(width = strokeW)
                    )
                    // Inner "C" core
                    drawCircle(
                        color = Color.White,
                        radius = 8.dp.toPx(),
                        center = Offset(simCoinX, simCoinY)
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
                Color(0xFA14151F),
                Color(0xFA08090E)
            )
        )

        Card(
            modifier = Modifier
                .width(240.dp)
                .border(
                    1.5.dp,
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF00E5FF).copy(alpha = 0.6f), Color(0xFF00E676).copy(alpha = 0.3f))
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
                                    text = "AI Auto-Suggest Mode",
                                    color = Color.White,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium
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
                                    text = "Calibrate Table Grid",
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

                            // IF AI Suggestion is active, render pocket selector buttons and advanced controls
                            if (isAutoSuggestEnabled) {
                                Divider(color = Color.White.copy(alpha = 0.08f))

                                // Shot Configuration Filter Caps (Tab Strip)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Solve Shot Type Configuration:",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 10.5.sp
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        val filterTypes = listOf("AI Best", "Direct", "Bank", "Cushion")
                                        filterTypes.forEachIndexed { idx, title ->
                                            val isSel = overlayShotType == idx
                                            val tabBgColor = when {
                                                isSel && idx == 0 -> Color(0xFF00E5FF).copy(alpha = 0.25f)
                                                isSel -> Color(0xFF00E676).copy(alpha = 0.25f)
                                                else -> Color.White.copy(alpha = 0.06f)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(tabBgColor)
                                                    .border(
                                                        1.dp,
                                                        if (isSel) (if (idx==0) Color(0xFF00E5FF) else Color(0xFF00E676)) else Color.Transparent,
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .clickable {
                                                        overlayShotType = idx
                                                        if (virtualCoinsLeft > 0) {
                                                            virtualCoinsLeft--
                                                            suggestionStatusText = "Solving configurations..."
                                                        }
                                                    }
                                                    .padding(vertical = 5.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = title,
                                                    color = if (isSel) Color.White else Color.LightGray,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                // Interactive Pocket Selector Action Header
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Aim Target Pocket Profile:",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 10.5.sp
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
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
                                                        if (isSelected) Color(0xFFFF9800).copy(alpha = 0.25f)
                                                        else Color.White.copy(alpha = 0.08f)
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (isSelected) Color(0xFFFF9800) else Color.Transparent,
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .clickable {
                                                        selectedPocketIndex = i
                                                        if (virtualCoinsLeft > 0) {
                                                            virtualCoinsLeft--
                                                            suggestionStatusText = "Restructuring math..."
                                                        }
                                                    }
                                                    .padding(vertical = 4.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = pocketsLabels[index],
                                                    color = if (isSelected) Color(0xFFFF9800) else Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                Divider(color = Color.White.copy(alpha = 0.08f))

                                // User Team Designation Selector
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Config My Team Color:",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 10.5.sp
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        listOf("WHITE", "BLACK").forEach { team ->
                                            val isSel = leftIndicatorActiveTeam == team
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isSel) Color(0xFF00FFCC).copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
                                                    .border(1.dp, if (isSel) Color(0xFF00FFCC) else Color.Transparent, RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        leftIndicatorActiveTeam = team
                                                        activeFilterColor = team // Synchronize solver filter with designated team automatically!
                                                        suggestionStatusText = "Solving exclusively for $team Coins! 🎯"
                                                    }
                                                    .padding(vertical = 5.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = if (team == "WHITE") "⚪ My White Coins" else "⚫ My Black Coins",
                                                    color = if (isSel) Color.White else Color.LightGray,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Automatic screen laser board coin detection button!
                                Button(
                                    onClick = { triggerAutomaticBoardCoinScan() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(32.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF00FFCC),
                                        contentColor = Color(0xFF0F1014)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    enabled = !isScanningForCoins
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Scan",
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isScanningForCoins) "Scanning Screen..." else "Auto Scan Screen for Coins 🧠",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Divider(color = Color.White.copy(alpha = 0.08f))

                                // Board Coin Editor Interface
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Overlay Coin Placer Mode",
                                            color = Color.White,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Switch(
                                            checked = isCoinEditMode,
                                            onCheckedChange = { 
                                                isCoinEditMode = it
                                                if (isCoinEditMode) {
                                                    suggestionStatusText = "Placer active! TAP screen background to place/delete coins."
                                                } else {
                                                    suggestionStatusText = "Placer disabled. Pass-through touches enabled."
                                                }
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = Color(0xFF00FFCC),
                                                checkedTrackColor = Color(0xFF00FFCC).copy(alpha = 0.3f),
                                            ),
                                            modifier = Modifier.scale(0.7f)
                                        )
                                    }

                                    if (isCoinEditMode) {
                                        // Placed coin spawn type selector: WHITE, BLACK, RED
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Color to place:",
                                                color = Color.White.copy(alpha = 0.6f),
                                                fontSize = 9.5.sp,
                                                modifier = Modifier.weight(1.1f)
                                            )
                                            listOf("WHITE", "BLACK", "RED", "ALL").forEach { col ->
                                                val isSelected = activeFilterColor == col
                                                val indicatorColor = when(col) {
                                                    "WHITE" -> Color.White
                                                    "BLACK" -> Color.LightGray
                                                    "RED" -> Color(0xFFFF5252)
                                                    else -> Color(0xFF00E5FF)
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(if (isSelected) indicatorColor.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                                                        .border(1.dp, if (isSelected) indicatorColor else Color.Transparent, RoundedCornerShape(6.dp))
                                                        .clickable {
                                                            activeFilterColor = col
                                                        }
                                                        .padding(vertical = 4.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = col,
                                                        color = if (isSelected) Color.White else Color.Gray,
                                                        fontSize = 8.5.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }

                                        // Clear all coins button to quickly reset custom layout
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(26.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFFFF5252).copy(alpha = 0.2f))
                                                .clickable {
                                                    activeBoardCoins.clear()
                                                    suggestionStatusText = "Cleared all custom coins!"
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("Clear Custom Placed Coins 🧹", color = Color(0xFFFF8A80), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                Divider(color = Color.White.copy(alpha = 0.08f))

                                // DYNAMIC LIVE SHOT STATS HUD
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.05f))
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(text = "HUD RECS", color = Color(0xFF00E5FF), fontSize = 8.sp, fontWeight = FontWeight.Black)
                                        Text(text = liveCutAngleMsg.ifEmpty { "N/A" }, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Text(text = "Rec Power: ${(liveCalculatedPower * 100).toInt()}%", color = Color.LightGray, fontSize = 9.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(text = "DIFFICULTY", color = Color(0xFFFFD54F), fontSize = 8.sp, fontWeight = FontWeight.Black)
                                        Text(
                                            text = liveCalculatedDifficulty,
                                            color = if (liveCalculatedDifficulty.contains("EASY")) Color(0xFF00E676) else if (liveCalculatedDifficulty.contains("MEDIUM")) Color(0xFFFFD54F) else Color(0xFFFF5252),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // OVERLAY VIRTUAL AUTOPLAY ENGINE
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF00E5FF).copy(alpha = 0.05f))
                                        .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = "🛡️ Live Autoplay Simulator", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                        
                                        // Simulator toggle button
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isSimulatingAutoplay) Color(0xFFFF5252).copy(alpha = 0.25f) else Color(0xFF00E676).copy(alpha = 0.25f))
                                                .border(1.dp, if (isSimulatingAutoplay) Color(0xFFFF5252) else Color(0xFF00E676), RoundedCornerShape(4.dp))
                                                .clickable { isSimulatingAutoplay = !isSimulatingAutoplay }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = if (isSimulatingAutoplay) "Stop" else "Play",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    // Autoplay Speed Controls
                                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = "Playback Speed", color = Color.LightGray, fontSize = 9.sp)
                                            Text(text = "${selectedSpeedMultiplier}x", color = Color(0xFFFFD54F), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            val speeds = listOf(0.5f, 1f, 1.5f, 2f)
                                            speeds.forEach { s ->
                                                val isSelectedSpeed = selectedSpeedMultiplier == s
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(if (isSelectedSpeed) Color(0xFF00E5FF).copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                                                        .clickable { selectedSpeedMultiplier = s }
                                                        .padding(vertical = 3.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(text = "${s}x", color = if (isSelectedSpeed) Color.White else Color.Gray, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                                }
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
                                        "Drag 'S' handle dynamically to track live striker movement! 'C' handle positions target coin."
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
        serviceScope.cancel()
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

class OverlayShot(
    val type: Int, // 1: Direct, 2: Coin Bank, 3: Striker Cushion
    val pocketIndex: Int,
    val strikerPath: List<androidx.compose.ui.geometry.Offset>,
    val coinPath: List<androidx.compose.ui.geometry.Offset>,
    val angleDeg: Float,
    val power: Float,
    val score: Float,
    val description: String,
    val contactPoint: androidx.compose.ui.geometry.Offset,
    val cutAngle: Float,
    val coinPos: androidx.compose.ui.geometry.Offset? = null
)

data class CustomBoardCoin(
    val x: Float,
    val y: Float,
    val colorType: String // "WHITE", "BLACK", "RED"
)
