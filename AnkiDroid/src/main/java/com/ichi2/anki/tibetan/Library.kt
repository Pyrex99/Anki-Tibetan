/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: Library + playlist decks.
 *
 *  - "Library" is the one real Anki deck; every word lives there.
 *    Studying it is the daily "Roundup" (normal Anki scheduling).
 *  - A "deck" the user creates is a *playlist*: a set of words picked from the
 *    Library (or from its parent playlist, for a subdeck). A word can be in any
 *    number of playlists. Stored as note tags "Playlist::Verbs::Irregular", so a
 *    subdeck's words are automatically part of its parent.
 *  - Studying a playlist builds a temporary filtered deck ("Study session") of
 *    its weakest / least-recently-seen words. Answers update the word's scores.
 *
 * All functions must be called inside `withCol { }`.
 */

package com.ichi2.anki.tibetan

import anki.decks.Deck
import anki.decks.DeckKt.FilteredKt.searchTerm
import anki.decks.DeckKt.filtered
import com.ichi2.anki.backend.stripHTML
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.Consts
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.NoteId
import com.ichi2.anki.libanki.Utils
import timber.log.Timber

/** One word (note) with the data shown in word lists. */
data class Word(
    val noteId: NoteId,
    val firstCardId: CardId,
    val tibetan: String,
    val english: String,
    /** % of reviews not answered "Again", per direction; null = never reviewed */
    val tibetanScore: Int?,
    val englishScore: Int?,
    /** epoch millis */
    val added: Long,
    /** tags other than playlist tags */
    val tags: List<String>,
    /** full playlist tags, e.g. "Playlist::Verbs::Irregular" */
    val playlistTags: List<String>,
) {
    fun isIn(playlist: String): Boolean {
        val tag = Library.tagFor(playlist)
        return playlistTags.any { it.equals(tag, ignoreCase = true) || it.startsWith("$tag::", ignoreCase = true) }
    }
}

/** A playlist row for the home screen. */
data class PlaylistInfo(
    /** full name, e.g. "Verbs::Irregular"; "" for the Library */
    val name: String,
    val wordCount: Int,
    /** average % correct per direction; null = nothing reviewed yet */
    val tibetanScore: Int? = null,
    val englishScore: Int? = null,
) {
    val depth get() = name.count { it == ':' } / 2
    val shortName get() = name.substringAfterLast("::")
}

object Library {
    const val LIBRARY_DECK_ID: DeckId = Consts.DEFAULT_DECK_ID
    const val LIBRARY_NAME = "Library"
    const val STUDY_SESSION_NAME = "Study session"

    /** Words per playlist study session. */
    const val SESSION_SIZE = 20

    private const val TAG_ROOT = "Playlist"
    private const val CONFIG_PLAYLISTS = "tibetanPlaylists"
    private const val CONFIG_MIGRATED = "tibetanLibraryMigrated"

    fun tagFor(playlist: String): String = "$TAG_ROOT::" + playlist.split("::").joinToString("::") { it.trim().replace(Regex("\\s+"), "_") }

    /** Cleans a user-typed name segment: no "::" (that's the nesting separator). */
    fun cleanName(input: String): String = input.trim().replace("::", ":").replace(Regex("\\s+"), " ")

    fun displayName(playlist: String): String = playlist.replace("::", " › ")

    fun parentOf(playlist: String): String? = playlist.substringBeforeLast("::", "").ifEmpty { null }

    /*
     * One-time conversion
     */

    /**
     * Default → Library; temporary (filtered) decks emptied and removed; any other deck
     * becomes a playlist with the same name, its words moved into the Library.
     */
    fun migrateIfNeeded(col: Collection) {
        if (col.config.get<Boolean>(CONFIG_MIGRATED) == true) return
        Timber.i("Migrating decks to Library + playlists")

        val decks = col.decks.allNamesAndIds(includeFiltered = true)
        for (deck in decks) {
            if (deck.id != LIBRARY_DECK_ID && col.decks.isFiltered(deck.id)) {
                col.sched.emptyFilteredDeck(deck.id)
            }
        }
        val toRemove = mutableListOf<DeckId>()
        for (deck in col.decks.allNamesAndIds(includeFiltered = true)) {
            if (deck.id == LIBRARY_DECK_ID) continue
            if (!col.decks.isFiltered(deck.id)) {
                val playlist = deck.name.split("::").joinToString("::") { cleanName(it) }
                addPlaylistName(col, playlist)
                val noteIds = col.db.queryLongList("select distinct nid from cards where did = ?", deck.id)
                if (noteIds.isNotEmpty()) col.tags.bulkAdd(noteIds, tagFor(playlist))
                val cardIds = col.db.queryLongList("select id from cards where did = ?", deck.id)
                if (cardIds.isNotEmpty()) col.setDeck(cardIds, LIBRARY_DECK_ID)
            }
            toRemove.add(deck.id)
        }
        if (toRemove.isNotEmpty()) col.decks.remove(toRemove)
        col.decks.rename(LIBRARY_DECK_ID, LIBRARY_NAME)
        col.decks.select(LIBRARY_DECK_ID)
        col.config.set(CONFIG_MIGRATED, true)
    }

    /*
     * Words
     */

    /** Every word in the collection (the Library), with scores. */
    fun allWords(col: Collection): List<Word> {
        // nid -> ord -> (total, correct)
        val scores = mutableMapOf<Long, MutableMap<Int, Pair<Int, Int>>>()
        col.db
            .query(
                "select c.nid, c.ord, count(), coalesce(sum(r.ease > 1), 0) from revlog r " +
                    "join cards c on r.cid = c.id where r.ease > 0 group by c.nid, c.ord",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    scores.getOrPut(cursor.getLong(0)) { mutableMapOf() }[cursor.getInt(1)] =
                        cursor.getInt(2) to cursor.getInt(3)
                }
            }

        fun score(
            nid: Long,
            ord: Int,
        ): Int? {
            val (total, correct) = scores[nid]?.get(ord) ?: return null
            return if (total == 0) null else correct * 100 / total
        }

        val words = mutableListOf<Word>()
        col.db
            .query(
                "select n.id, n.flds, n.tags, (select min(c.id) from cards c where c.nid = n.id) from notes n",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val nid = cursor.getLong(0)
                    val fields = Utils.splitFields(cursor.getString(1)).map { stripHTML(it).trim() }
                    val allTags =
                        cursor
                            .getString(2)
                            .trim()
                            .split(" ")
                            .filter { it.isNotEmpty() }
                    val (playlistTags, tags) = allTags.partition { it.startsWith("$TAG_ROOT::", ignoreCase = true) }
                    words.add(
                        Word(
                            noteId = nid,
                            firstCardId = cursor.getLong(3),
                            tibetan = fields.getOrElse(0) { "" },
                            english = fields.getOrElse(1) { "" },
                            tibetanScore = score(nid, ORD_TIBETAN_FIRST),
                            englishScore = score(nid, ORD_ENGLISH_FIRST),
                            added = nid,
                            tags = tags,
                            playlistTags = playlistTags,
                        ),
                    )
                }
            }
        return words
    }

    /** Words in [playlist], or the whole Library if null. */
    fun words(
        col: Collection,
        playlist: String?,
    ): List<Word> = allWords(col).let { all -> if (playlist == null) all else all.filter { it.isIn(playlist) } }

    fun deleteWords(
        col: Collection,
        noteIds: List<NoteId>,
    ) {
        col.removeNotes(noteIds = noteIds)
    }

    /*
     * Playlists
     */

    fun playlistNames(col: Collection): List<String> {
        val names = (col.config.get<List<String>>(CONFIG_PLAYLISTS) ?: emptyList()).toMutableSet()
        // make sure parents exist for every child
        for (name in names.toList()) {
            var parent = parentOf(name)
            while (parent != null) {
                names.add(parent)
                parent = parentOf(parent)
            }
        }
        return names.sortedWith(
            Comparator { a, b ->
                val pa = a.lowercase().split("::")
                val pb = b.lowercase().split("::")
                for (i in 0 until minOf(pa.size, pb.size)) {
                    val c = pa[i].compareTo(pb[i])
                    if (c != 0) return@Comparator c
                }
                pa.size - pb.size
            },
        )
    }

    fun playlists(col: Collection): List<PlaylistInfo> {
        val words = allWords(col)
        return playlistNames(col).map { name -> summarize(name, words.filter { it.isIn(name) }) }
    }

    /** The Library as a whole, for the home screen. */
    fun librarySummary(col: Collection): PlaylistInfo = summarize("", allWords(col))

    private fun summarize(
        name: String,
        members: List<Word>,
    ) = PlaylistInfo(
        name = name,
        wordCount = members.size,
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

    private fun addPlaylistName(
        col: Collection,
        name: String,
    ) {
        val names = (col.config.get<List<String>>(CONFIG_PLAYLISTS) ?: emptyList()).toMutableList()
        if (names.none { it.equals(name, ignoreCase = true) }) names.add(name)
        col.config.set(CONFIG_PLAYLISTS, names)
    }

    fun exists(
        col: Collection,
        name: String,
    ): Boolean = playlistNames(col).any { it.equals(name, ignoreCase = true) }

    /** Creates (or updates) [playlist] so that it contains exactly [noteIds]. */
    fun savePlaylist(
        col: Collection,
        playlist: String,
        noteIds: Set<NoteId>,
    ) {
        addPlaylistName(col, playlist)
        val current = words(col, playlist).map { it.noteId }.toSet()
        val toAdd = noteIds - current
        val toRemove = current - noteIds
        if (toAdd.isNotEmpty()) col.tags.bulkAdd(toAdd.toList(), tagFor(playlist))
        // removing a word from a deck also removes it from that deck's subdecks
        if (toRemove.isNotEmpty()) col.tags.bulkRemove(toRemove.toList(), tagsIncludingChildren(col, playlist))
    }

    fun addWords(
        col: Collection,
        playlist: String,
        noteIds: List<NoteId>,
    ) {
        if (noteIds.isNotEmpty()) col.tags.bulkAdd(noteIds, tagFor(playlist))
    }

    fun removeWords(
        col: Collection,
        playlist: String,
        noteIds: List<NoteId>,
    ) {
        if (noteIds.isNotEmpty()) col.tags.bulkRemove(noteIds, tagsIncludingChildren(col, playlist))
    }

    private fun tagsIncludingChildren(
        col: Collection,
        playlist: String,
    ): String =
        playlistNames(col)
            .filter { it.equals(playlist, true) || it.startsWith("$playlist::", true) }
            .plus(playlist)
            .distinct()
            .joinToString(" ") { tagFor(it) }

    /** Renames [old] to [newName] (a full name), keeping its subdecks under it. */
    fun rename(
        col: Collection,
        old: String,
        newName: String,
    ) {
        col.tags.rename(tagFor(old), tagFor(newName))
        val names =
            (col.config.get<List<String>>(CONFIG_PLAYLISTS) ?: emptyList()).map { name ->
                when {
                    name.equals(old, true) -> newName
                    name.startsWith("$old::", true) -> newName + name.substring(old.length)
                    else -> name
                }
            }
        col.config.set(CONFIG_PLAYLISTS, names.distinct())
    }

    /** Deletes [playlist] and its subdecks. Words stay in the Library. */
    fun deletePlaylist(
        col: Collection,
        playlist: String,
    ) {
        val members = words(col, playlist).map { it.noteId }
        if (members.isNotEmpty()) col.tags.bulkRemove(members, tagsIncludingChildren(col, playlist))
        val names =
            (col.config.get<List<String>>(CONFIG_PLAYLISTS) ?: emptyList()).filterNot {
                it.equals(playlist, true) || it.startsWith("$playlist::", true)
            }
        col.config.set(CONFIG_PLAYLISTS, names)
    }

    /*
     * Studying
     */

    /**
     * Prepares a study session and selects its deck; returns the deck to study.
     * Library → the Library deck itself (Roundup). Playlist → a filtered deck of its
     * [SESSION_SIZE] weakest / least-recently-seen cards; answers count normally.
     */
    fun prepareStudy(
        col: Collection,
        playlist: String?,
    ): DeckId? {
        endStudySession(col)
        if (playlist == null) {
            col.decks.select(LIBRARY_DECK_ID)
            return LIBRARY_DECK_ID
        }
        val noteIds = words(col, playlist).map { it.noteId }
        if (noteIds.isEmpty()) return null
        return startSession(col, "nid:" + noteIds.joinToString(","))
    }

    /**
     * Drills specific words (e.g. the weakest ones from Statistics).
     * @param ord only this direction's cards, or null for both
     */
    fun prepareDrill(
        col: Collection,
        noteIds: List<Long>,
        ord: Int?,
    ): DeckId? {
        endStudySession(col)
        if (noteIds.isEmpty()) return null
        val direction = ord?.let { " card:${it + 1}" }.orEmpty()
        return startSession(col, "nid:" + noteIds.joinToString(",") + direction)
    }

    private fun startSession(
        col: Collection,
        cardSearch: String,
    ): DeckId {
        val existing = col.decks.idForName(STUDY_SESSION_NAME) ?: 0L
        val base = col.sched.getOrCreateFilteredDeck(existing)
        val update =
            base
                .toBuilder()
                .setName(STUDY_SESSION_NAME)
                .setAllowEmpty(true)
                .setConfig(
                    filtered {
                        reschedule = true
                        searchTerms.add(
                            searchTerm {
                                search = cardSearch
                                limit = SESSION_SIZE
                                order = Deck.Filtered.SearchTerm.Order.RETRIEVABILITY_ASCENDING
                            },
                        )
                    },
                ).build()
        val did = col.sched.addOrUpdateFilteredDeck(update).id
        col.decks.select(did)
        return did
    }

    /** Returns any cards in the temporary study session to the Library. */
    fun endStudySession(col: Collection) {
        val did = col.decks.idForName(STUDY_SESSION_NAME) ?: return
        col.sched.emptyFilteredDeck(did)
        if (col.decks.selected() == did) col.decks.select(LIBRARY_DECK_ID)
    }
}
