package com.jot.app.companion

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceTokenStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("device-token-store-test", Context.MODE_PRIVATE)
    private val store = DeviceTokenStore(context, prefs)

    @After fun cleanUp() { prefs.edit().clear().commit() }

    @Test fun migratesLegacyCredentialsAndUsesAFreshIvForEachWrite() {
        assertTrue("The app must be able to write its private preferences", prefs.edit().clear().putString("access_token", "test-fixture").commit())
        assertEquals("test-fixture", store.read())
        assertEquals(setOf("device_token_v1"), prefs.all.keys)
        val original = prefs.getString("device_token_v1", null)
        assertNotEquals("test-fixture", original)
        assertEquals("test-fixture", DeviceTokenStore(context, prefs).read())
        assertTrue(store.write("test-fixture"))
        assertNotEquals(original, prefs.getString("device_token_v1", null))
        store.clear()
        assertNull(store.read())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun detectsTamperingAndAllowsPairingAgain() {
        prefs.edit().clear().commit()
        assertTrue(store.write("initial-fixture"))
        val bytes = Base64.decode(checkNotNull(prefs.getString("device_token_v1", null)), Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        prefs.edit().putString("device_token_v1", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        assertNull(store.read())
        assertFalse(store.write("invalid token"))
        assertTrue(store.write("replacement-fixture"))
        assertEquals("replacement-fixture", store.read())
    }
}
