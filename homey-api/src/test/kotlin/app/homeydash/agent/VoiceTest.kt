package app.homeydash.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class VoiceTest {
    @Test
    fun `filterbank matches the reference features`() {
        // Same signal and numbers as a numpy copy of Kaldi's filterbank.
        val x = ShortArray(8000) { k ->
            (8000 * sin(2 * PI * (200 + 0.05 * k) * k / 16000) + 3000 * sin(2 * PI * 1234 * k / 16000)).toInt().toShort()
        }
        val f = Fbank.features(x)
        assertEquals(48, f.size)
        assertEquals(80, f[0].size)
        assertEquals(3.7416f, f[0][0], 2e-3f)
        assertEquals(4.4487f, f[0][10], 2e-3f)
        assertEquals(-0.1144f, f[10][40], 2e-3f)
        assertEquals(-0.3222f, f[30][79], 2e-3f)
        assertEquals(-2.9584f, f[47][5], 2e-3f)
        assertEquals(0, Fbank.features(ShortArray(100)).size)
    }

    private fun near(base: FloatArray, random: Random, noise: Float) =
        VoiceId.normalize(FloatArray(base.size) { base[it] + random.nextFloat() * noise - noise / 2 })

    @Test
    fun `voices are told apart, strangers are not anyone`() {
        val random = Random(1)
        val gabriel = FloatArray(256) { random.nextFloat() - 0.5f }
        val anna = FloatArray(256) { random.nextFloat() - 0.5f }
        val people = listOf(
            KnownPerson("g", "Gabriel", Role.Owner, emptyList(), print = List(8) { near(gabriel, random, 1.6f) }),
            KnownPerson("a", "Anna", Role.Family, emptyList(), print = List(8) { near(anna, random, 1.6f) }),
            KnownPerson("v", "Old voice only", Role.Family, emptyList()),
        )
        repeat(10) {
            assertEquals("g", VoiceId.match(near(gabriel, random, 1.6f), people)?.id)
            assertEquals("a", VoiceId.match(near(anna, random, 1.6f), people)?.id)
        }
        val stranger = FloatArray(256) { random.nextFloat() - 0.5f }
        assertNull(VoiceId.match(VoiceId.normalize(stranger), people))
        assertTrue(VoiceId.margin(near(gabriel, random, 1.6f), people[0]) > 0f)
        assertEquals(-1f, VoiceId.margin(stranger, people[2]))
        val bar = VoiceId.bar(people[0].print)
        assertTrue(bar in 0.36f..0.6f)
        assertEquals(VoiceId.DEFAULT_BAR, VoiceId.bar(people[0].print.take(3)))
        assertEquals(VoiceId.MAX_PRINTS, VoiceId.with(List(29) { gabriel }, List(5) { anna }).size)
    }

    @Test
    fun `the wake name is spotted among other words`() {
        fun words(vararg w: Pair<String, Double>) = w.map { WakeName.Word(it.first, it.second) }
        assertTrue(WakeName.spot(words("hey" to 1.0, "nova" to 0.9), "Nova", 0.7))
        assertTrue(WakeName.spot(words("the" to 0.5, "hey" to 0.9, "nova" to 0.8, "play" to 0.6), "Nova", 0.7))
        assertFalse(WakeName.spot(words("hey" to 1.0, "nova" to 0.5), "Nova", 0.7))
        assertFalse(WakeName.spot(words("nova" to 1.0, "scotia" to 1.0), "Nova", 0.7))
        assertFalse(WakeName.spot(words("hey" to 1.0, "noah" to 1.0), "Nova", 0.7))
        assertTrue(WakeName.spot(words("hey" to 1.0, "big" to 0.9, "max" to 0.9), "Big Max", 0.7))
        assertFalse(WakeName.spot(emptyList(), "  ", 0.7))
    }

    @Test
    fun `wake sounds are short, loud enough and never clip`() {
        WakeSound.entries.forEach { sound ->
            val pcm = WakeSounds.render(sound)
            if (sound == WakeSound.Off) {
                assertEquals(0, pcm.size)
                return@forEach
            }
            val ms = pcm.size * 1000 / WakeSounds.RATE
            assertTrue("$sound lasts $ms ms", ms in 300..700)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("$sound peaks at $peak", peak in 8000..32767)
            // Starts and ends quietly, so it doesn't click.
            assertTrue(abs(pcm.first().toInt()) < 2000 && abs(pcm.last().toInt()) < 2000)
        }
        assertSame(WakeSound.Hum, AgentSettings().wakeSound)
    }

    @Test
    fun `float wav from a voice server is read`() {
        val rate = 24000
        val n = 100
        val b = java.nio.ByteBuffer.allocate(44 + n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(1).putInt(rate).putInt(rate * 4).putShort(4).putShort(32)
        b.put("data".toByteArray()).putInt(n * 4)
        repeat(n) { b.putFloat(if (it == 10) 0.5f else 0f) }
        val pcm = VoiceFx.readWav(b.array())!!
        assertEquals(rate, pcm.sampleRate)
        assertEquals(n, pcm.samples.size)
        assertEquals(16383, pcm.samples[10].toInt())
        assertEquals("http://10.0.0.5:8090", AgentSettings(local = Connection(Provider.Ollama, "http://10.0.0.5:11434/v1", "", "q")).voiceServerUrlOrGuess())
        assertEquals("", AgentSettings().voiceServerUrlOrGuess())
    }

    @Test
    fun `answers are cut into sentences, short ones riding along`() {
        assertEquals(
            listOf("Done. The kitchen lights are off now.", "Humor setting at seventy-five percent, as usual."),
            Sentences.split("Done. The kitchen lights are off now. Humor setting at seventy-five percent, as usual."),
        )
        assertEquals(listOf("Okay."), Sentences.split("Okay."))
        assertEquals(listOf("The heating is on, and the window is open. Bold."), Sentences.split("The heating is on, and the window is open. Bold."))
    }

    @Test
    fun `each character's name keeps its own voiceprints`() {
        val v = FloatArray(4) { 1f }
        val old = KnownPerson("g", "Gabriel", Role.Owner, emptyList())
        val p = old.withPrints("Nova", listOf(v)).withPrints("TARS", listOf(v, v))
        assertEquals(1, p.printsFor("nova").size)
        assertEquals(2, p.printsFor("Hey TARS").size)
        assertEquals(2, p.forWake("Tars").print.size)
        assertTrue(p.forWake("Max").print.isEmpty())
    }

    @Test
    fun `switching characters swaps the wake name and keeps Nova's own`() {
        val kim = AgentSettings(wakeName = "Kim")
        val tars = kim.asCharacter(Persona.Tars)
        assertEquals("TARS", tars.wakeName)
        assertEquals("Kim", tars.nameOf(Persona.Nova))
        assertEquals("Kim", tars.asCharacter(Persona.Nova).wakeName)
        assertEquals("TARS", kim.nameOf(Persona.Tars))
        assertTrue(Agent(ChatClient(okhttp3.OkHttpClient())).rules(kim).contains("Your name is Kim."))
    }
}
