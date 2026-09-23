/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: colours and fonts for our screens.
 *
 * Two palettes, chosen in Settings → Appearance → Colours:
 *  - Flag colours: deep indigo, saffron highlights, red for weak scores
 *  - Black & white: monochrome
 * Each has a light and a dark variant, following the app's theme.
 */

package com.ichi2.anki.tibetan

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.ichi2.anki.R
import com.ichi2.anki.common.preferences.sharedPrefs

data class Palette(
    /** headings, bars, outlined buttons */
    val primary: Int,
    val onPrimary: Int,
    /** the main call to action (Study, Add) */
    val accent: Int,
    val onAccent: Int,
    val good: Int,
    val okay: Int,
    val bad: Int,
    /** unfilled bar track, empty chips */
    val track: Int,
    val onSurface: Int,
    val isMono: Boolean,
)

object TibetanTheme {
    const val PREF_COLOURS = "tibetanColours"
    const val COLOURS_FLAG = "flag"
    const val COLOURS_MONO = "mono"

    fun isMono(context: Context) = context.sharedPrefs().getString(PREF_COLOURS, COLOURS_FLAG) == COLOURS_MONO

    fun isDark(context: Context): Boolean {
        val bg = MaterialColors.getColor(context, android.R.attr.colorBackground, Color.WHITE)
        return ColorUtils.calculateLuminance(bg) < 0.5
    }

    fun palette(context: Context): Palette {
        val dark = isDark(context)
        val onSurface = if (dark) 0xFFECECEC.toInt() else 0xFF161616.toInt()
        val track = ColorUtils.setAlphaComponent(onSurface, 0x22)
        return if (isMono(context)) {
            Palette(
                primary = onSurface,
                onPrimary = if (dark) 0xFF111111.toInt() else Color.WHITE,
                accent = onSurface,
                onAccent = if (dark) 0xFF111111.toInt() else Color.WHITE,
                good = ColorUtils.setAlphaComponent(onSurface, 0x33),
                okay = ColorUtils.setAlphaComponent(onSurface, 0x80),
                bad = onSurface,
                track = track,
                onSurface = onSurface,
                isMono = true,
            )
        } else if (dark) {
            Palette(
                primary = 0xFFB3A6FF.toInt(),
                onPrimary = 0xFF1A0E45.toInt(),
                accent = 0xFFF5CF3A.toInt(),
                onAccent = 0xFF231A00.toInt(),
                good = 0xFF5CC98A.toInt(),
                okay = 0xFFF2B84B.toInt(),
                bad = 0xFFFF6B5E.toInt(),
                track = track,
                onSurface = onSurface,
                isMono = false,
            )
        } else {
            Palette(
                primary = 0xFF29166F.toInt(),
                onPrimary = Color.WHITE,
                accent = 0xFFF2C300.toInt(),
                onAccent = 0xFF1B1400.toInt(),
                good = 0xFF2E8B57.toInt(),
                okay = 0xFFE09A00.toInt(),
                bad = 0xFFDA251C.toInt(),
                track = track,
                onSurface = onSurface,
                isMono = false,
            )
        }
    }

    fun tibetanTypeface(context: Context): Typeface? = ResourcesCompat.getFont(context, R.font.noto_serif_tibetan)

    fun englishTypeface(context: Context): Typeface? = ResourcesCompat.getFont(context, R.font.inter)

    /** Colour for a % correct score. */
    fun scoreColor(
        palette: Palette,
        score: Int,
    ) = when {
        score >= 80 -> palette.good
        score >= 50 -> palette.okay
        else -> palette.bad
    }

    /** Turns a TextView into a small rounded score pill ("83%"), or a quiet "–". */
    fun styleScoreChip(
        view: TextView,
        score: Int?,
        palette: Palette,
    ) {
        val density = view.resources.displayMetrics.density
        if (score == null) {
            view.text = "–"
            view.background = null
            view.setTextColor(ColorUtils.setAlphaComponent(palette.onSurface, 0x88))
            return
        }
        val fill = scoreColor(palette, score)
        view.text = "$score%"
        view.background =
            GradientDrawable().apply {
                cornerRadius = 100 * density
                setColor(fill)
            }
        val light = ColorUtils.calculateLuminance(fill) > 0.45
        view.setTextColor(if (light) 0xFF161616.toInt() else Color.WHITE)
        view.setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
    }

    /** Filled call-to-action (e.g. Study) or outlined secondary button in the palette. */
    fun styleButton(
        button: MaterialButton,
        palette: Palette,
        filled: Boolean,
    ) {
        val density = button.resources.displayMetrics.density
        button.cornerRadius = (24 * density).toInt()
        if (filled) {
            button.backgroundTintList = ColorStateList.valueOf(palette.accent)
            button.setTextColor(palette.onAccent)
            button.strokeWidth = 0
        } else {
            button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            button.setTextColor(palette.primary)
            button.strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.primary, 0x66))
            button.strokeWidth = (1 * density).toInt().coerceAtLeast(1)
        }
    }
}
