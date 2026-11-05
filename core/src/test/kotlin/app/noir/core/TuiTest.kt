/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuiTest {

    private fun app(pkg: String) = AppEntry(
        pkg = pkg,
        uid = "1000",
        isSystem = true,
        debuggable = false,
        installer = null,
        permissions = listOf("INTERNET", "READ_SMS", "QUERY_ALL_PACKAGES"),
        endpoints = listOf("https://tracking.example.com/track/v1"),
        score = 12,
        category = Category.TELEMETRY,
        vendor = "Xiaomi"
    )

    @Test
    fun `clip never exceeds the requested width`() {
        assertEquals("abc…", Tui.clip("abcdef", 4))
        assertTrue(Tui.visibleWidth(Tui.clip("a".repeat(200), 40)) <= 40)
    }

    @Test
    fun `clip counts wide characters as two columns`() {
        assertEquals("中文…", Tui.clip("中文中文", 6))
        assertEquals(5, Tui.visibleWidth(Tui.clip("中文中文", 6)))
    }

    @Test
    fun `visibleWidth ignores ansi escapes`() {
        assertEquals(3, Tui.visibleWidth("\u001b[38;2;1;2;3mabc\u001b[0m"))
    }

    @Test
    fun `dashboard lines fit the terminal`() {
        val output = Tui.dashboard(
            device = Device("serial", "device", "Pixel", "product"),
            apps = listOf(app("com.example.one"), app("com.example.two")),
            recommendations = listOf(
                Recommendation("com.example.one", ActionType.APPOP_PROJECT_MEDIA, "MED", "demo", true)
            ),
            android = "16",
            host = "test host",
            installed = 42
        )
        val limit = Tui.terminalColumns()
        output.lines().filter { it.isNotBlank() }.forEach { line ->
            assertTrue(Tui.visibleWidth(line) <= limit, "line wider than $limit: [$line]")
        }
    }

    @Test
    fun `dashboard renders in ascii mode without box glyphs`() {
        val output = Tui.dashboard(
            device = Device("s", "device", "Pixel", "p"),
            apps = listOf(app("com.example.one")),
            recommendations = emptyList(),
            android = "16",
            host = "h",
            installed = 1,
            ascii = true
        )
        assertTrue(output.contains("+"))
        assertTrue(!output.contains("╭"))
    }
}
