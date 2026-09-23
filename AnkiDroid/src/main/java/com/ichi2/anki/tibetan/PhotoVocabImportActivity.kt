/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan vocabulary card import, in two modes:
 *  - [Mode.PHOTO]: capture/pick a photo of a textbook vocabulary page
 *  - [Mode.ASK]: ask Claude for words ("give me ten new verbs to learn")
 * Pairs come from [ClaudeVocab]; the user reviews/edits them, and they are
 * added as Basic notes to the Default deck.
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
import com.ichi2.anki.libanki.Consts
import com.ichi2.anki.libanki.Note
import com.ichi2.anki.withProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File

class PhotoVocabImportActivity : AnkiActivity() {
    private lateinit var mode: Mode
    private var apiKey: String = ""
    private var cameraImageUri: Uri? = null

    private lateinit var adapter: VocabReviewAdapter
    private lateinit var addButton: MaterialButton
    private lateinit var progressContainer: View

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = cameraImageUri
            if (success && uri != null) {
                processImage(uri)
            } else {
                finishIfEmpty()
            }
        }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                processImage(uri)
            } else {
                finishIfEmpty()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (showedActivityFailedScreen(savedInstanceState)) return
        setContentView(R.layout.activity_photo_vocab_import)

        mode = Mode.entries[intent.getIntExtra(EXTRA_MODE, Mode.PHOTO.ordinal)]
        apiKey = sharedPrefs().getString(getString(R.string.anthropic_api_key_pref), "").orEmpty().trim()

        enableToolbar().apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(if (mode == Mode.ASK) R.string.ask_claude_for_words else R.string.photo_import_title)
        }
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        progressContainer = findViewById(R.id.progress_container)
        findViewById<TextView>(R.id.progress_text).setText(
            if (mode == Mode.ASK) R.string.ask_claude_thinking else R.string.photo_import_reading,
        )
        addButton = findViewById(R.id.add_button)
        addButton.setOnClickListener { addSelectedCards() }

        adapter = VocabReviewAdapter { updateAddButton() }
        findViewById<RecyclerView>(R.id.cards_list).apply {
            layoutManager = LinearLayoutManager(this@PhotoVocabImportActivity)
            adapter = this@PhotoVocabImportActivity.adapter
        }
        updateAddButton()

        if (apiKey.isBlank()) {
            showThemedToast(this, R.string.photo_import_no_api_key, false)
            finish()
            return
        }

        if (savedInstanceState == null) {
            when (mode) {
                Mode.PHOTO -> showSourceChooser()
                Mode.ASK -> showAskDialog()
            }
        }
    }

    private fun showAskDialog() {
        val input =
            EditText(this).apply {
                setHint(R.string.ask_claude_hint)
                minLines = 2
            }
        val container =
            android.widget.FrameLayout(this).apply {
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ask_claude_for_words)
            .setView(container)
            .setPositiveButton(R.string.ask_claude_button) { _, _ ->
                val request = input.text.toString()
                if (request.isBlank()) finishIfEmpty() else askClaude(request)
            }.setNegativeButton(R.string.dialog_cancel) { _, _ -> finishIfEmpty() }
            .setOnCancelListener { finishIfEmpty() }
            .show()
    }

    private fun askClaude(request: String) {
        launchCatchingTask {
            progressContainer.visibility = View.VISIBLE
            val result =
                try {
                    val knownWords = withCol { db.queryStringList("select sfld from notes") }
                    ClaudeVocab.suggest(request, knownWords, apiKey)
                } finally {
                    progressContainer.visibility = View.GONE
                }
            showResult(result)
        }
    }

    private fun showSourceChooser() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.photo_import_choose_source)
            .setItems(
                arrayOf(
                    getString(R.string.photo_import_source_camera),
                    getString(R.string.photo_import_source_gallery),
                ),
            ) { _, which ->
                when (which) {
                    0 -> launchCamera()
                    1 ->
                        pickImageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                }
            }.setOnCancelListener { finishIfEmpty() }
            .show()
    }

    private fun launchCamera() {
        val dir = File(externalCacheDir, "temp-photos").apply { mkdirs() }
        val file = File(dir, "vocab_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.apkgfileprovider", file)
        cameraImageUri = uri
        takePictureLauncher.launch(uri)
    }

    private fun processImage(uri: Uri) {
        launchCatchingTask {
            progressContainer.visibility = View.VISIBLE
            val result =
                try {
                    val bytes = withContext(Dispatchers.IO) { loadDownscaledJpeg(uri) }
                    ClaudeVocab.extractFromPhoto(bytes, "image/jpeg", apiKey)
                } finally {
                    progressContainer.visibility = View.GONE
                }
            showResult(result)
        }
    }

    private fun showResult(result: ExtractionResult) {
        when (result) {
            is ExtractionResult.Success -> {
                adapter.setRows(result.pairs.map { Row(it.tibetan, it.english) })
                updateAddButton()
            }
            is ExtractionResult.Failure -> {
                showThemedToast(this, result.message, false)
                finishIfEmpty()
            }
        }
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
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true,
                )
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
        val selected =
            adapter.rows.filter {
                it.checked && it.tibetan.isNotBlank() && it.english.isNotBlank()
            }
        if (selected.isEmpty()) {
            showThemedToast(this, R.string.photo_import_none_selected, true)
            return
        }
        launchCatchingTask {
            val added =
                withProgress {
                    withCol {
                        val notetype = notetypes.byName("Basic")
                        if (notetype == null) {
                            Timber.w("Basic note type not found")
                            return@withCol -1
                        }
                        for (row in selected) {
                            val note = Note.fromNotetypeId(this, notetype.id)
                            note.setField(0, row.tibetan.trim())
                            note.setField(1, row.english.trim())
                            addNote(note, Consts.DEFAULT_DECK_ID)
                        }
                        selected.size
                    }
                }
            if (added < 0) {
                showThemedToast(this@PhotoVocabImportActivity, R.string.photo_import_notetype_missing, false)
            } else {
                showThemedToast(
                    this@PhotoVocabImportActivity,
                    getString(R.string.photo_import_added, added),
                    false,
                )
                finish()
            }
        }
    }

    /** If the user backed out before any cards were loaded, close the screen. */
    private fun finishIfEmpty() {
        if (adapter.rows.isEmpty()) finish()
    }

    /** A single editable row in the review list. */
    data class Row(
        var tibetan: String,
        var english: String,
        var checked: Boolean = true,
    )

    private class VocabReviewAdapter(
        private val onSelectionChanged: () -> Unit,
    ) : RecyclerView.Adapter<VocabReviewAdapter.ViewHolder>() {
        val rows = mutableListOf<Row>()

        fun setRows(newRows: List<Row>) {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        fun selectedCount(): Int = rows.count { it.checked && it.tibetan.isNotBlank() && it.english.isNotBlank() }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ViewHolder {
            val view =
                LayoutInflater
                    .from(parent.context)
                    .inflate(R.layout.item_photo_vocab, parent, false)
            return ViewHolder(view, onSelectionChanged)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
        ) {
            holder.bind(rows[position])
        }

        class ViewHolder(
            view: View,
            private val onSelectionChanged: () -> Unit,
        ) : RecyclerView.ViewHolder(view) {
            private val checkbox: MaterialCheckBox = view.findViewById(R.id.card_checkbox)
            private val tibetanEdit: EditText = view.findViewById(R.id.tibetan_edit)
            private val englishEdit: EditText = view.findViewById(R.id.english_edit)
            private var boundRow: Row? = null

            init {
                tibetanEdit.addTextChangedListener(
                    simpleWatcher {
                        boundRow?.tibetan = it
                        onSelectionChanged()
                    },
                )
                englishEdit.addTextChangedListener(
                    simpleWatcher {
                        boundRow?.english = it
                        onSelectionChanged()
                    },
                )
                checkbox.setOnCheckedChangeListener { _, isChecked ->
                    boundRow?.checked = isChecked
                    onSelectionChanged()
                }
            }

            fun bind(row: Row) {
                boundRow = null // suppress watcher writes while setting text
                if (tibetanEdit.text.toString() != row.tibetan) tibetanEdit.setText(row.tibetan)
                if (englishEdit.text.toString() != row.english) englishEdit.setText(row.english)
                checkbox.isChecked = row.checked
                boundRow = row
            }

            private fun simpleWatcher(onChange: (String) -> Unit): TextWatcher =
                object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        if (boundRow != null) onChange(s?.toString().orEmpty())
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

    enum class Mode { PHOTO, ASK }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val MAX_EDGE = 1568

        fun getIntent(
            context: Context,
            mode: Mode,
        ): Intent =
            Intent(context, PhotoVocabImportActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode.ordinal)
            }
    }
}
