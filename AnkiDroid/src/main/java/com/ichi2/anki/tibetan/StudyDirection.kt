/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: study direction (Tibetan first / English first / Mix).
 *
 * You add a word once. Behind the scenes the "Basic" note type has two card
 * templates, so each word keeps two independent schedules:
 *   ord 0: Tibetan → English    ord 1: English → Tibetan
 * The chosen direction is applied by suspending the other direction's cards.
 * We remember which cards *we* suspended, so cards you suspend yourself are
 * never touched.
 */

package com.ichi2.anki.tibetan

import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.NotetypeJson
import com.ichi2.anki.model.CardsOrNotes
import timber.log.Timber

enum class StudyDirection(
    val label: String,
    /** card ords that are studied in this mode */
    val activeOrds: Set<Int>,
) {
    TIBETAN_FIRST("Tibetan first", setOf(ORD_TIBETAN_FIRST)),
    ENGLISH_FIRST("English first", setOf(ORD_ENGLISH_FIRST)),
    MIX("Mix", setOf(ORD_TIBETAN_FIRST, ORD_ENGLISH_FIRST)),
    ;

    companion object {
        private const val CONFIG_KEY = "tibetanStudyDirection"
        private const val CONFIG_KEY_SUSPENDED = "tibetanDirectionSuspendedCards"
        private const val CONFIG_KEY_SETUP_DONE = "tibetanDirectionSetupDone"
        private const val NOTETYPE_NAME = "Basic"
        private const val REVERSE_TEMPLATE_NAME = "English first"

        /** anki.deck_config.DeckConfig.Config.NewCardSortOrder.NEW_CARD_SORT_ORDER_RANDOM_CARD */
        private const val NEW_SORT_ORDER_RANDOM_CARD = 4

        fun get(col: Collection): StudyDirection =
            col.config.get<String>(CONFIG_KEY)?.let { name -> entries.firstOrNull { it.name == name } }
                ?: TIBETAN_FIRST

        fun set(
            col: Collection,
            direction: StudyDirection,
        ) {
            col.config.set(CONFIG_KEY, direction.name)
            sync(col)
            // mid-session: refill the playlist session with the new direction's cards
            val session = col.decks.idForName(Library.STUDY_SESSION_NAME)
            if (session != null && col.decks.selected() == session) col.sched.rebuildFilteredDeck(session)
        }

        /**
         * Idempotent; cheap when nothing changed. Called whenever the deck list refreshes so
         * newly added words (from any screen) pick up the current direction.
         */
        fun sync(col: Collection) {
            val nt = col.notetypes.byName(NOTETYPE_NAME) ?: return
            ensureReverseTemplate(col, nt)
            applyDirection(col, nt.id, get(col))
        }

        /** Adds the English → Tibetan template once; this creates a card for every existing word. */
        private fun ensureReverseTemplate(
            col: Collection,
            nt: NotetypeJson,
        ) {
            if (nt.templates.length() > 1) return
            Timber.i("Adding English-first template to Basic")
            val template = col.notetypes.newTemplate(REVERSE_TEMPLATE_NAME)
            template.qfmt = "{{Back}}"
            template.afmt = "{{FrontSide}}\n\n<hr id=answer>\n\n{{Front}}"
            col.notetypes.addTemplate(nt, template)

            if (col.config.get<Boolean>(CONFIG_KEY_SETUP_DONE) != true) {
                // Mix new cards randomly, and don't show both sides of a word on the same day
                for (conf in col.decks.allConfig()) {
                    conf.jsonObject.put("newSortOrder", NEW_SORT_ORDER_RANDOM_CARD)
                    conf.new.bury = true
                    conf.rev.jsonObject.put("bury", true)
                    col.decks.save(conf)
                }
                // one row per word in the card browser
                CardsOrNotes.NOTES.saveToCollection(col)
                col.config.set(CONFIG_KEY_SETUP_DONE, true)
            }
        }

        private fun applyDirection(
            col: Collection,
            notetypeId: Long,
            direction: StudyDirection,
        ) {
            val ourSuspended = (col.config.get<List<Long>>(CONFIG_KEY_SUSPENDED) ?: emptyList()).toMutableSet()

            // un-hide cards of a now-active direction
            val activeOrds = direction.activeOrds.joinToString(",")
            val toRestore =
                if (ourSuspended.isEmpty()) {
                    emptyList()
                } else {
                    col.db
                        .queryLongList(
                            "select c.id from cards c join notes n on c.nid = n.id " +
                                "where n.mid = ? and c.ord in ($activeOrds) and c.queue = -1",
                            notetypeId,
                        ).filter { it in ourSuspended }
                }

            // hide un-suspended cards of an inactive direction (includes newly added words)
            val toHide: List<CardId> =
                col.db.queryLongList(
                    "select c.id from cards c join notes n on c.nid = n.id " +
                        "where n.mid = ? and c.ord not in ($activeOrds) and c.queue != -1",
                    notetypeId,
                )

            if (toRestore.isEmpty() && toHide.isEmpty()) return
            Timber.i("Study direction %s: showing %d, hiding %d cards", direction, toRestore.size, toHide.size)
            if (toRestore.isNotEmpty()) col.sched.unsuspendCards(toRestore)
            if (toHide.isNotEmpty()) col.sched.suspendCards(toHide)

            ourSuspended.removeAll(toRestore.toSet())
            ourSuspended.addAll(toHide)
            // forget cards that were deleted or that the user unsuspended manually
            val stillSuspended =
                col.db
                    .queryLongList(
                        "select c.id from cards c join notes n on c.nid = n.id where n.mid = ? and c.queue = -1",
                        notetypeId,
                    ).toSet()
            col.config.set(CONFIG_KEY_SUSPENDED, ourSuspended.filter { it in stillSuspended })
        }
    }
}

const val ORD_TIBETAN_FIRST = 0
const val ORD_ENGLISH_FIRST = 1
