/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: the "Add cards" chooser, from the home screen's + button
 * (adds to the Library) or a deck's Add cards button (Library + that deck).
 */

package com.ichi2.anki.tibetan

import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.tibetan.PhotoVocabImportActivity.Mode

object AddCards {
    /** @param playlist deck to add to (as well as the Library); null = Library only */
    fun show(
        activity: FragmentActivity,
        playlist: String?,
    ) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (playlist != null) {
            options += "From Library / other decks" to {
                activity.startActivity(WordPickerActivity.getIntent(activity, playlist, isNew = false))
            }
        }
        options += "Type them in" to { open(activity, Mode.MANUAL, playlist) }
        options += "Type English — Claude fills in Tibetan" to { open(activity, Mode.MANUAL, playlist) }
        options += "Ask Claude (e.g. “ten new verbs”)" to { open(activity, Mode.ASK, playlist) }
        options += "From a photo" to { open(activity, Mode.PHOTO, playlist) }

        MaterialAlertDialogBuilder(activity)
            .setTitle(if (playlist == null) "Add cards" else "Add cards to ${playlist.substringAfterLast("::")}")
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun open(
        activity: FragmentActivity,
        mode: Mode,
        playlist: String?,
    ) {
        activity.startActivity(PhotoVocabImportActivity.getIntent(activity, mode, playlist))
    }
}
