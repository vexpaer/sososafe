package app.sososafe.core

object PermissionRules {
    private val groups = linkedMapOf(
        "Location" to listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION"),
        "Contacts" to listOf("READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS"),
        "SMS" to listOf("READ_SMS", "SEND_SMS", "RECEIVE_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH"),
        "Call Logs" to listOf("READ_CALL_LOG", "WRITE_CALL_LOG", "PROCESS_OUTGOING_CALLS"),
        "Phone" to listOf("READ_PHONE_STATE", "READ_PHONE_NUMBERS", "READ_PRIVILEGED_PHONE_STATE", "CALL_PHONE", "ANSWER_PHONE_CALLS"),
        "Microphone" to listOf("RECORD_AUDIO", "CAPTURE_AUDIO_OUTPUT"), "Camera" to listOf("CAMERA"),
        "Calendar" to listOf("READ_CALENDAR", "WRITE_CALENDAR"),
        "Files" to listOf("MANAGE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_MEDIA_VISUAL_USER_SELECTED"),
        "Storage" to listOf("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"),
        "Nearby Devices" to listOf("NEARBY_WIFI_DEVICES", "UWB_RANGING"),
        "Bluetooth" to listOf("BLUETOOTH", "BLUETOOTH_ADMIN", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE"),
        "Notifications" to listOf("POST_NOTIFICATIONS", "BIND_NOTIFICATION_LISTENER_SERVICE"),
        "Accessibility" to listOf("BIND_ACCESSIBILITY_SERVICE"),
        "Usage Stats" to listOf("PACKAGE_USAGE_STATS"), "Overlay" to listOf("SYSTEM_ALERT_WINDOW"),
        "Device Administrator" to listOf("BIND_DEVICE_ADMIN"),
        "Screen Capture" to listOf("FOREGROUND_SERVICE_MEDIA_PROJECTION", "CAPTURE_VIDEO_OUTPUT", "CAPTURE_SECURE_VIDEO_OUTPUT"),
        "App Inventory" to listOf("QUERY_ALL_PACKAGES")
    )
    val special = setOf("SYSTEM_ALERT_WINDOW", "PACKAGE_USAGE_STATS", "MANAGE_EXTERNAL_STORAGE", "REQUEST_INSTALL_PACKAGES", "BIND_ACCESSIBILITY_SERVICE", "BIND_NOTIFICATION_LISTENER_SERVICE", "BIND_DEVICE_ADMIN", "FOREGROUND_SERVICE_MEDIA_PROJECTION")
    fun group(name: String): String = groups.entries.firstOrNull { name.substringAfterLast('.') in it.value }?.key ?: "Other"
    fun protection(base: Int): String = when (base and 15) { 0 -> "normal"; 1 -> "dangerous"; 2, 3 -> "signature/system"; 4 -> "internal"; else -> "unknown" }
    fun granted(flags: Int?): Boolean? = flags?.let { it and 2 != 0 }
    fun audit(permissions: List<PermissionFact>): List<Finding> = permissions.filter { it.group != "Other" }.map {
        val active = it.granted == true || it.specialState == "enabled"
        val weight = when(it.group) { "SMS", "Call Logs", "Accessibility", "Device Administrator", "Screen Capture" -> 7; "Location", "Contacts", "Microphone", "Camera", "Files" -> 5; else -> 2 }
        Finding("permission:${it.name}", "${it.group}: ${if(active) "granted/enabled" else if(it.granted == false) "declared, not granted" else "state unknown"}", if(active) Severity.MEDIUM else Severity.LOW,
            if(active) weight else 1, Evidence(it.evidence, "${it.name}; declared=${it.declared}; granted=${it.granted}; AppOps=${it.appOp}; special=${it.specialState}", EvidenceLevel.CAPABILITY), "Review whether this access is needed. A declaration alone is not active access.")
    }
}
