@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.sososafe.mobile.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sososafe.core.*
import app.sososafe.mobile.AuditViewModel
import app.sososafe.mobile.BuildConfig
import app.sososafe.mobile.platform.vpn.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private data class Destination(val en:String,val zh:String,val icon:ImageVector)
private val destinations=listOf(Destination("Overview","概览",Icons.Default.Shield),Destination("Applications","应用",Icons.Default.Apps),Destination("Network","网络",Icons.Default.Lan),Destination("Actions","处置",Icons.Default.Rule),Destination("Settings","设置",Icons.Default.Settings))
internal fun date(time:Long?)=time?.let{DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(it))} ?: "—"
internal fun bytes(value:Long):String=when {value>=1024L*1024*1024->"%.2f GiB".format(value/1073741824.0);value>=1024*1024->"%.1f MiB".format(value/1048576.0);value>=1024->"%.1f KiB".format(value/1024.0);else->"$value B"}

@Composable fun SosoApp(vm:AuditViewModel){
    val settings by vm.settings.collectAsStateWithLifecycle()
    SosoTheme(settings){
        if(!settings.loaded){Surface(Modifier.fillMaxSize()){Box(contentAlignment=Alignment.Center){CircularProgressIndicator()}};return@SosoTheme}
        val apps by vm.apps.collectAsStateWithLifecycle();val scans by vm.scans.collectAsStateWithLifecycle()
        val connections by vm.connections.collectAsStateWithLifecycle();val actions by vm.actions.collectAsStateWithLifecycle()
        val progress by vm.progress.collectAsStateWithLifecycle();val privilege by vm.privilege.collectAsStateWithLifecycle()
        val monitor by MonitorVpnService.state.collectAsStateWithLifecycle();val busy by vm.busy.collectAsStateWithLifecycle()
        val plan by vm.plan.collectAsStateWithLifecycle();val message by vm.message.collectAsStateWithLifecycle()
        val context=LocalContext.current
        var tab by rememberSaveable{mutableIntStateOf(0)};var selected by rememberSaveable{mutableStateOf<String?>(null)}
        var vpnDialog by remember{mutableStateOf(false)};var rootDialog by remember{mutableStateOf(false)}
        var clearDialog by remember{mutableStateOf(false)};var rollback by remember{mutableStateOf<ActionRecord?>(null)}
        var exportFile by remember{mutableStateOf<File?>(null)}
        val snackbar=remember{SnackbarHostState()}
        val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let(vm::importApk)}
        fun startVpn(){context.startForegroundService(Intent(context,MonitorVpnService::class.java))}
        val vpnConsent=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){r->if(r.resultCode==android.app.Activity.RESULT_OK)startVpn()else vm.message.value="VPN permission cancelled / VPN 授权已取消"}
        fun consentVpn(){val intent=VpnService.prepare(context);if(intent==null)startVpn()else vpnConsent.launch(intent)}
        val networkPermissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->
            if(Build.VERSION.SDK_INT>=37 && result["android.permission.ACCESS_LOCAL_NETWORK"]==false)vm.message.value="Local network permission is required to forward LAN traffic / 转发局域网流量需要本地网络权限"
            else consentVpn()
        }
        val saveDocument=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
            if(uri!=null){val file=exportFile;vm.app.scope.launchSafe(vm){withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri)).use{out->checkNotNull(file).inputStream().use{it.copyTo(out)}}};vm.message.value="Report saved / 报告已保存"}}
        }
        LaunchedEffect(message){if(message!=null && message!!.length<300){snackbar.showSnackbar(message!!);vm.message.value=null}}
        BackHandler(selected!=null){selected=null}
        val selectedApp=apps.firstOrNull{it.packageName==selected}
        BoxWithConstraints(Modifier.fillMaxSize()){
            val expanded=maxWidth>=840.dp
            Scaffold(
                topBar={TopAppBar(title={Text(if(selectedApp!=null)selectedApp.label else t(destinations[tab].en,destinations[tab].zh),fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background),navigationIcon={if(selectedApp!=null)IconButton(onClick={selected=null}){Icon(Icons.AutoMirrored.Filled.ArrowBack,t("Back","返回"))}},actions={
                    if(tab==1 && selectedApp==null)IconButton(onClick={picker.launch(arrayOf("application/vnd.android.package-archive","application/octet-stream","application/zip"))},enabled=!busy){Icon(Icons.Default.FileOpen,t("Import APK","导入 APK"))}
                    if(selectedApp!=null)IconButton(onClick={openSettings(context,selectedApp.packageName)},enabled=!selectedApp.imported){Icon(Icons.Default.OpenInNew,t("App details","应用详情"))}
                })},
                bottomBar={if(!expanded)NavigationBar{destinations.forEachIndexed{i,d->NavigationBarItem(selected=tab==i,onClick={tab=i;selected=null},icon={Icon(d.icon,t(d.en,d.zh))},label={Text(if(i==1)t("Apps","应用")else t(d.en,d.zh),maxLines=1)},alwaysShowLabel=true)}}},
                snackbarHost={SnackbarHost(snackbar)}
            ){padding->
                Row(Modifier.fillMaxSize().padding(padding)){
                    if(expanded)NavigationRail{destinations.forEachIndexed{i,d->NavigationRailItem(selected=tab==i,onClick={tab=i;selected=null},icon={Icon(d.icon,t(d.en,d.zh))},label={Text(if(i==1)t("Apps","应用")else t(d.en,d.zh),maxLines=1)})}}
                    Column(Modifier.weight(1f).fillMaxHeight()){
                        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                        if(progress.status=="IMPORTING")Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){Text(t("Analyzing imported APK…","正在分析导入的 APK…"),Modifier.weight(1f));TextButton(onClick=vm::cancel){Text(t("Cancel","取消"))}}
                        if(selectedApp!=null) AppDetail(selectedApp,connections,vm)
                        else when(tab){
                            0->Dashboard(apps,scans,progress,privilege,settings.mode,vm,onApps={tab=1},onSettings={tab=4})
                            1->Applications(apps,onSelect={selected=it.packageName},onScan=vm::scan,onImport={picker.launch(arrayOf("application/vnd.android.package-archive","application/octet-stream","application/zip"))})
                            2->NetworkScreen(connections,monitor,onStart={vpnDialog=true},onStop={context.startService(Intent(context,MonitorVpnService::class.java).setAction(MonitorVpnService.STOP))},onShell=vm::shellNetwork)
                            3->ActionsScreen(apps,actions,onApp={selected=it.packageName},onRollback={rollback=it})
                            4->SettingsScreen(vm,onRoot={rootDialog=true},onClear={clearDialog=true},onExport={format->vm.export(format){exportFile=it}})
                        }
                    }
                }
            }
        }
        if(!settings.languageChosen)AlertDialog(onDismissRequest={},title={Text("选择语言 / Choose language")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){OutlinedButton(onClick={vm.set("language","zh")},modifier=Modifier.fillMaxWidth()){Text("中文")};OutlinedButton(onClick={vm.set("language","en")},modifier=Modifier.fillMaxWidth()){Text("English")}}},confirmButton={})
        if(vpnDialog)AlertDialog(onDismissRequest={vpnDialog=false},title={Text(t("Start local monitoring?","启动本地网络监控？"))},text={Text(t("Android will ask for VPN permission. The existing VPN will be replaced. TCP/UDP traffic is forwarded on this device; only connection and DNS metadata is saved. No HTTPS decryption. sososafe's own traffic is excluded. Encrypted DNS and ECH may hide names. Network switches can require apps to reconnect.","Android 将请求 VPN 授权，并替换当前 VPN。TCP/UDP 流量在设备本地转发，仅保存连接和 DNS 元数据。不解密 HTTPS。排除 sososafe 自身流量。加密 DNS 与 ECH 可能隐藏域名，切换网络时应用可能需要重连。"))},confirmButton={TextButton(onClick={vpnDialog=false;val required=buildList { if(Build.VERSION.SDK_INT>=33)add(Manifest.permission.POST_NOTIFICATIONS);if(Build.VERSION.SDK_INT>=37)add("android.permission.ACCESS_LOCAL_NETWORK") }.filter{context.checkSelfPermission(it)!=android.content.pm.PackageManager.PERMISSION_GRANTED};if(required.isEmpty())consentVpn()else networkPermissions.launch(required.toTypedArray())}){Text(t("Continue","继续"))}},dismissButton={TextButton(onClick={vpnDialog=false}){Text(t("Cancel","取消"))}})
        if(rootDialog)AlertDialog(onDismissRequest={rootDialog=false},title={Text(t("Request Root authorization","申请 Root 授权"))},text={Text(t("This starts su and may show your KernelSU, Magisk or APatch authorization dialog. Only id is executed to verify access. Scans remain read-only; every change has a separate confirmation.","将调用 su，可能显示 KernelSU、Magisk 或 APatch 的授权窗口。仅执行 id 验证权限。扫描保持只读，每次修改均需另行确认。"))},confirmButton={TextButton(onClick={rootDialog=false;vm.root()}){Text(t("Request Root","申请 Root"))}},dismissButton={TextButton(onClick={rootDialog=false}){Text(t("Cancel","取消"))}})
        plan?.let{p->AlertDialog(onDismissRequest={vm.plan.value=null},title={Text(t("Confirm device change","确认设备修改"))},text={SelectionContainer{Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())){Text("${p.app.label}\n${p.app.packageName}",fontWeight=FontWeight.Bold);Text("\n${p.kind}: ${p.argument}\n${p.original} → ${p.desired}\n\n${p.effect}");Text(t("\nOriginal state is saved before execution. Restore from Actions history.","\n执行前保存原始状态，可在处置历史中恢复。"))}}},confirmButton={TextButton(onClick=vm::execute){Text(t("Confirm change","确认修改"))}},dismissButton={TextButton(onClick={vm.plan.value=null}){Text(t("Cancel","取消"))}})}
        rollback?.let{r->AlertDialog(onDismissRequest={rollback=null},title={Text(t("Restore previous state?","恢复之前状态？"))},text={Text("${r.packageName}\n${r.action} ${r.argument}\n${r.desired} → ${r.original}\n"+t("This may re-enable access. Reinstall restores APK files only; deleted private data cannot be recovered.","此操作可能恢复原有权限。重新安装仅恢复 APK，无法恢复被删除的应用私有数据。"))},confirmButton={TextButton(onClick={rollback=null;vm.rollback(r)}){Text(t("Restore","恢复"))}},dismissButton={TextButton(onClick={rollback=null}){Text(t("Cancel","取消"))}})}
        if(clearDialog)AlertDialog(onDismissRequest={clearDialog=false},title={Text(t("Clear local audit data?","清除本地审计数据？"))},text={Text(t("Deletes scan results, connection metadata and exported reports. Action history and recovery APKs are retained for rollback. Stop monitoring and scans first.","删除扫描结果、网络元数据和已导出报告。处置历史与恢复 APK 将保留，以便回滚。请先停止监控和扫描。"))},confirmButton={TextButton(onClick={clearDialog=false;if(monitor.running)vm.message.value="Stop monitoring first / 请先停止监控"else vm.clear()}){Text(t("Clear data","清除数据"))}},dismissButton={TextButton(onClick={clearDialog=false}){Text(t("Cancel","取消"))}})
        exportFile?.let{file->AlertDialog(onDismissRequest={exportFile=null},title={Text(t("Report saved locally","报告已本地保存"))},text={Text(t("Review sensitive information before sharing: app inventory, permission states, DNS names, IPs and device details may be included.\n","分享前请检查敏感信息：报告可能包含应用清单、权限状态、DNS 域名、IP 与设备信息。\n")+file.name)},confirmButton={TextButton(onClick={val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if(file.extension=="json")"application/json"else "text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"sososafe"));exportFile=null}){Text(t("Share","分享"))}},dismissButton={TextButton(onClick={saveDocument.launch(file.name)}){Text(t("Save copy","另存副本"))}})}
        if(message!=null && message!!.length>=300)AlertDialog(onDismissRequest={vm.message.value=null},title={Text(t("Result","结果"))},text={SelectionContainer{Text(message!!,Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()))}},confirmButton={TextButton(onClick={vm.message.value=null}){Text(t("Close","关闭"))}})
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchSafe(vm:AuditViewModel,block:suspend ()->Unit){launch {try{block()}catch(e:Exception){vm.message.value=e.message}}}
private fun openSettings(context:android.content.Context,pkg:String){context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$pkg")))}

@Composable internal fun Section(title:String,subtitle:String?=null){Column(Modifier.fillMaxWidth().padding(top=16.dp,bottom=6.dp)){Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold);if(subtitle!=null)Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
@Composable internal fun Panel(content:@Composable ColumnScope.()->Unit){Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(0.75.dp,MaterialTheme.colorScheme.outlineVariant)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)}}
@Composable internal fun modeName(mode:ScanMode?)=when(mode){ScanMode.AUTO->t("Automatic","自动");ScanMode.ROOT->"Root";ScanMode.SHIZUKU->"Shizuku";ScanMode.STANDARD->t("Standard","普通模式");else->t("Authorization required","需要授权")}
@Composable internal fun MoreInfo(title:String,body:String){var open by remember{mutableStateOf(false)};TextButton(onClick={open=true}){Icon(Icons.Default.Info,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(title)};if(open)AlertDialog(onDismissRequest={open=false},title={Text(title)},text={Text(body,Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={open=false}){Text(t("Close","关闭"))}})}
@Composable internal fun Empty(icon:ImageVector,title:String,body:String,action:String?=null,onAction:()->Unit={}){Column(Modifier.fillMaxWidth().padding(vertical=36.dp,horizontal=24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){Icon(icon,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary);Text(title,style=MaterialTheme.typography.titleLarge);Text(body,style=MaterialTheme.typography.bodyMedium);if(action!=null)Button(onClick=onAction){Text(action)}}}
@Composable private fun Fact(label:String,value:String){Column(Modifier.fillMaxWidth().padding(vertical=8.dp)){Text(label,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant);SelectionContainer{Text(value,style=MaterialTheme.typography.bodyLarge)}}}
@Composable private fun Risk(score:Int){val color=when{score>=60->MaterialTheme.colorScheme.error;score>=30->MaterialTheme.colorScheme.tertiary;else->MaterialTheme.colorScheme.primary};Surface(shape=MaterialTheme.shapes.small,color=color.copy(alpha=0.12f)){Text("$score / 100",Modifier.padding(horizontal=10.dp,vertical=6.dp),color=color,style=MaterialTheme.typography.labelLarge)}}

@Composable private fun Dashboard(apps:List<AppRecord>,scans:List<ScanRecord>,progress:app.sososafe.mobile.data.ScanProgress,priv:app.sososafe.mobile.platform.PrivilegeState,preferred:ScanMode,vm:AuditViewModel,onApps:()->Unit,onSettings:()->Unit){
    val mode=runCatching{ModePolicy.select(preferred,priv.rootAuthorized,priv.shizukuAuthorized)}.getOrNull()
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item{Panel{
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.primaryContainer){Icon(Icons.Default.Shield,null,Modifier.padding(12.dp).size(28.dp),tint=MaterialTheme.colorScheme.onPrimaryContainer)};Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(t("Privacy audit","隐私审计"),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold);Text(if(apps.isEmpty())t("Ready to scan","准备扫描")else t("Results saved","结果已保存"),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)};TextButton(onClick=onSettings){Text(modeName(mode));Icon(Icons.Default.ChevronRight,null,Modifier.size(18.dp))}}
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(t("Security score","安全评分"),style=MaterialTheme.typography.bodyLarge);Text(if(apps.isEmpty())"—"else "${100-apps.map{it.riskScore}.average().toInt()} / 100",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.primary)}
        }}
        item{Panel{Row(horizontalArrangement=Arrangement.spacedBy(24.dp)){Column(Modifier.weight(1f)){Fact(t("Applications","应用"),apps.size.toString());Fact(t("High / medium risk","高 / 中风险"),"${apps.count{it.risk==Severity.HIGH}} / ${apps.count{it.risk==Severity.MEDIUM}}")};Column(Modifier.weight(1f)){Fact(t("Endpoints","网络端点"),apps.flatMap{it.analysis.endpoints}.map{it.host}.distinct().size.toString());Fact(t("Tracking services","追踪服务"),apps.flatMap{it.analysis.endpoints}.filter{it.tracker}.map{it.provider+it.host}.distinct().size.toString())}}}}
        if(progress.status=="SCANNING")item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){LinearProgressIndicator(progress={if(progress.total>0)progress.done.toFloat()/progress.total else 0f},modifier=Modifier.fillMaxWidth());Text("${progress.done} / ${progress.total} · ${progress.current}",style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick=vm::cancel){Text(t("Cancel scan","取消扫描"))}}}
        else item{Button(onClick=vm::scan,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Icon(Icons.Default.Search,null);Spacer(Modifier.width(8.dp));Text(t("Scan applications","扫描应用"))}}
        if(progress.status in listOf("COMPLETED","CANCELLED","ERROR"))item{Text(when(progress.status){"COMPLETED"->t("Scan complete","扫描完成");"CANCELLED"->t("Scan cancelled","扫描已取消");else->progress.error ?: t("Scan failed. Check access in Settings.","扫描失败，请检查访问权限。")},color=if(progress.status=="ERROR")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}
        if(scans.isNotEmpty())item{Text(t("Last scan · ","上次扫描 · ")+date(scans.first().startedAt),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(apps.isNotEmpty())item{TextButton(onClick=onApps){Text(t("View applications","查看应用"));Icon(Icons.Default.ChevronRight,null)}}
        item{MoreInfo(t("About evidence & scores","证据与评分"),t("Scores prioritize review; they do not certify safety. Capability, static references and observed connections are separate evidence. They do not confirm personal data disclosure. HTTPS content is not inspected.","评分用于确定检查优先级。访问能力、静态引用与实际连接分别记录，不代表已证实个人数据泄露。应用不检查 HTTPS 明文。"))}
    }
}

@Composable private fun Applications(apps:List<AppRecord>,onSelect:(AppRecord)->Unit,onScan:()->Unit,onImport:()->Unit){
    var query by rememberSaveable{mutableStateOf("")};var filter by rememberSaveable{mutableStateOf("all")};var sort by rememberSaveable{mutableStateOf("risk")}
    val filtered=remember(apps,query,filter,sort){apps.filter{(query.isBlank()||it.label.contains(query,true)||it.packageName.contains(query,true))&&when(filter){"high"->it.risk==Severity.HIGH;"medium"->it.risk==Severity.MEDIUM;"system"->it.system;"imported"->it.imported;else->true}}.let{list->when(sort){"name"->list.sortedBy{it.label.lowercase()};"endpoints"->list.sortedByDescending{it.analysis.endpoints.size};else->list.sortedByDescending{it.riskScore}}}}
    Column(Modifier.fillMaxSize()){
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(horizontal=16.dp),label={Text(t("Search name or package","搜索名称或包名"))},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf("all" to t("All","全部"),"high" to t("High","高风险"),"medium" to t("Medium","中风险"),"system" to t("System","系统"),"imported" to t("Imported","已导入")).forEach{(key,label)->FilterChip(selected=filter==key,onClick={filter=key},label={Text(label)})}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){Text("${filtered.size} "+t("results","项结果"),Modifier.weight(1f),style=MaterialTheme.typography.labelLarge);Box(Modifier.width(190.dp)){Choice(t("Sort","排序"),sort,listOf("risk" to t("Risk","风险"),"name" to t("Name","名称"),"endpoints" to t("Endpoints","端点"))){sort=it}}}
        if(apps.isEmpty())Empty(Icons.Default.Apps,t("No audit yet","暂无审计结果"),t("Scan installed applications or import an APK for offline analysis.","扫描已安装应用，或导入 APK 进行离线分析。"),t("Scan applications","扫描应用"),onScan)
        else if(filtered.isEmpty())Empty(Icons.Default.SearchOff,t("No matching applications","没有匹配的应用"),t("Change the search or filter.","请调整搜索条件或筛选项。"))
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(filtered,key={it.packageName}){app->AppRow(app,onClick={onSelect(app)})}}
        TextButton(onClick=onImport,modifier=Modifier.align(Alignment.CenterHorizontally)){Icon(Icons.Default.FileOpen,null);Spacer(Modifier.width(8.dp));Text(t("Import APK","导入 APK"))}
    }
}
@Composable private fun AppRow(app:AppRecord,onClick:()->Unit){
    val context=LocalContext.current
    val icon by produceState<ImageBitmap?>(null,app.packageName){value=withContext(Dispatchers.IO){runCatching{context.packageManager.getApplicationIcon(app.packageName).toBitmap(96,96).asImageBitmap()}.getOrNull()}}
    Surface(shape=RoundedCornerShape(16.dp),border=BorderStroke(0.75.dp,MaterialTheme.colorScheme.outlineVariant),color=MaterialTheme.colorScheme.surfaceContainerLow){ListItem(headlineContent={Text(app.label,maxLines=2,fontWeight=FontWeight.Medium)},supportingContent={Column{Text(app.packageName,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${app.analysis.endpoints.size} "+t("endpoints","个端点")+" · "+app.permissions.filter{it.group!="Other"}.map{it.group}.distinct().take(2).joinToString(),style=MaterialTheme.typography.bodySmall)}},leadingContent={if(icon!=null)Image(icon!!,null,Modifier.size(40.dp))else Icon(Icons.Default.Android,null,Modifier.size(40.dp))},trailingContent={Risk(app.riskScore)},colors=ListItemDefaults.colors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow),modifier=Modifier.clickable(onClick=onClick))}
}

@Composable private fun AppDetail(app:AppRecord,connections:List<ConnectionRecord>,vm:AuditViewModel){
    var page by rememberSaveable(app.packageName){mutableIntStateOf(0)}
    val titles=listOf(t("Overview","概览"),t("Permissions","权限"),t("Endpoints","端点"),t("SDKs","SDK"),t("Manifest","清单"),t("DEX","DEX"),t("Advice","建议"))
    Column(Modifier.fillMaxSize()){
        ScrollableTabRow(page,edgePadding=12.dp){titles.forEachIndexed{i,title->Tab(selected=page==i,onClick={page=i},text={Text(title)})}}
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            when(page){
                0->{
                    item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(t("Review priority","检查优先级"),style=MaterialTheme.typography.titleLarge);Risk(app.riskScore)}}
                    item{Fact(t("Package","包名"),app.packageName);Fact(t("Version / UID / Target SDK","版本 / UID / 目标 SDK"),"${app.version} / ${app.uid} / ${app.targetSdk}");Fact(t("Source / Scan","来源 / 扫描"),"${app.installer ?: "Unknown"} · ${app.mode} · ${date(app.scannedAt)}");Fact(t("System / Enabled / Shared UID","系统 / 启用 / 共享 UID"),"${app.system} / ${app.enabled} / ${app.sharedUid ?: "—"}")}
                    item{MoreInfo(t("Score & coverage","计分与分析范围"),t("Score contributions:\n","分项计分：\n")+app.scoreComponents.entries.joinToString("\n"){"${it.key}: ${it.value}"}+"\n\n"+(app.limitations+app.analysis.limitations).joinToString("\n\n"))}
                    item{MoreInfo(t("Analyzed files","已分析文件"),app.analysis.filesAnalyzed.joinToString("\n").ifEmpty{t("No readable APK analyzed.","尚未分析可读 APK。")})}
                    item{Section(t("Data exposure correlations","数据暴露关联"))}
                    if(app.exposures.isEmpty())item{Text(t("No capability / endpoint pairs were found within this scan's coverage.","本次扫描范围内未发现可关联的权限与端点。"))}
                    items(app.exposures.take(200)){link->Fact("${link.dataType} → ${link.provider}","${link.host}\n${link.permission}\n${link.severity} · ${link.level}")}
                }
                1->{
                    item{MoreInfo(t("Permission states","权限状态"),t("Declared, granted, AppOps and special access are separate facts. Unknown does not mean denied.","声明、实际授予、AppOps 与特殊权限分别记录；未知不代表拒绝。"))}
                    if(app.permissions.isEmpty())item{Text(t("No permission information available.","暂无可用权限信息。"))}
                    items(app.permissions){p->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text(p.group,style=MaterialTheme.typography.titleMedium);SelectionContainer{Text(p.name,style=MaterialTheme.typography.bodyMedium)};Text("${p.protection}\nDeclared=${p.declared} · Granted=${p.granted ?: "Unknown"}\nAppOps=${p.appOp ?: "Unknown"}\nSpecial=${p.specialState ?: "—"}\n${p.evidence}",style=MaterialTheme.typography.bodySmall)}}}
                    app.appOps?.let{item{Section("AppOps");SelectionContainer{Text(it,style=MaterialTheme.typography.bodySmall)}}}
                }
                2->{
                    if(app.analysis.endpoints.isEmpty())item{Empty(Icons.Default.Lan,t("No extracted endpoints","未提取到端点"),t("Check APK coverage. Encrypted or dynamically built addresses may not be visible.","请检查 APK 分析范围。加密或动态构建的地址可能不可见。"))}
                    items(app.analysis.endpoints){ep->
                        val observed=connections.filter{it.uid==app.uid && app.uid>=0 && (it.domain==ep.host || ep.host in it.dnsQueries)}
                        Fact(ep.host,"${ep.value}\n${ep.provider} · ${ep.category}\n${ep.evidence.level} · ${ep.evidence.source}"+if(observed.isNotEmpty())"\nObserved UID metadata / DNS association: ${observed.size}; shared UID=${app.sharedUid!=null}; host association may be ambiguous" else "")
                        HorizontalDivider()
                    }
                }
                3->{if(app.analysis.sdks.isEmpty())item{Text(t("No supported SDK class signatures found. This is not proof that no SDK is present.","未找到规则覆盖的 SDK 类签名，不代表不存在其他 SDK。"))};items(app.analysis.sdks){Fact("${it.name} · ${it.category}","${it.signature}\n${it.source}")}}
                4->{
                    val m=app.analysis.manifest
                    if(m==null)item{Empty(Icons.Default.Description,t("Manifest unavailable","无法读取清单"),t("Import a readable APK or enable an authorized scan mode.","请导入可读 APK，或启用已授权的增强扫描模式。"))}
                    else {item{Fact("allowBackup / debuggable / cleartext","${m.allowBackup} / ${m.debuggable} / ${m.cleartext}");Fact("Network Security Config",m.networkSecurityConfig ?: "None declared");Fact("Target SDK",m.targetSdk?.toString() ?: "Unknown")};items(m.components){Fact("${it.kind} · ${it.name}","Exported=${it.exported}\nPermission=${it.permission ?: "None"}\nAuthorities=${it.authorities ?: "—"}")}}
                }
                5->{val findings=app.findings.filter{it.id.startsWith("dex:")};if(findings.isEmpty())item{Text(t("No supported code features found within analyzed DEX files.","已分析的 DEX 文件中未发现规则覆盖的代码特征。"))};items(findings){FindingRow(it)}}
                6->{
                    items(app.findings){FindingRow(it)}
                    if(!app.imported){
                        item{val context=LocalContext.current;Button(onClick={openSettings(context,app.packageName)}){Text(t("Open app / permissions settings","打开应用 / 权限设置"))}}
                        item{Section(t("Restrict AppOp","限制 AppOp"));var op by remember{mutableStateOf("CAMERA")};Choice("AppOp",op,Commands.allowedOps.sorted().map{it to it}){op=it};OutlinedButton(onClick={vm.prepare(app,"APP_OP",op)}){Text(t("Review restriction","检查限制操作"))}}
                        item{OutlinedButton(onClick={vm.prepare(app,"DISABLE")},enabled=!app.system){Text(t("Review disabling app","检查停用操作"))}}
                        item{OutlinedButton(onClick={vm.prepare(app,"UNINSTALL")},enabled=!app.system){Text(t("Review uninstall & preserve APK","检查卸载并保留 APK"))}}
                    }
                }
            }
        }
    }
}
@Composable private fun FindingRow(f:Finding){Column(Modifier.fillMaxWidth().padding(vertical=8.dp)){Text(f.title,style=MaterialTheme.typography.titleMedium);Text("${f.severity} · weight ${f.points} (before cap) · ${f.evidence.level}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary);SelectionContainer{Text("${f.evidence.source}\n${f.evidence.detail}",style=MaterialTheme.typography.bodySmall)};Text(f.recommendation,Modifier.padding(top=6.dp),style=MaterialTheme.typography.bodyMedium);HorizontalDivider(Modifier.padding(top=12.dp))}}

@Composable private fun ActionsScreen(apps:List<AppRecord>,records:List<ActionRecord>,onApp:(AppRecord)->Unit,onRollback:(ActionRecord)->Unit){
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Section(t("Recommended reviews","建议检查"))}
        if(apps.isEmpty())item{Text(t("Scan applications to see recommendations.","扫描应用后将显示检查建议。"))}
        items(apps.filter{it.findings.isNotEmpty()}.sortedByDescending{it.riskScore}.take(30),key={it.packageName}){AppRow(it){onApp(it)}}
        item{Section(t("Action & rollback history","处置与回滚历史"))}
        if(records.isEmpty())item{Empty(Icons.Default.History,t("No device changes","暂无设备修改"),t("Confirmed changes and their original state appear here.","确认的修改及原始状态将保存在这里。"))}
        items(records,key={it.id}){r->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(r.packageName,style=MaterialTheme.typography.titleMedium);Text("${r.action} ${r.argument} · ${r.scope}\n${r.original} → ${r.desired}\n${r.result}\n${date(r.createdAt)} · ${r.mode} · user=${r.userId}",style=MaterialTheme.typography.bodyMedium);if(r.canRollback)OutlinedButton(onClick={onRollback(r)}){Text(t("Review rollback","检查回滚"))}}}}
    }
}

@Composable internal fun Toggle(label:String,checked:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(checked,onChange)}}
@Composable internal fun Choice(label:String,value:String,options:List<Pair<String,String>>,onSelect:(String)->Unit){var open by remember{mutableStateOf(false)};Box{Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable{open=true}.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge);Spacer(Modifier.width(12.dp));Text(options.firstOrNull{it.first==value}?.second ?: value,color=MaterialTheme.colorScheme.primary,maxLines=2,style=MaterialTheme.typography.bodyMedium);Icon(Icons.Default.KeyboardArrowDown,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)};DropdownMenu(expanded=open,onDismissRequest={open=false}){options.forEach{(key,text)->DropdownMenuItem(text={Text(text)},onClick={open=false;onSelect(key)})}}}}
