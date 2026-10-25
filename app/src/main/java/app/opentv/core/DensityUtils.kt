/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Forces the UI density on car head units.
 *
 * Automotive OEM images commonly leave the system density at 160 (mdpi) on a panel that is really
 * ~320 dpi, so every dp renders at half its intended size and the whole app looks tiny. Changing
 * the system density (`wm density`) would need adb and would affect every other app on the unit,
 * so the override is applied to this app's own resources instead.
 *
 * Wrapped at attachBaseContext time, like [LocaleUtils], so Compose, dialogs and popups all
 * resolve dp against the same density. A Compose-only `LocalDensity` override would not reach
 * `Dialog`/`Popup` windows, which read the density from their own context.
 *
 * Only `densityDpi` is overridden; `screenWidthDp`/`screenHeightDp` keep the system's values.
 * Nothing in the app lays out by those, so recomputing them is not worth the risk of freezing a
 * stale value into the override across a rotation.
 */
object DensityUtils {

    // 240, not the panel's true ~320: at 320 a 1440 px-wide unit is only 720 dp across, and the live
    // guide's header (fixed-width preview card beside the open category rail) leaves its info column
    // ~70 dp, so the text wraps one letter per line. 240 gives 960 dp and the header fits.
    const val AUTOMOTIVE_DPI = 240

    fun wrap(base: Context): Context {
        if (!isAutomotive(base)) return base
        val current = base.resources.configuration
        if (current.densityDpi == AUTOMOTIVE_DPI) return base
        val config = Configuration(current)
        config.densityDpi = AUTOMOTIVE_DPI
        return base.createConfigurationContext(config)
    }

    private fun isAutomotive(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_CAR) return true
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    }
}
