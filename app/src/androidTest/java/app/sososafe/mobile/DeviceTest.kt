package app.sososafe.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.sososafe.core.*
import app.sososafe.mobile.platform.*
import app.sososafe.mobile.platform.vpn.MonitorVpnService
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val app=context as SosoApplication
    private val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun shell(command:String):String=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use{it.readText()}
    @Test fun standardScanReadsRealMetadataAndApk()=runBlocking {
        val scanner=PackageScanner(context,app.repo.access,ScanMode.STANDARD)
        val inventory=scanner.inventory();assertTrue(inventory.any{it.name==context.packageName})
        val record=scanner.scan(inventory.first{it.name==context.packageName},true)
        assertEquals(context.packageName,record.packageName);assertTrue(record.uid>=0);assertNotNull(record.analysis.manifest)
        assertTrue(record.analysis.filesAnalyzed.any{it.endsWith(".dex")});assertTrue(record.permissions.any{it.name=="android.permission.INTERNET"})
        assertTrue(record.riskScore in 0..100);app.repo.save(record)
        listOf("json","md","txt").forEach{val report=app.repo.export(it);assertTrue(report.length()>0);assertTrue(report.readText().contains(context.packageName))}
    }
    @Test fun importsActualApkAndPersistsSeparateRecord()=runBlocking {
        val path=context.packageManager.getApplicationInfo(context.packageName,0).sourceDir
        val imported=app.repo.importApk(Uri.fromFile(File(path)))
        assertTrue(imported.imported);assertTrue(imported.packageName.startsWith("import:"));assertTrue(imported.permissions.all{it.granted==null})
    }
    @Test fun shizukuUserServiceEnforcesAllowlistWithoutRoot(){
        val service=PrivilegedUserService()
        val denied=ReportWriter.json.decodeFromString<ShellResult>(service.execute(arrayOf("reboot")))
        assertTrue(denied.code!=0)
        val result=ReportWriter.json.decodeFromString<ShellResult>(service.execute(arrayOf("id")))
        assertEquals(0,result.code);assertTrue(result.output.contains("uid="))
    }
    @Test fun localVpnActuallyForwardsShellTcpAndUdpAndStops()=runBlocking {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val intent=VpnService.prepare(context)
        if(intent!=null){context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));device.wait(Until.hasObject(By.res("android","button1")),10000);device.findObject(By.res("android","button1"))?.click()}
        withTimeout(15000){while(VpnService.prepare(context)!=null)delay(100)}
        context.startForegroundService(Intent(context,MonitorVpnService::class.java))
        try {
            withTimeout(15000){while(!MonitorVpnService.state.value.running){MonitorVpnService.state.value.error?.let{error(it)};delay(100)}}
            delay(1000)
            // Shell UID is outside sososafe's excluded UID and therefore traverses the TUN.
            val tcp=shell("echo soso-tcp | toybox nc -w 5 10.0.2.2 18080")
            assertTrue("TCP relay returned: $tcp",tcp.contains("soso-tcp"))
            val udp=shell("echo soso-udp | toybox nc -u -w 3 10.0.2.2 18081")
            assertTrue("UDP relay returned: $udp",udp.contains("soso-udp"))
            withTimeout(10000){while(app.repo.dao.allConnections().none{ReportWriter.json.decodeFromString<ConnectionRecord>(it.json).remotePort==18080})delay(500)}
            val records=app.repo.dao.allConnections().map{ReportWriter.json.decodeFromString<ConnectionRecord>(it.json)}
            assertTrue(records.any{it.protocol=="TCP" && it.uploaded>0 && it.downloaded>0})
            assertTrue(records.any{it.protocol=="UDP" && it.remotePort==18081})
        } finally {
            context.startService(Intent(context,MonitorVpnService::class.java).setAction(MonitorVpnService.STOP))
            withTimeout(10000){while(MonitorVpnService.state.value.running)delay(100)}
        }
    }
}
