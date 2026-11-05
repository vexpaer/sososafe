package app.sososafe.mobile.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import app.sososafe.core.ActionRecord
import app.sososafe.core.ReportWriter
import app.sososafe.mobile.SosoApplication
import kotlinx.coroutines.*

class InstallResultReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val confirm=intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity);return
        }
        val id=intent.getStringExtra("action_id") ?: return
        val pending=goAsync();val app=context.applicationContext as SosoApplication
        app.scope.launch(Dispatchers.IO) {
            try {
                val row=app.repo.dao.allActions().firstOrNull{it.id==id} ?: return@launch
                val record=ReportWriter.json.decodeFromString<ActionRecord>(row.json)
                app.actions.save(record.copy(rolledBack=status==PackageInstaller.STATUS_SUCCESS,result=if(status==PackageInstaller.STATUS_SUCCESS) "RESTORED: APK only; private data not restored" else "ROLLBACK_FAILED: Android installer status $status"))
            } finally {pending.finish()}
        }
    }
}
