package com.expensegarden.app.game

/** Persona intensity (spec §7). The boundaries do not relax at SAVAGE — only the tone does.
 *  Stored as `Tone.name` in AiPrefs and in the `quip.tone` column, so these names are a
 *  persisted contract: renaming one requires a migration. */
enum class Tone { SHARP, SAVAGE, GENTLE }
