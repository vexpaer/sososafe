package app.sososafe.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import app.sososafe.mobile.ui.SosoApp

class MainActivity:ComponentActivity() {
    private lateinit var model:AuditViewModel
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        model=ViewModelProvider(this)[AuditViewModel::class.java]
        setContent {SosoApp(model)}
    }
    override fun onResume(){super.onResume();if(::model.isInitialized){model.refresh();model.reconcile()}}
}
