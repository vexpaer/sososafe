/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportTest {

    @Test
    fun `json escapes quotes and control characters`() {
        val entry = AppEntry(
            pkg = "com.\"weird\"",
            uid = "1",
            isSystem = false,
            debuggable = false,
            installer = null,
            permissions = emptyList(),
            endpoints = listOf("https://a.example.com/x"),
            score = 0
        )
        val json = Report.json(Device("s", "device"), listOf(entry))
        assertTrue(json.contains("com.\\\"weird\\\""))
        assertTrue(json.contains("\"apps\""))
    }

    @Test
    fun `report includes reproducible environment details`() {
        val meta = ReportMeta(
            tool = "NOIR test",
            host = "macOS aarch64",
            android = "16",
            sdk = "36",
            build = "PLK110_16.0.0",
            fingerprint = "oneplus/PLK110:16/abc",
            user = "0",
            generatedAt = "2026-11-04T00:00:00Z"
        )
        val json = Report.json(Device("s", "device", "PLK110"), emptyList(), meta = meta)
        val md = Report.markdown(Device("s", "device", "PLK110"), emptyList(), meta = meta)
        assertTrue(json.contains("\"sdk\": \"36\""))
        assertTrue(json.contains("\"build\": \"PLK110_16.0.0\""))
        assertTrue(md.contains("- user: 0"))
        assertTrue(md.contains("PLK110_16.0.0"))
    }

    @Test
    fun `markdown contains the report table header`() {
        val entry = AppEntry(
            pkg = "com.example",
            uid = "1",
            isSystem = true,
            debuggable = false,
            installer = "com.android.vending",
            permissions = listOf("INTERNET"),
            endpoints = emptyList(),
            score = 3,
            category = Category.SYSTEM
        )
        val md = Report.markdown(Device("s", "device", "Pixel"), listOf(entry))
        assertTrue(md.startsWith("# NOIR report"))
        assertTrue(md.contains("| score | package |"))
        assertTrue(md.contains("`com.example`"))
    }
}
