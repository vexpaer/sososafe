package app.sososafe.mobile

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sososafe.core.*
import app.sososafe.mobile.data.*
import app.sososafe.mobile.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

class AuditViewModel(application:Application):AndroidViewModel(application) {
    val app=application as SosoApplication;val repo=app.repo
    val apps=repo.apps.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val scans=repo.scans.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val connections=repo.connections.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val actions=repo.actions.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val settings=repo.settings.flow.stateIn(viewModelScope,SharingStarted.Eagerly,UserSettings())
    val privilege=repo.access.state;val progress=repo.progress
    val message=MutableStateFlow<String?>(null);val busy=MutableStateFlow(false)
    val plan=MutableStateFlow<ActionPlan?>(null)
    fun scan(){repo.scan(viewModelScope)}
    fun cancel(){repo.cancelScan()}
    fun importApk(uri:Uri)=task {repo.importApk(uri);message.value="APK analysis saved / APK 分析已保存"}
    fun refresh(){repo.access.refresh()}
    fun root()=task {repo.access.requestRoot();message.value=repo.access.state.value.message}
    fun shizuku(){runCatching{repo.access.requestShizuku()}.onFailure{message.value=it.message}}
    fun set(key:String,value:String){viewModelScope.launch{repo.settings.set(key,value)}}
    fun set(key:String,value:Boolean){viewModelScope.launch{repo.settings.set(key,value)}}
    fun retention(days:Int){viewModelScope.launch{repo.settings.retention(days)}}
    fun accent(value:String){viewModelScope.launch{repo.settings.accent(value)}}
    fun prepare(app:AppRecord,kind:String,argument:String="")=task{plan.value=this.app.actions.prepare(app,kind,argument)}
    fun execute() {val p=plan.value ?: return;plan.value=null;task{val result=app.actions.execute(p);message.value=result.result;if(result.result=="AWAITING_SYSTEM_CONFIRMATION")app.actions.launchUninstall(result)}}
    fun rollback(record:ActionRecord)=task{app.actions.rollback(record);message.value="Rollback result saved / 回滚结果已保存"}
    fun reconcile()=task{app.actions.reconcileUninstall()}
    fun export(format:String,done:(File)->Unit)=task{val file=repo.export(format);withContext(Dispatchers.Main){done(file)}}
    fun clear()=task{repo.clearAudit();message.value="Audit data cleared; action history and recovery APKs retained / 审计数据已清除，保留处置历史和恢复 APK"}
    fun shellNetwork()=task{val mode=repo.access.selected(settings.value.mode);message.value=repo.access.execute(mode,listOf("ss","-tunape")).checked().take(12000).ifBlank{"No socket records visible / 未获得可见连接"}}
    private fun task(block:suspend ()->Unit) {viewModelScope.launch(Dispatchers.IO){busy.value=true;try{block()}catch(e:CancellationException){throw e}catch(e:Exception){message.value=e.message ?: e.javaClass.simpleName}finally{busy.value=false}}}
}
