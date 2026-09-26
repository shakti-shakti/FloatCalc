package com.floatcalc.app

/**
 * Preferences used by the standalone overlay. The names match the calculator
 * preferences in PW so the copied calculator behavior remains unchanged while
 * FloatCalc stores its state in its own package sandbox.
 */
object FloatCalcPreferences {
    const val PREFS_NAME = "pw_settings"
    const val PREF_FLOATING_CALCULATOR = "floating_calculator_enabled"
    const val PREF_FLOATING_CALCULATOR_SCALE = "floating_calculator_scale"
}