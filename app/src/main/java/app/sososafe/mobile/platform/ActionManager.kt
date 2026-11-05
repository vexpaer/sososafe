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

data class ActionPlan(val id:String,val app:AppRecord,val kind:String,val argument:String,val original:String,val desired:String,val mode:ScanMode,val userId:Int,val effect:String,val scope:String="PACKAGE")
class ActionManager(private val context:Context,private val repo:AuditRepository) {
    private val access=repo.access;private val dao=repo.dao; private val json=ReportWriter.json
    private val protected=setOf("android","com.android.systemui","com.android.settings","com.android.shell","com.android.permissioncontroller","com.google.android.permissioncontroller",context.packageName)
    suspend fun prepare(app:AppRecord,kind:String,argument:String=""):ActionPlan=withContext(Dispatchers.IO) {
        check(!app.imported){"Imported APK has no installed target"};Commands.packageName(app.packageName)
        val currentInfo=context.packageManager.getPackageInfo(app.packageName,0)
        val currentApp=checkNotNull(currentInfo.applicationInfo)
        val target=app.copy(uid=currentApp.uid,version=currentInfo.versionName.orEmpty(),label=context.packageManager.getApplicationLabel(currentApp).toString(),system=currentApp.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM!=0)
        val mode=access.selected(repo.settings.flow.first().mode)
        val user=access.userId
        var scope="PACKAGE"
        val original:String;val desired:String;val effect:String
        when(kind) {
            "APP_OP" -> {
                check(target.uid%100000>=10000 && target.packageName !in protected){"Core/system UIDs are protected from AppOps changes. Use Android settings."}
                check(mode!=ScanMode.STANDARD){"Root or Shizuku authorization required"};require(argument in Commands.allowedOps)
                val output=access.execute(mode,Commands.op(app.packageName,argument,user)).checked()
                val uidMode=Commands.uidOpMode(output,argument)
                if(uidMode!=null)scope="UID"
                original=uidMode ?: Commands.packageOpMode(output,argument) ?: error("Cannot determine original AppOp. Use Android settings.")
                val shared=context.packageManager.getPackagesForUid(target.uid).orEmpty().joinToString()
                desired="ignore";effect="Restrict $argument to ignore at $scope scope. The related feature may stop working. Original mode: $original. ${if(scope=="UID")"Applies to ALL packages of UID ${target.uid}: $shared"else "Package setting only; UID overrides take precedence."}\n将 $argument 限制为 ignore，相关功能可能失效。范围：$scope。${if(scope=="UID")"将影响此 UID 下的全部应用：$shared"else "仅修改此包；UID 级设置优先。"}"
            }
            "DISABLE" -> {
                check(mode!=ScanMode.STANDARD){"Root or Shizuku authorization required"};check(app.packageName !in protected && !target.system){"Core and system applications are protected from disabling"}
                val current=context.packageManager.getApplicationEnabledSetting(app.packageName)
                original=current.toString();desired="3";effect="Disable this user application for Android user $user. Notifications, background work and launching will stop until restored."
            }
            "UNINSTALL" -> {
                check(!target.system && app.packageName !in protected){"Only user applications can be uninstalled"}
                original="installed:${target.version}";desired="uninstalled";effect="The Android uninstall dialog will ask again. Private app data is deleted and cannot be recovered by sososafe. APK files are saved locally for reinstalling this version; reinstalling requires Android approval."
            }
            else -> error("Unsupported operation")
        }
        ActionPlan(UUID.randomUUID().toString(),target,kind,argument,original,desired,mode,user,effect,scope)
    }
    suspend fun execute(plan:ActionPlan):ActionRecord=withContext(Dispatchers.IO) {
        check(plan.userId==access.userId)
        check(context.packageManager.getApplicationInfo(plan.app.packageName,0).uid==plan.app.uid){"Target app installation changed; prepare again"}
        val pending=ActionRecord(plan.id,plan.app.packageName,plan.kind,plan.argument,plan.original,plan.desired,System.currentTimeMillis(),"PENDING",true,mode=plan.mode,userId=plan.userId,scope=plan.scope,targetUid=plan.app.uid)
        save(pending)
        val result=try {
            when(plan.kind) {
                "APP_OP" -> {
                    val before=access.execute(plan.mode,Commands.op(plan.app.packageName,plan.argument,plan.userId)).checked()
                    val current=if(plan.scope=="UID")Commands.uidOpMode(before,plan.argument)else Commands.packageOpMode(before,plan.argument)
                    check(current==plan.original){"State changed after confirmation; prepare again"}
                    val command=if(plan.scope=="UID")Commands.setUidOp(plan.app.packageName,plan.argument,plan.desired,plan.userId)else Commands.setOp(plan.app.packageName,plan.argument,plan.desired,plan.userId)
                    access.execute(plan.mode,command).checked()
                    val after=access.execute(plan.mode,Commands.op(plan.app.packageName,plan.argument,plan.userId)).checked()
                    check((if(plan.scope=="UID")Commands.uidOpMode(after,plan.argument)else Commands.packageOpMode(after,plan.argument))==plan.desired){"AppOps did not confirm requested mode"};"SUCCESS"
                }
                "DISABLE" -> {
                    check(context.packageManager.getApplicationEnabledSetting(plan.app.packageName).toString()==plan.original){"Enabled state changed; prepare again"}
                    access.execute(plan.mode,Commands.enabled(plan.app.packageName,false,plan.userId)).checked()
                    check(context.packageManager.getApplicationEnabledSetting(plan.app.packageName)==PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER){"Disabled state not confirmed"};"SUCCESS"
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
                    check(record.targetUid<0 || context.packageManager.getApplicationInfo(record.packageName,0).uid==record.targetUid){"Target installation UID changed; original settings do not belong to this installation"}
                    val before=access.execute(record.mode,Commands.op(record.packageName,record.argument,record.userId)).checked()
                    val current=if(record.scope=="UID")Commands.uidOpMode(before,record.argument)else Commands.packageOpMode(before,record.argument)
                    check(current==record.original || current==record.desired){"AppOp changed after this operation; review current settings before restoring"}
                    if(current!=record.original)access.execute(record.mode,if(record.scope=="UID")Commands.setUidOp(record.packageName,record.argument,record.original,record.userId)else Commands.setOp(record.packageName,record.argument,record.original,record.userId)).checked()
                    val after=access.execute(record.mode,Commands.op(record.packageName,record.argument,record.userId)).checked()
                    check((if(record.scope=="UID")Commands.uidOpMode(after,record.argument)else Commands.packageOpMode(after,record.argument))==record.original){"Restored mode not confirmed"}
                }
                "DISABLE" -> {
                    check(record.targetUid<0 || context.packageManager.getApplicationInfo(record.packageName,0).uid==record.targetUid){"Target installation UID changed"}
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
