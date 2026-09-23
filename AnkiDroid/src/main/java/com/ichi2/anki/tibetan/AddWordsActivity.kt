/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: the "Add cards" screen. Everything on one screen, and every
 * source appends to the same editable list:
 *  - Ask Claude ("ten new verbs")
 *  - Photo of a textbook page (camera or gallery)
 *  - Type words; "Fill in with Claude" completes whichever side is empty
 * Words are added as Basic notes to the Library, plus the deck this was opened
 * from (if any). From the Library, it then offers to put them in a deck too.
 */

package com.ichi2.anki.tibetan

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.libanki.Note
import com.ichi2.anki.libanki.NoteId
import com.ichi2.anki.withProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File

class AddWordsActivity : AnkiActivity() {
    /** deck to also add the words to; null = Library only */
    private var playlist: String? = null
    private var apiKey: String = ""
    private var cameraImageUri: Uri? = null

    private val adapter = RowsAdapter()
    private lateinit var addButton: MaterialButton
    private lateinit var progressContainer: View
    private lateinit var progressText: TextView
    private lateinit var promptInput: EditText

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = cameraImageUri
            if (success && uri != null) processImage(uri)
        }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) processImage(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (showedActivityFailedScreen(savedInstanceState)) return
        setContentView(R.layout.activity_add_words)
        playlist = intent.getStringExtra(EXTRA_PLAYLIST)
        apiKey = sharedPrefs().getString(getString(R.string.anthropic_api_key_pref), "").orEmpty().trim()

        enableToolbar().apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Add cards"
            subtitle = "To " + (playlist?.let { "Library + ${Library.displayName(it)}" } ?: Library.LIBRARY_NAME)
        }
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        progressContainer = findViewById(R.id.progress_container)
        progressText = findViewById(R.id.progress_text)
        addButton = findViewById(R.id.add_button)
        addButton.setOnClickListener { addSelectedCards() }
        val palette = TibetanTheme.palette(this)
        TibetanTheme.styleButton(addButton, palette, filled = true)
        for (id in listOf(R.id.photo_button, R.id.fill_button, R.id.add_row_button)) {
            TibetanTheme.styleButton(findViewById(id), palette, filled = false)
        }
        promptInput = findViewById(R.id.prompt)
        promptInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                askClaude()
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.ask_button).setOnClickListener { askClaude() }
        findViewById<View>(R.id.photo_button).setOnClickListener { showPhotoSourceChooser() }
        findViewById<View>(R.id.fill_button).setOnClickListener { fillWithClaude() }
        findViewById<View>(R.id.add_row_button).setOnClickListener { adapter.addBlankRow() }
        findViewById<RecyclerView>(R.id.cards_list).apply {
            layoutManager = LinearLayoutManager(this@AddWordsActivity)
            adapter = this@AddWordsActivity.adapter
        }
        if (savedInstanceState == null) repeat(3) { adapter.addBlankRow() }
        updateAddButton()
    }

    private fun requireApiKey(): Boolean {
        if (apiKey.isNotBlank()) return true
        showThemedToast(this, R.string.photo_import_no_api_key, false)
        return false
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(promptInput.windowToken, 0)
    }

    /** Runs a Claude request with the progress overlay, then appends / applies the result. */
    private fun runClaude(
        message: String,
        request: suspend () -> ExtractionResult,
        onSuccess: (List<VocabPair>) -> Unit,
    ) {
        if (!requireApiKey()) return
        hideKeyboard()
        launchCatchingTask {
            progressText.text = message
            progressContainer.visibility = View.VISIBLE
            val result =
                try {
                    request()
                } finally {
                    progressContainer.visibility = View.GONE
                }
            when (result) {
                is ExtractionResult.Success -> {
                    onSuccess(result.pairs)
                    updateAddButton()
                }
                is ExtractionResult.Failure -> showThemedToast(this@AddWordsActivity, result.message, false)
            }
        }
    }

    private fun askClaude() {
        val request = promptInput.text.toString()
        if (request.isBlank()) {
            showThemedToast(this, "Type what you'd like, e.g. “ten new verbs”", false)
            return
        }
        runClaude("Asking Claude…", {
            // avoid words already in the Library, and ones already in this list
            val known = withCol { db.queryStringList("select sfld from notes") } + adapter.rows.map { it.tibetan }
            ClaudeVocab.suggest(request, known.filter { it.isNotBlank() }, apiKey)
        }) { pairs ->
            adapter.appendRows(pairs)
            promptInput.text.clear()
        }
    }

    private fun fillWithClaude() {
        val toFill = adapter.rows.filter { it.tibetan.isBlank() != it.english.isBlank() }
        if (toFill.isEmpty()) {
            showThemedToast(this, "Type a word on one side of a row first", false)
            return
        }
        runClaude("Filling in…", {
            ClaudeVocab.fill(toFill.map { VocabPair(it.tibetan.trim(), it.english.trim()) }, apiKey)
        }) { pairs ->
            toFill.zip(pairs).forEach { (row, pair) ->
                row.tibetan = pair.tibetan
                row.english = pair.english
                row.checked = true
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun showPhotoSourceChooser() {
        if (!requireApiKey()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.photo_import_choose_source)
            .setItems(
                arrayOf(getString(R.string.photo_import_source_camera), getString(R.string.photo_import_source_gallery)),
            ) { _, which ->
                when (which) {
                    0 -> launchCamera()
                    1 -> pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }.show()
    }

    private fun launchCamera() {
        val dir = File(externalCacheDir, "temp-photos").apply { mkdirs() }
        val file = File(dir, "vocab_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.apkgfileprovider", file)
        cameraImageUri = uri
        takePictureLauncher.launch(uri)
    }

    private fun processImage(uri: Uri) {
        runClaude(getString(R.string.photo_import_reading), {
            val bytes = withContext(Dispatchers.IO) { loadDownscaledJpeg(uri) }
            ClaudeVocab.extractFromPhoto(bytes, "image/jpeg", apiKey)
        }) { pairs -> adapter.appendRows(pairs) }
    }

    /** Decodes [uri] to a bitmap downscaled to a max long edge, re-encoded as JPEG bytes. */
    private fun loadDownscaledJpeg(uri: Uri): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        val longest = maxOf(opts.outWidth, opts.outHeight)
        while (longest / sample > MAX_EDGE * 2) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap =
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: throw IllegalStateException("Could not decode image")

        val scale = MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled =
            if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else {
                bitmap
            }
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    private fun updateAddButton() {
        val count = adapter.selectedCount()
        addButton.isEnabled = count > 0
        addButton.text = getString(R.string.photo_import_add_cards, count)
    }

    private fun addSelectedCards() {
        val selected = adapter.rows.filter { it.checked && it.tibetan.isNotBlank() && it.english.isNotBlank() }
        if (selected.isEmpty()) return
        launchCatchingTask {
            val added: List<NoteId>? =
                withProgress {
                    withCol {
                        val notetype = notetypes.byName("Basic") ?: return@withCol null
                        selected
                            .map { row ->
                                val note = Note.fromNotetypeId(this, notetype.id)
                                note.setField(0, row.tibetan.trim())
                                note.setField(1, row.english.trim())
                                addNote(note, Library.LIBRARY_DECK_ID)
                                note.id
                            }.also { ids -> playlist?.let { Library.addWords(this, it, ids) } }
                    }
                }
            if (added == null) {
                Timber.w("Basic note type not found")
                showThemedToast(this@AddWordsActivity, R.string.photo_import_notetype_missing, false)
                return@launchCatchingTask
            }
            showThemedToast(this@AddWordsActivity, getString(R.string.photo_import_added, added.size), false)
            if (playlist == null) offerToAddToDeck(added) else finish()
        }
    }

    /** After adding to the Library only: "Also put these in a deck?" */
    private suspend fun offerToAddToDeck(noteIds: List<NoteId>) {
        val decks = withCol { Library.playlistNames(this) }
        if (decks.isEmpty()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Also put these ${noteIds.size} words in a deck?")
            .setItems(decks.map { Library.displayName(it) }.toTypedArray()) { _, which ->
                launchCatchingTask {
                    withCol { Library.addWords(this, decks[which], noteIds) }
                    showThemedToast(this@AddWordsActivity, "Added to ${Library.displayName(decks[which])}", false)
                    finish()
                }
            }.setNegativeButton("No thanks") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** A single editable row. */
    data class Row(
        var tibetan: String,
        var english: String,
        var checked: Boolean = true,
    ) {
        val isBlank get() = tibetan.isBlank() && english.isBlank()
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RowsAdapter.ViewHolder>() {
        val rows = mutableListOf<Row>()

        fun addBlankRow() {
            rows.add(Row("", ""))
            notifyItemInserted(rows.size - 1)
        }

        /** Appends results, dropping empty rows so new words aren't buried below blanks. */
        fun appendRows(pairs: List<VocabPair>) {
            rows.removeAll { it.isBlank }
            rows.addAll(pairs.map { Row(it.tibetan, it.english) })
            notifyDataSetChanged()
        }

        fun selectedCount(): Int = rows.count { it.checked && it.tibetan.isNotBlank() && it.english.isNotBlank() }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_photo_vocab, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
        ) {
            holder.bind(rows[position])
        }

        inner class ViewHolder(
            view: View,
        ) : RecyclerView.ViewHolder(view) {
            private val checkbox: MaterialCheckBox = view.findViewById(R.id.card_checkbox)
            private val tibetanEdit: EditText = view.findViewById(R.id.tibetan_edit)
            private val englishEdit: EditText = view.findViewById(R.id.english_edit)
            private var boundRow: Row? = null

            init {
                tibetanEdit.addTextChangedListener(watcher { boundRow?.tibetan = it })
                englishEdit.addTextChangedListener(watcher { boundRow?.english = it })
                checkbox.setOnCheckedChangeListener { _, isChecked ->
                    boundRow?.checked = isChecked
                    updateAddButton()
                }
            }

            fun bind(row: Row) {
                boundRow = null // suppress watcher writes while setting text
                if (tibetanEdit.text.toString() != row.tibetan) tibetanEdit.setText(row.tibetan)
                if (englishEdit.text.toString() != row.english) englishEdit.setText(row.english)
                checkbox.isChecked = row.checked
                boundRow = row
            }

            private fun watcher(onChange: (String) -> Unit): TextWatcher =
                object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        if (boundRow == null) return
                        onChange(s?.toString().orEmpty())
                        updateAddButton()
                    }

                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) {}

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {}
                }
        }
    }

    companion object {
        private const val EXTRA_PLAYLIST = "playlist"
        private const val MAX_EDGE = 1568

        /** @param playlist deck to also add the words to; null = Library only */
        fun getIntent(
            context: Context,
            playlist: String?,
        ): Intent = Intent(context, AddWordsActivity::class.java).putExtra(EXTRA_PLAYLIST, playlist)
    }
}
