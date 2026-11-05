package app.sososafe.mobile.data

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import app.sososafe.core.*
import app.sososafe.mobile.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

data class ScanProgress(val status:String="IDLE",val done:Int=0,val total:Int=0,val current:String="",val error:String?=null)
class AuditRepository(private val context:Context,val db:AuditDatabase,val settings:SettingsStore,val access:PrivilegedAccess) {
    val dao=db.dao(); private val json=ReportWriter.json
    private val appCache=java.util.concurrent.ConcurrentHashMap<String,Pair<Long,AppRecord>>()
    private suspend fun hydrate(row:AppEntity):AppRecord {
        appCache[row.packageName]?.takeIf{it.first==row.updated}?.let{return it.second}
        val parts=dao.chunks(row.packageName)
        val record=json.decodeFromString<AppRecord>(if(parts.isEmpty())row.json else parts.joinToString(""){it.json})
        appCache[row.packageName]=row.updated to record;return record
    }
    val apps=dao.apps().map { rows -> withContext(Dispatchers.IO){appCache.keys.retainAll(rows.map{it.packageName}.toSet());rows.map{hydrate(it)}} }
    val scans=dao.scans().map { rows -> rows.map { json.decodeFromString<ScanRecord>(it.json) } }
    val connections=dao.connections().map { rows -> rows.map { json.decodeFromString<ConnectionRecord>(it.json) } }
    val actions=dao.actions().map { rows -> rows.map { json.decodeFromString<ActionRecord>(it.json) } }
    val progress=MutableStateFlow(ScanProgress())
    private var scanJob:Job?=null
    suspend fun save(record:AppRecord) {
        db.withTransaction {
            val encoded=json.encodeToString(record)
            dao.clearChunks(record.packageName)
            dao.chunks(JsonChunks.split(encoded).mapIndexed{i,part->AppChunkEntity(record.packageName,i,part)})
            dao.app(AppEntity(record.packageName,record.riskScore,record.scannedAt,"{}"))
            appCache[record.packageName]=record.scannedAt to record
            dao.clearPermissions(record.packageName);dao.clearEndpoints(record.packageName);dao.clearFindings(record.packageName)
            dao.permissions(record.permissions.map { PermissionEntity(record.packageName,it.name,json.encodeToString(it)) })
            dao.endpoints(record.analysis.endpoints.map { EndpointEntity(record.packageName,it.value,json.encodeToString(it)) })
            dao.findings(record.findings.map { FindingEntity(record.packageName,it.id,json.encodeToString(it)) })
        }
    }
    fun scan(scope:CoroutineScope) {
        if(scanJob?.isActive==true)return
        scanJob=scope.launch(Dispatchers.IO) {
            val id=UUID.randomUUID().toString();val start=System.currentTimeMillis(); var count=0;var mode=ScanMode.STANDARD
            progress.value=ScanProgress("SCANNING")
            try {
                val prefs=settings.flow.first(); mode=access.selected(prefs.mode)
                val scanner:DeviceScanner=PackageScanner(context,access,mode)
                val targets=scanner.inventory();progress.value=ScanProgress("SCANNING",0,targets.size)
                dao.scan(ScanEntity(id,start,json.encodeToString(ScanRecord(id,start,null,mode,"SCANNING",0))))
                for((i,target) in targets.withIndex()) {
                    ensureActive();progress.value=ScanProgress("SCANNING",i,targets.size,target.name)
                    val ai=runCatching { context.packageManager.getApplicationInfo(target.name,0) }.getOrNull()
                    val system=ai?.let { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM!=0 }==true
                    if(prefs.includeSystem || !system) {
                        try { save(scanner.scan(target,prefs.deepScan));count++ }
                        catch(e:CancellationException){throw e}
                        catch(e:Exception) { save(AppRecord(target.name,target.name,uid=target.uid,mode=mode,limitations=listOf("Scan failed: ${e.message}"))) }
                    }
                }
                progress.value=ScanProgress("COMPLETED",targets.size,targets.size)
                dao.scan(ScanEntity(id,start,json.encodeToString(ScanRecord(id,start,System.currentTimeMillis(),mode,"COMPLETED",count))))
            } catch(e:CancellationException) {
                withContext(NonCancellable){dao.scan(ScanEntity(id,start,json.encodeToString(ScanRecord(id,start,System.currentTimeMillis(),mode,"CANCELLED",count))))}
                progress.value=progress.value.copy(status="CANCELLED"); throw e
            } catch(e:Exception) {
                progress.value=progress.value.copy(status="ERROR",error=e.message)
                dao.scan(ScanEntity(id,start,json.encodeToString(ScanRecord(id,start,System.currentTimeMillis(),mode,"ERROR",count,e.message))))
            }
        }
    }
    fun cancelScan(){scanJob?.cancel()}
    suspend fun importApk(uri:Uri)=withContext(Dispatchers.IO) {
        val file=File.createTempFile("import-",".apk",context.cacheDir)
        try {
            checkNotNull(context.contentResolver.openInputStream(uri)).use { input -> file.outputStream().use { output ->
                val buf=ByteArray(32768);var size=0L
                while(true){currentCoroutineContext().ensureActive();val n=input.read(buf);if(n<0)break;size+=n;require(size<=512L*1024*1024){"APK exceeds 512 MiB"};output.write(buf,0,n)}
            } }
            val job=currentCoroutineContext()[Job]; val analysis=ApkAnalyzer.analyze(listOf(file)){job?.ensureActive()}
            val m=checkNotNull(analysis.manifest){"APK Manifest could not be read: ${analysis.limitations.joinToString()}"}
            val packageInfo=context.packageManager.getPackageArchiveInfo(file.absolutePath,android.content.pm.PackageManager.GET_PERMISSIONS)
            val p=m.permissions.map { PermissionFact(it,PermissionRules.group(it),"unknown (imported APK)",true,null,evidence="Imported AndroidManifest.xml; installed authorization unknown") }
            val name=m.packageName.ifBlank { packageInfo?.packageName ?: "imported.${UUID.randomUUID().toString().replace("-","")}" }
            val record=RiskEngine.evaluate(AppRecord("import:$name",name,packageInfo?.versionName.orEmpty(),targetSdk=m.targetSdk ?: 0,permissions=p,analysis=analysis,imported=true,limitations=listOf("Imported APK is not installed; runtime grants, UID and AppOps are unknown.")))
            save(record);record
        } finally {file.delete()}
    }
    suspend fun export(format:String):File=withContext(Dispatchers.IO) {
        val savedScans=dao.allScans().map { json.decodeFromString<ScanRecord>(it.json) }
        val report=AuditReport(generatedAt=System.currentTimeMillis(),device=mapOf("manufacturer" to Build.MANUFACTURER,"model" to Build.MODEL,"android" to Build.VERSION.RELEASE,"api" to Build.VERSION.SDK_INT.toString(),"securityPatch" to Build.VERSION.SECURITY_PATCH),mode=savedScans.firstOrNull()?.mode ?: ScanMode.STANDARD,apps=dao.allApps().map { hydrate(it) },scans=savedScans,connections=dao.allConnections().map { json.decodeFromString(it.json) },actions=dao.allActions().map { json.decodeFromString(it.json) })
        val dir=File(context.filesDir,"reports").apply{mkdirs()}
        File(dir,"sososafe-${System.currentTimeMillis()}.$format").apply { outputStream().use{ReportWriter.write(report,format,it)} }
    }
    suspend fun clearAudit()=withContext(Dispatchers.IO){
        check(scanJob?.isActive!=true){"Cancel the scan first"}
        db.withTransaction {dao.clearApps();dao.clearChunks();dao.clearPermissions();dao.clearEndpoints();dao.clearFindings();dao.clearScans();dao.clearConnections()};appCache.clear()
        File(context.filesDir,"reports").listFiles()?.forEach{it.delete()}
    }
}
