package com.autoclicker.chattask.core

/**
 * Makes UI labels comparable across Arabic spelling variants and invisible formatting. The emoji
 * variation selectors go too, as the same emoji can arrive with or without one. A joiner between
 * emoji stays, because it builds a different emoji. Between letters it is only formatting.
 */
object TextNormalizer {
    private val INVISIBLE = Regex(
        "[\\u0640\\u064B-\\u065F\\u0670\\u200B\\u200C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFE0E\\uFE0F\\uFEFF]" +
            "|(?<=\\p{L})\\u200D|\\u200D(?=\\p{L})",
    )
    private val SPACES = Regex("\\s+")

    fun normalize(text: CharSequence): String {
        val stripped = INVISIBLE.replace(text, "")
        val mapped = buildString(stripped.length) {
            for (ch in stripped) {
                append(
                    when (ch) {
                        'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                        'ى' -> 'ي'
                        else -> ch
                    },
                )
            }
        }
        return SPACES.replace(mapped, " ").trim().lowercase()
    }
}

/**
 * Whole-word keyword matching on short labels.
 *
 * Plain substring matching is unsafe here: "تم" is also inside "تمام" and "متمم", and any long
 * sentence may contain a keyword. A real button label is short and holds the word on its own.
 */
class KeywordSet(keywords: List<String>, private val maxLabelLength: Int = 24) {
    val raw: List<String> = keywords.filter { it.isNotBlank() }.distinctBy(TextNormalizer::normalize)
    private val normalized = raw.map { TextNormalizer.normalize(it) }.filter { it.isNotEmpty() }

    fun matches(label: CharSequence?): Boolean {
        if (label.isNullOrBlank()) return false
        val text = TextNormalizer.normalize(label)
        if (text.isEmpty() || text.length > maxLabelLength) return false
        if (text in normalized) return true
        val words = text.split(NON_WORD).filter { it.isNotEmpty() }.joinToString(" ", " ", " ")
        return normalized.any { " $it " in words }
    }

    private companion object {
        val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")
    }
}

/** Recognises a chat message box, including custom views that are not marked editable. */
object InputMatcher {
    private const val MAX_HINT_LENGTH = 40
    private val HINTS = listOf("رسالتك", "type a message").map(TextNormalizer::normalize)

    fun isMessageBox(className: CharSequence?, editable: Boolean, hint: CharSequence?, text: CharSequence?): Boolean =
        editable || className?.contains("EditText") == true || isHint(hint) || isHint(text)

    /** The length limit keeps a chat message that quotes the hint from counting as one. */
    fun isHint(label: CharSequence?): Boolean {
        if (label.isNullOrBlank() || label.length > MAX_HINT_LENGTH) return false
        val normalized = TextNormalizer.normalize(label)
        return HINTS.any { it in normalized }
    }
}

/** Recognises well-known resource-id naming for buttons that have no text. */
object ViewIdMatcher {
    private val CLOSE = Regex(
        "(^|[_.])(close|dismiss)(?![a-z])|(btn|button|ic|icon|img|iv|image)_?(close|dismiss)|" +
            "(close|dismiss)_?(btn|button|icon)",
        RegexOption.IGNORE_CASE,
    )
    private val SEND = Regex(
        "(^|[_.])send(?!er)|(btn|button|ic|icon|iv|image)_?send(?!er)|send_?(btn|button|icon)",
        RegexOption.IGNORE_CASE,
    )

    fun isClose(viewId: String?): Boolean = entryName(viewId)?.let { CLOSE.containsMatchIn(it) } == true

    fun isSend(viewId: String?): Boolean = entryName(viewId)?.let { SEND.containsMatchIn(it) } == true

    /** "com.app:id/btn_close" becomes "btn_close", so a package name can never cause a match. */
    private fun entryName(viewId: String?): String? =
        viewId?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
}
