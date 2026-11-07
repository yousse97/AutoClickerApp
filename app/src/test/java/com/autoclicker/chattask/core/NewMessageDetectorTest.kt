package com.autoclicker.chattask.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewMessageDetectorTest {
    private val detector = NewMessageDetector()

    private fun view(
        vararg bottoms: Int,
        place: String = "chat one",
        atEnd: Boolean? = true,
    ) = ChatView(place, atEnd, bottoms.toList(), viewBottom = 2_000, viewHeight = 1_600)

    private fun look(view: ChatView?, now: Long = 0) = detector.observe(view, now)

    @Test
    fun `text already on screen at Start is not new`() {
        assertFalse(look(view(600, 1_900)))
        assertFalse(look(view(600, 1_900)))
    }

    @Test
    fun `a message arriving at the bottom is new, and counts once`() {
        look(view(600))
        assertTrue(look(view(400, 1_900)))
        assertFalse(look(view(400, 1_900)))
    }

    @Test
    fun `a second arrival is new too`() {
        look(view(600))
        assertTrue(look(view(400, 1_900)))
        assertTrue(look(view(200, 900, 1_900)))
    }

    @Test
    fun `scrolling back through old messages never counts`() {
        look(view(1_900), 0)
        assertFalse(look(view(1_950, atEnd = false), 500))
        assertFalse(look(view(300, 1_950, atEnd = false), 1_000))
        assertFalse(look(view(300, 900, 1_950, atEnd = false), 3_000))
    }

    @Test
    fun `scrolling forward again to the bottom never counts`() {
        look(view(1_900), 0)
        look(view(1_000, atEnd = false), 500)
        look(view(1_400, atEnd = false), 3_000)
        assertFalse(look(view(1_000, 1_900), 4_000))
        assertFalse(look(view(1_000, 1_900), 5_000))
    }

    @Test
    fun `reaching the far end of the history never counts`() {
        look(view(1_900), 0)
        look(view(1_500, atEnd = false), 500)
        look(view(1_700, atEnd = false), 3_000)
        assertFalse(look(view(300, 700), 4_000))
    }

    @Test
    fun `old text coming into view at the top of a chat that is at its end does not count`() {
        look(view(1_000))
        assertFalse(look(view(300, 1_000)))
    }

    @Test
    fun `reopening a chat never counts`() {
        look(view(600))
        look(null)
        assertFalse(look(view(600, 1_900)))
        assertFalse(look(view(600, 1_900)))
    }

    @Test
    fun `returning to a page never counts`() {
        look(view(600, place = "chat one"))
        look(view(place = "chat two"))
        assertFalse(look(view(600, 1_900, place = "chat one")))
    }

    @Test
    fun `the keyboard closing and showing older text does not count`() {
        look(view(1_900, place = "chat one small"))
        assertFalse(look(view(600, 1_900, place = "chat one large")))
    }

    @Test
    fun `a chat whose position cannot be read never counts`() {
        look(view(600, atEnd = null))
        assertFalse(look(view(600, 1_900, atEnd = null)))
        assertFalse(look(view(600, 1_900)))
    }

    @Test
    fun `a brief move while a message lands still counts`() {
        look(view(600), 0)
        assertFalse(look(view(600, atEnd = false), 500))
        assertTrue(look(view(400, 1_900), 1_000))
    }

    @Test
    fun `leaving the end for longer than a moment starts over`() {
        look(view(600), 0)
        look(view(600, atEnd = false), 500)
        look(view(600, atEnd = false), 2_000)
        assertFalse(look(view(400, 1_900), 2_500))
    }

    @Test
    fun `the first look at a screen is not settled, which keeps a popup there from counting`() {
        look(view(600))
        assertFalse(detector.settled)
        look(view(600))
        assertTrue(detector.settled)
        look(null)
        assertFalse(detector.settled)
    }

    @Test
    fun `after a reply the chat starts over, so the reply itself never counts`() {
        look(view(600))
        detector.forget()
        assertFalse(look(view(400, 1_900)))
    }
}
