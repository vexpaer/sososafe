package app.sososafe.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
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
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.sososafe.mobile.data.AuditDatabase
import kotlinx.serialization.encodeToString

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
    @Test fun largeReportMigrationPreservesUnicodeWithoutLargeCursorRows(){
        val name="migration-fixture.db";context.deleteDatabase(name)
        val schema=org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("app.sososafe.mobile.data.AuditDatabase/1.json").bufferedReader().use{it.readText()}).getJSONObject("database").getJSONArray("entities")
        val factory=FrameworkSQLiteOpenHelperFactory()
        val helper=factory.create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(1){
            override fun onCreate(db:SupportSQLiteDatabase){for(i in 0 until schema.length()){val table=schema.getJSONObject(i);db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}",table.getString("tableName")))}}
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int){error("Unexpected upgrade")}
        }).build())
        val value=ReportWriter.json.encodeToString(AppRecord("fixture.large","Unicode 😀",analysis=StaticResult(limitations=listOf("中文😀".repeat(300000)))))
        helper.writableDatabase.execSQL("INSERT INTO applications(packageName,risk,updated,json) VALUES(?,?,?,?)",arrayOf<Any>("fixture.large",0,1,value));helper.close()
        val migrated=Room.databaseBuilder(context,AuditDatabase::class.java,name).addMigrations(AuditDatabase.MIGRATION_1_2).build()
        try {
            val builder=StringBuilder()
            migrated.openHelper.writableDatabase.query("SELECT json FROM application_chunks ORDER BY sequence").use{cursor->while(cursor.moveToNext()){val chunk=cursor.getString(0);assertTrue(chunk.codePointCount(0,chunk.length)<=32768);builder.append(chunk)}}
            assertEquals(value,builder.toString())
        }finally{migrated.close();context.deleteDatabase(name)}
    }
    @Test fun localVpnActuallyForwardsShellTcpAndUdpAndStops()=runBlocking {
        val activity=ActivityScenario.launch(MainActivity::class.java)
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val intent=VpnService.prepare(context)
        if(intent!=null){
            activity.onActivity{host->@Suppress("DEPRECATION") host.startActivityForResult(intent,901)}
            assertTrue("VPN confirmation dialog did not appear",device.wait(Until.hasObject(By.res("android","button1")),10000))
            device.takeScreenshot(File(context.getExternalFilesDir(null),"vpn-consent.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null),"vpn-consent.xml"))
            device.findObject(By.res("android","button1"))?.click()
        }
        withTimeout(15000){while(VpnService.prepare(context)!=null)delay(100)}
        context.startForegroundService(Intent(context,MonitorVpnService::class.java))
        try {
            withTimeout(15000){while(!MonitorVpnService.state.value.running){MonitorVpnService.state.value.error?.let{error(it)};delay(100)}}
            delay(1000)
            // Shell UID is outside sososafe's excluded UID and therefore traverses the TUN.
            val tcp=shell("echo soso-tcp | toybox nc -w 5 -W 5 10.0.2.2 18080")
            assertTrue("TCP relay returned: $tcp",tcp.contains("soso-tcp"))
            val udp=shell("echo soso-udp | toybox nc -u -w 5 -W 3 10.0.2.2 18081")
            assertTrue("UDP relay returned: $udp",udp.contains("soso-udp"))
            withTimeout(10000){while(app.repo.dao.allConnections().none{ReportWriter.json.decodeFromString<ConnectionRecord>(it.json).remotePort==18080})delay(500)}
            val records=app.repo.dao.allConnections().map{ReportWriter.json.decodeFromString<ConnectionRecord>(it.json)}
            assertTrue(records.any{it.protocol=="TCP" && it.uploaded>0 && it.downloaded>0})
            assertTrue(records.any{it.protocol=="UDP" && it.remotePort==18081})
        } finally {
            context.startService(Intent(context,MonitorVpnService::class.java).setAction(MonitorVpnService.STOP))
            withTimeout(10000){while(MonitorVpnService.state.value.running)delay(100)}
            activity.close()
        }
    }
}
