/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: the "Study direction" chooser (Tibetan first / English first / Mix),
 * shown from the home menu, a deck's word list, and the study screen.
 * The setting is global.
 */

package com.ichi2.anki.tibetan

import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.withProgress

object StudyDirectionPicker {
    fun show(
        activity: FragmentActivity,
        onChanged: (StudyDirection) -> Unit,
    ) {
        activity.launchCatchingTask {
            val directions = StudyDirection.entries
            val current = withCol { StudyDirection.get(this) }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.study_direction)
                .setSingleChoiceItems(directions.map { it.label }.toTypedArray(), directions.indexOf(current)) { dialog, which ->
                    dialog.dismiss()
                    val chosen = directions[which]
                    if (chosen == current) return@setSingleChoiceItems
                    activity.launchCatchingTask {
                        activity.withProgress { withCol { StudyDirection.set(this, chosen) } }
                        onChanged(chosen)
                    }
                }.setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }
}
