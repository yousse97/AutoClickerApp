package com.autoclicker.chattask.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatcherTest {
    private val keywords = KeywordSet(TaskConfig.DEFAULT_POPUP_KEYWORDS)

    @Test
    fun `matches the exact dismiss labels`() {
        listOf("تم", "إغلاق", "Close", "DONE", "×", "✕").forEach {
            assertTrue(it, keywords.matches(it))
        }
    }

    @Test
    fun `matches arabic spelling and formatting variants`() {
        assertTrue(keywords.matches("اغلاق"))
        assertTrue(keywords.matches("إغلاق النافذة"))
        assertTrue(keywords.matches("إِغْلاق"))
        assertTrue(keywords.matches("‏تم‏"))
        assertTrue(keywords.matches("تـــم"))
        assertTrue(keywords.matches("تم ✓"))
    }

    @Test
    fun `does not match words that merely contain a keyword`() {
        listOf("تمام", "متمم", "تمت", "enclosed", "Abandoned", "closer").forEach {
            assertFalse(it, keywords.matches(it))
        }
    }

    @Test
    fun `does not match long sentences or empty labels`() {
        assertFalse(keywords.matches("تم تحديث شروط الخدمة الخاصة بنا اليوم"))
        assertFalse(keywords.matches(""))
        assertFalse(keywords.matches("   "))
        assertFalse(keywords.matches(null))
    }

    @Test
    fun `the default command is not treated as a dismiss label`() {
        assertFalse(keywords.matches(TaskConfig().command))
    }

    @Test
    fun `normalizer unifies alef forms and strips diacritics`() {
        assertEquals("ارسال", TextNormalizer.normalize("إرسال"))
        assertEquals("ارسل", TextNormalizer.normalize("أَرسِل"))
    }

    @Test
    fun `recognises close button ids`() {
        listOf("com.app:id/btn_close", "app:id/closeButton", "app:id/ic_dismiss", "app:id/close").forEach {
            assertTrue(it, ViewIdMatcher.isClose(it))
        }
    }

    @Test
    fun `ignores unrelated ids and package names`() {
        listOf("com.close.app:id/title", "app:id/enclosed_view", "app:id/closed_caption", null).forEach {
            assertFalse(it.toString(), ViewIdMatcher.isClose(it))
        }
    }

    @Test
    fun `recognises send ids but not sender ids`() {
        assertTrue(ViewIdMatcher.isSend("app:id/send"))
        assertTrue(ViewIdMatcher.isSend("app:id/btn_send"))
        assertTrue(ViewIdMatcher.isSend("app:id/sendButton"))
        assertFalse(ViewIdMatcher.isSend("app:id/sender_name"))
        assertFalse(ViewIdMatcher.isSend("com.send.app:id/title"))
    }
}
