package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.expensegarden.app.game.Tone
import com.expensegarden.app.gate.Severity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuipToneTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: QuipRepository

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        repo = QuipRepository(db)
        runBlocking {
            db.quipDao().insertAll(
                listOf(
                    QuipEntity(severity = "BREACH", origin = "STATIC", tone = "SHARP", text = "static sharp", usedAt = null),
                    QuipEntity(severity = "BREACH", origin = "LLM", tone = "SAVAGE", text = "llm savage", usedAt = null),
                )
            )
        }
    }

    @After fun tearDown() = db.close()

    @Test fun picks_a_line_from_the_requested_tone_bucket() = runBlocking {
        assertEquals("llm savage", repo.pick(Severity.BREACH, Tone.SAVAGE))
    }

    @Test fun falls_back_to_the_STATIC_bank_when_a_tone_bucket_is_empty() {
        // GENTLE has no lines at all. The gate must still have something to say — that is
        // the guarantee that lets the whole AI layer fail without breaking the gate.
        runBlocking { assertEquals("static sharp", repo.pick(Severity.BREACH, Tone.GENTLE)) }
    }

    @Test fun marks_the_picked_line_used_so_the_next_pick_differs() = runBlocking {
        repo.pick(Severity.BREACH, Tone.SAVAGE)
        val row = db.quipDao().leastRecentlyUsed("BREACH", "SAVAGE")
        assertTrue("the picked line must be stamped used", row?.usedAt != null)
    }
}
