package app.sososafe.mobile.platform.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import app.sososafe.core.*
import app.sososafe.mobile.data.ConnectionEntity
import app.sososafe.mobile.data.AuditRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import java.net.InetSocketAddress
import java.util.LinkedHashMap
import java.util.UUID

class TrafficRecorder(private val context:Context,private val repo:AuditRepository,private val scope:CoroutineScope):PacketSink {
    private val lock=Any();private val flows=LinkedHashMap<String,ConnectionRecord>(256,0.75f,true)
    private val dirty=hashSetOf<String>();private val dns=LinkedHashMap<String,Pair<String,Long>>(256,0.75f,true)
    private val session=UUID.randomUUID().toString()
    private val cm=context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var shellOwners:Pair<Long,Map<String,Int>> = 0L to emptyMap()
    @Volatile var dropped:Long=0
        private set
    init {
        scope.launch(Dispatchers.IO){while(isActive){delay(1500);runCatching{flush()}.onFailure{dropped++}}}
        scope.launch(Dispatchers.IO){while(isActive){
            runCatching{
                val mode=repo.access.selected(repo.settings.flow.first().mode)
                if(mode!=ScanMode.STANDARD){val text=repo.access.execute(mode,listOf("ss","-tunape")).checked();shellOwners=System.currentTimeMillis() to SocketOwnership.parseSs(text)}
            }
            delay(10000)
        }}
    }
    override fun onPacket(data:ByteArray,wireLength:Int,outbound:Boolean) {
        try {
            val p=Packets.parse(data,wireLength) ?: return
            val now=System.currentTimeMillis()
            val local=if(outbound)p.source else p.destination;val remote=if(outbound)p.destination else p.source
            val lp=if(outbound)p.sourcePort else p.destinationPort;val rp=if(outbound)p.destinationPort else p.sourcePort
            val key="${p.protocol}|$local|$lp|$remote|$rp"
            synchronized(lock) {
                val old=flows[key]
                val osUid=if(old?.uid==null && Build.VERSION.SDK_INT>=29 && outbound)runCatching { cm.getConnectionOwnerUid(p.protocol,InetSocketAddress(local,lp),InetSocketAddress(remote,rp)).takeIf{it>=0} }.getOrNull()else null
                val snapshot=shellOwners
                val shellUid=if(old?.uid==null && osUid==null && now-snapshot.first<15000)SocketOwnership.key(p.protocol,local,lp,remote,rp)?.let{snapshot.second[it]}else null
                val uid=old?.uid ?: osUid ?: shellUid
                val packages=old?.packages?.takeIf{it.isNotEmpty()} ?: uid?.let{context.packageManager.getPackagesForUid(it)?.toList()}.orEmpty()
                var query:List<String> = emptyList()
                if(p.protocol==17 && (lp==53 || rp==53) && p.payloadOffset<data.size) {
                    Packets.dns(data.copyOfRange(p.payloadOffset,minOf(data.size,p.length)))?.let { message ->
                        query=message.names
                        if(message.response) message.addresses.forEach{(name,ips)->ips.forEach{dns[it]=name to now}}
                    }
                }
                val dnsHost=dns[remote]?.takeIf{now-it.second<300000}?.first
                val domain=dnsHost ?: query.firstOrNull() ?: old?.domain
                val rule=DomainRules.classify(domain ?: remote)
                val syn=p.protocol==6 && outbound && p.tcpFlags and 2!=0 && p.tcpFlags and 16==0
                val state=when {p.protocol==6 && p.tcpFlags and 4!=0 -> "RESET";p.protocol==6 && p.tcpFlags and 1!=0 -> "CLOSING";else->"ACTIVE"}
                val record=(old ?: ConnectionRecord("$session:$key",now,now,if(p.protocol==6)"TCP" else "UDP",local,lp,remote,rp)).copy(
                    lastSeen=now,uid=uid,packages=packages,attribution=when{uid==null->"UNKNOWN";old?.uid!=null->old.attribution;shellUid!=null->"SHELL_SOCKET_UID";packages.size==1->"OS_CONFIRMED_UID";else->"SHARED_OR_UNRESOLVED_UID"},
                    domain=domain,provider=rule.provider,category=rule.category,tracker=rule.tracker,
                    uploaded=(old?.uploaded ?: 0)+if(outbound)p.length else 0,
                    downloaded=(old?.downloaded ?: 0)+if(outbound)0 else p.length,
                    packets=(old?.packets ?: 0)+1,connections=(old?.connections ?: 0)+if(old==null || (syn && (old.state in setOf("RESET","CLOSING") || now-old.lastSeen>10000)))1 else 0,
                    dnsQueries=(old?.dnsQueries.orEmpty()+query).distinct().take(100),state=state,
                    domainEvidence=if(dnsHost!=null)"DNS_ANSWER_IP_ASSOCIATION: shared IPs may be ambiguous" else if(query.isNotEmpty())"DNS_QUESTION_ONLY: does not prove a connection to the named host"else old?.domainEvidence)
                flows[key]=record;dirty+=key
                // Bounded memory. Dirty rows are flushed; admission pauses if the writer is behind.
                if(flows.size>10000){val first=flows.entries.firstOrNull{it.key !in dirty};if(first!=null)flows.remove(first.key)else{flows.remove(key);dirty.remove(key);dropped++}}
                while(dns.size>10000)dns.remove(dns.keys.first())
            }
        }catch(_:Exception){dropped++}
    }
    suspend fun flush() {
        val rows=synchronized(lock){dirty.mapNotNull{flows[it]}.also{dirty.clear()}}
        try{if(rows.isNotEmpty())repo.dao.connections(rows.map{ConnectionEntity(it.id,it.lastSeen,ReportWriter.json.encodeToString(it))})}
        catch(e:Exception){synchronized(lock){rows.forEach{row->flows.entries.firstOrNull{it.value.id==row.id}?.let{dirty.add(it.key)}}};throw e}
        repo.dao.capConnections()
    }
}
