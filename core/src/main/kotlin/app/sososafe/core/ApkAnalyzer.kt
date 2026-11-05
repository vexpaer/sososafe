package app.sososafe.core

import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

object ApkAnalyzer {
    private val sdkSignatures=linkedMapOf(
        "Lcom/google/firebase/analytics/" to ("Firebase Analytics" to "Analytics"),
        "Lcom/google/android/gms/measurement/" to ("Google Analytics" to "Analytics"),
        "Lcom/google/android/gms/ads/" to ("Google Ads" to "Advertising"),
        "Lcom/google/firebase/crashlytics/" to ("Crashlytics" to "Crash Reporting"),
        "Lcom/facebook/" to ("Meta / Facebook" to "Social"),
        "Lcom/bytedance/" to ("ByteDance" to "Telemetry"),
        "Lcom/ss/android/" to ("ByteDance / TikTok" to "Telemetry"),
        "Lcom/tencent/" to ("Tencent" to "Functional"),
        "Lcom/alibaba/" to ("Alibaba" to "Cloud"),
        "Lcom/baidu/" to ("Baidu" to "Functional"),
        "Lcom/xiaomi/" to ("Xiaomi" to "Functional"),
        "Lcom/huawei/" to ("Huawei" to "Functional"),
        "Lcom/umeng/" to ("Umeng" to "Analytics"),
        "Lcom/appsflyer/" to ("AppsFlyer" to "Analytics"),
        "Lcom/adjust/sdk/" to ("Adjust" to "Analytics"),
        "Lcom/unity3d/ads/" to ("Unity Ads" to "Advertising"),
        "Lcom/applovin/" to ("AppLovin" to "Advertising"),
        "Lio/sentry/" to ("Sentry" to "Crash Reporting")
    )
    private val methods=mapOf(
        "Ljava/lang/Runtime;->exec" to ("Runtime.exec" to 5),
        "Ljava/lang/ProcessBuilder;->start" to ("ProcessBuilder" to 4),
        "Ldalvik/system/DexClassLoader;-><init>" to ("Dynamic code loading: DexClassLoader" to 8),
        "Ldalvik/system/PathClassLoader;-><init>" to ("PathClassLoader" to 2),
        "Landroid/media/projection/MediaProjectionManager;->getMediaProjection" to ("MediaProjection" to 4),
        "Landroid/webkit/WebView;->addJavascriptInterface" to ("WebView JavaScript bridge" to 4),
        "Landroid/telephony/TelephonyManager;->getDeviceId" to ("Device identifier access" to 4),
        "Landroid/telephony/TelephonyManager;->getImei" to ("IMEI access" to 4),
        "Landroid/location/LocationManager;->getLastKnownLocation" to ("Location access" to 3),
        "Landroid/content/ContentResolver;->query" to ("Content provider query" to 1),
        "Landroid/provider/Settings\$Secure;->getString" to ("Secure settings read" to 1)
    )
    data class DexResult(val endpoints: List<Endpoint>,val sdks: List<SdkFinding>,val findings: List<Finding>)
    fun dex(bytes: ByteArray, source: String): DexResult {
        require(bytes.size<=128*1024*1024) { "DEX exceeds 128 MiB limit" }
        val file=DexBackedDexFile(Opcodes.getDefault(),bytes)
        val endpoints=linkedMapOf<String,Endpoint>(); val sdks=linkedMapOf<String,SdkFinding>(); val findings=linkedMapOf<String,Finding>()
        for(ref in file.stringReferences) {
            if(endpoints.size<5000) DomainRules.extract(ref.string,source).forEach { endpoints[it.value]=it }
        }
        fun feature(id:String,title:String,points:Int,detail:String) {
            if(findings.size<500 && id !in findings) findings[id]=Finding(id,title,Severity.MEDIUM,points,Evidence(source,detail,EvidenceLevel.STATIC_REFERENCE),"Review this code reference in context. Presence does not prove runtime execution.")
        }
        for(c in file.classes) {
            sdkSignatures.forEach { (sig,sdk) -> if(c.type.startsWith(sig)) sdks.putIfAbsent(sdk.first,SdkFinding(sdk.first,sdk.second,sig,source+":"+c.type)) }
            when(c.superclass) {
                "Landroid/accessibilityservice/AccessibilityService;" -> feature("dex:accessibility","AccessibilityService implementation",5,c.type)
                "Landroid/service/notification/NotificationListenerService;" -> feature("dex:notification","Notification listener implementation",4,c.type)
                "Landroid/app/admin/DeviceAdminReceiver;" -> feature("dex:admin","Device administrator implementation",4,c.type)
            }
            for(m in c.methods) for(i in (m.implementation?.instructions ?: emptyList())) {
                val ref=(i as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                val key="${ref.definingClass}->${ref.name}"
                methods[key]?.let { (title,points) -> feature("dex:$key",title,points,"${c.type}->${m.name} invokes $key") }
            }
        }
        return DexResult(endpoints.values.toList(),sdks.values.toList(),findings.values.toList())
    }
    fun analyze(apks: List<File>, checkpoint: () -> Unit = {}): StaticResult {
        var manifest:ManifestResult?=null
        val endpoints=linkedMapOf<String,Endpoint>(); val sdks=linkedMapOf<String,SdkFinding>(); val findings=linkedMapOf<String,Finding>(); val files=mutableListOf<String>(); val limits=mutableListOf<String>()
        apks.forEachIndexed { index,apk ->
            checkpoint()
            try {
                ZipFile(apk).use { zip ->
                    if(index==0) {
                        val entry=zip.getEntry("AndroidManifest.xml") ?: error("Missing Manifest")
                        manifest=zip.getInputStream(entry).use { ManifestParser.parse(readLimited(it,8*1024*1024)) }
                        ManifestParser.findings(checkNotNull(manifest),"${apk.name}:AndroidManifest.xml").forEach { findings[it.id]=it }
                    }
                    val entries=zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.take(100).toList()
                    if(entries.isEmpty()) limits += "${apk.name}: no classes*.dex; native code and encrypted/loaded payloads are not analyzed."
                    for(e in entries) {
                        checkpoint()
                        val source="${apk.name}:${e.name}"
                        try {
                            val budget=minOf(128L*1024*1024,Runtime.getRuntime().maxMemory()/4).toInt()
                            val result=dex(zip.getInputStream(e).use { readLimited(it,budget) },source)
                            result.endpoints.forEach { if(endpoints.size<5000) endpoints[it.value]=it }
                            result.sdks.forEach { sdks[it.name]=it }; result.findings.forEach { findings[it.id]=it }; files+=source
                        } catch(e:Exception) { if(e is java.util.concurrent.CancellationException)throw e;limits+="$source: ${e.javaClass.simpleName}: ${e.message?.take(200)}" }
                    }
                }
            } catch(e:Exception) { if(e is java.util.concurrent.CancellationException)throw e;limits+="${apk.name}: ${e.javaClass.simpleName}: ${e.message?.take(200)}" }
        }
        limits += "Static signatures cannot inspect native code, encrypted strings, reflection targets or downloaded code."
        return StaticResult(manifest,endpoints.values.toList(),sdks.values.toList(),findings.values.toList(),files,limits)
    }
    fun readLimited(input:InputStream,max:Int):ByteArray {
        val out=java.io.ByteArrayOutputStream(); val buf=ByteArray(16384); var total=0
        while(true) { val n=input.read(buf); if(n<0) break; total+=n; require(total<=max) { "Input exceeds $max bytes" }; out.write(buf,0,n) }
        return out.toByteArray()
    }
}
