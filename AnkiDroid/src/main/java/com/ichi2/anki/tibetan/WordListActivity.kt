/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: the word list for the Library or a playlist deck.
 * Shows the chosen columns (Tibetan / English / scores / added / tags), sortable
 * by tapping a header, with a Study button on top.
 */

package com.ichi2.anki.tibetan

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.Reviewer
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.noteeditor.NoteEditorLauncher
import com.ichi2.anki.withProgress

class WordListActivity : AnkiActivity() {
    /** null = the Library */
    private var playlist: String? = null

    private var words: List<Word> = emptyList()
    private var visible: List<Word> = emptyList()
    private var columns: List<WordColumn> = emptyList()
    private var query = ""
    private var sortColumn: WordColumn? = null
    private var sortDescending = false

    private lateinit var header: LinearLayout
    private lateinit var summary: TextView
    private lateinit var studyButton: MaterialButton
    private val adapter = WordAdapter()

    private val studyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (showedActivityFailedScreen(savedInstanceState)) return
        setContentView(R.layout.activity_word_list)
        playlist = intent.getStringExtra(EXTRA_PLAYLIST)

        enableToolbar().setDisplayHomeAsUpEnabled(true)
        updateTitle()
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        header = findViewById(R.id.header)
        summary = findViewById(R.id.summary)
        studyButton = findViewById(R.id.study_button)
        studyButton.text = if (playlist == null) "Start Roundup" else "Study this deck"
        studyButton.setOnClickListener { study() }
        findViewById<EditText>(R.id.search).doAfterTextChanged {
            query = it?.toString().orEmpty()
            applyFilterAndSort()
        }
        findViewById<RecyclerView>(R.id.words).apply {
            layoutManager = LinearLayoutManager(this@WordListActivity)
            adapter = this@WordListActivity.adapter
        }
        columns = WordColumn.enabled(this)
        buildHeader()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun updateTitle() {
        supportActionBar?.title = playlist?.let { Library.displayName(it) } ?: Library.LIBRARY_NAME
    }

    private fun reload() {
        launchCatchingTask {
            words =
                withCol {
                    Library.endStudySession(this)
                    Library.words(this, playlist)
                }
            val subdecks = playlist?.let { p -> withCol { Library.playlistNames(this) }.count { it.startsWith("$p::") } } ?: 0
            summary.text =
                buildString {
                    append("${words.size} words")
                    if (subdecks > 0) append(" · $subdecks subdecks")
                    if (playlist != null) append(" · a session is the ${Library.SESSION_SIZE} weakest / least recently seen")
                }
            applyFilterAndSort()
        }
    }

    private fun applyFilterAndSort() {
        val q = query.trim().lowercase()
        var list =
            if (q.isEmpty()) {
                words
            } else {
                words.filter {
                    it.tibetan.lowercase().contains(q) ||
                        it.english.lowercase().contains(q) ||
                        it.tags.any { t -> t.lowercase().contains(q) }
                }
            }
        val sort = sortColumn
        list =
            if (sort == null) {
                list.sortedByDescending { it.added }
            } else {
                @Suppress("UNCHECKED_CAST")
                val comparator = compareBy<Word> { sort.sortKey(it) as Comparable<Any> }
                list.sortedWith(if (sortDescending) comparator.reversed() else comparator)
            }
        visible = list
        adapter.notifyDataSetChanged()
    }

    private fun buildHeader() {
        header.removeAllViews()
        for (column in columns) {
            val arrow =
                when {
                    sortColumn != column -> ""
                    sortDescending -> " ↓"
                    else -> " ↑"
                }
            header.addView(
                cell(column, column.label + arrow).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setOnClickListener {
                        if (sortColumn == column) sortDescending = !sortDescending else sortColumn = column
                        buildHeader()
                        applyFilterAndSort()
                    }
                },
            )
        }
    }

    private fun cell(
        column: WordColumn,
        text: String,
    ): TextView =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, column.weight)
            this.text = text
            setPadding(4, 0, 4, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun study() {
        launchCatchingTask {
            val did = withCol { Library.prepareStudy(this, playlist) }
            if (did == null) {
                showThemedToast(this@WordListActivity, "This deck has no words yet", true)
                return@launchCatchingTask
            }
            val hasCards = withCol { sched.deckDueTree().find(did)?.let { it.newCount + it.lrnCount + it.revCount > 0 } == true }
            if (!hasCards) {
                showThemedToast(this@WordListActivity, "Nothing to study right now", true)
                withCol { Library.endStudySession(this) }
                return@launchCatchingTask
            }
            studyLauncher.launch(Reviewer.getIntent(this@WordListActivity))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_COLUMNS, 0, "Columns")
        if (playlist != null) {
            menu.add(0, MENU_CHOOSE, 1, "Choose words")
            menu.add(0, MENU_SUBDECK, 2, "Add subdeck")
            menu.add(0, MENU_RENAME, 3, "Rename deck")
            menu.add(0, MENU_DELETE, 4, "Delete deck")
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val p = playlist
        when (item.itemId) {
            MENU_COLUMNS -> showColumnsDialog()
            MENU_CHOOSE -> if (p != null) startActivity(WordPickerActivity.getIntent(this, p, isNew = false))
            MENU_SUBDECK -> if (p != null) PlaylistActions.newDeck(this, p)
            MENU_RENAME ->
                if (p != null) {
                    PlaylistActions.rename(this, p) { newName ->
                        playlist = newName
                        intent.putExtra(EXTRA_PLAYLIST, newName)
                        updateTitle()
                        reload()
                    }
                }
            MENU_DELETE -> if (p != null) PlaylistActions.delete(this, p) { finish() }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showColumnsDialog() {
        val all = WordColumn.entries
        val checked = all.map { it in columns }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Columns")
            .setMultiChoiceItems(all.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }.setPositiveButton(R.string.dialog_ok) { _, _ ->
                val chosen = all.filterIndexed { i, _ -> checked[i] }.ifEmpty { listOf(WordColumn.TIBETAN) }
                WordColumn.setEnabled(this, chosen)
                columns = chosen
                if (sortColumn !in columns) sortColumn = null
                buildHeader()
                adapter.notifyDataSetChanged()
            }.setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun onWordLongPressed(word: Word) {
        val p = playlist
        val options = mutableListOf("Edit")
        if (p != null) options.add("Remove from this deck")
        options.add("Delete word")
        MaterialAlertDialogBuilder(this)
            .setTitle(word.tibetan)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Edit" -> editWord(word)
                    "Remove from this deck" ->
                        launchCatchingTask {
                            withCol { Library.removeWords(this, p!!, listOf(word.noteId)) }
                            reload()
                        }
                    "Delete word" -> confirmDelete(word)
                }
            }.show()
    }

    private fun confirmDelete(word: Word) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete “${word.tibetan}”?")
            .setMessage("Deletes the word from the Library and every deck, with its scores.")
            .setPositiveButton("Delete") { _, _ ->
                launchCatchingTask {
                    withProgress { withCol { Library.deleteWords(this, listOf(word.noteId)) } }
                    reload()
                }
            }.setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun editWord(word: Word) {
        startActivity(NoteEditorLauncher.EditNoteFromPreviewer(word.firstCardId).toIntent(this))
    }

    private inner class WordAdapter : RecyclerView.Adapter<WordAdapter.Holder>() {
        inner class Holder(
            val row: LinearLayout,
        ) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): Holder {
            val row =
                LinearLayout(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    val outValue = TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                }
            return Holder(row)
        }

        override fun getItemCount() = visible.size

        override fun onBindViewHolder(
            holder: Holder,
            position: Int,
        ) {
            val word = visible[position]
            holder.row.removeAllViews()
            for (column in columns) {
                holder.row.addView(
                    cell(column, column.text(word)).apply {
                        if (column == WordColumn.TIBETAN) setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    },
                )
            }
            holder.row.setOnClickListener { editWord(word) }
            holder.row.setOnLongClickListener {
                onWordLongPressed(word)
                true
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_PLAYLIST = "playlist"
        private const val MENU_COLUMNS = 1
        private const val MENU_CHOOSE = 2
        private const val MENU_SUBDECK = 3
        private const val MENU_RENAME = 4
        private const val MENU_DELETE = 5

        /** @param playlist null for the Library */
        fun getIntent(
            context: Context,
            playlist: String?,
        ): Intent = Intent(context, WordListActivity::class.java).putExtra(EXTRA_PLAYLIST, playlist)
    }
}
