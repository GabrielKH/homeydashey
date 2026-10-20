package app.homeydash.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** The wake judge, the sound fingerprints, the turn judge and the memory facts they feed. */
class ListeningTest {
    private val noon = ZonedDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneId.of("Europe/Stockholm"))

    @Test
    fun `the lines are where Wald puts them`() {
        val j = WakeJudge(alpha = 0.02, beta = 0.05)
        assertEquals(3.86, j.upper, 0.01)
        assertEquals(-2.98, j.lower, 0.01)
    }

    @Test
    fun `a known voice saying the name wakes it after two witnesses, the Whisper check is never asked`() {
        var whisperAsked = false
        val v = WakeJudge().judge(
            WakeJudge.prior(mediaPlaying = false, nobodyHome = false, faceKnown = false),
            listOf(
                WakeJudge.Witness { WakeJudge.spotter(withGreeting = true) },
                WakeJudge.Witness { WakeJudge.voice(margin = 0.2f, who = "Gabriel") },
                WakeJudge.Witness { whisperAsked = true; WakeJudge.whisper("hej nova", listOf("Nova")) },
            ),
        )
        assertTrue(v.line(), v.wake)
        assertFalse(whisperAsked)
        assertEquals(2, v.heard.size)
    }

    @Test
    fun `the TV saying the name in a room with media playing is dropped by the fingerprint without Whisper`() {
        var whisperAsked = false
        val v = WakeJudge().judge(
            WakeJudge.prior(mediaPlaying = true, nobodyHome = false, faceKnown = false),
            listOf(
                WakeJudge.Witness { WakeJudge.spotter(withGreeting = false) },
                WakeJudge.Witness { WakeJudge.voice(margin = -0.15f, who = null) },
                WakeJudge.Witness { WakeJudge.fingerprint(0.2) },
                WakeJudge.Witness { whisperAsked = true; WakeJudge.whisper("nova", listOf("Nova")) },
            ),
        )
        assertFalse(v.line(), v.wake)
        assertFalse(whisperAsked)
        assertTrue(v.line(), v.line().contains("fingerprint"))
    }

    @Test
    fun `an unclear hit goes to Whisper, and its word decides`() {
        fun run(text: String) = WakeJudge().judge(
            WakeJudge.prior(mediaPlaying = false, nobodyHome = false, faceKnown = false),
            listOf(
                WakeJudge.Witness { WakeJudge.spotter(withGreeting = false) },
                WakeJudge.Witness { null }, // no voiceprints taught
                WakeJudge.Witness { WakeJudge.fingerprint(0.5) },
                WakeJudge.Witness { WakeJudge.whisper(text, listOf("Nova", "TARS")) },
            ),
        )
        assertTrue(run("Hej Nova, tänd lampan").wake)
        assertFalse(run("no var det").wake)
        assertFalse(run("").wake)
    }

    @Test
    fun `undecided is dropped, but arms a lower bar for the repeat`() {
        val witnesses = listOf(WakeJudge.Witness { WakeJudge.spotter(withGreeting = false) })
        val first = WakeJudge().judge(0.5, witnesses)
        assertTrue(first.undecided)
        assertFalse(first.wake)
        assertTrue(WakeJudge().judge(0.5, witnesses, armed = true).wake)
    }

    @Test
    fun `nobody home and the tablet's own voice both keep it asleep`() {
        val quiet = WakeJudge().judge(
            WakeJudge.prior(mediaPlaying = false, nobodyHome = true, faceKnown = false),
            listOf(WakeJudge.Witness { WakeJudge.spotter(withGreeting = true) }, WakeJudge.Witness { WakeJudge.voice(0.0f, null) }),
        )
        assertFalse(quiet.line(), quiet.wake)
        val itself = WakeJudge().judge(0.5, listOf(WakeJudge.Witness { WakeJudge.spotter(true) }, WakeJudge.Witness { WakeJudge.itself() }))
        assertFalse(itself.wake)
        assertFalse(itself.undecided)
    }

    @Test
    fun `the sensitivity slider sets the accepted false-wake rate`() {
        assertEquals(0.005, MicSensitivity.alpha(0), 1e-6)
        assertEquals(0.05, MicSensitivity.alpha(100), 1e-6)
        assertTrue(MicSensitivity.alpha(50) in 0.015..0.017)
    }

    /** A note as an instrument plays it: many harmonics, a little vibrato and tremolo, plus the room's noise. */
    private fun tone(seconds: Double, hz: Double, amplitude: Double = 6000.0, seed: Int = 1, noise: Double = 200.0): ShortArray {
        val r = Random(seed)
        val n = (seconds * 16000).toInt()
        return ShortArray(n) { i ->
            val t = i / 16000.0
            val f = hz * (1 + 0.01 * sin(2 * PI * 5.5 * t))
            var v = 0.0
            for (k in 1..12) v += sin(2 * PI * k * f * t) / k
            val env = 0.7 + 0.3 * sin(2 * PI * 7.0 * t)
            (amplitude * env * v / 2.0 + r.nextDouble(-noise, noise)).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** A jingle: a few notes in a row, so the fingerprint has something to change over time. */
    private fun jingle(seed: Int, notes: List<Double> = listOf(440.0, 660.0, 550.0, 880.0, 330.0, 740.0)): ShortArray {
        val parts = notes.mapIndexed { i, hz -> tone(0.25, hz, seed = seed * 10 + i) }
        return ShortArray(parts.sumOf { it.size }).also { out ->
            var at = 0
            parts.forEach { p -> p.copyInto(out, at); at += p.size }
        }
    }

    @Test
    fun `the same jingle heard again matches, a different one does not`() {
        val a = SoundPrint.of(jingle(1))
        val again = SoundPrint.of(jingle(2)) // same notes, other noise
        val other = SoundPrint.of(jingle(3, listOf(880.0, 330.0, 740.0, 440.0, 660.0, 550.0)))
        assertTrue(a.size >= SoundPrint.MIN_FRAMES)
        assertTrue("%.2f".format(a.bitErrorRate(again)), a.same(again))
        assertTrue("%.2f".format(a.bitErrorRate(other)), a.bitErrorRate(other) > SoundPrint.SAME)
        val back = SoundPrint.decode(a.encode())
        assertNotNull(back)
        assertEquals(0.0, a.bitErrorRate(back!!), 1e-9)
        assertNull(SoundPrint.decode("not,hex,zz"))
    }

    @Test
    fun `the wait falls fast with the cues and scales with the person's habit`() {
        assertEquals(300L, TurnJudge.wait(5))
        assertEquals(3000L, TurnJudge.wait(0))
        assertTrue(TurnJudge.wait(3) in 600L..750L)
        assertTrue(TurnJudge.wait(1) in 1900L..2100L)
        assertTrue(TurnJudge.wait(2, habit = 1.5) > TurnJudge.wait(2))
        assertEquals(300L, TurnJudge.wait(5, habit = 1.6))
    }

    /** Speech-like sound: syllables of a tone with a loudness envelope, pitch sliding from [fromHz] to [toHz]. */
    private fun speech(seconds: Double, fromHz: Double, toHz: Double, syllablesPerSecond: Double, loudEnd: Double): ShortArray {
        val n = (seconds * 16000).toInt()
        var phase = 0.0
        return ShortArray(n) { i ->
            val t = i / 16000.0
            val share = t / seconds
            val hz = fromHz + (toHz - fromHz) * share
            phase += 2 * PI * hz / 16000
            val syllable = 0.55 + 0.45 * sin(2 * PI * syllablesPerSecond * t)
            val amplitude = 7000.0 * (1 - share) + 7000.0 * loudEnd * share
            // A few harmonics, so the pitch finder has a voice-like wave to work on.
            val wave = sin(phase) + 0.5 * sin(2 * phase) + 0.25 * sin(3 * phase)
            (amplitude * syllable * wave / 1.75).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun `a statement that falls and trails off is judged done, a level loud run is not`() {
        val done = TurnJudge.judge("Tänd lampan i köket.", "Nova", held = false, turn = speech(1.5, 180.0, 120.0, 4.0, loudEnd = 0.2))
        val going = TurnJudge.judge("och sen vill jag att", "Nova", held = false, turn = speech(1.5, 150.0, 150.0, 4.0, loudEnd = 1.0))
        assertTrue(done.line(), done.cues >= 4)
        assertTrue(going.line(), going.cues <= 2)
        assertTrue(done.waitMs < going.waitMs)
        assertTrue(done.line(), "trailing off" in done.found)
        assertTrue(done.line(), "falling or asking tone" in done.found)
        assertTrue(going.line(), "complete words" in going.missing)
    }

    @Test
    fun `a held sound and a question both count as they should`() {
        val held = TurnJudge.judge("Spela", "Nova", held = true, turn = speech(1.0, 150.0, 150.0, 4.0, 1.0))
        assertTrue(held.line(), "no held sound" in held.missing)
        val asking = TurnJudge.judge("Är lampan på?", "Nova", held = false, turn = speech(1.2, 120.0, 220.0, 4.0, 0.9))
        assertTrue(asking.line(), "falling or asking tone" in asking.found)
    }

    @Test
    fun `an ööö while searching for the words is waited through, never sent`() {
        for (thinking in listOf("Ööö…", "Äää", "Eeeh,", "Mmmm.", "Hmm, ööh", "Ö.")) {
            assertEquals(thinking, "", TurnEnd.clean(thinking))
            val j = TurnJudge.judge(thinking, "Nova", held = false, turn = speech(1.0, 150.0, 140.0, 4.0, 0.5))
            assertTrue(j.line(), j.waitMs >= 3000)
        }
        assertEquals("Tänd lampan", TurnEnd.clean("Äää, tänd lampan"))
        assertEquals("Om en timme", TurnEnd.clean("Om en timme"))
        assertFalse(TurnEnd.complete("Tänd, ööö"))
    }

    @Test
    fun `a known voice over its bar plus a greeting wakes it even when the margin is small`() {
        val v = WakeJudge(alpha = MicSensitivity.alpha(50)).judge(
            WakeJudge.prior(mediaPlaying = false, nobodyHome = false, faceKnown = false),
            listOf(WakeJudge.Witness { WakeJudge.spotter(withGreeting = true) }, WakeJudge.Witness { WakeJudge.voice(0.02f, "Gabriel") }),
        )
        assertTrue(v.line(), v.wake)
        assertEquals(-1.8, WakeJudge.voice(-0.15f, null)!!.lambda, 1e-6)
    }

    @Test
    fun `a false wake is remembered once, found again, and the oldest go after sixty`() {
        val print = SoundPrint.of(jingle(1))
        val fact = Memory.falseWake(print, "Kök", noon)!!
        var data = Memory.merge(MemoryData(), fact, 1L)
        assertEquals(1, data.facts.size)
        assertTrue(Memory.knownFalseWake(data.facts, SoundPrint.of(jingle(2))))
        assertFalse(Memory.knownFalseWake(data.facts, SoundPrint.of(jingle(3, listOf(523.0, 392.0, 311.0, 1047.0, 262.0, 466.0)))))
        assertEquals(1, Memory.falseWakes(data.facts).size)
        assertTrue(Memory.line(fact).startsWith("Listening woke falsely to a sound in Kök at 12:00"))
        // Another sound is another fact, not a merge into the first.
        data = Memory.merge(data, Memory.falseWake(SoundPrint.of(jingle(3, listOf(880.0, 330.0, 740.0, 440.0, 660.0, 550.0))), "Kök", noon)!!, 2L)
        assertEquals(2, data.facts.size)
        for (i in 0 until 70) {
            val p = SoundPrint(IntArray(20) { k -> (i * 7919 + k * 104729) })
            data = Memory.merge(data, Memory.falseWake(p, null, noon)!!, 10L + i)
        }
        data = Memory.trimFalseWakes(data)
        assertEquals(60, Memory.falseWakes(data.facts).size)
    }

    @Test
    fun `the pause habit is one fact with a running mean, read against the house`() {
        val gabriel = Asker("g", "Gabriel")
        val elise = Asker("e", "Elise")
        var data = Memory.merge(MemoryData(), Memory.pauseHabit(800, gabriel, noon), 1L)
        data = Memory.merge(data, Memory.pauseHabit(1200, gabriel, noon), 2L)
        val mine = data.facts.filter { it.who == "g" }
        assertEquals(1, mine.size)
        assertEquals(900L, mine[0].data.toLong())
        assertEquals("0.9 s between thoughts", mine[0].obj)
        assertEquals(2, mine[0].count)
        data = Memory.merge(data, Memory.pauseHabit(500, elise, noon), 3L)
        data = Memory.merge(data, Memory.pauseHabit(700, Asker("k", "Kim"), noon), 4L)
        assertTrue(Memory.pauseHabitOf(data.facts, "g") > 1.0)
        assertTrue(Memory.pauseHabitOf(data.facts, "e") < 1.0)
        assertEquals(1.0, Memory.pauseHabitOf(data.facts, "nobody"), 1e-9)
        assertEquals(1.0, Memory.pauseHabitOf(emptyList(), "g"), 1e-9)
    }

    @Test
    fun `the hour's share of wakes needs a few counted first, then favours the usual hours`() {
        var data = MemoryData()
        val morning = ZonedDateTime.of(2026, 10, 20, 7, 30, 0, 0, ZoneId.of("Europe/Stockholm"))
        assertEquals(1.0, Memory.wakeHourShare(data.facts, 7), 1e-9)
        repeat(25) { data = Memory.merge(data, Memory.woke(Asker("g", "Gabriel"), Persona.Nova, morning), it.toLong()) }
        assertTrue(Memory.wakeHourShare(data.facts, 7) > 2.0)
        assertEquals(0.2, Memory.wakeHourShare(data.facts, 3), 1e-9)
    }
}

class WakeKeywordsTest {
    private val tokens = WakeKeywords.tokensOf(javaClass.getResource("/kws-tokens.txt")!!.readText())

    @Test
    fun `the names become the spotter's tokens, with greetings and the ways they are said`() {
        assertEquals("▁HE Y ▁NO V A", WakeKeywords.encode("hey nova", tokens))
        assertEquals("▁TA R S", WakeKeywords.encode("TARS", tokens))
        assertEquals("▁HE J ▁NO V A", WakeKeywords.encode("hej Nova", tokens))
        val lines = WakeKeywords.keywords(listOf("Nova", "TARS"), tokens)
        assertTrue(lines.toString(), "▁NO V A :2.0 #0.15 @n0" in lines)
        assertTrue(lines.toString(), "▁HE Y ▁TA R S :2.0 #0.15 @g1" in lines)
        // The way the model itself writes TARS is there too.
        assertTrue(lines.toString(), "▁HE Y ▁T AR S :2.0 #0.15 @g1" in lines)
        assertTrue(WakeKeywords.encodings("TARS", tokens).containsAll(listOf("▁TA R S", "▁T AR S")))
        assertTrue(lines.toString(), lines.any { it.startsWith("▁NOW A ") })
        assertTrue(lines.toString(), lines.any { it.startsWith("▁TA R Z ") })
        assertEquals(WakeKeywords.Tag(1, true), WakeKeywords.tag("@g1"))
        assertEquals(WakeKeywords.Tag(0, false), WakeKeywords.tag("▁NO V A @n0"))
        assertNull(WakeKeywords.tag("HELLO WORLD"))
        assertTrue(WakeKeywords.stream(lines).contains("/"))
    }
}

class SaidNameTest {
    /** A made-up word: a few vowel-like tones in a row, each a harmonic stack, with a little noise. */
    private fun word(pitches: List<Double>, formants: List<Double>, msEach: Int = 160, seed: Int = 1): ShortArray {
        val rng = java.util.Random(seed.toLong())
        val per = 16000 * msEach / 1000
        val out = ShortArray(per * pitches.size + 6400)
        for ((k, f0) in pitches.withIndex()) for (i in 0 until per) {
            val t = i / 16000.0
            var v = 0.0
            for (h in 1..12) {
                val f = f0 * h
                val gain = kotlin.math.exp(-kotlin.math.abs(f - formants[k]) / 150.0)
                v += gain * kotlin.math.sin(2 * Math.PI * f * t)
            }
            out[3200 + k * per + i] = (v * 3000 + rng.nextGaussian() * 60).toInt().coerceIn(-32768, 32767).toShort()
        }
        for (i in 0 until 3200) out[i] = (rng.nextGaussian() * 60).toInt().toShort()
        for (i in out.size - 3200 until out.size) out[i] = (rng.nextGaussian() * 60).toInt().toShort()
        return out
    }

    private val nova = listOf(500.0, 900.0, 1400.0, 700.0)
    private val other = listOf(2400.0, 300.0, 2000.0, 350.0)

    @Test
    fun `the name as taught is found again, said a little differently, inside other speech`() {
        val taught = (1..4).mapNotNull { SaidName.of(word(listOf(120.0, 125.0, 130.0, 110.0), nova, 160, seed = it)) }
        assertEquals(4, taught.size)
        val bar = SaidName.bar(taught)!!
        val said = word(listOf(120.0, 125.0, 130.0, 110.0) + listOf(150.0, 140.0, 145.0, 130.0), nova + other, 160, seed = 9)
        val found = SaidName.find(taught, SaidName.heard(said))!!
        assertTrue("%.3f vs %.3f".format(found.distance, bar), found.distance <= bar)
        val notIt = SaidName.find(taught, SaidName.heard(word(listOf(120.0, 125.0, 130.0, 110.0), other, 160, seed = 5)))!!
        assertTrue("%.3f vs %.3f".format(notIt.distance, bar), notIt.distance > bar)
    }

    @Test
    fun `a taught name survives saving, and the judge weighs it`() {
        val shape = SaidName.of(word(listOf(120.0, 125.0, 130.0, 110.0), nova))!!
        val back = SaidName.decode(SaidName.encode(shape))!!
        assertEquals(shape.size, back.size)
        assertTrue(SaidName.distance(shape, back)!!.distance < 0.02f)
        val person = KnownPerson("g", "Gabriel", Role.Owner, emptyList()).withSaid("Nova", listOf(shape, shape))
        assertEquals(2, person.saidFor("nova").size)
        assertEquals(3.5, WakeJudge.said(0.08f, 0.2f)!!.lambda, 1e-9)
        assertEquals(-1.5, WakeJudge.said(0.3f, 0.2f)!!.lambda, 1e-9)
        assertNull(WakeJudge.said(null, 0.2f))
        val woke = WakeJudge(alpha = MicSensitivity.alpha(50)).judge(
            0.5,
            listOf(WakeJudge.Witness { WakeJudge.said(0.1f, 0.2f) }, WakeJudge.Witness { WakeJudge.voice(0.05f, "Gabriel") }),
        )
        assertTrue(woke.line(), woke.wake)
        assertEquals(100, WakeJudge.share(woke, WakeJudge(alpha = MicSensitivity.alpha(50))))
    }
}
