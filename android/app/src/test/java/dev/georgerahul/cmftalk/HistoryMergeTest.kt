package dev.georgerahul.cmftalk

import org.junit.Assert.assertEquals
import org.junit.Test

/** Time on both devices counts once; the phone's own noise mode record wins. */
class HistoryMergeTest {
    private fun e(t: Long, k: Char, v: Int) = History.Event(t, k, v)

    @Test fun overlapCountsOnce() {
        val now = System.currentTimeMillis()
        val h = 3600_000L
        val start = now - 3 * h
        // Phone: connected 3 h ago to 1 h ago, ANC. Mac: connected 2 h ago to now, Transparency.
        val phone = listOf(e(start, 'C', 1), e(start, 'A', 1), e(start + 2 * h, 'C', 0))
        val mac = listOf(e(start + h, 'C', 1), e(start + h, 'A', 7), e(now, 'C', 0))
        val hv = HistoryView(listOf(phone, mac), now)
        assertEquals(3 * h, hv.usage.sum() )
        val anc = hv.spans.filter { it.mode == ModeGroup.ANC }.sumOf { it.end - it.start }
        val tr = hv.spans.filter { it.mode == ModeGroup.TRANSPARENCY }.sumOf { it.end - it.start }
        assertEquals(2 * h, anc)
        assertEquals(1 * h, tr)
    }
}
