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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sososafe.core.*
import app.sososafe.mobile.AuditViewModel
import app.sososafe.mobile.platform.vpn.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private data class Destination(val en:String,val zh:String,val icon:ImageVector)
private val destinations=listOf(Destination("Overview","概览",Icons.Default.Shield),Destination("Applications","应用",Icons.Default.Apps),Destination("Network","网络",Icons.Default.Lan),Destination("Actions","处置",Icons.Default.Rule),Destination("Settings","设置",Icons.Default.Settings))
private fun date(time:Long?)=time?.let{DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(it))} ?: "—"
private fun bytes(value:Long):String=when {value>=1024L*1024*1024->"%.2f GiB".format(value/1073741824.0);value>=1024*1024->"%.1f MiB".format(value/1048576.0);value>=1024->"%.1f KiB".format(value/1024.0);else->"$value B"}

@Composable fun SosoApp(vm:AuditViewModel){
    val settings by vm.settings.collectAsStateWithLifecycle()
    SosoTheme(settings){
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
        val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let(vm::import)}
        fun startVpn(){context.startForegroundService(Intent(context,MonitorVpnService::class.java))}
        val vpnConsent=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){r->if(r.resultCode==android.app.Activity.RESULT_OK)startVpn()else vm.message.value="VPN permission cancelled / VPN 授权已取消"}
        val notification=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ /* VPN always has a foreground notification; Android may hide it from the drawer if denied. */ }
        val saveDocument=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
            if(uri!=null){val file=exportFile;vm.app.scope.launchSafe(vm){withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri)).use{out->checkNotNull(file).inputStream().use{it.copyTo(out)}}};vm.message.value="Report saved / 报告已保存"}}
        }
        LaunchedEffect(message){if(message!=null && message!!.length<300){snackbar.showSnackbar(message!!);vm.message.value=null}}
        BackHandler(selected!=null){selected=null}
        val selectedApp=apps.firstOrNull{it.packageName==selected}
        BoxWithConstraints(Modifier.fillMaxSize()){
            val expanded=maxWidth>=840.dp
            Scaffold(
                topBar={TopAppBar(title={Text(if(selectedApp!=null)selectedApp.label else if(tab==0)"sososafe" else t(destinations[tab].en,destinations[tab].zh))},navigationIcon={if(selectedApp!=null)IconButton(onClick={selected=null}){Icon(Icons.AutoMirrored.Filled.ArrowBack,t("Back","返回"))}},actions={
                    if(tab==1 && selectedApp==null)IconButton(onClick={picker.launch(arrayOf("application/vnd.android.package-archive","application/octet-stream","application/zip"))},enabled=!busy){Icon(Icons.Default.FileOpen,t("Import APK","导入 APK"))}
                    if(selectedApp!=null)IconButton(onClick={openSettings(context,selectedApp.packageName)},enabled=!selectedApp.imported){Icon(Icons.Default.OpenInNew,t("App details","应用详情"))}
                })},
                bottomBar={if(!expanded)NavigationBar{destinations.forEachIndexed{i,d->NavigationBarItem(selected=tab==i,onClick={tab=i;selected=null},icon={Icon(d.icon,t(d.en,d.zh))},label={Text(t(d.en,d.zh))},alwaysShowLabel=true)}}},
                snackbarHost={SnackbarHost(snackbar)}
            ){padding->
                Row(Modifier.fillMaxSize().padding(padding)){
                    if(expanded)NavigationRail{destinations.forEachIndexed{i,d->NavigationRailItem(selected=tab==i,onClick={tab=i;selected=null},icon={Icon(d.icon,t(d.en,d.zh))},label={Text(t(d.en,d.zh))})}}
                    Column(Modifier.weight(1f).fillMaxHeight()){
                        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
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
        if(vpnDialog)AlertDialog(onDismissRequest={vpnDialog=false},title={Text(t("Start local monitoring?","启动本地网络监控？"))},text={Text(t("Android will ask for VPN permission. The existing VPN will be replaced. TCP/UDP traffic is forwarded on this device; only connection and DNS metadata is saved. No HTTPS decryption. sososafe's own traffic is excluded. Encrypted DNS and ECH may hide names. Network switches can require apps to reconnect.","Android 将请求 VPN 授权，并替换当前 VPN。TCP/UDP 流量在设备本地转发，仅保存连接和 DNS 元数据。不解密 HTTPS。排除 sososafe 自身流量。加密 DNS 与 ECH 可能隐藏域名，切换网络时应用可能需要重连。"))},confirmButton={TextButton(onClick={vpnDialog=false;if(Build.VERSION.SDK_INT>=33)notification.launch(Manifest.permission.POST_NOTIFICATIONS);val intent=VpnService.prepare(context);if(intent==null)startVpn()else vpnConsent.launch(intent)}){Text(t("Continue","继续"))}},dismissButton={TextButton(onClick={vpnDialog=false}){Text(t("Cancel","取消"))}})
        if(rootDialog)AlertDialog(onDismissRequest={rootDialog=false},title={Text(t("Request Root authorization","申请 Root 授权"))},text={Text(t("This starts su and may show your KernelSU, Magisk or APatch authorization dialog. Only id is executed to verify access. Scans remain read-only; every change has a separate confirmation.","将调用 su，可能显示 KernelSU、Magisk 或 APatch 的授权窗口。仅执行 id 验证权限。扫描保持只读，每次修改均需另行确认。"))},confirmButton={TextButton(onClick={rootDialog=false;vm.root()}){Text(t("Request Root","申请 Root"))}},dismissButton={TextButton(onClick={rootDialog=false}){Text(t("Cancel","取消"))}})
        plan?.let{p->AlertDialog(onDismissRequest={vm.plan.value=null},title={Text(t("Confirm device change","确认设备修改"))},text={SelectionContainer{Column{Text("${p.app.label}\n${p.app.packageName}",fontWeight=FontWeight.Bold);Text("\n${p.kind}: ${p.argument}\n${p.original} → ${p.desired}\n\n${p.effect}");Text(t("\nOriginal state is saved before execution. Restore from Actions history.","\n执行前保存原始状态，可在处置历史中恢复。"))}}},confirmButton={TextButton(onClick=vm::execute){Text(t("Confirm change","确认修改"))}},dismissButton={TextButton(onClick={vm.plan.value=null}){Text(t("Cancel","取消"))}})}
        rollback?.let{r->AlertDialog(onDismissRequest={rollback=null},title={Text(t("Restore previous state?","恢复之前状态？"))},text={Text("${r.packageName}\n${r.action} ${r.argument}\n${r.desired} → ${r.original}\n"+t("This may re-enable access. Reinstall restores APK files only; deleted private data cannot be recovered.","此操作可能恢复原有权限。重新安装仅恢复 APK，无法恢复被删除的应用私有数据。"))},confirmButton={TextButton(onClick={rollback=null;vm.rollback(r)}){Text(t("Restore","恢复"))}},dismissButton={TextButton(onClick={rollback=null}){Text(t("Cancel","取消"))}})}
        if(clearDialog)AlertDialog(onDismissRequest={clearDialog=false},title={Text(t("Clear local audit data?","清除本地审计数据？"))},text={Text(t("Deletes scan results, connection metadata and exported reports. Action history and recovery APKs are retained for rollback. Stop monitoring and scans first.","删除扫描结果、网络元数据和已导出报告。处置历史与恢复 APK 将保留，以便回滚。请先停止监控和扫描。"))},confirmButton={TextButton(onClick={clearDialog=false;if(monitor.running)vm.message.value="Stop monitoring first / 请先停止监控"else vm.clear()}){Text(t("Clear data","清除数据"))}},dismissButton={TextButton(onClick={clearDialog=false}){Text(t("Cancel","取消"))}})
        exportFile?.let{file->AlertDialog(onDismissRequest={exportFile=null},title={Text(t("Report saved locally","报告已本地保存"))},text={Text(t("Review sensitive information before sharing: app inventory, permission states, DNS names, IPs and device details may be included.\n","分享前请检查敏感信息：报告可能包含应用清单、权限状态、DNS 域名、IP 与设备信息。\n")+file.name)},confirmButton={TextButton(onClick={val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if(file.extension=="json")"application/json"else "text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"sososafe"));exportFile=null}){Text(t("Share","分享"))}},dismissButton={TextButton(onClick={saveDocument.launch(file.name)}){Text(t("Save copy","另存副本"))}})}
        if(message!=null && message!!.length>=300)AlertDialog(onDismissRequest={vm.message.value=null},title={Text(t("Result","结果"))},text={SelectionContainer{Text(message!!,Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()))}},confirmButton={TextButton(onClick={vm.message.value=null}){Text(t("Close","关闭"))}})
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchSafe(vm:AuditViewModel,block:suspend ()->Unit){kotlinx.coroutines.launch {try{block()}catch(e:Exception){vm.message.value=e.message}}}
private fun openSettings(context:android.content.Context,pkg:String){context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$pkg")))}

@Composable private fun Section(title:String,subtitle:String?=null){Column(Modifier.fillMaxWidth().padding(top=20.dp,bottom=8.dp)){Text(title,style=MaterialTheme.typography.titleLarge);if(subtitle!=null)Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
@Composable private fun Empty(icon:ImageVector,title:String,body:String,action:String?=null,onAction:()->Unit={}){Column(Modifier.fillMaxWidth().padding(vertical=36.dp,horizontal=24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){Icon(icon,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary);Text(title,style=MaterialTheme.typography.titleLarge);Text(body,style=MaterialTheme.typography.bodyMedium);if(action!=null)Button(onClick=onAction){Text(action)}}}
@Composable private fun Fact(label:String,value:String){Column(Modifier.fillMaxWidth().padding(vertical=8.dp)){Text(label,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant);SelectionContainer{Text(value,style=MaterialTheme.typography.bodyLarge)}}}
@Composable private fun Risk(score:Int){val color=when{score>=60->MaterialTheme.colorScheme.error;score>=30->MaterialTheme.colorScheme.tertiary;else->MaterialTheme.colorScheme.primary};Surface(shape=MaterialTheme.shapes.small,color=color.copy(alpha=0.12f)){Text("$score / 100",Modifier.padding(horizontal=10.dp,vertical=6.dp),color=color,style=MaterialTheme.typography.labelLarge)}}

@Composable private fun Dashboard(apps:List<AppRecord>,scans:List<ScanRecord>,progress:app.sososafe.mobile.data.ScanProgress,priv:app.sososafe.mobile.platform.PrivilegeState,preferred:ScanMode,vm:AuditViewModel,onApps:()->Unit,onSettings:()->Unit){
    val mode=runCatching{ModePolicy.select(preferred,priv.rootAuthorized,priv.shizukuAuthorized)}.getOrNull()
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Text(t("Your device, your evidence.","设备隐私，证据可查。"),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)}
        item{Text(t("Local analysis. Every finding has a source.","本地分析，每项发现均可追溯来源。"),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{OutlinedCard(Modifier.fillMaxWidth().padding(vertical=12.dp),onClick=onSettings){Column(Modifier.padding(16.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(t("Scan mode","扫描模式"),style=MaterialTheme.typography.titleMedium);Text(mode?.name ?: t("Authorization required","需要授权"),color=MaterialTheme.colorScheme.primary)};Text(when(mode){ScanMode.ROOT->t("Authorized su · APK, permissions, AppOps","已授权 su · APK、权限、AppOps");ScanMode.SHIZUKU->t("Shell identity · OEM restrictions may apply","Shell 身份 · 可能受 OEM 限制");else->t("Visible metadata + readable APKs / imported files","可见应用信息 + 可读 APK / 导入文件")},style=MaterialTheme.typography.bodyMedium)}}}
        item{Section(t("Audit summary","审计概览"),t("Scores prioritize review; they do not certify safety.","评分用于确定检查优先级，并非安全认证。"))}
        item{Fact(t("Security score (heuristic)","安全评分（启发式）"),if(apps.isEmpty())t("Not scanned","尚未扫描")else "${100-apps.map{it.riskScore}.average().toInt()} / 100")}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp)){Column(Modifier.weight(1f)){Fact(t("Applications","应用数量"),apps.size.toString());Fact(t("High / medium risk","高 / 中风险"),"${apps.count{it.risk==Severity.HIGH}} / ${apps.count{it.risk==Severity.MEDIUM}}")};Column(Modifier.weight(1f)){Fact(t("Unique endpoints","独立端点"),apps.flatMap{it.analysis.endpoints}.map{it.host}.distinct().size.toString());Fact(t("Tracking services","追踪服务"),apps.flatMap{it.analysis.endpoints}.filter{it.tracker}.map{it.provider+it.host}.distinct().size.toString())}}}
        item{Fact(t("Last scan","上次扫描"),scans.firstOrNull()?.let{"${date(it.startedAt)} · ${it.status}"} ?: t("No scan history","暂无扫描记录"))}
        if(progress.status=="SCANNING")item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){LinearProgressIndicator(progress={if(progress.total>0)progress.done.toFloat()/progress.total else 0f},modifier=Modifier.fillMaxWidth());Text("${progress.done} / ${progress.total} · ${progress.current}",style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick=vm::cancel){Text(t("Cancel scan","取消扫描"))}}}
        else item{Button(onClick=vm::scan,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Search,null);Spacer(Modifier.width(8.dp));Text(t("Scan applications","扫描应用"))}}
        if(progress.status in listOf("COMPLETED","CANCELLED","ERROR"))item{Text(when(progress.status){"COMPLETED"->t("Scan completed. Results are saved locally.","扫描已完成，结果已本地保存。");"CANCELLED"->t("Scan cancelled. Finished app results were retained.","扫描已取消，保留已完成的应用结果。");else->progress.error ?: t("Scan failed. Check access in Settings.","扫描失败，请在设置中检查权限。")},color=if(progress.status=="ERROR")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}
        item{TextButton(onClick=onApps){Text(t("Review applications","查看应用结果"));Icon(Icons.Default.ChevronRight,null)}}
        item{Section(t("What the evidence means","如何理解证据"))}
        item{Text(t("Capability → static reference → observed connection → confirmed content. sososafe records the first three separately and never infers personal data disclosure from them. HTTPS content is not inspected.","访问能力 → 静态引用 → 实际连接 → 已确认内容。sososafe 分别记录前三类证据，不据此推断个人数据已经泄露，也不检查 HTTPS 明文。"),style=MaterialTheme.typography.bodyMedium)}
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
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){Text("${filtered.size} "+t("results","项结果"),Modifier.weight(1f),style=MaterialTheme.typography.labelLarge);Choice(t("Sort","排序"),sort,listOf("risk" to t("Risk","风险"),"name" to t("Name","名称"),"endpoints" to t("Endpoints","端点"))){sort=it}}
        if(apps.isEmpty())Empty(Icons.Default.Apps,t("No audit yet","暂无审计结果"),t("Scan installed applications or import an APK for offline analysis.","扫描已安装应用，或导入 APK 进行离线分析。"),t("Scan applications","扫描应用"),onScan)
        else if(filtered.isEmpty())Empty(Icons.Default.SearchOff,t("No matching applications","没有匹配的应用"),t("Change the search or filter.","请调整搜索条件或筛选项。"))
        LazyColumn(Modifier.weight(1f)){items(filtered,key={it.packageName}){app->AppRow(app,onClick={onSelect(app)});HorizontalDivider(Modifier.padding(start=72.dp))}}
        TextButton(onClick=onImport,modifier=Modifier.align(Alignment.CenterHorizontally)){Icon(Icons.Default.FileOpen,null);Spacer(Modifier.width(8.dp));Text(t("Import APK using system picker","通过系统文件选择器导入 APK"))}
    }
}
@Composable private fun AppRow(app:AppRecord,onClick:()->Unit){
    val context=LocalContext.current
    val icon by produceState<ImageBitmap?>(null,app.packageName){value=withContext(Dispatchers.IO){runCatching{context.packageManager.getApplicationIcon(app.packageName).toBitmap(96,96).asImageBitmap()}.getOrNull()}}
    ListItem(headlineContent={Text(app.label,maxLines=2)},supportingContent={Column{Text(app.packageName,style=MaterialTheme.typography.bodySmall);Text("${app.analysis.endpoints.size} "+t("endpoints","个端点")+" · "+app.permissions.filter{it.group!="Other"}.map{it.group}.distinct().take(3).joinToString(),style=MaterialTheme.typography.bodySmall)}},leadingContent={if(icon!=null)Image(icon!!,null,Modifier.size(40.dp))else Icon(Icons.Default.Android,null,Modifier.size(40.dp))},trailingContent={Risk(app.riskScore)},modifier=Modifier.clickable(onClick=onClick))
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
                    item{Section(t("Coverage & access limits","覆盖范围与访问限制"))}
                    items(app.limitations+app.analysis.limitations){Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    item{Fact(t("Analyzed files","已分析文件"),app.analysis.filesAnalyzed.joinToString("\n").ifEmpty{t("None. APK analysis not performed.","无，尚未进行 APK 分析。")})}
                    item{Section(t("Data exposure correlations","数据暴露关联"),t("Coexistence is not proof of disclosure.","共存关系不代表已证实泄露。"))}
                    if(app.exposures.isEmpty())item{Text(t("No capability / endpoint pairs were found within this scan's coverage.","本次扫描范围内未发现可关联的权限与端点。"))}
                    items(app.exposures.take(200)){link->Fact("${link.dataType} → ${link.provider}","${link.permission}\n${link.host}\n${link.level}\n${link.explanation}")}
                }
                1->{
                    item{Text(t("Declared, granted, AppOps and special access are separate facts. Unknown does not mean denied.","声明、实际授予、AppOps 与特殊权限分别记录；未知不代表拒绝。"))}
                    if(app.permissions.isEmpty())item{Text(t("No permission information available.","暂无可用权限信息。"))}
                    items(app.permissions){p->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text(p.group,style=MaterialTheme.typography.titleMedium);SelectionContainer{Text(p.name,style=MaterialTheme.typography.bodyMedium)};Text("${p.protection}\nDeclared=${p.declared} · Granted=${p.granted ?: "Unknown"}\nAppOps=${p.appOp ?: "Unknown"}\nSpecial=${p.specialState ?: "—"}\n${p.evidence}",style=MaterialTheme.typography.bodySmall)}}}
                    app.appOps?.let{item{Section("AppOps");SelectionContainer{Text(it,style=MaterialTheme.typography.bodySmall)}}}
                }
                2->{
                    if(app.analysis.endpoints.isEmpty())item{Empty(Icons.Default.Lan,t("No extracted endpoints","未提取到端点"),t("Check APK coverage. Encrypted or dynamically built addresses may not be visible.","请检查 APK 分析范围。加密或动态构建的地址可能不可见。"))}
                    items(app.analysis.endpoints){ep->
                        val observed=connections.filter{it.uid==app.uid && app.uid>=0 && (it.domain==ep.host || ep.host in it.dnsQueries)}
                        Fact(ep.host,"${ep.value}\n${ep.provider} · ${ep.category}\n${ep.evidence.level} · ${ep.evidence.source}"+if(observed.isNotEmpty())"\nObserved UID connection/DNS: ${observed.size}; shared UID=${app.sharedUid!=null}" else "")
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
                    item{Text(t("All changes require confirmation. Android settings are available in every mode; AppOps and disabling require Root or Shizuku.","所有修改均需确认。各模式均可打开 Android 设置；AppOps 和停用操作需要 Root 或 Shizuku。"))}
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
@Composable private fun FindingRow(f:Finding){Column(Modifier.fillMaxWidth().padding(vertical=8.dp)){Text(f.title,style=MaterialTheme.typography.titleMedium);Text("${f.severity} · +${f.points} · ${f.evidence.level}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary);SelectionContainer{Text("${f.evidence.source}\n${f.evidence.detail}",style=MaterialTheme.typography.bodySmall)};Text(f.recommendation,Modifier.padding(top=6.dp),style=MaterialTheme.typography.bodyMedium);HorizontalDivider(Modifier.padding(top=12.dp))}}

@Composable private fun NetworkScreen(records:List<ConnectionRecord>,state:MonitorState,onStart:()->Unit,onStop:()->Unit,onShell:()->Unit){
    var filter by rememberSaveable{mutableStateOf("all")}
    val filtered=records.filter{when(filter){"dns"->it.dnsQueries.isNotEmpty();"trackers"->it.tracker;"active"->it.state=="ACTIVE" && System.currentTimeMillis()-it.lastSeen<30000;else->true}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Icon(if(state.running)Icons.Default.VpnKey else Icons.Default.VpnLock,null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(if(state.running)t("Local VPN active","本地 VPN 已启用")else t("Monitoring stopped","监控已停止"),style=MaterialTheme.typography.titleLarge);Text(state.status,style=MaterialTheme.typography.bodySmall)}}}
        state.error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
        item{if(state.running)Button(onClick=onStop,modifier=Modifier.fillMaxWidth()){Text(t("Stop monitoring","停止监控"))}else Button(onClick=onStart,modifier=Modifier.fillMaxWidth()){Text(t("Start local monitor","启动本地监控"))}}
        item{Text(t("Metadata only. HTTPS, DoH/DoT and ECH content stays encrypted. DNS names are associated through observed DNS answers, not domain guesses. IP-based or shared-UID attribution has limits. On Android 8–9, app attribution is Unknown. Device apps may reconnect after a network change.","仅记录元数据，HTTPS、DoH/DoT 与 ECH 内容保持加密。域名来自实际 DNS 响应，不按域名猜测应用。IP 复用与共享 UID 会限制归属精度。Android 8–9 的应用归属显示未知。网络切换后应用可能需要重新连接。"),style=MaterialTheme.typography.bodySmall)}
        item{Fact(t("Recorded IP traffic (retained records)","已记录 IP 流量（保留的记录）"),"↑ ${bytes(records.sumOf{it.uploaded})}   ↓ ${bytes(records.sumOf{it.downloaded})}");if(state.unrecorded>0)Text(t("Records missed under load: ","高负载下遗漏记录：")+state.unrecorded,color=MaterialTheme.colorScheme.error)}
        item{Section(t("Traffic by observed UID","按观测 UID 统计流量"))}
        items(records.groupBy{it.uid}.entries.sortedByDescending{it.value.sumOf{r->r.uploaded+r.downloaded}}.take(30)){entry->Fact(entry.value.flatMap{it.packages}.distinct().joinToString().ifBlank{if(entry.key!=null)"UID ${entry.key}"else t("Unknown / device level","未知 / 设备级")},"↑ ${bytes(entry.value.sumOf{it.uploaded})} · ↓ ${bytes(entry.value.sumOf{it.downloaded})}")}
        item{TextButton(onClick=onShell){Text(t("Query privileged socket metadata","查询增强模式系统连接信息"))}}
        item{Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("all" to t("All","全部"),"active" to t("Active (30s)","活跃（30秒）"),"dns" to "DNS","trackers" to t("Tracking","追踪")).forEach{(key,label)->FilterChip(selected=filter==key,onClick={filter=key},label={Text(label)})}}}
        if(filtered.isEmpty())item{Empty(Icons.Default.Lan,t("No observed records","暂无观测记录"),t("Start monitoring and use another app. Encrypted DNS may hide query names.","启动监控后使用其他应用。加密 DNS 可能隐藏查询域名。"))}
        items(filtered,key={it.id}){c->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(c.domain ?: c.remoteIp,style=MaterialTheme.typography.titleMedium);SelectionContainer{Text("${c.protocol} ${c.remoteIp}:${c.remotePort}\n${c.localIp}:${c.localPort}",style=MaterialTheme.typography.bodySmall)};Text(c.packages.joinToString().ifBlank{t("Unknown application","应用未知")},style=MaterialTheme.typography.bodyMedium);Text("${c.attribution} · UID ${c.uid ?: "?"}\n${c.provider} · ${c.category}\n↑ ${bytes(c.uploaded)} ↓ ${bytes(c.downloaded)} · ${c.packets} packets · ${c.connections} connections\n${date(c.firstSeen)} → ${date(c.lastSeen)}",style=MaterialTheme.typography.bodySmall);if(c.dnsQueries.isNotEmpty())SelectionContainer{Text("DNS: "+c.dnsQueries.joinToString(),style=MaterialTheme.typography.bodySmall)}}}}
    }
}

@Composable private fun ActionsScreen(apps:List<AppRecord>,records:List<ActionRecord>,onApp:(AppRecord)->Unit,onRollback:(ActionRecord)->Unit){
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text(t("Read-only until you confirm.","确认之前，仅进行读取。"),style=MaterialTheme.typography.headlineSmall)}
        item{Text(t("Review evidence before restricting access. Higher scores suggest review, not automatic removal. System applications are protected from disabling and uninstalling.","限制权限前请查看证据。较高评分表示优先检查，不会自动删除应用。系统应用受保护，不提供停用和卸载。"))}
        item{Section(t("Recommended reviews","建议检查"))}
        if(apps.isEmpty())item{Text(t("Scan applications to see recommendations.","扫描应用后将显示检查建议。"))}
        items(apps.filter{it.findings.isNotEmpty()}.sortedByDescending{it.riskScore}.take(30),key={it.packageName}){AppRow(it){onApp(it)}}
        item{Section(t("Action & rollback history","处置与回滚历史"))}
        if(records.isEmpty())item{Empty(Icons.Default.History,t("No device changes","暂无设备修改"),t("Confirmed changes and their original state appear here.","确认的修改及原始状态将保存在这里。"))}
        items(records,key={it.id}){r->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(r.packageName,style=MaterialTheme.typography.titleMedium);Text("${r.action} ${r.argument}\n${r.original} → ${r.desired}\n${r.result}\n${date(r.createdAt)} · ${r.mode} · user=${r.userId}",style=MaterialTheme.typography.bodyMedium);if(r.reversible && !r.rolledBack && (r.result.startsWith("SUCCESS") || r.result.startsWith("ROLLBACK_FAILED")))OutlinedButton(onClick={onRollback(r)}){Text(t("Review rollback","检查回滚"))}}}}
    }
}

@Composable private fun SettingsScreen(vm:AuditViewModel,onRoot:()->Unit,onClear:()->Unit,onExport:(String)->Unit){
    val s by vm.settings.collectAsStateWithLifecycle();val p by vm.privilege.collectAsStateWithLifecycle();val context=LocalContext.current
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Section(t("Device access","设备访问"))}
        item{Choice(t("Scan mode","扫描模式"),s.mode.name,ScanMode.entries.map{it.name to it.name}){vm.set("mode",it)}}
        item{Fact("Root","su ${if(p.rootAvailable)t("detected","已检测")else t("not found in visible paths","在可见路径中未找到")} · ${if(p.rootAuthorized)t("authorized this session","本次会话已授权")else t("not authorized","未授权")}");OutlinedButton(onClick=onRoot){Text(t("Request Root authorization","申请 Root 授权"))}}
        item{Fact("Shizuku","${t("Installed","已安装")}=${p.shizukuInstalled}\n${t("Service alive","服务运行")}=${p.shizukuAlive}\n${t("Authorized","已授权")}=${p.shizukuAuthorized}");Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick=vm::shizuku,enabled=p.shizukuAlive){Text(t("Authorize","授权"))};TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/guide/setup/")))}){Text(t("Setup guide","配置指南"))}}}
        item{Text(t("Android 11+: start Shizuku on the device using wireless debugging. Earlier Android versions need Root or the standard mode for computer-free use. Shell identity is not Root; OEM restrictions are shown per app.","Android 11 及以上可在设备上通过无线调试启动 Shizuku。较早版本如不使用电脑，可使用 Root 或普通模式。Shell 不等于 Root，OEM 限制会逐项显示。"),style=MaterialTheme.typography.bodySmall);TextButton(onClick=vm::refresh){Text(t("Refresh capabilities","刷新能力状态"))}}
        item{Section(t("Appearance","外观"))}
        item{Choice(t("Theme","主题"),s.theme,listOf("system" to t("System","跟随系统"),"light" to t("Light","浅色"),"dark" to t("Dark","深色"))){vm.set("theme",it)}}
        item{Toggle(t("Dynamic color","动态取色"),s.dynamic){vm.set("dynamic",it)}}
        item{Choice(t("Manual accent","手动主题色"),s.color,listOf("teal" to t("Teal","青绿"),"blue" to t("Blue","蓝色"),"plum" to t("Plum","紫色"))){vm.set("color",it)}}
        item{Choice(t("Language","语言"),s.language,listOf("system" to t("System","跟随系统"),"zh" to "中文","en" to "English")){vm.set("language",it)}}
        item{Section(t("Scanning & storage","扫描与存储"))}
        item{Toggle(t("Analyze readable APK / DEX files","分析可读 APK / DEX 文件"),s.deepScan){vm.set("deep",it)}}
        item{Toggle(t("Include system applications","包含系统应用"),s.includeSystem){vm.set("system",it)}}
        item{Choice(t("Network history retention","网络记录保留时间"),s.retentionDays.toString(),listOf("1" to t("1 day","1 天"),"7" to t("7 days","7 天"),"30" to t("30 days","30 天"))){vm.retention(it.toInt())}}
        item{Text(t("The local Room database retains up to 20,000 recent connection tuples. Reports and preserved uninstall APKs remain private in app storage. Uninstalling sososafe removes this data.","本地 Room 数据库最多保留 20,000 条近期连接。报告与卸载前保留的 APK 位于应用私有存储。卸载 sososafe 将删除这些数据。"),style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick=onClear){Text(t("Clear audit data","清除审计数据"))}}
        item{Section(t("Reports","审计报告"));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("json","md","txt").forEach{f->OutlinedButton(onClick={onExport(f)}){Text(f.uppercase())}}}
        item{OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))}){Text(t("Allow APK recovery installs","允许安装用于恢复的 APK"))}}
        item{Section(t("Privacy & About","隐私与关于"))}
        item{Text(t("sososafe 1.0.0\nNo accounts, telemetry, ads or uploaded scans. All analysis is local. The local relay forwards only your device's requested traffic; no external VPN server is used. HTTPS is not decrypted and payloads are not retained. DNS query names, IPs, UID and app inventory can be sensitive. Use the stop notification to end monitoring at any time.","sososafe 1.0.0\n无需账户、无遥测、无广告、不上传扫描结果。所有分析均在本地进行。本地转发器只转发设备请求的网络流量，不使用外部 VPN 服务器。不解密 HTTPS，也不保存网络载荷。DNS 域名、IP、UID 与应用清单属于敏感信息。可随时通过通知停止监控。"))}
        item{Text(t("QUERY_ALL_PACKAGES is used for the auditor's core inventory. Google Play distribution requires a package visibility policy declaration. This repository provides direct APK installation.","审计工具使用 QUERY_ALL_PACKAGES 获取应用清单。通过 Google Play 分发需遵守应用可见性政策并声明用途。本仓库支持直接下载安装 APK。"),style=MaterialTheme.typography.bodySmall)}
        item{TextButton(onClick={vm.message.value="NOIR © 2026 Verlintas · MIT\nHevSocks5Tunnel © hev · MIT\nShizuku API · MIT\ndexlib2 · BSD 3-Clause\nAndroidX · Apache 2.0\n\n"+context.assets.open("licenses.txt").bufferedReader().use{it.readText()}}){Text(t("Open-source licenses","开源许可"))}}
    }
}
@Composable private fun Toggle(label:String,checked:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(checked,onChange)}}
@Composable private fun Choice(label:String,value:String,options:List<Pair<String,String>>,onSelect:(String)->Unit){var open by remember{mutableStateOf(false)};Box{TextButton(onClick={open=true},modifier=Modifier.heightIn(min=48.dp)){Text("$label: ${options.firstOrNull{it.first==value}?.second ?: value}");Icon(Icons.Default.ArrowDropDown,null)};DropdownMenu(expanded=open,onDismissRequest={open=false}){options.forEach{(key,text)->DropdownMenuItem(text={Text(text)},onClick={open=false;onSelect(key)})}}}}
