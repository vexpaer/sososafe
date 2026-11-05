package app.sososafe.mobile.platform

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.*
import android.os.Build
import android.provider.Settings
import app.noir.core.adb.Dumpsys
import app.sososafe.core.*
import kotlinx.coroutines.*
import java.io.File

interface DeviceScanner {
    val mode:ScanMode
    suspend fun inventory():List<PackageTarget>
    suspend fun scan(target:PackageTarget,deep:Boolean):AppRecord
}
data class PackageTarget(val name:String,val uid:Int=-1)
class PackageScanner(private val context:Context,private val access:PrivilegedAccess,override val mode:ScanMode):DeviceScanner {
    private val pm=context.packageManager
    private val flags=PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS
    override suspend fun inventory():List<PackageTarget> = withContext(Dispatchers.IO) {
        if(mode==ScanMode.STANDARD) pm.getInstalledPackages(flags).map { PackageTarget(it.packageName,it.applicationInfo?.uid ?: -1) }
        else access.execute(mode,Commands.list(access.userId)).checked().lineSequence().mapNotNull {
            val m=Regex("^package:([^\\s]+)(?:\\s+uid:(\\d+))?").find(it.trim()) ?: return@mapNotNull null
            PackageTarget(Commands.packageName(m.groupValues[1]),m.groupValues[2].toIntOrNull() ?: -1)
        }.distinctBy { it.name }.toList()
    }
    override suspend fun scan(target:PackageTarget,deep:Boolean):AppRecord = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val info=runCatching { pm.getPackageInfo(target.name,flags) }.getOrNull(); val ai=info?.applicationInfo
        val limits=mutableListOf<String>(); var dump:String?=null; var ops:String?=null
        if(mode!=ScanMode.STANDARD) {
            dump=runCatching { access.execute(mode,Commands.dump(target.name)).checked() }.onFailure { limits+="dumpsys: ${it.message}" }.getOrNull()
            ops=runCatching { access.execute(mode,Commands.ops(target.name,access.userId)).checked() }.onFailure { limits+="AppOps: ${it.message}" }.getOrNull()
        }
        val meta=dump?.let { Dumpsys.parse(it)[target.name] }
        val permissions=info?.requestedPermissions.orEmpty().mapIndexed { i,name ->
            val protection=runCatching { PermissionRules.protection(pm.getPermissionInfo(name,0).protectionLevel) }.getOrDefault("unknown")
            val op=android.app.AppOpsManager.permissionToOp(name)?.substringAfter(':')?.uppercase()
            val short=name.substringAfterLast('.')
            PermissionFact(name,PermissionRules.group(name),if(short in PermissionRules.special) "special ($protection)" else protection,true,
                if(short in PermissionRules.special) null else PermissionRules.granted(info?.requestedPermissionsFlags?.getOrNull(i)),
                op?.let { code -> ops?.let { Commands.opMode(it,code) } },
                if(short in PermissionRules.special) "unknown: special access is not a runtime permission" else null)
        }.toMutableList()
        if(info==null) {
            limits+="PackageManager metadata unavailable; shell inventory and dumpsys only."
            meta?.perms.orEmpty().forEach { permissions+=PermissionFact("android.permission.$it",PermissionRules.group(it),"unknown",true,true,evidence="dumpsys package granted=true") }
        }
        val secure=context.contentResolver
        fun addSpecial(permission:String,enabled:Boolean?,detail:String) {
            permissions+=PermissionFact(permission,PermissionRules.group(permission),"special",false,null,specialState=if(enabled==null) "unknown" else if(enabled) "enabled" else "disabled",evidence=detail)
        }
        info?.services.orEmpty().forEach { s -> when(s.permission) {
            "android.permission.BIND_ACCESSIBILITY_SERVICE" -> addSpecial(s.permission,runCatching { Settings.Secure.getString(secure,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any { it.substringBefore('/')==target.name } }.getOrNull(),"Manifest service + Settings.Secure enabled_accessibility_services")
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" -> addSpecial(s.permission,runCatching { Settings.Secure.getString(secure,"enabled_notification_listeners").orEmpty().split(':').any { it.substringBefore('/')==target.name } }.getOrNull(),"Manifest service + Settings.Secure enabled_notification_listeners")
        } }
        info?.receivers.orEmpty().filter { it.permission=="android.permission.BIND_DEVICE_ADMIN" }.forEach {
            addSpecial(it.permission,runCatching { context.getSystemService(DevicePolicyManager::class.java).activeAdmins.orEmpty().any { c -> c.packageName==target.name } }.getOrNull(),"Manifest receiver + DevicePolicyManager activeAdmins")
        }
        val apkFiles=mutableListOf<File>(); val temporary=mutableListOf<File>()
        var analysis=StaticResult(limitations=listOf("Deep scan disabled by settings."))
        if(deep) {
            val paths=if(mode==ScanMode.STANDARD) listOfNotNull(ai?.sourceDir)+ai?.splitSourceDirs.orEmpty().toList()
                else runCatching { access.execute(mode,Commands.paths(target.name,access.userId)).checked().lineSequence().filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.toList() }.onFailure { limits+="APK path: ${it.message}" }.getOrDefault(emptyList())
            for((index,path) in paths.withIndex()) {
                currentCoroutineContext().ensureActive()
                val f=File(path)
                if(f.canRead()) apkFiles+=f
                else if(mode!=ScanMode.STANDARD) {
                    val temp=File.createTempFile("scan-$index-",".apk",context.cacheDir);temporary+=temp
                    runCatching { access.copyApk(mode,path,temp);apkFiles+=temp }.onFailure { limits+="APK read denied: ${it.message}" }
                } else limits+="APK access restricted by Android/OEM. Import this APK using the system file picker."
            }
            try {
                val job=currentCoroutineContext()[Job]
                analysis=if(apkFiles.isEmpty()) StaticResult(limitations=listOf("No readable APK; static analysis not performed. Use Import APK.")) else ApkAnalyzer.analyze(apkFiles){job?.ensureActive()}
            } finally { temporary.forEach { it.delete() } }
        }
        val uid=ai?.uid ?: target.uid.takeIf { it>=0 } ?: meta?.appId?.toIntOrNull() ?: -1
        val shared=if(uid>=0) runCatching { pm.getPackagesForUid(uid).orEmpty().takeIf { it.size>1 }?.joinToString() }.getOrNull() ?: meta?.sharedUser else meta?.sharedUser
        val installer=runCatching { if(Build.VERSION.SDK_INT>=30) pm.getInstallSourceInfo(target.name).installingPackageName else { @Suppress("DEPRECATION") pm.getInstallerPackageName(target.name) } }.getOrNull() ?: meta?.installer
        val record=AppRecord(target.name,ai?.let { pm.getApplicationLabel(it).toString() } ?: target.name,info?.versionName ?: "unknown",uid,ai?.let { it.flags and ApplicationInfo.FLAG_SYSTEM!=0 } ?: meta?.isSystem ?: false,installer,ai?.targetSdkVersion ?: analysis.manifest?.targetSdk ?: 0,ai?.enabled ?: true,shared,permissions.distinctBy { it.name+it.declared },analysis,mode=mode,limitations=limits,appOps=ops)
        RiskEngine.evaluate(record)
    }
}
