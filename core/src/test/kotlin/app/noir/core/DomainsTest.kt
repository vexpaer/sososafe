/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainsTest {

    @Test
    fun `trackers are separated from functional endpoints`() {
        assertEquals(DomainClass.TRACKER, Domains.classify("applog.snssdk.com"))
        assertEquals(DomainClass.TRACKER, Domains.classify("tracking.miui.com"))
        assertEquals(DomainClass.TRACKER, Domains.classify("rttgpsreport.map.qq.com"))
        assertEquals(DomainClass.TRACKER, Domains.classify("stat.api.tpns.tencent.com"))

        assertEquals(DomainClass.FUNCTIONAL, Domains.classify("api.push.oppomobile.com"))
        assertEquals(DomainClass.FUNCTIONAL, Domains.classify("android.googleapis.com"))
        assertEquals(DomainClass.FUNCTIONAL, Domains.classify("play.googleapis.com"))
        assertEquals(DomainClass.FUNCTIONAL, Domains.classify("upload-data-api.xmpush.xiaomi.net"))
        assertEquals(DomainClass.FUNCTIONAL, Domains.classify("connect.rom.miui.com"))
        assertEquals(DomainClass.FUNCTIONAL, Domains.classify("statusapi.micloud.xiaomi.net"))

        assertEquals(DomainClass.REVIEW, Domains.classify("api.bilibili.com"))
        assertEquals(DomainClass.REVIEW, Domains.classify("instagram.com"))
        assertEquals(DomainClass.REVIEW, Domains.classify("api.sec.miui.com"))
        assertEquals(DomainClass.TRACKER, Domains.classify("stage-data.ads.example.com"))
        assertEquals(DomainClass.REVIEW, Domains.classify("staging-notify.sec.miui.com"))
    }

    @Test
    fun `status is not mistaken for stat`() {
        assertEquals(DomainClass.REVIEW, Domains.classify("status.example.com"))
    }

    @Test
    fun `infrastructure hosts and bare IPs are ignored`() {
        assertTrue(Domains.isIgnored("schemas.android.com"))
        assertTrue(Domains.isIgnored("www.w3.org"))
        assertTrue(Domains.isIgnored("127.0.0.1"))
        assertTrue(Domains.isIgnored("183.36.108.226"))
        assertFalse(Domains.isIgnored("api.bilibili.com"))
    }

    @Test
    fun `export splits tiers and drops test hosts`() {
        val app = AppEntry(
            pkg = "com.example",
            uid = "1",
            isSystem = false,
            debuggable = false,
            installer = null,
            permissions = listOf("INTERNET"),
            endpoints = listOf(
                "https://applog.snssdk.com/upload",
                "https://api.push.oppomobile.com/upload",
                "https://api.bilibili.com/upload",
                "https://log.testing.amap.com/upload",
                "http://127.0.0.1:8080/report"
            ),
            score = 0
        )

        val export = Domains.export(listOf(app))
        assertEquals(listOf("applog.snssdk.com"), export.trackers)
        assertEquals(listOf("api.bilibili.com"), export.review)
        assertEquals(listOf("api.push.oppomobile.com"), export.functional)
        assertTrue(export.trackers.none { it.contains("testing") })
    }
}
