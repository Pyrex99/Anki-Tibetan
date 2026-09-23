/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: numbers for the Statistics screen. Call inside `withCol { }`.
 *
 * "Solid" = Anki's "mature": the word's interval in that direction is 21+ days.
 * "Score" = % of reviews not answered "Again" (same as the word lists).
 */

package com.ichi2.anki.tibetan

import com.ichi2.anki.libanki.Collection

data class DirectionProgress(
    val ord: Int,
    val solid: Int,
    val learning: Int,
    val new: Int,
    val solidGainedLast30Days: Int,
) {
    val total get() = solid + learning + new
}

data class WeakWord(
    val word: Word,
    val ord: Int,
    val score: Int,
    val reviews: Int,
)

data class DeckMastery(
    /** null = Library */
    val deck: String?,
    val words: Int,
    val tibetanScore: Int?,
    val englishScore: Int?,
)

data class StudyActivity(
    val streakDays: Int,
    /** reviews per day, oldest first, last entry = today */
    val last30Days: List<Int>,
    /** reviews due per day, first entry = today (includes overdue) */
    val next7Days: List<Int>,
)

data class TibetanStatsData(
    val progress: List<DirectionProgress>,
    val weakest: Map<Int, List<WeakWord>>,
    val mastery: List<DeckMastery>,
    val activity: StudyActivity,
)

object TibetanStats {
    private const val SOLID_DAYS = 21
    private const val DAY_SECS = 86_400L
    private const val WEAKEST_COUNT = 8
    private const val WEAKEST_MIN_REVIEWS = 2

    fun load(col: Collection): TibetanStatsData {
        val words = Library.allWords(col)
        return TibetanStatsData(
            progress = progress(col),
            weakest = weakest(col, words),
            mastery = mastery(col, words),
            activity = activity(col),
        )
    }

    private fun progress(col: Collection): List<DirectionProgress> {
        val mid = col.notetypes.byName("Basic")?.id ?: return emptyList()
        val cutoffMs = (col.sched.dayCutoff - 30 * DAY_SECS) * 1000
        val solidThen = mutableMapOf<Int, Int>()
        col.db
            .query(
                "select c.ord, count() from cards c " +
                    "join (select cid, max(id) mid from revlog where id < ? and ease > 0 group by cid) last on last.cid = c.id " +
                    "join revlog r on r.id = last.mid " +
                    "join notes n on n.id = c.nid where n.mid = ? and r.ivl >= ? group by c.ord",
                cutoffMs,
                mid,
                SOLID_DAYS,
            ).use { while (it.moveToNext()) solidThen[it.getInt(0)] = it.getInt(1) }

        val result = mutableListOf<DirectionProgress>()
        col.db
            .query(
                "select c.ord, sum(c.type = 2 and c.ivl >= ?), sum(c.type != 0 and not (c.type = 2 and c.ivl >= ?)), " +
                    "sum(c.type = 0) from cards c join notes n on n.id = c.nid where n.mid = ? group by c.ord order by c.ord",
                SOLID_DAYS,
                SOLID_DAYS,
                mid,
            ).use {
                while (it.moveToNext()) {
                    val ord = it.getInt(0)
                    val solid = it.getInt(1)
                    result.add(
                        DirectionProgress(
                            ord = ord,
                            solid = solid,
                            learning = it.getInt(2),
                            new = it.getInt(3),
                            solidGainedLast30Days = solid - (solidThen[ord] ?: 0),
                        ),
                    )
                }
            }
        return result
    }

    private fun weakest(
        col: Collection,
        words: List<Word>,
    ): Map<Int, List<WeakWord>> {
        val byNote = words.associateBy { it.noteId }
        val out = mutableMapOf<Int, MutableList<WeakWord>>()
        col.db
            .query(
                "select c.nid, c.ord, count(), coalesce(sum(r.ease > 1), 0) from revlog r " +
                    "join cards c on r.cid = c.id where r.ease > 0 group by c.nid, c.ord having count() >= ?",
                WEAKEST_MIN_REVIEWS,
            ).use {
                while (it.moveToNext()) {
                    val word = byNote[it.getLong(0)] ?: continue
                    val total = it.getInt(2)
                    out
                        .getOrPut(it.getInt(1)) { mutableListOf() }
                        .add(WeakWord(word, it.getInt(1), it.getInt(3) * 100 / total, total))
                }
            }
        return out.mapValues { (_, list) ->
            list.sortedWith(compareBy<WeakWord> { it.score }.thenByDescending { it.reviews }).take(WEAKEST_COUNT)
        }
    }

    private fun mastery(
        col: Collection,
        words: List<Word>,
    ): List<DeckMastery> {
        fun summarize(
            deck: String?,
            members: List<Word>,
        ) = DeckMastery(
            deck = deck,
            words = members.size,
            tibetanScore =
                members
                    .mapNotNull { it.tibetanScore }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toInt(),
            englishScore =
                members
                    .mapNotNull { it.englishScore }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toInt(),
        )
        return listOf(summarize(null, words)) +
            Library.playlistNames(col).map { deck -> summarize(deck, words.filter { it.isIn(deck) }) }
    }

    private fun activity(col: Collection): StudyActivity {
        val cutoff = col.sched.dayCutoff
        // days ago (0 = today) -> reviews
        val perDay = mutableMapOf<Int, Int>()
        col.db
            .query(
                "select (? - id / 1000 - 1) / ? as d, count() from revlog where ease > 0 and id >= ? group by d",
                cutoff,
                DAY_SECS,
                (cutoff - 400 * DAY_SECS) * 1000,
            ).use { while (it.moveToNext()) perDay[it.getInt(0)] = it.getInt(1) }

        // a streak isn't broken until today ends
        var day = if ((perDay[0] ?: 0) > 0) 0 else 1
        var streak = 0
        while ((perDay[day] ?: 0) > 0) {
            streak++
            day++
        }

        val today = col.sched.today
        val upcoming = IntArray(7)
        col.db
            .query(
                "select case when queue = 1 then 0 else max(due - ?, 0) end d, count() from cards " +
                    "where queue in (1, 2, 3) and (queue = 1 or due <= ?) group by d",
                today,
                today + 6,
            ).use {
                while (it.moveToNext()) {
                    val d = it.getInt(0)
                    if (d in 0..6) upcoming[d] += it.getInt(1)
                }
            }

        return StudyActivity(
            streakDays = streak,
            last30Days = (29 downTo 0).map { perDay[it] ?: 0 },
            next7Days = upcoming.toList(),
        )
    }
}
