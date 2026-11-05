package app.sososafe.mobile.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.net.Uri
import app.sososafe.core.*
import app.sososafe.mobile.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

data class ActionPlan(val id:String,val app:AppRecord,val kind:String,val argument:String,val original:String,val desired:String,val mode:ScanMode,val userId:Int,val effect:String)
class ActionManager(private val context:Context,private val repo:AuditRepository) {
    private val access=repo.access;private val dao=repo.dao; private val json=ReportWriter.json
    private val protected=setOf("android","com.android.systemui","com.android.settings","com.android.shell","com.android.permissioncontroller","com.google.android.permissioncontroller",context.packageName)
    suspend fun prepare(app:AppRecord,kind:String,argument:String=""):ActionPlan=withContext(Dispatchers.IO) {
        check(!app.imported){"Imported APK has no installed target"};Commands.packageName(app.packageName)
        val mode=access.selected(repo.settings.flow.first().mode)
        val user=access.userId
        val original:String;val desired:String;val effect:String
        when(kind) {
            "APP_OP" -> {
                check(mode!=ScanMode.STANDARD){"Root or Shizuku authorization required"};require(argument in Commands.allowedOps)
                val output=access.execute(mode,Commands.op(app.packageName,argument,user)).checked()
                original=Commands.packageOpMode(output,argument) ?: error("Cannot determine original package AppOp separately from UID overrides. Use Android settings.")
                desired="ignore";effect="Restrict $argument to ignore. The related feature may stop working. Original mode: $original. UID-wide AppOps can affect packages sharing a UID."
            }
            "DISABLE" -> {
                check(mode!=ScanMode.STANDARD){"Root or Shizuku authorization required"};check(app.packageName !in protected && !app.system){"Core and system applications are protected from disabling"}
                val current=context.packageManager.getApplicationEnabledSetting(app.packageName)
                original=current.toString();desired="3";effect="Disable this user application for Android user $user. Notifications, background work and launching will stop until restored."
            }
            "UNINSTALL" -> {
                check(!app.system && app.packageName !in protected){"Only user applications can be uninstalled"}
                original="installed:${app.version}";desired="uninstalled";effect="The Android uninstall dialog will ask again. Private app data is deleted and cannot be recovered by sososafe. APK files are saved locally for reinstalling this version; reinstalling requires Android approval."
            }
            else -> error("Unsupported operation")
        }
        ActionPlan(UUID.randomUUID().toString(),app,kind,argument,original,desired,mode,user,effect)
    }
    suspend fun execute(plan:ActionPlan):ActionRecord=withContext(Dispatchers.IO) {
        check(plan.userId==access.userId)
        val pending=ActionRecord(plan.id,plan.app.packageName,plan.kind,plan.argument,plan.original,plan.desired,System.currentTimeMillis(),"PENDING",true,mode=plan.mode,userId=plan.userId)
        save(pending)
        val result=try {
            when(plan.kind) {
                "APP_OP" -> {
                    val before=access.execute(plan.mode,Commands.op(plan.app.packageName,plan.argument,plan.userId)).checked()
                    val current=Commands.packageOpMode(before,plan.argument)
                    check(current==plan.original){"State changed after confirmation; prepare again"}
                    access.execute(plan.mode,Commands.setOp(plan.app.packageName,plan.argument,plan.desired,plan.userId)).checked()
                    val after=access.execute(plan.mode,Commands.op(plan.app.packageName,plan.argument,plan.userId)).checked()
                    check(Commands.packageOpMode(after,plan.argument)==plan.desired){"AppOps did not confirm requested package mode"};"SUCCESS"
                }
                "DISABLE" -> {
                    check(context.packageManager.getApplicationEnabledSetting(plan.app.packageName).toString()==plan.original){"Enabled state changed; prepare again"}
                    access.execute(plan.mode,Commands.enabled(plan.app.packageName,false,plan.userId)).checked()
                    check(context.packageManager.getApplicationEnabledSetting(plan.app.packageName)==3){"Disabled state not confirmed"};"SUCCESS"
                }
                "UNINSTALL" -> { backup(plan);"AWAITING_SYSTEM_CONFIRMATION" }
                else -> error("Unsupported action")
            }
        } catch(e:Exception) { "FAILED: ${e.message}" }
        pending.copy(result=result).also { save(it) }
    }
    private suspend fun backup(plan:ActionPlan) {
        val ai=context.packageManager.getApplicationInfo(plan.app.packageName,0)
        val paths=listOf(ai.sourceDir)+ai.splitSourceDirs.orEmpty()
        val dir=File(context.filesDir,"recovery/${plan.id}").apply{mkdirs()}
        try {
            paths.forEachIndexed { i,path ->
                val out=File(dir,"split-$i.apk");val source=File(path)
                if(source.canRead()){check(source.length()<=512L*1024*1024){"APK exceeds recovery size limit"};source.copyTo(out,true)}else access.copyApk(plan.mode,path,out)
                check(dir.listFiles().orEmpty().sumOf{it.length()}<=1024L*1024*1024){"Recovery APK set exceeds 1 GiB limit"}
            }
            check(dir.listFiles().orEmpty().sumOf{it.length()} > 0){"Could not preserve APK for rollback"}
        }catch(e:Exception){dir.listFiles()?.forEach{it.delete()};dir.delete();throw e}
    }
    fun launchUninstall(record:ActionRecord) {
        require(record.action=="UNINSTALL" && record.result=="AWAITING_SYSTEM_CONFIRMATION")
        context.startActivity(Intent(Intent.ACTION_DELETE,Uri.parse("package:${record.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    suspend fun reconcileUninstall()=withContext(Dispatchers.IO) {
        dao.allActions().map { json.decodeFromString<ActionRecord>(it.json) }.filter { it.result=="AWAITING_SYSTEM_CONFIRMATION" }.forEach {
            val installed=runCatching { context.packageManager.getPackageInfo(it.packageName,0);true }.getOrDefault(false)
            save(it.copy(result=if(installed) "CANCELLED_OR_NOT_REMOVED" else "SUCCESS: APK preserved; private data not recoverable"))
        }
    }
    suspend fun rollback(record:ActionRecord)=withContext(Dispatchers.IO) {
        check(record.canRollback)
        check(record.userId==access.userId){"Rollback belongs to another Android user"}
        var reinstall=false
        val result=try {
            when(record.action) {
                "APP_OP" -> {
                    val before=access.execute(record.mode,Commands.op(record.packageName,record.argument,record.userId)).checked()
                    val current=Commands.packageOpMode(before,record.argument)
                    check(current==record.original || current==record.desired){"AppOp changed after this operation; review current settings before restoring"}
                    if(current!=record.original)access.execute(record.mode,Commands.setOp(record.packageName,record.argument,record.original,record.userId)).checked()
                    val after=access.execute(record.mode,Commands.op(record.packageName,record.argument,record.userId)).checked()
                    check(Commands.packageOpMode(after,record.argument)==record.original){"Restored package mode not confirmed"}
                }
                "DISABLE" -> {
                    val current=context.packageManager.getApplicationEnabledSetting(record.packageName).toString()
                    check(current==record.original || current==record.desired){"Enabled state changed after this operation; review settings first"}
                    if(current!=record.original)access.execute(record.mode,Commands.restoreEnabled(record.packageName,record.original.toInt(),record.userId)).checked()
                    check(context.packageManager.getApplicationEnabledSetting(record.packageName)==record.original.toInt()){ "Restored enabled state not confirmed" }
                }
                "UNINSTALL" -> {
                    check(context.packageManager.canRequestPackageInstalls()){ "Allow sososafe to install unknown apps in Android settings, then retry" }
                    val files=File(context.filesDir,"recovery/${record.id}").listFiles().orEmpty().filter{it.extension=="apk"}.sortedBy{it.name}
                    check(files.isNotEmpty()){"Preserved APK files missing"}
                    val installer=context.packageManager.packageInstaller
                    val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply{ setAppPackageName(record.packageName) }
                    val sessionId=installer.createSession(params)
                    save(record.copy(result="REINSTALL_REQUESTED"))
                    installer.openSession(sessionId).use { session ->
                        files.forEach { f -> session.openWrite(f.name,0,f.length()).use { out -> f.inputStream().use { it.copyTo(out) };session.fsync(out) } }
                        val intent=Intent(context,InstallResultReceiver::class.java).putExtra("action_id",record.id)
                        val flags=android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                        val sender=android.app.PendingIntent.getBroadcast(context,sessionId,intent,flags).intentSender
                        session.commit(sender)
                    }
                    reinstall=true
                }
                else -> error("No rollback available")
            }
            if(reinstall) "REINSTALL_REQUESTED" else "RESTORED"
        } catch(e:Exception){"ROLLBACK_FAILED: ${e.message}"}
        if(result!="REINSTALL_REQUESTED")save(record.copy(rolledBack=result=="RESTORED",result=result))
    }
    suspend fun save(record:ActionRecord){dao.action(ActionEntity(record.id,record.createdAt,json.encodeToString(record)))}
}
