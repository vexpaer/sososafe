package app.sososafe.mobile

import android.app.Application
import androidx.room.Room
import app.sososafe.mobile.data.*
import app.sososafe.mobile.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class SosoApplication:Application() {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    lateinit var repo:AuditRepository;private set
    lateinit var actions:ActionManager;private set
    override fun onCreate(){
        super.onCreate()
        val db=Room.databaseBuilder(this,AuditDatabase::class.java,"sososafe.db").build()
        repo=AuditRepository(this,db,SettingsStore(this),PrivilegedAccess(this))
        actions=ActionManager(this,repo)
        scope.launch {
            // Interrupted scans are historical facts, never silently resumed.
            val j=app.sososafe.core.ReportWriter.json
            db.dao().allScans().forEach { row ->
                val record=j.decodeFromString<app.sososafe.core.ScanRecord>(row.json)
                if(record.status=="SCANNING")db.dao().scan(ScanEntity(row.id,row.time,j.encodeToString(app.sososafe.core.ScanRecord.serializer(),record.copy(status="INTERRUPTED",finishedAt=System.currentTimeMillis(),error="Process ended before scan completion"))))
            }
            db.dao().allActions().forEach { row ->
                val record=j.decodeFromString<app.sososafe.core.ActionRecord>(row.json)
                if(record.result=="PENDING")actions.save(record.copy(result="INTERRUPTED_UNKNOWN"))
            }
            while(isActive){val p=repo.settings.flow.first();db.dao().pruneConnections(System.currentTimeMillis()-p.retentionDays*86400000L);delay(3600000)}
        }
    }
}
