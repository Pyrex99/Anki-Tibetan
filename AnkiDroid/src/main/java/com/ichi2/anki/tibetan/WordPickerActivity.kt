/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: choose which words are in a playlist deck. Candidates are the
 * Library (top-level deck) or the parent deck's words (subdeck).
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
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.libanki.NoteId
import com.ichi2.anki.withProgress

class WordPickerActivity : AnkiActivity() {
    private lateinit var playlist: String
    private var isNew = false

    private var candidates: List<Word> = emptyList()
    private var visible: List<Word> = emptyList()
    private val selected = mutableSetOf<NoteId>()

    private lateinit var includeAll: MaterialCheckBox
    private lateinit var saveButton: MaterialButton
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
            subtitle = "From " + (Library.parentOf(playlist)?.let { Library.displayName(it) } ?: Library.LIBRARY_NAME)
        }
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        includeAll = findViewById(R.id.include_all)
        includeAll.setOnClickListener {
            if (includeAll.isChecked) selected.addAll(candidates.map { it.noteId }) else selected.clear()
            refresh()
        }
        saveButton = findViewById(R.id.save_button)
        saveButton.setOnClickListener { save() }
        findViewById<EditText>(R.id.search).doAfterTextChanged { applyFilter(it?.toString().orEmpty()) }
        findViewById<RecyclerView>(R.id.words).apply {
            layoutManager = LinearLayoutManager(this@WordPickerActivity)
            adapter = this@WordPickerActivity.adapter
        }

        launchCatchingTask {
            val (source, current) =
                withCol {
                    val all = Library.allWords(this)
                    val parent = Library.parentOf(playlist)
                    val source = if (parent == null) all else all.filter { it.isIn(parent) }
                    source to all.filter { it.isIn(playlist) }.map { it.noteId }
                }
            candidates = source.sortedBy { it.tibetan }
            if (savedInstanceState == null) selected.addAll(current)
            applyFilter("")
        }
    }

    private fun applyFilter(query: String) {
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
        includeAll.isChecked = candidates.isNotEmpty() && selected.size == candidates.size
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
