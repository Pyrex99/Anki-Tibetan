/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: choose which words are in a playlist deck. Words can come from the
 * Library or any other deck ("From: …"); a new subdeck starts out showing its
 * parent's words. Adding a word to a subdeck also puts it in the parent.
 */

package com.ichi2.anki.tibetan

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.libanki.NoteId
import com.ichi2.anki.withProgress

class WordPickerActivity : AnkiActivity() {
    private lateinit var playlist: String
    private var isNew = false

    private var allWords: List<Word> = emptyList()
    private var otherDecks: List<String> = emptyList()

    /** null = whole Library */
    private var source: String? = null
    private var candidates: List<Word> = emptyList()
    private var visible: List<Word> = emptyList()
    private val selected = mutableSetOf<NoteId>()

    private lateinit var includeAll: MaterialCheckBox
    private lateinit var saveButton: MaterialButton
    private lateinit var sourceButton: MaterialButton
    private var query = ""
    private val adapter = PickAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (showedActivityFailedScreen(savedInstanceState)) return
        setContentView(R.layout.activity_word_picker)
        playlist = requireNotNull(intent.getStringExtra(EXTRA_PLAYLIST))
        isNew = intent.getBooleanExtra(EXTRA_IS_NEW, false)

        enableToolbar().apply {
            setDisplayHomeAsUpEnabled(true)
            title = playlist.substringAfterLast("::")
        }
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        includeAll = findViewById(R.id.include_all)
        includeAll.setOnClickListener {
            if (includeAll.isChecked) {
                selected.addAll(candidates.map { it.noteId })
            } else {
                selected.removeAll(candidates.map { it.noteId }.toSet())
            }
            refresh()
        }
        saveButton = findViewById(R.id.save_button)
        saveButton.setOnClickListener { save() }
        findViewById<EditText>(R.id.search).doAfterTextChanged {
            query = it?.toString().orEmpty()
            applyFilter()
        }
        sourceButton = findViewById(R.id.source_button)
        sourceButton.setOnClickListener { chooseSource() }
        findViewById<RecyclerView>(R.id.words).apply {
            layoutManager = LinearLayoutManager(this@WordPickerActivity)
            adapter = this@WordPickerActivity.adapter
        }

        launchCatchingTask {
            val (all, decks) = withCol { Library.allWords(this) to Library.playlistNames(this) }
            allWords = all.sortedBy { it.tibetan }
            otherDecks = decks.filterNot { it.equals(playlist, true) }
            if (savedInstanceState == null) selected.addAll(all.filter { it.isIn(playlist) }.map { it.noteId })
            setSource(if (isNew) Library.parentOf(playlist) else null)
        }
    }

    private fun setSource(deck: String?) {
        source = deck
        sourceButton.text = "From: " + (deck?.let { Library.displayName(it) } ?: Library.LIBRARY_NAME) + " ▾"
        // keep already-chosen words visible so they can be unticked
        candidates = allWords.filter { deck == null || it.isIn(deck) || it.noteId in selected }
        applyFilter()
    }

    private fun chooseSource() {
        val choices = listOf<String?>(null) + otherDecks
        MaterialAlertDialogBuilder(this)
            .setTitle("Show words from")
            .setSingleChoiceItems(
                choices.map { it?.let { d -> Library.displayName(d) } ?: Library.LIBRARY_NAME }.toTypedArray(),
                choices.indexOf(source),
            ) { dialog, which ->
                dialog.dismiss()
                setSource(choices[which])
            }.show()
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        visible =
            if (q.isEmpty()) {
                candidates
            } else {
                candidates.filter { it.tibetan.lowercase().contains(q) || it.english.lowercase().contains(q) }
            }
        refresh()
    }

    private fun refresh() {
        includeAll.isChecked = candidates.isNotEmpty() && candidates.all { it.noteId in selected }
        includeAll.text = "Include all (${candidates.size})"
        saveButton.text = "Save · ${selected.size} words"
        adapter.notifyDataSetChanged()
    }

    private fun save() {
        launchCatchingTask {
            withProgress { withCol { Library.savePlaylist(this, playlist, selected.toSet()) } }
            if (isNew) startActivity(WordListActivity.getIntent(this@WordPickerActivity, playlist))
            finish()
        }
    }

    private inner class PickAdapter : RecyclerView.Adapter<PickAdapter.Holder>() {
        inner class Holder(
            view: View,
        ) : RecyclerView.ViewHolder(view) {
            val checkbox: MaterialCheckBox = view.findViewById(R.id.checkbox)
            val tibetan: TextView = view.findViewById(R.id.tibetan)
            val english: TextView = view.findViewById(R.id.english)
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_word_pick, parent, false))

        override fun getItemCount() = visible.size

        override fun onBindViewHolder(
            holder: Holder,
            position: Int,
        ) {
            val word = visible[position]
            holder.tibetan.text = word.tibetan
            holder.english.text = word.english
            holder.checkbox.isChecked = word.noteId in selected
            holder.itemView.setOnClickListener {
                if (!selected.remove(word.noteId)) selected.add(word.noteId)
                refresh()
            }
        }
    }

    companion object {
        private const val EXTRA_PLAYLIST = "playlist"
        private const val EXTRA_IS_NEW = "isNew"

        fun getIntent(
            context: Context,
            playlist: String,
            isNew: Boolean,
        ): Intent =
            Intent(context, WordPickerActivity::class.java)
                .putExtra(EXTRA_PLAYLIST, playlist)
                .putExtra(EXTRA_IS_NEW, isNew)
    }
}
