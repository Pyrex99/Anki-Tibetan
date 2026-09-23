/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: which columns the word lists show. One global setting, changed
 * from any word list's ⋮ → Columns.
 */

package com.ichi2.anki.tibetan

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.R
import com.ichi2.anki.common.preferences.sharedPrefs
import java.text.DateFormat
import java.util.Date

enum class WordColumn(
    val label: String,
    val weight: Float,
    val defaultOn: Boolean,
) {
    TIBETAN("Tibetan", 3f, true),
    ENGLISH("English", 3f, true),
    TIBETAN_SCORE("Tib score", 1.5f, true),
    ENGLISH_SCORE("Eng score", 1.5f, true),
    ADDED("Added", 2f, false),
    TAGS("Tags", 2f, false),
    ;

    fun text(word: Word): String =
        when (this) {
            TIBETAN -> word.tibetan
            ENGLISH -> word.english
            TIBETAN_SCORE -> word.tibetanScore?.let { "$it%" } ?: "–"
            ENGLISH_SCORE -> word.englishScore?.let { "$it%" } ?: "–"
            ADDED -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(word.added))
            TAGS -> word.tags.joinToString(" ")
        }

    /** Sort key; never-reviewed scores sort before 0%. */
    fun sortKey(word: Word): Comparable<*> =
        when (this) {
            TIBETAN -> word.tibetan.lowercase()
            ENGLISH -> word.english.lowercase()
            TIBETAN_SCORE -> word.tibetanScore ?: -1
            ENGLISH_SCORE -> word.englishScore ?: -1
            ADDED -> word.added
            TAGS -> word.tags.joinToString(" ").lowercase()
        }

    /**
     * A table cell for [word] in this column: Tibetan in the Tibetan font, scores as
     * coloured pills, everything else in the English font.
     */
    fun cell(
        context: Context,
        word: Word,
        palette: Palette,
    ): View {
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        if (this == TIBETAN_SCORE || this == ENGLISH_SCORE) {
            val chip =
                TextView(context).apply {
                    typeface = TibetanTheme.englishTypeface(context)
                    textSize = 12f
                }
            TibetanTheme.styleScoreChip(chip, if (this == TIBETAN_SCORE) word.tibetanScore else word.englishScore, palette)
            return FrameLayout(context).apply {
                layoutParams = params
                addView(chip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        return TextView(context).apply {
            layoutParams = params
            text = text(word)
            setPadding(4, 0, 4, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(palette.onSurface)
            if (this@WordColumn == TIBETAN) {
                typeface = TibetanTheme.tibetanTypeface(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            } else {
                typeface = TibetanTheme.englishTypeface(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                if (this@WordColumn == ADDED || this@WordColumn == TAGS) alpha = 0.7f
            }
        }
    }

    /** Header cell ("Tibetan ↑"); tap to sort. */
    fun headerCell(
        context: Context,
        label: String,
        palette: Palette,
        onClick: () -> Unit,
    ): TextView =
        TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            text = label
            setPadding(4, 0, 4, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create(TibetanTheme.englishTypeface(context), Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.04f
            setTextColor(ColorUtils.setAlphaComponent(palette.onSurface, 0xAA))
            setOnClickListener { onClick() }
        }

    companion object {
        private const val PREF_KEY = "tibetanWordColumns"

        fun enabled(context: Context): List<WordColumn> {
            val saved = context.sharedPrefs().getStringSet(PREF_KEY, null)
            return entries.filter { if (saved == null) it.defaultOn else it.name in saved }
        }

        /** The ⋮ → Columns dialog; saves the choice and passes it to [onChosen]. */
        fun showChooser(
            context: Context,
            current: List<WordColumn>,
            onChosen: (List<WordColumn>) -> Unit,
        ) {
            val all = entries
            val checked = all.map { it in current }.toBooleanArray()
            MaterialAlertDialogBuilder(context)
                .setTitle("Columns")
                .setMultiChoiceItems(all.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }.setPositiveButton(R.string.dialog_ok) { _, _ ->
                    val chosen = all.filterIndexed { i, _ -> checked[i] }.ifEmpty { listOf(TIBETAN) }
                    setEnabled(context, chosen)
                    onChosen(chosen)
                }.setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        fun setEnabled(
            context: Context,
            columns: Collection<WordColumn>,
        ) {
            context.sharedPrefs().edit { putStringSet(PREF_KEY, columns.map { it.name }.toSet()) }
        }
    }
}
