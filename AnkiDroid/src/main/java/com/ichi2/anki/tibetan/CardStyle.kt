/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: how study cards look. Copies the bundled fonts into the
 * collection's media folder (cards are rendered as web pages, so they need the
 * font files there) and sets the "Basic" note type's templates and CSS:
 * Noto Serif Tibetan for Tibetan script, Inter for English, word centred and
 * large, answer below a thin rule. Colours are left to the app's light/dark theme.
 *
 * Versioned: bump [VERSION] to re-apply after changing the style.
 */

package com.ichi2.anki.tibetan

import android.content.Context
import com.google.protobuf.kotlin.toByteString
import com.ichi2.anki.R
import com.ichi2.anki.libanki.Collection
import timber.log.Timber

object CardStyle {
    private const val VERSION = 2
    private const val CONFIG_VERSION = "tibetanCardStyleVersion"
    private const val NOTETYPE_NAME = "Basic"

    const val TIBETAN_FONT_FILE = "_NotoSerifTibetan.ttf"
    const val ENGLISH_FONT_FILE = "_Inter.ttf"

    const val FRONT_QFMT = "<div class=\"word\">{{Front}}</div>"
    const val FRONT_AFMT = "{{FrontSide}}\n\n<hr id=answer>\n\n<div class=\"answer\">{{Back}}</div>"
    const val BACK_QFMT = "<div class=\"word\">{{Back}}</div>"
    const val BACK_AFMT = "{{FrontSide}}\n\n<hr id=answer>\n\n<div class=\"answer\">{{Front}}</div>"

    private fun css(
        tibetanFont: String,
        englishFont: String,
    ) = """
        @font-face {
          font-family: "Tibetan Serif";
          src: url("$tibetanFont");
          unicode-range: U+0F00-0FFF;
          size-adjust: 105%;
        }
        @font-face {
          font-family: "Inter";
          src: url("$englishFont");
        }
        .card {
          font-family: "Tibetan Serif", "Inter", sans-serif;
          font-size: 24px;
          line-height: 1.6;
          text-align: center;
          color: black;
          background-color: white;
        }
        .word {
          font-size: 1.45em;
          font-weight: 500;
          margin: 22vh 1em 0;
        }
        .answer {
          font-size: 1.2em;
          margin: 0 1em;
          opacity: 0.9;
        }
        hr#answer {
          border: none;
          border-top: 1px solid rgba(128, 128, 128, 0.35);
          width: 40%;
          margin: 1.4em auto;
        }
        """.trimIndent()

    /** Idempotent; cheap once applied. */
    fun ensure(
        col: Collection,
        context: Context,
    ) {
        if ((col.config.get<Int>(CONFIG_VERSION) ?: 0) >= VERSION) return
        val nt = col.notetypes.byName(NOTETYPE_NAME) ?: return
        Timber.i("Applying Tibetan card style v%d", VERSION)

        val tibetanFont = addFont(col, context, R.font.noto_serif_tibetan, TIBETAN_FONT_FILE)
        val englishFont = addFont(col, context, R.font.inter, ENGLISH_FONT_FILE)

        nt.css = css(tibetanFont, englishFont)
        nt.templates[0].apply {
            qfmt = FRONT_QFMT
            afmt = FRONT_AFMT
        }
        if (nt.templates.length() > 1) {
            nt.templates[1].apply {
                qfmt = BACK_QFMT
                afmt = BACK_AFMT
            }
        }
        col.notetypes.save(nt)
        col.config.set(CONFIG_VERSION, VERSION)
    }

    /** @return the media filename actually used (the backend may rename on a clash) */
    private fun addFont(
        col: Collection,
        context: Context,
        resId: Int,
        name: String,
    ): String {
        val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
        return col.media.writeData(name, bytes.toByteString())
    }
}
