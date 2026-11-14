package com.expensegarden.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpiPrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = context.getSharedPreferences("upi_prefs", Context.MODE_PRIVATE)

    @After fun teardown() = file.edit().clear().commit().let { }

    @Test fun a_saved_upi_app_reads_back_from_its_own_prefs_file() {
        UpiPrefs(context).personAppPackage = "com.phonepe.app"
        assertEquals("com.phonepe.app", UpiPrefs(context).personAppPackage)
        assertEquals("com.phonepe.app", file.all.values.single())
    }

    @Test fun nothing_saved_reads_null() = assertNull(UpiPrefs(context).personAppPackage)

    // "None" in Settings: the key is removed, not stored as an empty string.
    @Test fun clearing_removes_the_choice() {
        val prefs = UpiPrefs(context)
        prefs.personAppPackage = "com.phonepe.app"
        prefs.personAppPackage = null
        assertNull(UpiPrefs(context).personAppPackage)
        assertFalse(file.all.isNotEmpty())
    }
}
