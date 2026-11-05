package app.sososafe.core

import kotlinx.serialization.Serializable

@Serializable enum class ScanMode { AUTO, STANDARD, SHIZUKU, ROOT }
@Serializable enum class EvidenceLevel { CAPABILITY, STATIC_REFERENCE, OBSERVED_CONNECTION, CONFIRMED_CONTENT }
@Serializable enum class Severity { INFO, LOW, MEDIUM, HIGH }
@Serializable data class Evidence(val source: String, val detail: String, val level: EvidenceLevel)
@Serializable data class PermissionFact(
    val name: String, val group: String, val protection: String,
    val declared: Boolean = true, val granted: Boolean? = null,
    val appOp: String? = null, val specialState: String? = null,
    val evidence: String = "PackageManager"
)
@Serializable data class Endpoint(
    val host: String, val value: String, val provider: String,
    val category: String, val tracker: Boolean, val evidence: Evidence
)
@Serializable data class SdkFinding(val name: String, val category: String, val signature: String, val source: String)
@Serializable data class Finding(val id: String, val title: String, val severity: Severity, val points: Int, val evidence: Evidence, val recommendation: String)
@Serializable data class Component(val kind: String, val name: String, val exported: Boolean, val permission: String?, val authorities: String? = null,val readPermission:String?=null,val writePermission:String?=null,val pathProtected:Boolean=false)
@Serializable data class ManifestResult(
    val packageName: String = "", val permissions: List<String> = emptyList(),
    val targetSdk: Int? = null, val allowBackup: Boolean? = null,
    val debuggable: Boolean? = null, val cleartext: Boolean? = null,
    val networkSecurityConfig: String? = null, val components: List<Component> = emptyList()
)
@Serializable data class ExposureLink(val dataType: String, val permission: String, val granted: Boolean?, val host: String, val provider: String, val level: EvidenceLevel, val explanation: String)
@Serializable data class StaticResult(
    val manifest: ManifestResult? = null, val endpoints: List<Endpoint> = emptyList(),
    val sdks: List<SdkFinding> = emptyList(), val findings: List<Finding> = emptyList(),
    val filesAnalyzed: List<String> = emptyList(), val limitations: List<String> = emptyList()
)
@Serializable data class AppRecord(
    val packageName: String, val label: String, val version: String = "", val uid: Int = -1,
    val system: Boolean = false, val installer: String? = null, val targetSdk: Int = 0,
    val enabled: Boolean = true, val sharedUid: String? = null,
    val permissions: List<PermissionFact> = emptyList(), val analysis: StaticResult = StaticResult(),
    val findings: List<Finding> = emptyList(), val exposures: List<ExposureLink> = emptyList(),
    val riskScore: Int = 0, val scannedAt: Long = System.currentTimeMillis(),
    val mode: ScanMode = ScanMode.STANDARD, val imported: Boolean = false,
    val limitations: List<String> = emptyList(), val appOps: String? = null
) { val risk: Severity get() = when { riskScore >= 60 -> Severity.HIGH; riskScore >= 30 -> Severity.MEDIUM; riskScore > 0 -> Severity.LOW; else -> Severity.INFO } }
@Serializable data class ScanRecord(val id: String, val startedAt: Long, val finishedAt: Long?, val mode: ScanMode, val status: String, val count: Int, val error: String? = null)
@Serializable data class ConnectionRecord(
    val id: String, val firstSeen: Long, val lastSeen: Long, val protocol: String,
    val localIp: String, val localPort: Int, val remoteIp: String, val remotePort: Int,
    val uid: Int? = null, val packages: List<String> = emptyList(),
    val attribution: String = "UNKNOWN", val domain: String? = null,
    val provider: String = "Unknown", val category: String = "Unknown", val tracker: Boolean = false,
    val uploaded: Long = 0, val downloaded: Long = 0, val packets: Long = 0,
    val connections: Int = 0, val dnsQueries: List<String> = emptyList(), val state: String = "OBSERVED",
    val domainEvidence:String?=null
)
@Serializable data class ActionRecord(
    val id: String, val packageName: String, val action: String, val argument: String = "",
    val original: String, val desired: String, val createdAt: Long,
    val result: String, val reversible: Boolean, val rolledBack: Boolean = false,
    val mode: ScanMode = ScanMode.STANDARD, val userId: Int = 0
)
@Serializable data class AuditReport(
    val schemaVersion: Int = 1, val product: String = "sososafe", val generatedAt: Long,
    val device: Map<String, String>, val mode: ScanMode, val apps: List<AppRecord>,
    val scans: List<ScanRecord>, val connections: List<ConnectionRecord>, val actions: List<ActionRecord>,
    val boundaries: List<String> = listOf("Static references do not prove execution or disclosure.", "Connections prove metadata only; HTTPS payloads are not decrypted.", "Encrypted DNS, ECH, pinned TLS and other VPN traffic may hide hostnames.", "Risk scores are explainable review priorities, not malware verdicts.")
)
data class Capabilities(val mode: ScanMode, val available: Boolean, val authorized: Boolean, val details: String)
object ModePolicy {
    fun select(preferred: ScanMode, root: Boolean, shizuku: Boolean): ScanMode = when(preferred) {
        ScanMode.AUTO -> when { root -> ScanMode.ROOT; shizuku -> ScanMode.SHIZUKU; else -> ScanMode.STANDARD }
        ScanMode.ROOT -> if(root) ScanMode.ROOT else throw IllegalStateException("Root authorization required")
        ScanMode.SHIZUKU -> if(shizuku) ScanMode.SHIZUKU else throw IllegalStateException("Shizuku authorization required")
        ScanMode.STANDARD -> ScanMode.STANDARD
    }
}
