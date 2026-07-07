package com.gameautopilot.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardConfigTest {

    private fun board(
        rows: Int = 8, cols: Int = 8,
        left: Float = 0f, top: Float = 0.2f, right: Float = 1f, bottom: Float = 0.9f
    ) = BoardConfig(rows, cols, left, top, right, bottom)

    @Test
    fun `valid board passes isValid`() {
        assertTrue(board().isValid())
    }

    @Test
    fun `rejects non-positive dimensions and inverted bounds`() {
        assertFalse(board(rows = 0).isValid())
        assertFalse(board(cols = 0).isValid())
        assertFalse(board(left = 0.5f, right = 0.5f).isValid())
        assertFalse(board(top = 0.5f, bottom = 0.2f).isValid())
    }

    @Test
    fun `cellCenter divides the board rect evenly`() {
        val b = BoardConfig(rows = 2, cols = 2, leftFrac = 0f, topFrac = 0f, rightFrac = 1f, bottomFrac = 1f)
        assertEquals(250 to 250, b.cellCenter(0, 0, 1000, 1000))
        assertEquals(750 to 250, b.cellCenter(0, 1, 1000, 1000))
        assertEquals(250 to 750, b.cellCenter(1, 0, 1000, 1000))
        assertEquals(750 to 750, b.cellCenter(1, 1, 1000, 1000))
    }

    @Test
    fun `cellCenter respects a non-full-screen board rect`() {
        // Board occupies the right half of the screen only, single cell.
        val b = BoardConfig(rows = 1, cols = 1, leftFrac = 0.5f, topFrac = 0f, rightFrac = 1f, bottomFrac = 1f)
        assertEquals(750 to 500, b.cellCenter(0, 0, 1000, 1000))
    }

    @Test
    fun `round-trips through json`() {
        val b = board()
        val parsed = BoardConfig.fromJson(b.toJson())
        assertEquals(b, parsed)
    }

    @Test
    fun `fromJson rejects null and invalid payloads`() {
        assertNull(BoardConfig.fromJson(null))
        assertNull(BoardConfig.fromJson(JSONObject("""{"rows":0,"cols":8}""")))
    }
}
