/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: a thin rounded progress bar (e.g. a deck's average score).
 */

package com.ichi2.anki.tibetan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class ScoreBarView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        /** 0–100, or null for "no data" (track only) */
        var percent: Int? = null
            set(value) {
                field = value
                invalidate()
            }
        var fillColor: Int = 0
            set(value) {
                field = value
                invalidate()
            }
        var trackColor: Int = 0x22000000
            set(value) {
                field = value
                invalidate()
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val radius = height / 2f
            paint.color = trackColor
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)
            val p = percent ?: return
            if (p <= 0) return
            paint.color = fillColor
            rect.set(0f, 0f, (width * p.coerceAtMost(100) / 100f).coerceAtLeast(height.toFloat()), height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
