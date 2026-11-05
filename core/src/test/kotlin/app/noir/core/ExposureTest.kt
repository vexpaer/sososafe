package app.noir.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExposureTest {

    private fun app(perms: List<String>, endpoints: List<String>) = AppEntry(
        pkg = "com.example",
        uid = "1",
        isSystem = false,
        debuggable = false,
        installer = null,
        permissions = perms,
        endpoints = endpoints,
        score = 0
    )

    @Test
    fun `permissions map to data categories`() {
        val types = Exposure.dataTypes(
            listOf("ACCESS_FINE_LOCATION", "READ_CONTACTS", "RECORD_AUDIO", "QUERY_ALL_PACKAGES")
        )
        assertEquals(
            setOf(DataType.LOCATION, DataType.CONTACTS, DataType.MIC, DataType.APPS),
            types
        )
    }

    @Test
    fun `capability alone is not a leak`() {
        val entry = app(listOf("ACCESS_FINE_LOCATION"), listOf("https://api.example.com/config"))
        assertTrue(entry.leaks.isEmpty())
    }

    @Test
    fun `data is joined with upload hosts`() {
        val entry = app(
            listOf("ACCESS_FINE_LOCATION", "READ_CONTACTS"),
            listOf("https://a.example.com/upload", "https://b.example.com/collect")
        )
        val leaks = entry.leaks
        assertEquals(setOf(DataType.LOCATION, DataType.CONTACTS), leaks.keys)
        assertEquals(listOf("a.example.com", "b.example.com"), leaks.getValue(DataType.LOCATION))
    }

    @Test
    fun `host extraction strips scheme path and port`() {
        assertEquals(
            "tracking.example.com",
            Exposure.hostOf("https://tracking.example.com:8443/collect?x=1")
        )
    }
}
