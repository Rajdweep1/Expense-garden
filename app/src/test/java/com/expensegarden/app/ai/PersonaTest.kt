package com.expensegarden.app.ai

import com.expensegarden.app.game.Tone
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTest {
    @Test fun `every tone carries every boundary`() {
        for (tone in Tone.values()) {
            val prompt = Persona.systemPrompt(tone)
            for (clause in Persona.BOUNDARIES) {
                assertTrue("$tone dropped boundary: $clause", prompt.contains(clause))
            }
        }
    }

    @Test fun `tones differ in voice`() {
        val prompts = Tone.values().map { Persona.systemPrompt(it) }
        assertTrue("tone presets must not be identical", prompts.toSet().size == Tone.values().size)
    }

    @Test fun `savage does not relax the boundaries`() {
        // The failure this guards: a "no holds barred" line in the SAVAGE preset that
        // contradicts the boundary list above it. Spec §7: only the tone changes.
        val savage = Persona.systemPrompt(Tone.SAVAGE).lowercase()
        for (forbidden in listOf("ignore the above", "no limits", "anything goes", "no rules")) {
            assertTrue("SAVAGE must not contain '$forbidden'", !savage.contains(forbidden))
        }
    }
}
