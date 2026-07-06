package com.gameautopilot.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class InterruptionRecoveryTest {

    private fun mark(id: Int, label: String) =
        MarkBox(id = id, left = 0, top = 0, right = 10, bottom = 10, source = MarkSource.OCR, label = label)

    @Test
    fun `taps a mark whose label exactly matches a known dismiss keyword`() {
        val marks = listOf(mark(1, "Play"), mark(2, "Skip"), mark(3, "Store"))
        assertEquals(Action.TapMark(2), InterruptionRecovery.findRecoveryAction(marks))
    }

    @Test
    fun `matching is case-insensitive and trims whitespace`() {
        val marks = listOf(mark(1, "  CLOSE  "))
        assertEquals(Action.TapMark(1), InterruptionRecovery.findRecoveryAction(marks))
    }

    @Test
    fun `does not match a label that merely contains a keyword as a substring`() {
        // "Max" contains "x" but must not be treated as the "x" close-button keyword.
        val marks = listOf(mark(1, "Max"), mark(2, "Taxi Game"))
        assertEquals(Action.Back, InterruptionRecovery.findRecoveryAction(marks))
    }

    @Test
    fun `falls back to Back when no marks match any keyword`() {
        val marks = listOf(mark(1, "Play"), mark(2, "Settings"))
        assertEquals(Action.Back, InterruptionRecovery.findRecoveryAction(marks))
    }

    @Test
    fun `falls back to Back when there are no marks at all`() {
        assertEquals(Action.Back, InterruptionRecovery.findRecoveryAction(emptyList()))
    }

    @Test
    fun `blank labels are ignored rather than matched`() {
        val marks = listOf(mark(1, ""), mark(2, "   "))
        assertEquals(Action.Back, InterruptionRecovery.findRecoveryAction(marks))
    }
}
