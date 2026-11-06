package app.sososafe.core

import kotlin.test.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AnalysisTest {
    @Test fun jsonChunksPreserveUnicodeAndLargeReports(){
        val value="abc😀中文".repeat(100000)
        val chunks=JsonChunks.split(value,32768)
        assertEquals(value,chunks.joinToString(""));assertTrue(chunks.all{it.length<=32768 && !it.last().isHighSurrogate()})
    }
    @Test fun permissionStatesRemainDistinct(){
        assertNull(PermissionRules.granted(null));assertFalse(PermissionRules.granted(0)!!);assertTrue(PermissionRules.granted(2)!!)
        assertEquals("Location",PermissionRules.group("android.permission.ACCESS_FINE_LOCATION"))
        assertEquals("signature/system",PermissionRules.protection(0x12));assertEquals("dangerous",PermissionRules.protection(1))
        assertEquals("Screen Capture",PermissionRules.group("FOREGROUND_SERVICE_MEDIA_PROJECTION"))
        val granted=PermissionFact("android.permission.CAMERA","Camera","dangerous",granted=true)
        assertTrue(PermissionRules.audit(listOf(granted)).single().points>PermissionRules.audit(listOf(granted.copy(appOp="ignore"))).single().points)
    }
    @Test fun boundedSuffixDomainRules(){
        assertTrue(DomainRules.classify("x.app-measurement.com").tracker)
        assertFalse(DomainRules.classify("app-measurement.com.evil.example").tracker)
        assertFalse(DomainRules.classify("notgoogle.com").provider=="Google")
        assertEquals("Authentication",DomainRules.classify("accounts.google.com").category)
        assertEquals("app-measurement.com",DomainRules.normalize("https://APP-MEASUREMENT.com/v1?q=1"))
        assertNull(DomainRules.normalize("https://"));assertTrue(DomainRules.extract("https://app-measurement.com/collect","fixture").single().tracker)
        assertEquals("api.example.com",DomainRules.extract("api.example.com/v1","fixture").single().host)
        assertTrue(DomainRules.extract("com.umeng.analytics","fixture").isEmpty())
        assertEquals("192.0.2.1",DomainRules.extract("http://192.0.2.1/api","fixture").single().host)
        assertEquals(SocketOwnership.address("2001:db8::1"),DomainRules.extract("https://[2001:db8::1]/api","fixture").single().host)
    }
    private val manifest="""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.audit"><uses-sdk android:targetSdkVersion="35"/><uses-permission android:name="android.permission.CAMERA"/><application android:permission="test.audit.PROTECTED" android:debuggable="true" android:networkSecurityConfig="@xml/network"><activity android:name=".Main" android:exported="true"/><provider android:name=".Data" android:authorities="test.audit"/><service android:name=".Worker"><intent-filter><action android:name="test.action"/></intent-filter></service></application></manifest>"""
    @Test fun manifestDefaultsAndInheritedProtection(){
        val m=ManifestParser.parse(manifest.toByteArray())
        assertEquals("test.audit",m.packageName);assertEquals(35,m.targetSdk);assertEquals(false,m.cleartext)
        assertFalse(m.components.first{it.kind=="provider"}.exported)
        assertEquals("test.audit.PROTECTED",m.components.first().permission)
        assertTrue(m.components.first{it.kind=="service"}.exported)
        assertEquals("@xml/network",m.networkSecurityConfig)
        assertTrue(ManifestParser.findings(m,"fixture").any{it.id=="manifest:debuggable"})
    }
    @Test fun rejectsExternalEntities(){assertFails{ManifestParser.parse("""<!DOCTYPE a [<!ENTITY x SYSTEM "file:///etc/passwd">]><manifest>&x;</manifest>""".toByteArray())}}
    @Test fun truncatedBinaryManifestIsRejected(){assertFails{ManifestParser.parse(ByteArray(32))}}
    @Test fun staticCorrelationNeverBecomesConfirmedLeak(){
        val p=PermissionFact("android.permission.CAMERA","Camera","dangerous",granted=null)
        val ep=DomainRules.extract("https://app-measurement.com/collect","fixture.dex")
        val result=RiskEngine.evaluate(AppRecord("test.audit","Audit",permissions=listOf(p),analysis=StaticResult(endpoints=ep)))
        assertEquals(EvidenceLevel.STATIC_REFERENCE,result.exposures.single().level)
        assertNull(result.exposures.single().granted)
        assertTrue(result.riskScore in 1..100)
        val granted=RiskEngine.evaluate(result.copy(permissions=listOf(p.copy(granted=true))))
        assertTrue(granted.riskScore>result.riskScore)
        assertEquals(Severity.MEDIUM,granted.exposures.single().severity)
        val c=ConnectionRecord("observed",0,0,"TCP","10.0.0.1",1000,"1.2.3.4",443,uid=10001,domain="app-measurement.com")
        val linked=ReportWriter.linkEvidence(listOf(granted.copy(uid=10001),granted.copy(uid=10002)),listOf(c))
        assertEquals(listOf("observed"),linked[0].exposures.single().observedConnectionIds)
        assertTrue(linked[1].exposures.single().observedConnectionIds.isEmpty())
        assertEquals(EvidenceLevel.STATIC_REFERENCE,linked[0].exposures.single().level)
    }
    @Test fun scoresAreCappedAndDeterministic(){
        val findings=(1..1000).map{Finding("manifest:$it","test",Severity.HIGH,10,Evidence("fixture","x",EvidenceLevel.STATIC_REFERENCE),"review")}
        val app=AppRecord("test.audit","A",analysis=StaticResult(findings=findings))
        assertEquals(30,RiskEngine.evaluate(app).riskScore)
        assertEquals(RiskEngine.evaluate(app),RiskEngine.evaluate(app))
    }
    @Test fun modePolicyRequiresAuthorization(){
        assertEquals(ScanMode.ROOT,ModePolicy.select(ScanMode.AUTO,true,true));assertEquals(ScanMode.SHIZUKU,ModePolicy.select(ScanMode.AUTO,false,true));assertEquals(ScanMode.STANDARD,ModePolicy.select(ScanMode.AUTO,false,false))
        assertFails{ModePolicy.select(ScanMode.ROOT,false,true)};assertFails{ModePolicy.select(ScanMode.SHIZUKU,true,false)}
    }
    @Test fun allowlistRejectsShellInjectionAndDangerousCommands(){
        assertTrue(Commands.valid(Commands.list(0)));assertTrue(Commands.valid(Commands.paths("test.audit",0)))
        assertTrue(Commands.valid(Commands.setOp("test.audit","CAMERA","ignore",0)))
        listOf("test.audit;reboot","test.audit\nreboot","$(id)","test.audit'", "--user").forEach{assertFails{Commands.packageName(it)}}
        assertFalse(Commands.valid(listOf("rm","-rf","/")));assertFalse(Commands.valid(listOf("reboot")))
        assertFails{Commands.apkPath("/data/data/test/private.apk")};assertFails{Commands.apkPath("/data/app/../private.apk")}
        assertFails{Commands.setOp("test.audit","RUN_COMMAND","allow",0)}
    }
    @Test fun rollbackRestoresExactOriginalModesAndEnabledStates(){
        Commands.modes.forEach{assertTrue(Commands.valid(Commands.setOp("test.audit","CAMERA",it,10)))}
        (0..4).forEach{assertTrue(Commands.valid(Commands.restoreEnabled("test.audit",it,0)))}
        assertEquals("default-state",Commands.restoreEnabled("test.audit",0,0)[1])
        assertEquals("foreground",Commands.opMode("CAMERA: foreground; time=123", "CAMERA"))
        assertEquals("allow",Commands.packageOpMode("Uid mode: CAMERA: ignore\nCAMERA: allow; time=0","CAMERA"))
        assertNull(Commands.packageOpMode("Uid mode: CAMERA: ignore","CAMERA"))
        assertEquals("allow",Commands.packageOpMode("No operations.\nDefault mode: allow\n","CAMERA"))
        assertEquals("ignore",Commands.uidOpMode("Uid mode: CAMERA: ignore\nCAMERA: allow","CAMERA"))
        assertTrue(Commands.valid(Commands.setUidOp("test.audit","CAMERA","foreground",0)))
    }
    @Test fun reportsContainEvidenceAndRoundTripJson(){
        val r=AuditReport(generatedAt=0,device=mapOf("model" to "fixture"),mode=ScanMode.STANDARD,apps=listOf(AppRecord("test.audit","A\"B")),scans=emptyList(),connections=emptyList(),actions=emptyList())
        val json=ReportWriter.render(r,"json");assertEquals(r,ReportWriter.json.decodeFromString<AuditReport>(json))
        listOf("json","md","txt").forEach{val text=ReportWriter.render(r,it);assertTrue(text.contains("test.audit"));assertTrue(text.contains("do not prove"));val stream=ByteArrayOutputStream();ReportWriter.write(r,it,stream);assertEquals(text,stream.toString("UTF-8"))}
        val untrusted=r.copy(apps=listOf(AppRecord("test.audit","![image](https://example.com/pixel)")))
        assertFalse(ReportWriter.render(untrusted,"md").contains("![image]("))
        assertTrue(ReportWriter.render(untrusted,"txt").contains("![image]("))
    }
    private fun dexWithString(value:String):ByteArray {
        val str=value.toByteArray();val data=byteArrayOf(str.size.toByte())+str+byteArrayOf(0)
        val mapOffset=(116+data.size+3) and -4
        val b=ByteBuffer.allocate(mapOffset+52).order(ByteOrder.LITTLE_ENDIAN)
        b.put("dex\n035\u0000".toByteArray());b.putInt(32,b.capacity());b.putInt(36,112);b.putInt(40,0x12345678)
        b.putInt(52,mapOffset);b.putInt(56,1);b.putInt(60,112);b.putInt(104,b.capacity()-116);b.putInt(108,116);b.position(112);b.putInt(116);b.put(data)
        b.position(mapOffset);b.putInt(4)
        listOf(Triple(0,1,0),Triple(1,1,112),Triple(0x2002,1,116),Triple(0x1000,1,mapOffset)).forEach{(type,count,offset)->b.putShort(type.toShort());b.putShort(0);b.putInt(count);b.putInt(offset)}
        val array=b.array();val signature=java.security.MessageDigest.getInstance("SHA-1").digest(array.copyOfRange(32,array.size));signature.copyInto(array,12)
        val checksum=java.util.zip.Adler32().apply{update(array,12,array.size-12)}.value.toInt();b.putInt(8,checksum)
        return b.array()
    }
    @Test fun parsesDexStringTableAndApkArchive(){
        val dex=dexWithString("https://app-measurement.com/collect")
        val scan=ApkAnalyzer.dex(dex,"fixture.dex")
        assertFailsWith<java.util.concurrent.CancellationException>{ApkAnalyzer.dex(dex,"fixture.dex"){throw java.util.concurrent.CancellationException()}}
        assertEquals("app-measurement.com",scan.endpoints.single().host);assertTrue(scan.sdks.isEmpty())
        val f=File.createTempFile("audit-fixture",".apk")
        try{ZipOutputStream(f.outputStream()).use{z->z.putNextEntry(ZipEntry("AndroidManifest.xml"));z.write(manifest.toByteArray());z.closeEntry();z.putNextEntry(ZipEntry("classes.dex"));z.write(dex);z.closeEntry()}
            val result=ApkAnalyzer.analyze(listOf(f));assertEquals("test.audit",result.manifest?.packageName);assertTrue(result.filesAnalyzed.any{it.endsWith("classes.dex")});assertEquals(1,result.endpoints.size)
        }finally{f.delete()}
    }
    @Test fun malformedDexAndOversizeStreamsFail(){assertFails{ApkAnalyzer.dex(ByteArray(100),"bad.dex")};assertFails{ApkAnalyzer.readLimited(ByteArray(100).inputStream(),50)}}
}
