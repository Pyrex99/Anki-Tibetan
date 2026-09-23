/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: friendlier card browser columns.
 *  - Sort field / Question  -> "Front" (the note's first field, whichever direction the card is)
 *  - Answer                 -> "Back"  (the note's second field)
 *  - Created                -> date only
 *  - Ease                   -> "% correct" per direction, e.g. "Tib 83% · Eng 40%"
 *                              (share of reviews not answered "Again"; Tib = Tibetan shown first)
 */

package com.ichi2.anki.tibetan

import anki.search.BrowserRow
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.backend.stripHTML
import com.ichi2.anki.browser.CardBrowserColumn
import com.ichi2.anki.libanki.Utils
import com.ichi2.anki.model.CardsOrNotes
import timber.log.Timber

object TibetanBrowserColumns {
    /** Replacement header label for [ankiColumnKey], or [default] if unchanged. */
    fun label(
        ankiColumnKey: String,
        default: String,
    ): String =
        when (ankiColumnKey) {
            CardBrowserColumn.SFLD.ankiColumnKey,
            CardBrowserColumn.QUESTION.ankiColumnKey,
            -> "Front"
            CardBrowserColumn.ANSWER.ankiColumnKey -> "Back"
            CardBrowserColumn.CREATED.ankiColumnKey -> "Created"
            CardBrowserColumn.EASE.ankiColumnKey -> "% correct (Tib first · Eng first)"
            else -> default
        }

    /**
     * Rewrites the cells of [row] for the columns we customise.
     * @param id a card id in [CardsOrNotes.CARDS] mode, a note id in [CardsOrNotes.NOTES] mode
     */
    fun transform(
        row: BrowserRow,
        columns: List<CardBrowserColumn>,
        id: Long,
        cardsOrNotes: CardsOrNotes,
    ): BrowserRow {
        if (columns.none { it in CUSTOMISED }) return row
        return try {
            val builder = row.toBuilder()
            val fields by lazy { noteFields(id, cardsOrNotes) }
            columns.forEachIndexed { i, column ->
                if (i >= row.cellsCount) return@forEachIndexed
                val newText =
                    when (column) {
                        CardBrowserColumn.SFLD, CardBrowserColumn.QUESTION -> fields.getOrNull(0)
                        CardBrowserColumn.ANSWER -> fields.getOrNull(1)
                        CardBrowserColumn.CREATED -> row.getCells(i).text.substringBefore(' ')
                        CardBrowserColumn.EASE -> percentCorrect(id, cardsOrNotes)
                        else -> null
                    } ?: return@forEachIndexed
                builder.setCells(i, row.getCells(i).toBuilder().setText(newText))
            }
            builder.build()
        } catch (e: Exception) {
            Timber.w(e, "Could not customise browser row")
            row
        }
    }

    private val CUSTOMISED =
        setOf(
            CardBrowserColumn.SFLD,
            CardBrowserColumn.QUESTION,
            CardBrowserColumn.ANSWER,
            CardBrowserColumn.CREATED,
            CardBrowserColumn.EASE,
        )

    private fun noteFields(
        id: Long,
        cardsOrNotes: CardsOrNotes,
    ): List<String> {
        val db = CollectionManager.getColUnsafe().db
        val flds =
            when (cardsOrNotes) {
                CardsOrNotes.CARDS ->
                    db.queryString("select n.flds from cards c join notes n on c.nid = n.id where c.id = ?", id)
                CardsOrNotes.NOTES -> db.queryString("select flds from notes where id = ?", id)
            }
        return Utils.splitFields(flds).map { stripHTML(it).trim() }
    }

    /** e.g. "Tib 83% · Eng 40%"; "–" for a direction never reviewed. Ease 1 = "Again" (wrong). */
    private fun percentCorrect(
        id: Long,
        cardsOrNotes: CardsOrNotes,
    ): String {
        val cardFilter =
            when (cardsOrNotes) {
                CardsOrNotes.CARDS -> "c.id = ?"
                CardsOrNotes.NOTES -> "c.nid = ?"
            }
        val byOrd = mutableMapOf<Int, Pair<Int, Int>>()
        CollectionManager
            .getColUnsafe()
            .db
            .query(
                "select c.ord, count(), coalesce(sum(r.ease > 1), 0) from revlog r " +
                    "join cards c on r.cid = c.id where r.ease > 0 and $cardFilter group by c.ord",
                id,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    byOrd[cursor.getInt(0)] = cursor.getInt(1) to cursor.getInt(2)
                }
            }

        fun format(ord: Int): String {
            val (total, correct) = byOrd[ord] ?: return "–"
            return if (total == 0) "–" else "${correct * 100 / total}%"
        }
        return when (cardsOrNotes) {
            CardsOrNotes.NOTES -> "Tib ${format(ORD_TIBETAN_FIRST)} · Eng ${format(ORD_ENGLISH_FIRST)}"
            CardsOrNotes.CARDS -> {
                val ord = byOrd.keys.firstOrNull() ?: return "–"
                (if (ord == ORD_TIBETAN_FIRST) "Tib " else "Eng ") + format(ord)
            }
        }
    }
}
