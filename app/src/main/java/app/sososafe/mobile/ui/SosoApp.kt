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
                topBar={TopAppBar(title={Text(if(selectedApp!=null && !expanded)selectedApp.label else t(destinations[tab].en,destinations[tab].zh),fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background),navigationIcon={if(selectedApp!=null && !expanded)IconButton(onClick={selected=null}){Icon(Icons.AutoMirrored.Filled.ArrowBack,t("Back","返回"))}},actions={
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
                        if(expanded && tab==1 && selectedApp!=null) {
                            Row(Modifier.fillMaxSize()) {
                                Applications(
                                    apps,
                                    onSelect={selected=it.packageName},
                                    onScan=vm::scan,
                                    onImport={picker.launch(arrayOf("application/vnd.android.package-archive","application/octet-stream","application/zip"))},
                                    selectedPackage=selected,
                                    modifier=Modifier.weight(0.42f).fillMaxHeight()
                                )
                                VerticalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                                AppDetail(selectedApp,connections,vm,modifier=Modifier.weight(0.58f).fillMaxHeight())
                            }
                        } else if(selectedApp!=null) AppDetail(selectedApp,connections,vm)
                        else when(tab){
                            0->Dashboard(apps,scans,progress,privilege,settings.mode,vm,onApps={tab=1},onSettings={tab=4},onApp={selected=it.packageName})
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
@Composable internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
@Composable internal fun modeName(mode:ScanMode?)=when(mode){ScanMode.AUTO->t("Automatic","自动");ScanMode.ROOT->"Root";ScanMode.SHIZUKU->"Shizuku";ScanMode.STANDARD->t("Standard","普通模式");else->t("Authorization required","需要授权")}
@Composable internal fun MoreInfo(title:String,body:String){var open by remember{mutableStateOf(false)};TextButton(onClick={open=true}){Icon(Icons.Default.Info,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(title)};if(open)AlertDialog(onDismissRequest={open=false},title={Text(title)},text={Text(body,Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={open=false}){Text(t("Close","关闭"))}})}
@Composable internal fun Empty(icon:ImageVector,title:String,body:String,action:String?=null,onAction:()->Unit={}){Column(Modifier.fillMaxWidth().padding(vertical=36.dp,horizontal=24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){Icon(icon,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary);Text(title,style=MaterialTheme.typography.titleLarge);Text(body,style=MaterialTheme.typography.bodyMedium);if(action!=null)Button(onClick=onAction){Text(action)}}}
@Composable private fun Fact(label:String,value:String){Column(Modifier.fillMaxWidth().padding(vertical=8.dp)){Text(label,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant);SelectionContainer{Text(value,style=MaterialTheme.typography.bodyLarge)}}}
@Composable private fun Risk(app: AppRecord) {
    val color = when (app.risk) {
        Severity.HIGH -> MaterialTheme.colorScheme.error
        Severity.MEDIUM -> MaterialTheme.colorScheme.tertiary
        Severity.LOW -> MaterialTheme.colorScheme.primary
        Severity.INFO -> MaterialTheme.colorScheme.secondary
    }
    val label = when (app.risk) {
        Severity.HIGH -> t("HIGH", "高")
        Severity.MEDIUM -> t("MEDIUM", "中")
        Severity.LOW -> t("LOW", "低")
        Severity.INFO -> t("INFO", "信息")
    }
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(app.riskScore.toString(), color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
}
@Composable private fun Dashboard(
    apps: List<AppRecord>,
    scans: List<ScanRecord>,
    progress: app.sososafe.mobile.data.ScanProgress,
    priv: app.sososafe.mobile.platform.PrivilegeState,
    preferred: ScanMode,
    vm: AuditViewModel,
    onApps: () -> Unit,
    onSettings: () -> Unit,
    onApp: (AppRecord) -> Unit
) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val mode = runCatching { ModePolicy.select(preferred, priv.rootAuthorized, priv.shizukuAuthorized) }.getOrNull()
    val high = apps.count { it.risk == Severity.HIGH }
    val medium = apps.count { it.risk == Severity.MEDIUM }
    val hosts = apps.flatMap { it.analysis.endpoints }.map { it.host }.distinct().size
    val latest = scans.maxByOrNull { it.startedAt }?.startedAt ?: apps.maxOfOrNull { it.scannedAt }
    val topApps = apps.filter { it.riskScore > 0 }.sortedByDescending { it.riskScore }.take(3)
    val scanning = progress.status == "SCANNING"
    val importing = progress.status == "IMPORTING"
    val status = when {
        scanning -> t("Scanning", "扫描中")
        importing -> t("Importing APK", "正在导入 APK")
        progress.status == "ERROR" -> t("Scan failed", "扫描失败")
        apps.isNotEmpty() -> t("Audit available", "已有审计结果")
        else -> t("Not scanned", "尚未扫描")
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.size(9.dp).background(if (progress.status == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, CircleShape))
                        Text(status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    latest?.let { Text(date(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                TextButton(onClick = onSettings) {
                    Text(modeName(mode))
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    DashboardMetric(t("Apps", "应用"), apps.size.toString(), Modifier.weight(1f))
                    DashboardMetric(t("High", "高风险"), high.toString(), Modifier.weight(1f), "high")
                    DashboardMetric(t("Medium", "中风险"), medium.toString(), Modifier.weight(1f), "medium")
                    DashboardMetric(t("Hosts", "域名"), hosts.toString(), Modifier.weight(1f))
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    scanning -> {
                        LinearProgressIndicator(
                            progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(progress.done.toString() + " / " + progress.total, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = vm::cancel) { Text(t("Cancel", "取消")) }
                        }
                    }
                    importing -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t("Analyzing APK", "正在分析 APK"), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = vm::cancel) { Text(t("Cancel", "取消")) }
                        }
                    }
                    else -> Button(onClick = vm::scan, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Icon(Icons.Default.Search, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(9.dp))
                        Text(t("Scan applications", "扫描应用"))
                    }
                }
                if (progress.status == "ERROR") Text(progress.error ?: t("Scan failed", "扫描失败"), color = MaterialTheme.colorScheme.error)
            }
        }
        if (topApps.isNotEmpty()) {
            item { Section(t("Review first", "优先检查")) }
            items(topApps, key = { it.packageName }) { app -> AppRow(app) { onApp(app) } }
            item { TextButton(onClick = onApps) { Text(t("All applications", "全部应用")); Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp)) } }
        } else if (apps.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Text(t("No flagged apps in this scan", "本次扫描未标记风险应用"), style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onApps) { Text(t("View applications", "查看应用")) }
            }
        }
        item {
            MoreInfo(
                t("How scoring works", "评分说明"),
                t(
                    "Risk scores rank items for review. They are not malware verdicts or proof of data disclosure. Static references, granted capabilities and observed connection metadata are separate evidence.",
                    "风险分数用于安排检查优先级，不代表恶意软件判定或数据泄露证据。静态引用、已授予能力和实际连接元数据分别记录。"
                )
            )
        }
    }
}

@Composable private fun DashboardMetric(label: String, value: String, modifier: Modifier = Modifier, tone: String = "normal") {
    val color = when (tone) {
        "high" -> MaterialTheme.colorScheme.error
        "medium" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
@Composable private fun Applications(
    apps: List<AppRecord>,
    onSelect: (AppRecord) -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    selectedPackage: String? = null,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var sort by rememberSaveable { mutableStateOf("risk") }
    val filtered = remember(apps, query, filter, sort) {
        apps.filter {
            (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)) &&
                when (filter) {
                    "high" -> it.risk == Severity.HIGH
                    "medium" -> it.risk == Severity.MEDIUM
                    "system" -> it.system
                    "imported" -> it.imported
                    else -> true
                }
        }.let { list ->
            when (sort) {
                "name" -> list.sortedBy { it.label.lowercase() }
                "hosts" -> list.sortedByDescending { it.analysis.endpoints.size }
                else -> list.sortedByDescending { it.riskScore }
            }
        }
    }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(t("Search apps", "搜索应用")) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "all" to t("All", "全部"),
                    "high" to t("High", "高风险"),
                    "medium" to t("Medium", "中风险"),
                    "system" to t("System", "系统"),
                    "imported" to t("Imported", "已导入")
                ).forEach { (key, label) ->
                    FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(filtered.size.toString(), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.widthIn(min = 150.dp, max = 210.dp)) {
                    Choice(t("Sort", "排序"), sort, listOf("risk" to t("Risk", "风险"), "name" to t("Name", "名称"), "hosts" to t("Hosts", "域名"))) { sort = it }
                }
            }
        }
        if (apps.isEmpty()) {
            Empty(Icons.Default.Apps, t("No audit yet", "暂无审计结果"), t("Scan installed apps or import an APK.", "扫描已安装应用，或导入 APK。"), t("Scan applications", "扫描应用"), onScan)
        } else if (filtered.isEmpty()) {
            Empty(Icons.Default.SearchOff, t("No matches", "没有匹配项"), t("Change the search or filter.", "调整搜索条件或筛选项。"))
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    AppRow(app, selected = selectedPackage == app.packageName) { onSelect(app) }
                }
            }
        }
        TextButton(onClick = onImport, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp)) {
            Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(t("Import APK", "导入 APK"))
        }
    }
}
@Composable private fun AppRow(app: AppRecord, selected: Boolean = false, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) Image(icon!!, null, Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)))
        else Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(Icons.Default.Android, null, Modifier.size(42.dp).padding(9.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Risk(app)
    }
    HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
}
@Composable private fun AppDetail(
    app: AppRecord,
    connections: List<ConnectionRecord>,
    vm: AuditViewModel,
    modifier: Modifier = Modifier
) {
    var page by rememberSaveable(app.packageName) { mutableIntStateOf(0) }
    val titles = listOf(t("Overview", "概览"), t("Permissions", "权限"), t("Network", "网络"), t("Technical", "技术"), t("Findings", "发现"))
    Column(modifier.fillMaxSize()) {
        ScrollableTabRow(page, edgePadding = 12.dp) {
            titles.forEachIndexed { index, title -> Tab(selected = page == index, onClick = { page = index }, text = { Text(title) }) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (page) {
                0 -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            val context = LocalContext.current
                            val icon by produceState<ImageBitmap?>(null, app.packageName) {
                                value = withContext(Dispatchers.IO) {
                                    runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                                }
                            }
                            if (icon != null) Image(icon!!, null, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                            else Icon(Icons.Default.Android, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(app.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Risk(app)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DashboardMetric(t("Permissions", "权限"), app.permissions.size.toString(), Modifier.weight(1f))
                            DashboardMetric(t("Hosts", "域名"), app.analysis.endpoints.size.toString(), Modifier.weight(1f))
                            DashboardMetric(t("Findings", "发现"), app.findings.size.toString(), Modifier.weight(1f))
                        }
                    }
                    item { Section(t("Application", "应用信息")) }
                    item { Fact(t("Version", "版本"), app.version) }
                    item { Fact("UID", app.uid.toString()) }
                    item { Fact(t("Target SDK", "目标 SDK"), app.targetSdk.toString()) }
                    item { Fact(t("Installer", "安装来源"), app.installer ?: "—") }
                    item { Fact(t("Scan mode", "扫描模式"), modeName(app.mode)) }
                    item { Fact(t("Scanned", "扫描时间"), date(app.scannedAt)) }
                    if (app.findings.isNotEmpty()) {
                        item { Section(t("Top findings", "主要发现")) }
                        items(app.findings.sortedByDescending { it.points }.take(3)) { FindingRow(it) }
                    }
                    item {
                        MoreInfo(
                            t("Analysis coverage", "分析范围"),
                            (app.limitations + app.analysis.limitations + app.analysis.filesAnalyzed).distinct().joinToString("\n").ifBlank { t("No additional coverage notes.", "没有其他范围说明。") }
                        )
                    }
                }
                1 -> {
                    item { Section(t("Permission states", "权限状态")) }
                    if (app.permissions.isEmpty()) item { Empty(Icons.Default.LockOpen, t("No permission data", "没有权限数据"), t("Permission details are unavailable.", "没有可用的权限详情。")) }
                    items(app.permissions) { permission ->
                        var expanded by rememberSaveable(permission.name) { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(permission.group, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(permission.name, style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(
                                    when (permission.granted) { true -> t("Granted", "已授予"); false -> t("Denied", "未授予"); null -> t("Unknown", "未知") },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (permission.granted == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.padding(start = 6.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (expanded) SelectionContainer {
                                Text(
                                    permission.protection + " · Declared=" + permission.declared +
                                        "\nAppOps=" + (permission.appOp ?: "Unknown") +
                                        " · Special=" + (permission.specialState ?: "—") +
                                        "\n" + permission.evidence,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    app.appOps?.let { value ->
                        item { Section("AppOps") }
                        item { SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall) } }
                    }
                }
                2 -> {
                    item { Section(t("Domains", "域名")) }
                    if (app.analysis.endpoints.isEmpty()) item {
                        Empty(Icons.Default.Language, t("No extracted domains", "没有提取到域名"), t("Encrypted or dynamically generated hosts may not appear.", "加密或动态生成的主机名可能不可见。"))
                    }
                    items(app.analysis.endpoints) { endpoint ->
                        val observed = connections.count {
                            it.uid == app.uid && app.uid >= 0 && (it.domain == endpoint.host || endpoint.host in it.dnsQueries)
                        }
                        Column(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(endpoint.host, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                if (endpoint.tracker) Text(t("Tracker", "追踪器"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                            Text(endpoint.provider + " · " + endpoint.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(endpoint.evidence.level.toString() + " · " + endpoint.evidence.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (observed > 0) Text(t("Observed connection metadata: ", "观察到的连接元数据：") + observed, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            SelectionContainer { Text(endpoint.value + "\n" + endpoint.evidence.detail, style = MaterialTheme.typography.bodySmall) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                3 -> {
                    val manifest = app.analysis.manifest
                    item { Section(t("Manifest", "清单")) }
                    if (manifest == null) item { Text(t("Manifest unavailable", "无法读取清单"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else {
                        item { Fact("allowBackup", manifest.allowBackup.toString()) }
                        item { Fact("debuggable", manifest.debuggable.toString()) }
                        item { Fact("usesCleartextTraffic", manifest.cleartext.toString()) }
                        item { Fact(t("Network security config", "网络安全配置"), manifest.networkSecurityConfig ?: "—") }
                        item { Fact(t("Components", "组件"), manifest.components.size.toString()) }
                        items(manifest.components) { component ->
                            Fact(component.kind + " · " + component.name, "Exported=" + component.exported + " · Permission=" + (component.permission ?: "—") + " · Authorities=" + (component.authorities ?: "—"))
                        }
                    }
                    item { Section("SDKs") }
                    if (app.analysis.sdks.isEmpty()) item { Text(t("No supported SDK signatures found.", "未发现规则覆盖的 SDK 特征。"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(app.analysis.sdks) { sdk ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(sdk.name, style = MaterialTheme.typography.titleSmall)
                            Text(sdk.category + " · " + sdk.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer { Text(sdk.signature, style = MaterialTheme.typography.bodySmall) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item { Section("DEX") }
                    val dexFindings = app.findings.filter { it.id.startsWith("dex:") }
                    if (dexFindings.isEmpty()) item { Text(t("No supported code features found.", "未发现规则覆盖的代码特征。"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(dexFindings) { FindingRow(it) }
                }
                4 -> {
                    item { Section(t("Findings", "风险发现")) }
                    if (app.findings.isEmpty()) item { Text(t("No findings in the analyzed scope.", "当前分析范围内没有风险发现。"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(app.findings) { FindingRow(it) }
                    if (app.exposures.isNotEmpty()) {
                        item { Section(t("Permission / host signals", "权限与域名关联")) }
                        items(app.exposures.take(200)) { link ->
                            Fact(link.dataType + " → " + link.provider, link.host + "\n" + link.permission + "\n" + link.severity + " · " + link.level)
                        }
                    }
                    if (!app.imported) {
                        item { Section(t("Device actions", "设备操作")) }
                        item {
                            var op by rememberSaveable(app.packageName) { mutableStateOf("CAMERA") }
                            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Choice("AppOp", op, Commands.allowedOps.sorted().map { it to it }) { op = it }
                                OutlinedButton(onClick = { vm.prepare(app, "APP_OP", op) }, modifier = Modifier.fillMaxWidth()) { Text(t("Review restriction", "检查权限限制")) }
                                OutlinedButton(onClick = { vm.prepare(app, "DISABLE") }, enabled = !app.system, modifier = Modifier.fillMaxWidth()) { Text(t("Review disabling app", "检查停用应用")) }
                                OutlinedButton(onClick = { vm.prepare(app, "UNINSTALL") }, enabled = !app.system, modifier = Modifier.fillMaxWidth()) { Text(t("Review uninstall", "检查卸载操作")) }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun FindingRow(f: Finding) {
    val color = when (f.severity) {
        Severity.HIGH -> MaterialTheme.colorScheme.error
        Severity.MEDIUM -> MaterialTheme.colorScheme.tertiary
        Severity.LOW -> MaterialTheme.colorScheme.primary
        Severity.INFO -> MaterialTheme.colorScheme.secondary
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(f.severity.name, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
        }
        Text(f.evidence.level.toString() + " · " + f.points, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(f.recommendation, style = MaterialTheme.typography.bodyMedium)
        SelectionContainer { Text(f.evidence.source + "\n" + f.evidence.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
@Composable private fun ActionsScreen(
    apps: List<AppRecord>,
    records: List<ActionRecord>,
    onApp: (AppRecord) -> Unit,
    onRollback: (ActionRecord) -> Unit
) {
    val review = apps.filter { it.findings.isNotEmpty() || it.riskScore > 0 }.sortedByDescending { it.riskScore }.take(30)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Section(t("Review queue", "待检查")) }
        if (review.isEmpty()) item { Text(t("No apps need review.", "暂无待检查应用。"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(review, key = { it.packageName }) { app -> AppRow(app) { onApp(app) } }
        item { Spacer(Modifier.height(10.dp)); Section(t("Action history", "处置记录")) }
        if (records.isEmpty()) item { Text(t("No device changes.", "暂无设备修改记录。"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(records, key = { it.id }) { record ->
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(record.packageName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    if (record.canRollback) TextButton(onClick = { onRollback(record) }) { Text(t("Restore", "恢复")) }
                }
                Text((record.action + " " + record.argument).trim(), style = MaterialTheme.typography.bodyMedium)
                Text(record.original + " → " + record.desired, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(if (record.result.startsWith("SUCCESS")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, CircleShape))
                    Text(record.result, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(date(record.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
@Composable internal fun Toggle(label:String,checked:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(checked,onChange)}}
@Composable internal fun Choice(label:String,value:String,options:List<Pair<String,String>>,onSelect:(String)->Unit){var open by remember{mutableStateOf(false)};Box{Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable{open=true}.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge);Spacer(Modifier.width(12.dp));Text(options.firstOrNull{it.first==value}?.second ?: value,color=MaterialTheme.colorScheme.primary,maxLines=2,style=MaterialTheme.typography.bodyMedium);Icon(Icons.Default.KeyboardArrowDown,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)};DropdownMenu(expanded=open,onDismissRequest={open=false}){options.forEach{(key,text)->DropdownMenuItem(text={Text(text)},onClick={open=false;onSelect(key)})}}}}
