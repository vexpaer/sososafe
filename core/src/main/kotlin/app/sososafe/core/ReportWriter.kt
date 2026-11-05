package app.sososafe.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

object ReportWriter {
    val json=Json { prettyPrint=true; encodeDefaults=true; ignoreUnknownKeys=true }
    fun render(report:AuditReport,format:String):String {
        if(format=="json") return json.encodeToString(report)
        require(format in setOf("md","txt"))
        fun clean(value:String)=value.replace("\r"," ").replace("\n"," ").replace("`","'").replace("#","\\#").replace("<","&lt;")
        return buildString {
            appendLine(if(format=="md") "# sososafe audit" else "sososafe audit")
            appendLine("Generated: ${Instant.ofEpochMilli(report.generatedAt)} | Mode: ${report.mode}")
            report.device.forEach { (k,v) -> appendLine("$k: ${clean(v)}") }
            report.boundaries.forEach { appendLine("- $it") }
            for(app in report.apps) {
                appendLine("\n${if(format=="md") "## " else ""}${clean(app.label)} (${app.packageName})")
                appendLine("UID=${app.uid}; version=${clean(app.version)}; system=${app.system}; target=${app.targetSdk}; risk=${app.riskScore}/100 (${app.risk}); scanned=${Instant.ofEpochMilli(app.scannedAt)}")
                appendLine("Score contributions after family caps: ${app.scoreComponents}; individual finding points below are pre-cap weights.")
                app.permissions.forEach { appendLine("Permission: ${it.name}; ${it.protection}; declared=${it.declared}; granted=${it.granted}; AppOps=${it.appOp}; special=${it.specialState}; source=${it.evidence}") }
                app.analysis.endpoints.forEach { appendLine("Endpoint: ${clean(it.value)}; ${it.provider}; ${it.category}; ${it.evidence.level}; source=${it.evidence.source}") }
                app.analysis.sdks.forEach { appendLine("SDK: ${it.name}; signature=${it.signature}; source=${it.source}") }
                app.analysis.manifest?.let { appendLine("Manifest: ${json.encodeToString(it)}") }
                app.findings.forEach { appendLine("Finding: ${it.title}; ${it.severity}; points=${it.points}; evidence=${clean(it.evidence.detail)}; level=${it.evidence.level}; source=${clean(it.evidence.source)}; advice=${it.recommendation}") }
                app.exposures.forEach { appendLine("Correlation: ${it.permission} -> ${it.dataType} -> ${it.host} -> ${it.provider}; ${it.level}; ${it.explanation}") }
                (app.limitations+app.analysis.limitations).forEach { appendLine("Limitation: ${clean(it)}") }
                app.appOps?.let { appendLine("AppOps: ${clean(it)}") }
            }
            appendLine("\nNetwork metadata")
            report.connections.forEach { appendLine(json.encodeToString(it)) }
            appendLine("\nAction history")
            report.actions.forEach { appendLine(json.encodeToString(it)) }
            appendLine("\nScan history")
            report.scans.forEach { appendLine(json.encodeToString(it)) }
        }
    }
}
