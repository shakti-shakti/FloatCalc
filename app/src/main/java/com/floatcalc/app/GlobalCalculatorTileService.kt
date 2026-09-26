package com.floatcalc.app

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            setTileState(false)
            Toast.makeText(
                this,
                "Allow Display over other apps, then tap the tile again",
                Toast.LENGTH_LONG
            ).show()
            @Suppress("DEPRECATION")
            startActivityAndCollapse(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
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
            .getBoolean(FloatCalcPreferences.PREF_FLOATING_CALCULATOR, false)
        setTileState(enabled)
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
    }
}