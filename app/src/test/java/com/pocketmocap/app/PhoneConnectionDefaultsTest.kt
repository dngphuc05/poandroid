package com.pocketmocap.app

import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneConnectionDefaultsTest {
    @Test
    fun phoneDoesNotShipWithDeveloperServerAddress() {
        assertTrue(PhoneConnectionDefaults.INITIAL_SERVER_URL.isBlank())
    }
}
