package app.sososafe.mobile.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sososafe.core.*
import app.sososafe.mobile.AuditViewModel
import app.sososafe.mobile.BuildConfig
import app.sososafe.mobile.platform.vpn.MonitorState

@Composable internal fun SettingsScreen(vm:AuditViewModel,onRoot:()->Unit,onClear:()->Unit,onExport:(String)->Unit){
    val s by vm.settings.collectAsStateWithLifecycle()
    val p by vm.privilege.collectAsStateWithLifecycle()
    val context=LocalContext.current
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Section(t("Appearance","外观"))}
        item{Panel{
            Choice(t("Language","界面语言"),s.language,listOf("zh" to "中文","en" to "English")){vm.set("language",it)}
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            Choice(t("Theme","主题"),s.theme,listOf("system" to t("System","跟随系统"),"light" to t("Light","浅色"),"dark" to t("Dark","深色"))){vm.set("theme",it)}
            Toggle(t("Dynamic color","动态取色"),s.dynamic){vm.set("dynamic",it)}
            Choice(t("Accent","主题色"),s.color,listOf("teal" to t("Teal","青绿"),"blue" to t("Blue","蓝色"),"plum" to t("Plum","紫色"))){vm.accent(it)}
        }}
        item{Section(t("Device access","设备访问"))}
        item{Panel{
            Choice(t("Scan mode","扫描模式"),s.mode.name,ScanMode.entries.map{it.name to modeName(it)}){vm.set("mode",it)}
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Root",style=MaterialTheme.typography.titleMedium);Text(if(p.rootAuthorized)t("Authorized","已授权")else if(p.rootAvailable)t("Available","可用 · 未授权")else t("Unavailable","不可用"),color=MaterialTheme.colorScheme.onSurfaceVariant)};OutlinedButton(onClick=onRoot){Text(t("Authorize","授权"))}}
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Shizuku",style=MaterialTheme.typography.titleMedium);Text(if(p.shizukuAuthorized)t("Authorized","已授权")else if(p.shizukuAlive)t("Running · not authorized","运行中 · 未授权")else if(p.shizukuInstalled)t("Stopped","服务未启动")else t("Not installed","未安装"),color=MaterialTheme.colorScheme.onSurfaceVariant)};OutlinedButton(onClick=vm::shizuku,enabled=p.shizukuAlive){Text(t("Authorize","授权"))}}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick=vm::refresh){Text(t("Refresh","刷新状态"))};TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/guide/setup/")))}){Text(t("Shizuku setup","配置 Shizuku"));Icon(Icons.Default.OpenInNew,null,Modifier.padding(start=6.dp).size(16.dp))}}
        }}
        item{Section(t("Scanning & storage","扫描与存储"))}
        item{Panel{
            Toggle(t("APK & DEX analysis","APK 与 DEX 分析"),s.deepScan){vm.set("deep",it)}
            Toggle(t("Include system apps","包含系统应用"),s.includeSystem){vm.set("system",it)}
            Choice(t("Network history","网络记录保留"),s.retentionDays.toString(),listOf("1" to t("1 day","1 天"),"7" to t("7 days","7 天"),"30" to t("30 days","30 天"))){vm.retention(it.toInt())}
            TextButton(onClick=onClear){Icon(Icons.Default.DeleteOutline,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(t("Clear audit data","清除审计数据"))}
        }}
        item{Section(t("Reports & recovery","报告与恢复"))}
        item{Panel{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("json","md","txt").forEach{format->OutlinedButton(onClick={onExport(format)},modifier=Modifier.weight(1f)){Text(format.uppercase())}}}
            TextButton(onClick={context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))}){Text(t("APK recovery permission","APK 恢复安装权限"));Icon(Icons.Default.ChevronRight,null)}
        }}
        item{Section(t("About","关于"))}
        item{Panel{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("sososafe",style=MaterialTheme.typography.titleMedium);Text(BuildConfig.VERSION_NAME,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            MoreInfo(t("Privacy & evidence","隐私与证据"),t("All analysis stays on this device. No accounts, advertising, telemetry or uploaded scans. The local VPN forwards TCP/UDP without external VPN servers or HTTPS decryption. Only connection and visible DNS metadata is retained. Encrypted DNS, ECH and shared IPs limit attribution. Scores prioritize review and do not establish data disclosure.\n\nUp to 20,000 recent connections are retained. Reports and recovery APKs stay in private storage. Uninstalling sososafe deletes them. QUERY_ALL_PACKAGES supports application auditing.\n\nRoot requires your explicit authorization. Shizuku provides shell access and remains subject to Android/OEM restrictions.","所有分析在本机完成，无账户、广告、遥测或扫描上传。本地 VPN 转发 TCP/UDP，不使用外部 VPN 服务器，不解密 HTTPS，仅保存连接与可见 DNS 元数据。加密 DNS、ECH 和共享 IP 会限制归属判断。评分用于优先检查，不代表已证实数据泄露。\n\n最多保留 20,000 条近期连接，报告与恢复 APK 位于私有存储，卸载 sososafe 会删除这些数据。QUERY_ALL_PACKAGES 用于应用审计。\n\nRoot 需要主动授权。Shizuku 提供 Shell 权限，仍受 Android 与厂商限制。"))
            TextButton(onClick={vm.message.value="NOIR © 2026 Verlintas · MIT\nHevSocks5Tunnel © hev · MIT\n\n"+context.assets.open("licenses.txt").bufferedReader().use{it.readText()}}){Text(t("Open-source licenses","开源许可"));Icon(Icons.Default.ChevronRight,null)}
        }}
    }
}

@Composable internal fun NetworkScreen(records:List<ConnectionRecord>,state:MonitorState,onStart:()->Unit,onStop:()->Unit,onShell:()->Unit){
    var filter by rememberSaveable{mutableStateOf("all")}
    val filtered=records.filter{when(filter){"dns"->it.dnsQueries.isNotEmpty();"trackers"->it.tracker;"active"->it.state=="ACTIVE" && System.currentTimeMillis()-it.lastSeen<30000;else->true}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Panel{
            Row(verticalAlignment=Alignment.CenterVertically){Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.primaryContainer){Icon(if(state.running)Icons.Default.VpnKey else Icons.Default.VpnLock,null,Modifier.padding(12.dp).size(28.dp),tint=MaterialTheme.colorScheme.onPrimaryContainer)};Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(if(state.running)t("Monitoring","监控中")else t("Not connected","未连接"),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold);Text(t("Local VPN","本地 VPN"),color=MaterialTheme.colorScheme.onSurfaceVariant)}}
            if(state.running && state.status!="ACTIVE")Text(state.status,color=MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            Button(onClick=if(state.running)onStop else onStart,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)){Text(if(state.running)t("Stop monitoring","停止监控")else t("Start monitoring","开始监控"))}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("↑ ${bytes(records.sumOf{it.uploaded})}");Text("↓ ${bytes(records.sumOf{it.downloaded})}")}
            if(state.unrecorded>0)Text(t("Missed records: ","遗漏记录：")+state.unrecorded,color=MaterialTheme.colorScheme.error)
        }}
        if(records.isNotEmpty()){
            item{Section(t("Applications","应用流量"))}
            items(records.groupBy{it.uid}.entries.sortedByDescending{it.value.sumOf{r->r.uploaded+r.downloaded}}.take(10)){entry->
                Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Text(entry.value.flatMap{it.packages}.distinct().joinToString().ifBlank{entry.key?.let{"UID $it"} ?: t("Unknown","未知")},Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis);Spacer(Modifier.width(12.dp));Text(bytes(entry.value.sumOf{it.uploaded+it.downloaded}),color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        }
        item{Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("all" to t("All","全部"),"active" to t("Active","活跃"),"dns" to "DNS","trackers" to t("Tracking","追踪")).forEach{(key,label)->FilterChip(selected=filter==key,onClick={filter=key},label={Text(label)})}}}
        if(filtered.isEmpty())item{Empty(Icons.Default.Lan,t("No connections","暂无连接"),t("Use another app while monitoring.","启动监控后使用其他应用。"))}
        items(filtered,key={it.id}){ConnectionCard(it)}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick=onShell){Text(t("System sockets","系统连接"))};MoreInfo(t("Visibility","可见范围"),t("Only connection metadata and visible DNS queries are recorded. HTTPS, DoH/DoT and ECH remain encrypted. Unknown attribution is kept explicit; shared IP and UID associations may be ambiguous. Android 8–9 may show device-level records only. Network changes may require apps to reconnect. Byte totals include IP headers and retransmissions.","仅记录连接元数据与可见 DNS 查询。HTTPS、DoH/DoT 与 ECH 保持加密。无法确认应用时显示未知，共享 IP 与 UID 关联可能存在歧义。Android 8–9 可能仅显示设备级记录。网络切换后应用可能需要重新连接。流量包含 IP 包头和重传。"))}}
    }
}

@Composable private fun ConnectionCard(c:ConnectionRecord){
    var expanded by rememberSaveable(c.id){mutableStateOf(false)}
    Surface(onClick={expanded=!expanded},shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(0.75.dp,MaterialTheme.colorScheme.outlineVariant)){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text(c.domain ?: c.remoteIp,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis);Icon(if(expanded)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)}
            Text(c.packages.joinToString().ifBlank{c.uid?.let{"UID $it"} ?: t("Unknown application","应用未知")},style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("${c.protocol} · ${c.remotePort}",style=MaterialTheme.typography.labelLarge,color=if(c.tracker)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary);Text("↑ ${bytes(c.uploaded)}   ↓ ${bytes(c.downloaded)}",style=MaterialTheme.typography.bodyMedium)}
            if(expanded){HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant);SelectionContainer{Text("${c.localIp}:${c.localPort} → ${c.remoteIp}:${c.remotePort}\n${c.attribution} · UID ${c.uid ?: "?"}\n${c.provider} · ${c.category}\n${c.domainEvidence ?: "IP metadata"}\n${c.packets} packets · ${c.connections} connections\n${date(c.firstSeen)} → ${date(c.lastSeen)}"+if(c.dnsQueries.isEmpty())""else "\nDNS: ${c.dnsQueries.joinToString()}",style=MaterialTheme.typography.bodyMedium)}}
        }
    }
}
