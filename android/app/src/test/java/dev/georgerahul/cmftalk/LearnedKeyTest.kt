package dev.georgerahul.cmftalk

import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone must turn names into the same keys as the Mac (Learned.swift), or synced picks won't match. */
class LearnedKeyTest {
    @Test fun titles() {
        val cases = mapOf(
            "Mr. Robot S1E3 | eps1.2_d3bug" to "mr robot", "Mr. Robot - Season 2 Episode 5" to "mr robot",
            "Mr Robot: eps2.4_m4ster-s1ave" to "mr robot", "Breaking Bad 3x07 One Minute" to "breaking bad",
            "One Sun One Moon (From \"Jailer 2\")" to "one sun one moon", "Kesariya (From \"Brahmastra\")" to "kesariya",
            "Stranger Things Ep. 4" to "stranger things", "Interstellar" to "interstellar", "Numb [Official Video]" to "numb",
        )
        for ((title, key) in cases) assertEquals(title, key, Learned.titleKey(title))
    }

    @Test fun artists() {
        assertEquals("anirudh ravichander", Learned.artistKey("Anirudh Ravichander, Super Subu and Heisenberg"))
        assertEquals("pritam", Learned.artistKey("Pritam & Arijit Singh"))
    }
}
