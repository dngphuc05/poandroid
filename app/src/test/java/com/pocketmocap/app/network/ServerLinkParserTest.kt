package com.pocketmocap.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerLinkParserTest {
    @Test
    fun parsesPocapQrPayload() {
        assertEquals(
            "http://192.168.1.20:8090",
            ServerLinkParser.parseServerUrl("pocap://link?server=http%3A%2F%2F192.168.1.20%3A8090"),
        )
    }

    @Test
    fun acceptsTypedIpAddress() {
        assertEquals(
            "http://192.168.1.20:8090",
            ServerLinkParser.parseServerUrl("192.168.1.20:8090"),
        )
    }

    @Test
    fun rejectsUnsupportedSchemes() {
        assertNull(ServerLinkParser.parseServerUrl("ftp://192.168.1.20:8090"))
    }
}
