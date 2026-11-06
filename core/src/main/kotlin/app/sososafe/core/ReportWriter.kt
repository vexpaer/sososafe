package app.sososafe.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.OutputStream
import java.time.Instant

object ReportWriter {
    val json=Json { prettyPrint=true; encodeDefaults=true; ignoreUnknownKeys=true }
    fun linkEvidence(apps:List<AppRecord>,connections:List<ConnectionRecord>):List<AppRecord>{
        val index=hashMapOf<Pair<Int,String>,MutableList<String>>()
        connections.forEach{c->c.uid?.let{uid->(listOfNotNull(c.domain)+c.dnsQueries).distinct().forEach{host->val ids=index.getOrPut(uid to host){mutableListOf()};if(ids.size<50)ids+=c.id}}}
        return apps.map{app->if(app.uid<0 || app.imported)app else app.copy(exposures=app.exposures.map{link->link.copy(observedConnectionIds=index[app.uid to link.host].orEmpty())})}
    }
    fun render(report:AuditReport,format:String):String {
        if(format=="json") return json.encodeToString(report)
        return buildString{text(report,format,this)}
    }
    @OptIn(ExperimentalSerializationApi::class)
    fun write(report:AuditReport,format:String,output:OutputStream){
        if(format=="json")json.encodeToStream(report,output)
        else output.bufferedWriter(Charsets.UTF_8).use{text(report,format,it)}
    }
    private fun text(report:AuditReport,format:String,output:Appendable){
        require(format in setOf("md","txt"))
        fun clean(value:String)=value.replace("\r"," ").replace("\n"," ")
        fun line(value:String){
            if(format!="md"){output.appendLine(value);return}
            val prefix=when{value=="# sososafe audit"->"# ";value.startsWith("\n## ")->"\n## ";else->""}
            val content=value.removePrefix(prefix)
            output.append(prefix).appendLine(buildString{content.forEach{c->when(c){'&'->append("&amp;");'<'->append("&lt;");'>'->append("&gt;");'\\','`','*','_','[',']','(',')','!','#','|'->{append('\\');append(c)};else->append(c)}}})
        }
        with(output) {
            line(if(format=="md") "# sososafe audit" else "sososafe audit")
            line("Generated: ${Instant.ofEpochMilli(report.generatedAt)} | Mode: ${report.mode}")
            report.device.forEach { (k,v) -> line("$k: ${clean(v)}") }
            report.boundaries.forEach { line("- $it") }
            for(app in report.apps) {
                line("\n${if(format=="md") "## " else ""}${clean(app.label)} (${app.packageName})")
                line("UID=${app.uid}; version=${clean(app.version)}; system=${app.system}; target=${app.targetSdk}; risk=${app.riskScore}/100 (${app.risk}); scanned=${Instant.ofEpochMilli(app.scannedAt)}")
                line("Score contributions after family caps: ${app.scoreComponents}; individual finding points below are pre-cap weights.")
                app.permissions.forEach { line("Permission: ${it.name}; ${it.protection}; declared=${it.declared}; granted=${it.granted}; AppOps=${it.appOp}; special=${it.specialState}; source=${it.evidence}") }
                app.analysis.endpoints.forEach { line("Endpoint: ${clean(it.value)}; ${it.provider}; ${it.category}; ${it.evidence.level}; source=${it.evidence.source}") }
                app.analysis.sdks.forEach { line("SDK: ${it.name}; signature=${it.signature}; source=${it.source}") }
                app.analysis.manifest?.let { line("Manifest: ${json.encodeToString(it)}") }
                app.findings.forEach { line("Finding: ${it.title}; ${it.severity}; points=${it.points}; evidence=${clean(it.evidence.detail)}; level=${it.evidence.level}; source=${clean(it.evidence.source)}; advice=${it.recommendation}") }
                app.exposures.forEach { line("Correlation: ${it.permission} -> ${it.dataType} -> ${it.host} -> ${it.provider}; ${it.severity}; ${it.level}; ${it.explanation}; observed metadata references (up to 50)=${it.observedConnectionIds}") }
                (app.limitations+app.analysis.limitations).forEach { line("Limitation: ${clean(it)}") }
                app.appOps?.let { line("AppOps: ${clean(it)}") }
            }
            line("\nNetwork metadata")
            report.connections.forEach { line(json.encodeToString(it)) }
            line("\nAction history")
            report.actions.forEach { line(json.encodeToString(it)) }
            line("\nScan history")
            report.scans.forEach { line(json.encodeToString(it)) }
        }
    }
}
