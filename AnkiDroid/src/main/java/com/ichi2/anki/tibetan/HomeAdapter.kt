/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: the home screen list, replacing Anki's deck tree.
 *
 *   Library        (all words; its screen has the daily Roundup)
 *   > Verbs
 *       Irregular
 */

package com.ichi2.anki.tibetan

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
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
    private val onLibrary: () -> Unit,
    private val onPlaylist: (String) -> Unit,
    private val onPlaylistLongPress: (String) -> Unit,
) : RecyclerView.Adapter<HomeAdapter.Holder>() {
    private sealed interface Item {
        data class LibraryRow(
            val wordCount: Int,
        ) : Item

        data class Playlist(
            val info: PlaylistInfo,
            val hasChildren: Boolean,
            val expanded: Boolean,
        ) : Item
    }

    private var counts = RoundupCounts(0, 0, 0, StudyDirection.TIBETAN_FIRST)
    private var libraryWordCount = 0
    private var playlists: List<PlaylistInfo> = emptyList()
    private val collapsed = mutableSetOf<String>()
    private var items: List<Item> = emptyList()

    fun submit(
        counts: RoundupCounts,
        libraryWordCount: Int,
        playlists: List<PlaylistInfo>,
    ) {
        this.counts = counts
        this.libraryWordCount = libraryWordCount
        this.playlists = playlists
        rebuild()
    }

    private fun rebuild() {
        val list = mutableListOf<Item>(Item.LibraryRow(libraryWordCount))
        for (info in playlists) {
            // hidden if any ancestor is collapsed
            var parent = Library.parentOf(info.name)
            var hidden = false
            while (parent != null) {
                if (parent in collapsed) hidden = true
                parent = Library.parentOf(parent)
            }
            if (hidden) continue
            val hasChildren = playlists.any { it.name.startsWith(info.name + "::") }
            list.add(Item.Playlist(info, hasChildren, info.name !in collapsed))
        }
        items = list
        notifyDataSetChanged()
    }

    class Holder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        val expander: ImageView = view.findViewById(R.id.expander)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val action: TextView = view.findViewById(R.id.action)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_home_row, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        val density = holder.itemView.resources.displayMetrics.density
        holder.itemView.setPaddingRelative(0, 0, holder.itemView.paddingEnd, 0)
        holder.itemView.setOnLongClickListener(null)
        holder.itemView.isLongClickable = false
        holder.expander.visibility = View.INVISIBLE
        holder.expander.setOnClickListener(null)
        holder.action.isVisible = false
        holder.subtitle.isVisible = true

        when (val item = items[position]) {
            is Item.LibraryRow -> {
                holder.title.text = Library.LIBRARY_NAME
                holder.subtitle.text =
                    "${item.wordCount} words · " +
                    if (counts.total == 0) "all done for today" else "${counts.total} to study today"
                holder.itemView.setOnClickListener { onLibrary() }
            }
            is Item.Playlist -> {
                holder.itemView.setPaddingRelative((item.info.depth * 24 * density).toInt(), 0, holder.itemView.paddingEnd, 0)
                holder.title.text = item.info.shortName
                holder.subtitle.text = "${item.info.wordCount} words"
                if (item.hasChildren) {
                    holder.expander.visibility = View.VISIBLE
                    holder.expander.rotation = if (item.expanded) 90f else 0f
                    holder.expander.setOnClickListener {
                        if (!collapsed.remove(item.info.name)) collapsed.add(item.info.name)
                        rebuild()
                    }
                }
                holder.itemView.setOnClickListener { onPlaylist(item.info.name) }
                holder.itemView.setOnLongClickListener {
                    onPlaylistLongPress(item.info.name)
                    true
                }
            }
        }
    }
}
