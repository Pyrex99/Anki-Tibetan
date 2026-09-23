/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: dialogs for creating / renaming / deleting playlist decks,
 * shared by the home screen and the word list.
 */

package com.ichi2.anki.tibetan

import android.widget.EditText
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.withProgress

object PlaylistActions {
    private fun promptName(
        activity: FragmentActivity,
        title: String,
        initial: String,
        onName: (String) -> Unit,
    ) {
        val input =
            EditText(activity).apply {
                setText(initial)
                setSelection(initial.length)
                isSingleLine = true
                hint = "Name"
            }
        val container =
            FrameLayout(activity).apply {
                val pad = (20 * activity.resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = Library.cleanName(input.text.toString())
                if (name.isNotEmpty()) onName(name)
            }.setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Asks for a name, then opens the word picker. [parent] = null for a top-level deck.
     * Decks go at most two levels deep: a "subdeck of a subdeck" is created under the main deck.
     */
    fun newDeck(
        activity: FragmentActivity,
        requestedParent: String?,
    ) {
        val parent = requestedParent?.substringBefore("::")
        val title = if (parent == null) "New deck" else "New subdeck of ${Library.displayName(parent)}"
        promptName(activity, title, "") { name ->
            val full = if (parent == null) name else "$parent::$name"
            activity.launchCatchingTask {
                if (withCol { Library.exists(this, full) }) {
                    showThemedToast(activity, "A deck called “$name” already exists", true)
                } else {
                    activity.startActivity(WordPickerActivity.getIntent(activity, full, isNew = true))
                }
            }
        }
    }

    fun rename(
        activity: FragmentActivity,
        playlist: String,
        onDone: (String) -> Unit,
    ) {
        promptName(activity, "Rename deck", playlist.substringAfterLast("::")) { name ->
            val parent = Library.parentOf(playlist)
            val full = if (parent == null) name else "$parent::$name"
            if (full == playlist) return@promptName
            activity.launchCatchingTask {
                if (withCol { Library.exists(this, full) }) {
                    showThemedToast(activity, "A deck called “$name” already exists", true)
                    return@launchCatchingTask
                }
                activity.withProgress { withCol { Library.rename(this, playlist, full) } }
                onDone(full)
            }
        }
    }

    fun delete(
        activity: FragmentActivity,
        playlist: String,
        onDone: () -> Unit,
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Delete “${playlist.substringAfterLast("::")}”?")
            .setMessage("The deck and its subdecks are removed. The words stay in your Library with their scores.")
            .setPositiveButton("Delete") { _, _ ->
                activity.launchCatchingTask {
                    activity.withProgress { withCol { Library.deletePlaylist(this, playlist) } }
                    onDone()
                }
            }.setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** Long-press menu for a playlist on the home screen. */
    fun showMenu(
        activity: FragmentActivity,
        playlist: String,
        onChanged: () -> Unit,
    ) {
        val options = listOf("Add cards", "Add subdeck", "Rename", "Delete")
        MaterialAlertDialogBuilder(activity)
            .setTitle(Library.displayName(playlist))
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> AddCards.show(activity, playlist)
                    1 -> newDeck(activity, playlist)
                    2 -> rename(activity, playlist) { onChanged() }
                    3 -> delete(activity, playlist, onChanged)
                }
            }.show()
    }
}
