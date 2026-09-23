/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: what the "+" / Add cards does.
 *  - Library / home: straight to the Add cards screen.
 *  - Inside a deck: "New cards" (Add cards screen) or "From another deck" (word picker).
 */

package com.ichi2.anki.tibetan

import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object AddCards {
    /** @param playlist deck to add to (as well as the Library); null = Library only */
    fun show(
        activity: FragmentActivity,
        playlist: String?,
    ) {
        if (playlist == null) {
            activity.startActivity(AddWordsActivity.getIntent(activity, null))
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Add cards to ${playlist.substringAfterLast("::")}")
            .setItems(arrayOf("New cards", "From Library / another deck")) { _, which ->
                when (which) {
                    0 -> activity.startActivity(AddWordsActivity.getIntent(activity, playlist))
                    1 -> activity.startActivity(WordPickerActivity.getIntent(activity, playlist, isNew = false))
                }
            }.show()
    }
}
