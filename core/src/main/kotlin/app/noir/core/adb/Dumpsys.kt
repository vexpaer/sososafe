/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core.adb

data class PkgMeta(
    val flags: String,
    val appId: String,
    val installer: String?,
    val perms: Set<String>,
    val sharedUser: String? = null
) {
    val isSystem: Boolean get() = flags.contains("SYSTEM")
    val debuggable: Boolean get() = flags.contains("DEBUGGABLE")
}

/**
 * Parses `adb shell dumpsys package`.
 *
 * Two quirks matter:
 *  - the dump lists every package more than once (summary + details), so entries are merged;
 *  - packages sharing a UID (e.g. `android.uid.system`, which covers ~58 apps) carry their
 *    granted permissions in the trailing "Shared users" section, not in their own block.
 *    Ignoring that section silently hides the most privileged apps on the device.
 */
object Dumpsys {

    private val PKG = Regex("^  Package \\[([^\\]]+)]")
    private val SHARED_USER = Regex("^sharedUser=SharedUserSetting\\{[0-9a-f]+ ([^/]+)/")
    private val SHARED = Regex("^  SharedUser \\[([^\\]]+)]")
    private val PKG_SETTING = Regex("PackageSetting\\{[0-9a-f]+ ([^/]+)/")
    private val GRANTED = Regex("^android\\.permission\\.([A-Z0-9_]+): granted=true(?:[,;].*)?$")

    fun parse(text: String): Map<String, PkgMeta> {
        val acc = LinkedHashMap<String, Mutable>()
        val sharedPackages = HashMap<String, MutableList<String>>()
        val sharedPerms = HashMap<String, MutableSet<String>>()
        val sharedAppId = HashMap<String, String>()

        var current: Mutable? = null
        var currentShared: String? = null
        var inSharedSection = false

        for (line in text.lineSequence()) {
            if (line.startsWith("Shared users:")) {
                inSharedSection = true
                current = null
                currentShared = null
                continue
            }

            if (!inSharedSection) {
                val pkgMatch = PKG.find(line)
                if (pkgMatch != null) {
                    current = acc.getOrPut(pkgMatch.groupValues[1]) { Mutable() }
                    continue
                }
                val c = current ?: continue
                val s = line.trim()
                when {
                    s.startsWith("appId=") && c.appId.isEmpty() -> c.appId = s.removePrefix("appId=")
                    s.startsWith("flags=[") && c.flags.isEmpty() -> c.flags = s
                    s.startsWith("installerPackageName=") && c.installer == null -> {
                        val v = s.removePrefix("installerPackageName=")
                        c.installer = if (v == "null") null else v
                    }
                    s.startsWith("sharedUser=") && c.sharedUser == null ->
                        c.sharedUser = SHARED_USER.find(s)?.groupValues?.get(1)
                    else -> GRANTED.find(s)?.let { c.perms.add(it.groupValues[1]) }
                }
            } else {
                val sharedMatch = SHARED.find(line)
                if (sharedMatch != null) {
                    currentShared = sharedMatch.groupValues[1]
                    sharedPackages.getOrPut(currentShared) { mutableListOf() }
                    sharedPerms.getOrPut(currentShared) { mutableSetOf() }
                    continue
                }
                val shared = currentShared ?: continue
                val s = line.trim()

                when {
                    s.startsWith("appId=") -> sharedAppId[shared] = s.removePrefix("appId=")
                    else -> {
                        PKG_SETTING.find(s)?.let { sharedPackages.getValue(shared).add(it.groupValues[1]) }
                        GRANTED.find(s)?.let { sharedPerms.getValue(shared).add(it.groupValues[1]) }
                    }
                }
            }
        }

        sharedPackages.forEach { (shared, packages) ->
            val perms = sharedPerms[shared].orEmpty()
            val appId = sharedAppId[shared]
            packages.forEach { pkg ->
                val entry = acc.getOrPut(pkg) { Mutable() }
                entry.perms.addAll(perms)
                if (entry.appId.isEmpty() && appId != null) entry.appId = appId
                if (entry.flags.isEmpty() && appId == "1000") entry.flags = "SYSTEM"
            }
        }

        return acc.mapValues { PkgMeta(it.value.flags, it.value.appId, it.value.installer, it.value.perms, it.value.sharedUser) }
    }

    private class Mutable {
        var flags: String = ""
        var appId: String = ""
        var installer: String? = null
        var sharedUser: String? = null
        val perms: MutableSet<String> = HashSet()
    }
}
