/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: the home screen, replacing Anki's deck tree.
 *
 *   ┌ Library ──────────────────────┐   hero card: today's count, Tib/Eng
 *   │ 12 to study today             │   score bars, Start Roundup
 *   └───────────────────────────────┘
 *   ┌ v Verbs            24 words ──┐   one card per top-level deck;
 *   │ ─────────────────────────────  │   its subdecks are rows inside it
 *   │   Irregular         8 words    │
 *   └───────────────────────────────┘
 */

package com.ichi2.anki.tibetan

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.ichi2.anki.R

/** Due counts for the Library (Roundup). */
data class RoundupCounts(
    val new: Int,
    val learning: Int,
    val review: Int,
    val direction: StudyDirection,
) {
    val total get() = new + learning + review
}

class HomeAdapter(
    private val onRoundup: () -> Unit,
    private val onLibrary: () -> Unit,
    private val onPlaylist: (String) -> Unit,
    private val onPlaylistLongPress: (String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private sealed interface Item {
        data object LibraryCard : Item

        data class Deck(
            val info: PlaylistInfo,
            /** all subdecks (any depth), in display order */
            val children: List<PlaylistInfo>,
            val expanded: Boolean,
        ) : Item
    }

    private var counts = RoundupCounts(0, 0, 0, StudyDirection.TIBETAN_FIRST)
    private var library = PlaylistInfo("", 0)
    private var playlists: List<PlaylistInfo> = emptyList()
    private val collapsed = mutableSetOf<String>()
    private var items: List<Item> = emptyList()

    fun submit(
        counts: RoundupCounts,
        library: PlaylistInfo,
        playlists: List<PlaylistInfo>,
    ) {
        this.counts = counts
        this.library = library
        this.playlists = playlists
        rebuild()
    }

    private fun rebuild() {
        val list = mutableListOf<Item>(Item.LibraryCard)
        for (info in playlists.filter { it.depth == 0 }) {
            val children = playlists.filter { it.name.startsWith(info.name + "::") }
            list.add(Item.Deck(info, children, info.name !in collapsed))
        }
        items = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = if (items[position] is Item.LibraryCard) TYPE_LIBRARY else TYPE_DECK

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_LIBRARY) {
            LibraryHolder(inflater.inflate(R.layout.item_home_library, parent, false))
        } else {
            DeckHolder(inflater.inflate(R.layout.item_home_deck, parent, false))
        }
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val item = items[position]) {
            Item.LibraryCard -> (holder as LibraryHolder).bind()
            is Item.Deck -> (holder as DeckHolder).bind(item)
        }
    }

    /** A "Tib ▬▬▬▬ 70%" line. */
    private class ScoreRow(
        root: View,
    ) {
        val label: TextView = root.findViewById(R.id.label)
        val bar: ScoreBarView = root.findViewById(R.id.bar)
        val value: TextView = root.findViewById(R.id.value)

        fun bind(
            name: String,
            score: Int?,
            fill: Int,
            track: Int,
            text: Int,
        ) {
            label.text = name
            label.setTextColor(text)
            value.text = score?.let { "$it%" } ?: "–"
            value.setTextColor(text)
            bar.percent = score
            bar.fillColor = fill
            bar.trackColor = track
        }
    }

    private inner class LibraryHolder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view.findViewById(R.id.card)
        private val title: TextView = view.findViewById(R.id.title)
        private val headline: TextView = view.findViewById(R.id.headline)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)
        private val tib = ScoreRow(view.findViewById(R.id.tib_row))
        private val eng = ScoreRow(view.findViewById(R.id.eng_row))
        private val studyButton: MaterialButton = view.findViewById(R.id.study_button)

        fun bind() {
            val context = itemView.context
            val p = TibetanTheme.palette(context)
            val font = TibetanTheme.englishTypeface(context)
            val onCard = p.onPrimary
            val muted = ColorUtils.setAlphaComponent(onCard, 0xB0)

            card.setCardBackgroundColor(p.primary)
            title.setTextColor(muted)
            title.typeface = Typeface.create(font, Typeface.BOLD)
            headline.setTextColor(onCard)
            headline.typeface = Typeface.create(font, Typeface.BOLD)
            headline.text = if (counts.total == 0) "All done for today" else "${counts.total} to study today"
            subtitle.setTextColor(muted)
            subtitle.typeface = font
            subtitle.text = "${library.wordCount} words · ${counts.direction.label}"

            // on the indigo card, bars fill in saffron (or white in black & white mode)
            val fill = if (p.isMono) onCard else p.accent
            val track = ColorUtils.setAlphaComponent(onCard, 0x33)
            tib.bind("Tib", library.tibetanScore, fill, track, muted)
            eng.bind("Eng", library.englishScore, fill, track, muted)

            studyButton.typeface = Typeface.create(font, Typeface.BOLD)
            studyButton.cornerRadius = (28 * context.resources.displayMetrics.density).toInt()
            studyButton.backgroundTintList = ColorStateList.valueOf(if (p.isMono) p.onPrimary else p.accent)
            studyButton.setTextColor(if (p.isMono) p.primary else p.onAccent)
            studyButton.text = if (counts.total == 0) "Open Library" else "Start Roundup"
            studyButton.setOnClickListener { if (counts.total == 0) onLibrary() else onRoundup() }
            card.setOnClickListener { onLibrary() }
        }
    }

    private inner class DeckHolder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view.findViewById(R.id.card)
        private val header: View = view.findViewById(R.id.header)
        private val childrenBox: LinearLayout = view.findViewById(R.id.children)
        private val expander: ImageView = view.findViewById(R.id.expander)
        private val title: TextView = view.findViewById(R.id.title)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)
        private val tib = ScoreRow(view.findViewById(R.id.tib_row))
        private val eng = ScoreRow(view.findViewById(R.id.eng_row))

        fun bind(item: Item.Deck) {
            val context = itemView.context
            val p = TibetanTheme.palette(context)
            val density = context.resources.displayMetrics.density
            val font = TibetanTheme.englishTypeface(context)
            val muted = ColorUtils.setAlphaComponent(p.onSurface, 0x99)

            card.strokeColor = ColorUtils.setAlphaComponent(p.onSurface, 0x1F)
            card.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)

            title.text = item.info.shortName
            title.setTextColor(p.onSurface)
            title.typeface = Typeface.create(font, Typeface.BOLD)
            subtitle.text = "${item.info.wordCount} words"
            subtitle.setTextColor(muted)
            subtitle.typeface = font

            tib.bind("Tib", item.info.tibetanScore, p.primary, p.track, muted)
            eng.bind("Eng", item.info.englishScore, p.primary, p.track, muted)

            val hasChildren = item.children.isNotEmpty()
            expander.visibility = if (hasChildren) View.VISIBLE else View.INVISIBLE
            expander.rotation = if (item.expanded) 90f else 0f
            expander.imageTintList = ColorStateList.valueOf(muted)
            expander.setOnClickListener {
                if (!collapsed.remove(item.info.name)) collapsed.add(item.info.name)
                rebuild()
            }
            header.setOnClickListener { onPlaylist(item.info.name) }
            header.setOnLongClickListener {
                onPlaylistLongPress(item.info.name)
                true
            }

            childrenBox.removeAllViews()
            childrenBox.visibility = if (hasChildren && item.expanded) View.VISIBLE else View.GONE
            if (hasChildren && item.expanded) {
                childrenBox.addView(
                    View(context).apply { setBackgroundColor(ColorUtils.setAlphaComponent(p.onSurface, 0x1A)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                        setMargins((48 * density).toInt(), 0, (16 * density).toInt(), (2 * density).toInt())
                    },
                )
                for (child in item.children) childrenBox.addView(childRow(child, p, muted))
            }
        }

        /** A compact subdeck row: "Irregular   8 words   Tib 70% · Eng –" */
        private fun childRow(
            child: PlaylistInfo,
            p: Palette,
            muted: Int,
        ): View {
            val context = itemView.context
            val density = context.resources.displayMetrics.density
            val font = TibetanTheme.englishTypeface(context)
            val out = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(out.resourceId)
                val indent = (48 + (child.depth - 1) * 18) * density
                setPadding(indent.toInt(), (9 * density).toInt(), (16 * density).toInt(), (9 * density).toInt())
                addView(
                    TextView(context).apply {
                        text = child.shortName
                        typeface = Typeface.create(font, Typeface.BOLD)
                        textSize = 15f
                        setTextColor(p.onSurface)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    TextView(context).apply {
                        typeface = font
                        textSize = 12f
                        setTextColor(muted)
                        text =
                            "${child.wordCount} words · Tib ${child.tibetanScore?.let { "$it%" } ?: "–"}" +
                            " · Eng ${child.englishScore?.let { "$it%" } ?: "–"}"
                    },
                )
                setOnClickListener { onPlaylist(child.name) }
                setOnLongClickListener {
                    onPlaylistLongPress(child.name)
                    true
                }
            }
        }
    }

    companion object {
        private const val TYPE_LIBRARY = 0
        private const val TYPE_DECK = 1
    }
}
