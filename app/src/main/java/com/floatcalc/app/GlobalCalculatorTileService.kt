package com.floatcalc.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Quick Settings tile for the system-wide calculator launcher.
 */
class GlobalCalculatorTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences(FloatCalcPreferences.PREFS_NAME, MODE_PRIVATE)

        if (!hasOverlayPermission()) {
            // A tile can be tapped while its saved state still says enabled, for
            // example after the user revoked the permission in Android Settings.
            // Always route that case to Settings instead of trying to start the
            // overlay service or toggling into an invalid state.
            prefs.edit()
                .putBoolean(FloatCalcPreferences.PREF_FLOATING_CALCULATOR, false)
                .apply()
            stopService(
                Intent(this, CalculatorOverlayService::class.java).apply {
                    action = CalculatorOverlayService.ACTION_STOP
                }
            )
            setTileState(false)
            openOverlayPermissionSettings()
            return
        }

        val currentlyEnabled =
            prefs.getBoolean(FloatCalcPreferences.PREF_FLOATING_CALCULATOR, false)

        if (currentlyEnabled) {
            prefs.edit()
                .putBoolean(FloatCalcPreferences.PREF_FLOATING_CALCULATOR, false)
                .apply()
            stopService(
                Intent(this, CalculatorOverlayService::class.java).apply {
                    action = CalculatorOverlayService.ACTION_STOP
                }
            )
            setTileState(false)
            Toast.makeText(this, "Global calculator disabled", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit()
            .putBoolean(FloatCalcPreferences.PREF_FLOATING_CALCULATOR, true)
            .apply()
        ContextCompat.startForegroundService(
            this,
            Intent(this, CalculatorOverlayService::class.java).apply {
                action = CalculatorOverlayService.ACTION_START
            }
        )
        setTileState(true)
        Toast.makeText(this, "Global calculator enabled", Toast.LENGTH_SHORT).show()
    }

    private fun updateTileState() {
        val enabled = getSharedPreferences(FloatCalcPreferences.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(FloatCalcPreferences.PREF_FLOATING_CALCULATOR, false) &&
            hasOverlayPermission()
        setTileState(enabled)
    }

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    /**
     * Android 14 deprecated the Intent overload for this API and may reject it
     * from the Quick Settings shade. Use a PendingIntent on Android 14+ and
     * retain the older overload for earlier releases.
     */
    private fun openOverlayPermissionSettings() {
        val appSettingsIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        val generalSettingsIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)

        try {
            launchSettingsIntent(appSettingsIntent)
        } catch (_: Exception) {
            // Some Android builds reject the app-specific Settings route from a
            // tile even though the action itself is supported.
            launchSettingsIntentSafely(generalSettingsIntent)
        }
    }

    private fun launchSettingsIntent(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                OVERLAY_PERMISSION_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun launchSettingsIntentSafely(intent: Intent) {
        try {
            launchSettingsIntent(intent)
        } catch (_: Exception) {
            // The final fallback still opens the system Settings screen without
            // relying on Quick Settings' collapse support.
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Open Settings > Apps > Special app access > Display over other apps",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setTileState(enabled: Boolean) {
        qsTile?.apply {
            label = getString(R.string.quick_tile_floating_calculator)
            icon = android.graphics.drawable.Icon.createWithResource(
                this@GlobalCalculatorTileService,
                R.drawable.ic_quick_calculator
            )
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    companion object {
        fun requestRefresh(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, GlobalCalculatorTileService::class.java)
                )
            }
        }

        private const val OVERLAY_PERMISSION_REQUEST_CODE = 9203
    }
}