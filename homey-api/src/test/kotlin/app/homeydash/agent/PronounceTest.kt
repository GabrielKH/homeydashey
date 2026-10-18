package app.homeydash.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class PronounceTest {
    @Test fun `compound seams get a hyphen so the first vowel stays long`() {
        assertEquals("Jag tände tak-lampan.", Pronounce.sayable("Jag tände taklampan.", true))
        assertEquals("Tak-fläkten och sov-rummet", Pronounce.sayable("Takfläkten och sovrummet", true))
        assertEquals("mat-bordet, mat-salen, tak-lampor", Pronounce.sayable("matbordet, matsalen, taklampor", true))
    }

    @Test fun `other words and English are left alone`() {
        assertEquals("taktik, tack, matte, taket", Pronounce.sayable("taktik, tack, matte, taket", true))
        assertEquals("ceiling taklampan", Pronounce.sayable("ceiling taklampan", false))
        assertEquals("tak-lampan", Pronounce.sayable("tak-lampan", true))
    }
}
