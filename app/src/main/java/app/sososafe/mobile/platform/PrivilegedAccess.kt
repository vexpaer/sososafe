package app.sososafe.mobile.platform

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import app.sososafe.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable data class ShellResult(val code:Int,val output:String,val error:String="") { fun checked():String { check(code==0) { error.ifBlank { output }.take(1000).ifBlank { "Command failed ($code)" } }; return output } }

object ProcessRunner {
    fun run(tokens:List<String>,root:Boolean=false):ShellResult {
        require(Commands.valid(tokens)) { "Command not allowed" }
        val p=ProcessBuilder(if(root) listOf("su","-c",Commands.quote(tokens)) else tokens).start()
        val pool=Executors.newFixedThreadPool(2)
        try {
            fun read(stream:java.io.InputStream)=stream.use { ApkAnalyzer.readLimited(it,12*1024*1024).toString(Charsets.UTF_8) }
            val out=pool.submit<String> { read(p.inputStream) }; val err=pool.submit<String> { read(p.errorStream) }
            if(!p.waitFor(30,TimeUnit.SECONDS)) { p.destroyForcibly(); return ShellResult(-1,"","Command timed out") }
            return ShellResult(p.exitValue(),out.get(2,TimeUnit.SECONDS),err.get(2,TimeUnit.SECONDS))
        } finally { p.destroy(); pool.shutdownNow() }
    }
}

/** Runs inside Shizuku's separate shell-identity user-service process. */
class PrivilegedUserService : IPrivilegedService.Stub {
    constructor()
    @Suppress("UNUSED_PARAMETER") constructor(context:Context)
    override fun execute(args:Array<String>):String = ReportWriter.json.encodeToString(runCatching { ProcessRunner.run(args.toList()) }.getOrElse { ShellResult(-1,"",it.message ?: "Shell error") })
    override fun executeStream(args:Array<String>):ParcelFileDescriptor {
        require(Commands.valid(args.toList()))
        val pipe=ParcelFileDescriptor.createPipe()
        Thread({runCatching{ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use{it.write(execute(args).toByteArray())}}},"sososafe-shell-output").start()
        return pipe[0]
    }
    override fun openApk(path:String):ParcelFileDescriptor = ParcelFileDescriptor.open(File(Commands.apkPath(path)),ParcelFileDescriptor.MODE_READ_ONLY)
    override fun destroy() { kotlin.system.exitProcess(0) }
}

data class PrivilegeState(val rootAvailable:Boolean=false,val rootAuthorized:Boolean=false,val shizukuInstalled:Boolean=false,val shizukuAlive:Boolean=false,val shizukuAuthorized:Boolean=false,val message:String="")
class PrivilegedAccess(private val context:Context) {
    private val mutable=MutableStateFlow(PrivilegeState())
    val state=mutable.asStateFlow()
    @Volatile private var service:IPrivilegedService?=null
    private var binding=false
    private val args=Shizuku.UserServiceArgs(ComponentName(context,PrivilegedUserService::class.java)).daemon(false).processNameSuffix("privileged").debuggable(false).version(1)
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName?,binder:IBinder?) { service=IPrivilegedService.Stub.asInterface(binder); binding=false; refresh() }
        override fun onServiceDisconnected(name:ComponentName?) { service=null; binding=false; refresh() }
    }
    private val received=Shizuku.OnBinderReceivedListener { refresh() }
    private val dead=Shizuku.OnBinderDeadListener { service=null;binding=false;refresh() }
    private val permission=Shizuku.OnRequestPermissionResultListener { _,_ -> refresh(); if(mutable.value.shizukuAuthorized) bind() }
    init {
        Shizuku.addBinderReceivedListenerSticky(received); Shizuku.addBinderDeadListener(dead); Shizuku.addRequestPermissionResultListener(permission); refresh()
    }
    fun refresh() {
        val alive=runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val authorized=alive && runCatching { Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val installed=runCatching { context.packageManager.getPackageInfo("moe.shizuku.privileged.api",0); true }.getOrDefault(false)
        val su=System.getenv("PATH").orEmpty().split(':').any { File(it,"su").canExecute() } || listOf("/system/bin/su","/system/xbin/su","/sbin/su","/debug_ramdisk/su").any { File(it).canExecute() }
        mutable.value=mutable.value.copy(rootAvailable=su,shizukuInstalled=installed,shizukuAlive=alive,shizukuAuthorized=authorized)
        if(authorized) bind()
    }
    private fun bind() {
        if(service!=null || binding || !mutable.value.shizukuAuthorized) return
        binding=true
        runCatching { Shizuku.bindUserService(args,connection) }.onFailure { binding=false;mutable.value=mutable.value.copy(message=it.message.orEmpty()) }
    }
    fun requestShizuku() { check(mutable.value.shizukuAlive) { "Start Shizuku on the device first" }; Shizuku.requestPermission(100) }
    suspend fun requestRoot()=withContext(Dispatchers.IO) {
        val result=runCatching { ProcessRunner.run(listOf("id"),true) }
        val granted=result.getOrNull()?.let { it.code==0 && Regex("uid=0\\b").containsMatchIn(it.output) }==true
        mutable.value=mutable.value.copy(rootAuthorized=granted,message=if(granted) "Root authorized for this app session" else result.exceptionOrNull()?.message ?: "su authorization denied or unavailable")
    }
    fun selected(preference:ScanMode)=ModePolicy.select(preference,mutable.value.rootAuthorized,mutable.value.shizukuAuthorized)
    suspend fun execute(mode:ScanMode,tokens:List<String>):ShellResult=withContext(Dispatchers.IO) {
        require(Commands.valid(tokens))
        when(mode) {
            ScanMode.ROOT -> { check(mutable.value.rootAuthorized) { "Root authorization required" }; ProcessRunner.run(tokens,true).also { if(it.code!=0 && tokens==listOf("id")) mutable.value=mutable.value.copy(rootAuthorized=false) } }
            ScanMode.SHIZUKU -> {
                check(mutable.value.shizukuAuthorized && Shizuku.pingBinder()) { "Shizuku stopped or permission revoked" }; bind()
                withTimeout(10000) { while(service==null) delay(100) }
                val response=ParcelFileDescriptor.AutoCloseInputStream(checkNotNull(service).executeStream(tokens.toTypedArray())).use{ApkAnalyzer.readLimited(it,32*1024*1024).toString(Charsets.UTF_8)}
                ReportWriter.json.decodeFromString<ShellResult>(response)
            }
            else -> error("Shell unavailable in Standard mode")
        }
    }
    suspend fun copyApk(mode:ScanMode,path:String,destination:File)=withContext(Dispatchers.IO) {
        Commands.apkPath(path)
        when(mode) {
            ScanMode.SHIZUKU -> {
                bind(); withTimeout(10000){while(service==null) delay(100)}
                ParcelFileDescriptor.AutoCloseInputStream(checkNotNull(service).openApk(path)).use { input -> destination.outputStream().use { copyLimited(input,it) } }
            }
            ScanMode.ROOT -> {
                check(mutable.value.rootAuthorized)
                val safe="'"+path.replace("'","'\\''")+"'"
                val p=ProcessBuilder("su","-c","exec /system/bin/cat -- $safe").redirectError(File(context.cacheDir,"root-read-error")).start()
                val watchdog=Executors.newSingleThreadScheduledExecutor()
                val timeout=watchdog.schedule({p.destroyForcibly()},45,TimeUnit.SECONDS)
                try { p.inputStream.use { input -> destination.outputStream().use { copyLimited(input,it) } }; check(p.waitFor(2,TimeUnit.SECONDS) && p.exitValue()==0) { "Root could not read APK" } }
                finally { timeout.cancel(true);watchdog.shutdownNow();p.destroy();File(context.cacheDir,"root-read-error").delete() }
            }
            else -> error("APK inaccessible; import using the system file picker")
        }
    }
    private fun copyLimited(input:java.io.InputStream,output:java.io.OutputStream) {
        val buffer=ByteArray(32768); var total=0L
        while(true) { val n=input.read(buffer);if(n<0)break;total+=n;check(total<=512L*1024*1024){"APK exceeds 512 MiB safety limit"};output.write(buffer,0,n) }
    }
    val userId:Int get()=Process.myUid()/100000
}
