package app.sososafe.core

object RiskEngine {
    fun evaluate(app:AppRecord):AppRecord {
        val findings=PermissionRules.audit(app.permissions).toMutableList()
        findings+=app.analysis.findings
        val trackers=app.analysis.endpoints.filter { it.tracker }
        if(trackers.isNotEmpty()) findings+=Finding("network:static-trackers","Known tracking service references",Severity.MEDIUM,minOf(15,trackers.map { it.provider }.distinct().size*3),Evidence("DEX strings + offline domain rules",trackers.joinToString { it.host },EvidenceLevel.STATIC_REFERENCE),"Compare with live connections. A reference does not prove the app contacted it.")
        if(app.sharedUid!=null) findings+=Finding("uid:shared","UID shared with other packages",Severity.LOW,3,Evidence("PackageManager / dumpsys",app.sharedUid,EvidenceLevel.CAPABILITY),"UID-based network attribution applies to every package sharing this UID.")
        val links=app.permissions.asSequence().filter { it.group !in setOf("Other","Notifications","Bluetooth","Nearby Devices","Overlay") }.flatMap { p ->
            app.analysis.endpoints.asSequence().map { ep -> ExposureLink(p.group,p.name,p.granted,ep.host,ep.provider,EvidenceLevel.STATIC_REFERENCE,"Access capability and a static endpoint coexist. No data transmission or disclosure is proven.") }
        }.distinctBy { Triple(it.dataType,it.host,it.permission) }.take(501).toList()
        val unique=findings.distinctBy { it.id }
        // Cap each independent family so a long manifest cannot dominate the score.
        val components=linkedMapOf(
            "permissions (cap 35)" to minOf(35,unique.filter { it.id.startsWith("permission:") }.sumOf { it.points }),
            "manifest (cap 30)" to minOf(30,unique.filter { it.id.startsWith("manifest:") }.sumOf { it.points }),
            "dex (cap 20)" to minOf(20,unique.filter { it.id.startsWith("dex:") }.sumOf { it.points }),
            "other (cap 15)" to minOf(15,unique.filter { !it.id.startsWith("permission:") && !it.id.startsWith("manifest:") && !it.id.startsWith("dex:") }.sumOf { it.points }))
        return app.copy(findings=unique,exposures=links.take(500),riskScore=components.values.sum().coerceIn(0,100),scoreComponents=components,limitations=(app.limitations+if(links.size>500)listOf("Exposure correlation list capped at 500 pairs; underlying permission and endpoint records are retained.")else emptyList()).distinct())
    }
}
