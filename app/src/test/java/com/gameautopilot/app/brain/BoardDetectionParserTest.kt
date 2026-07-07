package com.gameautopilot.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardDetectionParserTest {

    @Test
    fun `parses a found board`() {
        val detection = BoardDetectionParser.parse(
            """{"found":true,"rows":8,"cols":8,"leftPct":5,"topPct":20,"rightPct":95,"bottomPct":90}"""
        )
        assertEquals(8, detection?.rows)
        assertEquals(8, detection?.cols)
        assertEquals(5.0, detection?.leftPct)
        assertEquals(90.0, detection?.bottomPct)
    }

    @Test
    fun `returns null when not found or unparsable`() {
        assertNull(BoardDetectionParser.parse("""{"found":false}"""))
        assertNull(BoardDetectionParser.parse("not json at all"))
        assertNull(BoardDetectionParser.parse("""{"rows":8,"cols":8}"""))
    }

    @Test
    fun `strips markdown code fences before parsing`() {
        val detection = BoardDetectionParser.parse(
            "```json\n{\"found\":true,\"rows\":6,\"cols\":7,\"leftPct\":0,\"topPct\":0,\"rightPct\":100,\"bottomPct\":100}\n```"
        )
        assertEquals(6, detection?.rows)
        assertEquals(7, detection?.cols)
    }

    @Test
    fun `toBoardConfig rejects out-of-range detections`() {
        val bogus = BoardDetection(rows = 0, cols = 8, leftPct = 0.0, topPct = 0.0, rightPct = 100.0, bottomPct = 100.0)
        assertNull(bogus.toBoardConfig())

        val inverted = BoardDetection(rows = 8, cols = 8, leftPct = 90.0, topPct = 0.0, rightPct = 10.0, bottomPct = 100.0)
        assertNull(inverted.toBoardConfig())

        val valid = BoardDetection(rows = 8, cols = 8, leftPct = 5.0, topPct = 20.0, rightPct = 95.0, bottomPct = 90.0)
        val cfg = valid.toBoardConfig()
        assertEquals(8, cfg?.rows)
    }
}
