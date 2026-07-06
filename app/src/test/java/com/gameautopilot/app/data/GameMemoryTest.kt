package com.gameautopilot.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMemoryTest {

    @Test
    fun `default memory is blank`() {
        assertTrue(GameMemory().isBlank())
    }

    @Test
    fun `any populated field makes it non-blank`() {
        assertTrue(GameMemory(goal = "win").isBlank().not())
        assertTrue(GameMemory(unlocks = listOf("level 2")).isBlank().not())
        assertTrue(GameMemory(notes = "be careful").isBlank().not())
    }

    @Test
    fun `blank memory renders the fresh-start placeholder`() {
        assertEquals("(none yet — this is a fresh start)", GameMemory().toPromptText())
    }

    @Test
    fun `populated memory renders all present sections`() {
        val text = GameMemory(
            goal = "collect ready buildings",
            unlocks = listOf("bakery", "market"),
            notes = "don't spend diamonds"
        ).toPromptText()
        assertTrue(text.contains("Current goal: collect ready buildings"))
        assertTrue(text.contains("Unlocked so far: bakery, market"))
        assertTrue(text.contains("Notes: don't spend diamonds"))
    }

    @Test
    fun `partial memory only renders the fields that are set`() {
        val text = GameMemory(goal = "win").toPromptText()
        assertTrue(text.contains("Current goal: win"))
        assertTrue(text.contains("Unlocked so far:").not())
        assertTrue(text.contains("Notes:").not())
    }
}
