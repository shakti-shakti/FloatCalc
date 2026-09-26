package com.floatcalc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.roundToInt

/**
 * Hosts the standalone calculator launcher and panel outside an application screen.
 *
 * The service owns only the system-window plumbing. FloatingCalculatorView
 * remains the single calculator UI and engine entry point for both scopes.
 */
class CalculatorOverlayService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    private var iconRoot: FrameLayout? = null
    private var iconView: ImageView? = null
    private var iconParams: WindowManager.LayoutParams? = null
    private var iconAttached = false

    private var calculatorWindowRoot: FrameLayout? = null
    private var calculatorView: FloatingCalculatorView? = null
    private var calculatorParams: WindowManager.LayoutParams? = null
    private var calculatorBaseWidth = 0
    private var calculatorBaseHeight = 0
    private var calculatorMaximized = false
    private var restoreWidth = 0
    private var restoreHeight = 0
    private var restoreX = 0
    private var restoreY = 0
    private var restoreScale = 1f

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> ensureCalculatorOverlay()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        closeCalculator()
        removeIconWindow()
        mainHandler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun ensureCalculatorOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
        showToast("Allow Display over other apps for FloatCalc")
            stopSelf()
            return
        }
        if (iconRoot != null) return

        ensureIconWindow()
        val root = iconRoot ?: return
        try {
            windowManager.addView(root, iconParams)
            iconAttached = true
        } catch (_: Exception) {
            showToast("Could not show the global calculator")
            stopSelf()
        }
    }

    private fun ensureIconWindow() {
        if (iconRoot != null) return

        val size = dp(82f)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.calculator_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = null
            contentDescription = "Open floating calculator"
            setPadding(0, 0, 0, 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(icon)

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        icon.setOnTouchListener { view, event ->
            val params = iconParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && dx * dx + dy * dy > dp(6f).toFloat().let { it * it }) {
                        moved = true
                    }
                    if (moved) {
                        val maxX = resources.displayMetrics.widthPixels - params.width
                        val maxY = resources.displayMetrics.heightPixels - params.height
                        params.x = (startX + dx.roundToInt()).coerceIn(0, maxX.coerceAtLeast(0))
                        params.y = (startY + dy.roundToInt()).coerceIn(0, maxY.coerceAtLeast(0))
                        saveIconPosition(params.x, params.y)
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        view.performClick()
                        openCalculator()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        iconRoot = root
        iconView = icon
        iconParams = WindowManager.LayoutParams(
            size,
            size,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val preferences = getSharedPreferences(
                FloatCalcPreferences.PREFS_NAME,
                MODE_PRIVATE
            )
            x = preferences.getInt(
                FloatCalcPreferences.PREF_FLOATING_ICON_X,
                dp(26f)
            ).coerceIn(0, (resources.displayMetrics.widthPixels - size).coerceAtLeast(0))
            y = preferences.getInt(
                FloatCalcPreferences.PREF_FLOATING_ICON_Y,
                dp(180f)
            ).coerceIn(0, (resources.displayMetrics.heightPixels - size).coerceAtLeast(0))
        }
    }

    private fun openCalculator() {
        if (calculatorView != null) return

        val screenWidth = resources.displayMetrics.widthPixels
        val dp = resources.displayMetrics.density
        fun px(value: Int) = (value * dp + 0.5f).toInt()
        val width = (screenWidth - px(24)).coerceAtLeast(px(320)).coerceAtMost(px(560))
        val preferences = getSharedPreferences(FloatCalcPreferences.PREFS_NAME, MODE_PRIVATE)
        val view = FloatingCalculatorView(
            context = this,
            onClose = { closeCalculator() },
            onMinimize = { closeCalculator() },
            onMaximize = { toggleCalculatorMaximize() },
            onDrag = { dx, dy -> moveCalculator(dx, dy) },
            onResize = { dx, dy -> resizeCalculator(dx, dy) }
        )
        val windowRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            addView(
                view,
                FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT)
            )
        }
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt(
                FloatCalcPreferences.PREF_FLOATING_CALCULATOR_X,
                ((screenWidth - width) / 2).coerceAtLeast(0)
            ).coerceIn(0, (screenWidth - width).coerceAtLeast(0))
            y = preferences.getInt(
                FloatCalcPreferences.PREF_FLOATING_CALCULATOR_Y,
                px(26)
            ).coerceAtLeast(0)
        }

        try {
            windowManager.addView(windowRoot, params)
            calculatorWindowRoot = windowRoot
            calculatorView = view
            calculatorParams = params
            calculatorMaximized = false
            view.pivotX = 0f
            view.pivotY = 0f
            windowRoot.post { initializeCalculatorBounds() }
        } catch (_: Exception) {
            showToast("Could not open the global calculator")
        }
    }

    /**
     * A transformed child does not change an Android system window's touch
     * region. Keep the child at its natural size inside a wrapper and resize
     * the wrapper itself to the child's scaled bounds.
     */
    private fun initializeCalculatorBounds() {
        val root = calculatorWindowRoot ?: return
        val view = calculatorView ?: return
        val params = calculatorParams ?: return
        if (view.width <= 0 || view.height <= 0) {
            root.post { initializeCalculatorBounds() }
            return
        }
        calculatorBaseWidth = view.width
        calculatorBaseHeight = view.height
        val savedScale = loadCalculatorScale()
        view.layoutParams = FrameLayout.LayoutParams(
            calculatorBaseWidth,
            calculatorBaseHeight
        )
        view.scaleX = savedScale
        view.scaleY = savedScale
        view.pivotX = 0f
        view.pivotY = 0f
        restoreScale = savedScale
        params.width = (calculatorBaseWidth * savedScale).roundToInt().coerceAtLeast(1)
        params.height = (calculatorBaseHeight * savedScale).roundToInt().coerceAtLeast(1)
        params.x = params.x.coerceIn(
            0,
            (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0)
        )
        params.y = params.y.coerceIn(
            0,
            (resources.displayMetrics.heightPixels - params.height).coerceAtLeast(0)
        )
        saveCalculatorPosition(params.x, params.y)
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {
        }
    }

    private fun moveCalculator(dx: Float, dy: Float) {
        val root = calculatorWindowRoot ?: return
        val params = calculatorParams ?: return
        val maxX = (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0)
        val maxY = (resources.displayMetrics.heightPixels - params.height).coerceAtLeast(0)
        params.x = (params.x + dx).roundToInt().coerceIn(0, maxX)
        params.y = (params.y + dy).roundToInt().coerceIn(0, maxY)
        saveCalculatorPosition(params.x, params.y)
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {
        }
    }

    private fun resizeCalculator(dx: Float, dy: Float) {
        val root = calculatorWindowRoot ?: return
        val view = calculatorView ?: return
        val params = calculatorParams ?: return
        if (calculatorBaseWidth <= 0 || calculatorBaseHeight <= 0) return
        view.pivotX = 0f
        view.pivotY = 0f
        val delta = ((dx + dy) / 2f) / calculatorBaseWidth.toFloat()
        val scale = (view.scaleX + delta).coerceAtLeast(0.05f)
        view.scaleX = scale
        view.scaleY = scale
        params.width = (calculatorBaseWidth * scale).roundToInt().coerceAtLeast(1)
        params.height = (calculatorBaseHeight * scale).roundToInt().coerceAtLeast(1)
        saveCalculatorScale(scale)
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {
        }
    }

    private fun loadCalculatorScale(): Float {
        val saved = getSharedPreferences(FloatCalcPreferences.PREFS_NAME, MODE_PRIVATE)
            .getFloat(FloatCalcPreferences.PREF_FLOATING_CALCULATOR_SCALE, 1f)
        return if (saved.isFinite() && saved > 0f) saved else 1f
    }

    private fun saveCalculatorScale(scale: Float) {
        getSharedPreferences(FloatCalcPreferences.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putFloat(FloatCalcPreferences.PREF_FLOATING_CALCULATOR_SCALE, scale)
            .apply()
    }

    private fun saveIconPosition(x: Int, y: Int) {
        getSharedPreferences(FloatCalcPreferences.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(FloatCalcPreferences.PREF_FLOATING_ICON_X, x)
            .putInt(FloatCalcPreferences.PREF_FLOATING_ICON_Y, y)
            .apply()
    }

    private fun saveCalculatorPosition(x: Int, y: Int) {
        getSharedPreferences(FloatCalcPreferences.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(FloatCalcPreferences.PREF_FLOATING_CALCULATOR_X, x)
            .putInt(FloatCalcPreferences.PREF_FLOATING_CALCULATOR_Y, y)
            .apply()
    }

    private fun toggleCalculatorMaximize() {
        val root = calculatorWindowRoot ?: return
        val view = calculatorView ?: return
        val params = calculatorParams ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val dp = resources.displayMetrics.density
        fun px(value: Int) = (value * dp + 0.5f).toInt()

        if (!calculatorMaximized) {
            restoreWidth = params.width
            restoreHeight = params.height
            restoreX = params.x
            restoreY = params.y
            restoreScale = view.scaleX
            params.width = (screenWidth - px(16)).coerceAtLeast(px(320))
            params.height = calculatorBaseHeight.coerceAtLeast(px(320))
            params.x = px(8)
            params.y = px(26)
            view.scaleX = 1f
            view.scaleY = 1f
            try {
                windowManager.updateViewLayout(root, params)
            } catch (_: Exception) {
            }
            calculatorMaximized = true
        } else {
            params.width = restoreWidth
            params.height = restoreHeight
            params.x = restoreX
            params.y = restoreY
            view.scaleX = restoreScale
            view.scaleY = restoreScale
            try {
                windowManager.updateViewLayout(root, params)
            } catch (_: Exception) {
            }
            calculatorMaximized = false
        }
    }

    private fun closeCalculator() {
        calculatorWindowRoot?.let { root ->
            try {
                windowManager.removeView(root)
            } catch (_: Exception) {
            }
        }
        calculatorWindowRoot = null
        calculatorView = null
        calculatorParams = null
        calculatorBaseWidth = 0
        calculatorBaseHeight = 0
        calculatorMaximized = false
        iconRoot?.bringToFront()
    }

    private fun removeIconWindow() {
        iconRoot?.let { root ->
            if (iconAttached) {
                try {
                    windowManager.removeView(root)
                } catch (_: Exception) {
                }
                iconAttached = false
            }
        }
        iconView = null
        iconRoot = null
        iconParams = null
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Global calculator",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Controls the FloatCalc calculator over other apps"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.calculator_launcher)
            .setContentTitle("FloatCalc calculator is active")
            .setContentText("The calculator icon is available over other apps")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_START = "com.floatcalc.app.action.CALCULATOR_OVERLAY_START"
        const val ACTION_STOP = "com.floatcalc.app.action.CALCULATOR_OVERLAY_STOP"
        private const val CHANNEL_ID = "floatcalc_global_calculator"
        private const val NOTIFICATION_ID = 9202
    }
}