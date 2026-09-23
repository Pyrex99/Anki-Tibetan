/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: Statistics.
 *  - Progress by direction (solid / learning / new, gained in the last 30 days)
 *  - Weakest words per direction, with "Drill these"
 *  - Deck mastery (average score per direction for the Library and each deck)
 *  - Streak & activity (last 30 days, next 7 days)
 */

package com.ichi2.anki.tibetan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.Reviewer
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.launchCatchingTask
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class StatsActivity : AnkiActivity() {
    private lateinit var content: LinearLayout
    private val palette by lazy { TibetanTheme.palette(this) }
    private val primary get() = palette.primary
    private val onSurface get() = palette.onSurface

    private val drillLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (showedActivityFailedScreen(savedInstanceState)) return
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val toolbar =
                    com.google.android.material.appbar
                        .MaterialToolbar(context)
                        .apply { id = R.id.toolbar }
                addView(toolbar, ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
                content =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(4), dp(12), dp(24))
                    }
                addView(ScrollView(context).apply { addView(content) }, ViewGroup.LayoutParams.MATCH_PARENT, 0)
                (getChildAt(1).layoutParams as LinearLayout.LayoutParams).weight = 1f
            }
        setContentView(root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            title = getString(R.string.statistics)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        launchCatchingTask {
            val data =
                withCol {
                    Library.endStudySession(this)
                    TibetanStats.load(this) to StudyDirection.get(this)
                }
            render(data.first, data.second)
        }
    }

    private fun render(
        data: TibetanStatsData,
        direction: StudyDirection,
    ) {
        content.removeAllViews()
        renderProgress(data.progress)
        renderWeakest(data.weakest, direction)
        renderMastery(data.mastery)
        renderActivity(data.activity)
    }

    /*
     * Sections
     */

    private fun renderProgress(progress: List<DirectionProgress>) {
        val card = section("Progress by direction")
        if (progress.isEmpty()) {
            card.addView(body("No words yet."))
            return
        }
        for (p in progress) {
            card.addView(subheading(directionName(p.ord)))
            card.addView(
                text("${p.solid} of ${p.total} words solid", 20f, bold = true),
            )
            card.addView(
                StackedBar(this, listOf(p.solid, p.learning, p.new), listOf(primary, alpha(primary, 0.45f), alpha(onSurface, 0.12f))),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply { setMargins(0, dp(6), 0, dp(6)) },
            )
            val gained =
                when {
                    p.solidGainedLast30Days > 0 -> "+${p.solidGainedLast30Days} solid in the last 30 days"
                    p.solidGainedLast30Days < 0 -> "${p.solidGainedLast30Days} solid in the last 30 days"
                    else -> "No change in the last 30 days"
                }
            card.addView(body("$gained · ${p.learning} learning · ${p.new} not started"))
        }
        card.addView(small("Solid = you'll next see it in 3+ weeks."))
    }

    private fun renderWeakest(
        weakest: Map<Int, List<WeakWord>>,
        direction: StudyDirection,
    ) {
        val card = section("Weakest words")
        if (weakest.values.all { it.isEmpty() }) {
            card.addView(body("Review a few words first — this lists the ones you miss most."))
            return
        }
        for (ord in listOf(ORD_TIBETAN_FIRST, ORD_ENGLISH_FIRST)) {
            val list = weakest[ord].orEmpty()
            if (list.isEmpty()) continue
            val header =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(subheading(directionName(ord)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(
                        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                            text = "Drill these"
                            setOnClickListener { drill(list.map { it.word.noteId }, ord, direction) }
                        },
                    )
                }
            card.addView(header)
            for (w in list) {
                card.addView(
                    row(
                        listOf(
                            w.word.tibetan to 3f,
                            w.word.english to 3f,
                            "${w.score}% · ${w.reviews}×" to 1.8f,
                        ),
                        tibetanFirst = true,
                    ),
                )
            }
        }
        card.addView(small("Score = % of reviews you didn't mark “Again” · × = times reviewed"))
    }

    private fun renderMastery(mastery: List<DeckMastery>) {
        val card = section("Deck mastery")
        for (m in mastery) {
            val name = m.deck?.let { Library.displayName(it) } ?: Library.LIBRARY_NAME
            val block =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                    isClickable = true
                    val out = TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                    setBackgroundResource(out.resourceId)
                    setOnClickListener { startActivity(WordListActivity.getIntent(this@StatsActivity, m.deck)) }
                }
            block.addView(text("$name  ·  ${m.words} words", 15f, bold = true))
            block.addView(scoreBar("Tib", m.tibetanScore))
            block.addView(scoreBar("Eng", m.englishScore))
            card.addView(block)
        }
        card.addView(small("Average score per direction. Tap a deck to open it."))
    }

    private fun renderActivity(activity: StudyActivity) {
        val card = section("Streak & activity")
        card.addView(
            text(
                if (activity.streakDays > 0) "${activity.streakDays}-day streak" else "No streak yet — study today to start one",
                20f,
                bold = true,
            ),
        )
        card.addView(subheading("Reviews, last 30 days · ${activity.last30Days.sum()} total"))
        card.addView(
            BarChart(this, activity.last30Days, labels = null, color = primary, labelColor = alpha(onSurface, 0.7f)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90)),
        )
        card.addView(subheading("Coming up · ${activity.next7Days.sum()} reviews this week"))
        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
        val labels =
            (0..6).map { offset ->
                if (offset == 0) {
                    "Today"
                } else {
                    dayFormat.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }.time)
                }
            }
        card.addView(
            BarChart(this, activity.next7Days, labels = labels, color = alpha(primary, 0.7f), labelColor = alpha(onSurface, 0.7f)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)),
        )
    }

    private fun drill(
        noteIds: List<Long>,
        ord: Int,
        direction: StudyDirection,
    ) {
        if (ord !in direction.activeOrds) {
            showThemedToast(this, "Set study direction to ${directionShort(ord)} or Mix to drill these", false)
            return
        }
        launchCatchingTask {
            val did = withCol { Library.prepareDrill(this, noteIds, ord) }
            val hasCards = did != null && withCol { sched.deckDueTree().find(did)?.hasCardsReadyToStudy() == true }
            if (!hasCards) {
                withCol { Library.endStudySession(this) }
                showThemedToast(this@StatsActivity, "Nothing to drill right now", false)
                return@launchCatchingTask
            }
            drillLauncher.launch(Reviewer.getIntent(this@StatsActivity))
        }
    }

    /*
     * View helpers
     */

    private fun directionName(ord: Int) = if (ord == ORD_TIBETAN_FIRST) "Tibetan → English" else "English → Tibetan"

    private fun directionShort(ord: Int) = if (ord == ORD_TIBETAN_FIRST) "Tibetan first" else "English first"

    private fun section(title: String): LinearLayout {
        val inner =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(14))
                addView(text(title, 18f, bold = true))
            }
        val card =
            MaterialCardView(this).apply {
                addView(inner)
            }
        content.addView(
            card,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(8), 0, dp(4))
            },
        )
        return inner
    }

    private fun text(
        value: String,
        sizeSp: Float,
        bold: Boolean = false,
    ) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun subheading(value: String) =
        text(value, 14f, bold = true).apply {
            setPadding(0, dp(10), 0, dp(2))
            alpha = 0.85f
        }

    private fun body(value: String) = text(value, 14f)

    private fun small(value: String) =
        text(value, 12f).apply {
            alpha = 0.7f
            setPadding(0, dp(8), 0, 0)
        }

    private fun row(
        cells: List<Pair<String, Float>>,
        tibetanFirst: Boolean,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        cells.forEachIndexed { i, (value, weight) ->
            addView(
                text(value, if (i == 0 && tibetanFirst) 18f else 14f).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight),
            )
        }
    }

    private fun scoreBar(
        label: String,
        score: Int?,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), 0, dp(2))
        addView(text(label, 13f), LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(
            StackedBar(
                context,
                listOf(score ?: 0, 100 - (score ?: 0)),
                listOf(primary, alpha(onSurface, 0.12f)),
            ),
            LinearLayout.LayoutParams(0, dp(10), 1f).apply { setMargins(0, 0, dp(8), 0) },
        )
        addView(text(score?.let { "$it%" } ?: "–", 13f), LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun alpha(
        color: Int,
        fraction: Float,
    ) = ColorUtils.setAlphaComponent(color, (255 * fraction).toInt())

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** A horizontal bar split into proportional coloured segments. */
    private class StackedBar(
        context: Context,
        private val values: List<Int>,
        private val colors: List<Int>,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val total = values.sum().coerceAtLeast(1)
            val radius = height / 2f
            // background track
            paint.color = colors.last()
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)
            var x = 0f
            values.forEachIndexed { i, v ->
                val w = width * v.toFloat() / total
                if (w > 0 && i < colors.size - 1) {
                    paint.color = colors[i]
                    rect.set(x, 0f, x + w, height.toFloat())
                    canvas.drawRoundRect(rect, radius, radius, paint)
                }
                x += w
            }
        }
    }

    /** Simple vertical bar chart with optional labels under each bar. */
    private class BarChart(
        context: Context,
        private val values: List<Int>,
        private val labels: List<String>?,
        private val color: Int,
        private val labelColor: Int,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = labelColor
                textAlign = Paint.Align.CENTER
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, context.resources.displayMetrics)
            }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            if (values.isEmpty()) return
            val labelSpace = if (labels != null) textPaint.textSize * 1.6f else 0f
            val valueSpace = textPaint.textSize * 1.3f
            val chartHeight = height - labelSpace - valueSpace
            val max = values.max().coerceAtLeast(1)
            val slot = width.toFloat() / values.size
            val barWidth = slot * 0.7f
            values.forEachIndexed { i, v ->
                val cx = slot * i + slot / 2
                val h = chartHeight * v / max
                val top = valueSpace + chartHeight - h
                paint.color = color
                rect.set(cx - barWidth / 2, top, cx + barWidth / 2, valueSpace + chartHeight)
                canvas.drawRoundRect(rect, 4f, 4f, paint)
                // counts on the short charts only; 30 bars would be cluttered
                if (labels != null && v > 0) canvas.drawText(v.toString(), cx, top - 4f, textPaint)
                labels?.getOrNull(i)?.let { canvas.drawText(it, cx, height - textPaint.textSize * 0.3f, textPaint) }
            }
        }
    }
}
