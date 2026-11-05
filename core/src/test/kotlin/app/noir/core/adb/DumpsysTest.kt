/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DumpsysTest {

    private fun dump(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun `merges duplicate package blocks`() {
        val text = dump(
            "  Package [com.example.app] (aaa):",
            "    appId=10200",
            "    flags=[ HAS_CODE ]",
            "    installerPackageName=null",
            "  Package [com.example.app] (bbb):",
            "    versionName=1.2.3",
            "    android.permission.INTERNET: granted=true"
        )

        val meta = Dumpsys.parse(text).getValue("com.example.app")
        assertTrue(meta.perms.contains("INTERNET"))
        assertEquals("10200", meta.appId)
        assertFalse(meta.isSystem)
    }

    @Test
    fun `inherits permissions from shared uid when the package block has none`() {
        val text = dump(
            "  Package [com.miui.securitycenter] (abc):",
            "    appId=1000",
            "    sharedUser=SharedUserSetting{def android.uid.system/1000}",
            "    flags=[ SYSTEM HAS_CODE ]",
            "  Package [com.example.userapp] (123):",
            "    appId=10500",
            "    flags=[ HAS_CODE ]",
            "",
            "Shared users:",
            "  SharedUser [android.uid.system] (def):",
            "    appId=1000",
            "    Packages",
            "      PackageSetting{abc com.miui.securitycenter/1000}",
            "    install permissions:",
            "      android.permission.INTERNET: granted=true",
            "      android.permission.QUERY_ALL_PACKAGES: granted=true",
            "      android.permission.READ_SMS: granted=true"
        )

        val parsed = Dumpsys.parse(text)
        val security = parsed.getValue("com.miui.securitycenter")
        assertTrue(security.perms.contains("INTERNET"), "shared perm must be merged in")
        assertTrue(security.perms.contains("QUERY_ALL_PACKAGES"))
        assertTrue(security.isSystem)

        val userApp = parsed.getValue("com.example.userapp")
        assertFalse(userApp.perms.contains("INTERNET"), "shared perms must not leak to other uids")
        assertFalse(userApp.isSystem)
    }

    @Test
    fun `marks packages created only by the shared section as system when appId is 1000`() {
        val text = dump(
            "Shared users:",
            "  SharedUser [android.uid.system] (def):",
            "    appId=1000",
            "    Packages",
            "      PackageSetting{abc com.only.in.shared/1000}",
            "    install permissions:",
            "      android.permission.INTERNET: granted=true"
        )

        val meta = Dumpsys.parse(text).getValue("com.only.in.shared")
        assertTrue(meta.isSystem)
        assertTrue(meta.perms.contains("INTERNET"))
        assertEquals("1000", meta.appId)
    }

    @Test
    fun `parses runtime permissions that carry a flags suffix`() {
        val text = dump(
            "  Package [com.example] (a):",
            "    appId=1001",
            "    android.permission.INTERNET: granted=true",
            "    android.permission.ACCESS_FINE_LOCATION: granted=true, flags=[ USER_SENSITIVE_WHEN_GRANTED]",
            "    android.permission.CAMERA: granted=true; flags=[ USER_SET]",
            "    android.permission.RECORD_AUDIO: granted=false, flags=[ USER_SET]"
        )

        val meta = Dumpsys.parse(text).getValue("com.example")
        assertTrue(meta.perms.contains("INTERNET"))
        assertTrue(meta.perms.contains("ACCESS_FINE_LOCATION"), "runtime permission with ', flags' must be kept")
        assertTrue(meta.perms.contains("CAMERA"), "runtime permission with '; flags' must be kept")
        assertFalse(meta.perms.contains("RECORD_AUDIO"), "granted=false must be ignored")
    }

    @Test
    fun `ignores ungranted permissions`() {
        val text = dump(
            "  Package [com.example.app] (aaa):",
            "    appId=10200",
            "    flags=[ HAS_CODE ]",
            "    android.permission.RECORD_AUDIO: granted=false",
            "    android.permission.INTERNET: granted=true"
        )

        val meta = Dumpsys.parse(text).getValue("com.example.app")
        assertTrue(meta.perms.contains("INTERNET"))
        assertFalse(meta.perms.contains("RECORD_AUDIO"))
    }
}
