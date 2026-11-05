package app.sososafe.mobile.platform.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import app.sososafe.core.*
import app.sososafe.mobile.data.ConnectionEntity
import app.sososafe.mobile.data.AuditRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import java.net.InetSocketAddress
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class TrafficRecorder(private val context:Context,private val repo:AuditRepository,private val scope:CoroutineScope):PacketSink {
    private data class OwnerQuery(val tuple:String,val id:String,val protocol:Int,val local:String,val localPort:Int,val remote:String,val remotePort:Int)
    private val lock=Any()
    private val flows=LinkedHashMap<String,ConnectionRecord>(256,0.75f,true)
    private val active=hashMapOf<String,String>()
    private val dirty=hashSetOf<String>()
    private val requested=hashSetOf<String>()
    private val queries=Channel<OwnerQuery>(256)
    private val dns=LinkedHashMap<String,Pair<String,Long>>(256,0.75f,true)
    private val tcpDns=DnsTcpAssembler()
    private val session=UUID.randomUUID().toString();private var sequence=0L
    private val cm=context.getSystemService(ConnectivityManager::class.java)
    private val missed=AtomicLong()
    val dropped:Long get()=missed.get()
    @Volatile private var shellOwners:Pair<Long,Map<String,Int>> = 0L to emptyMap()
    init {
        scope.launch(Dispatchers.IO){while(isActive){delay(1500);runCatching{flush()}.onFailure{missed.incrementAndGet()}}}
        scope.launch(Dispatchers.IO){
            for(q in queries){
                val uid=if(Build.VERSION.SDK_INT>=29)runCatching{cm.getConnectionOwnerUid(q.protocol,InetSocketAddress(q.local,q.localPort),InetSocketAddress(q.remote,q.remotePort)).takeIf{it>=0}}.getOrNull()else null
                if(uid!=null){
                    val packages=runCatching{context.packageManager.getPackagesForUid(uid)?.toList()}.getOrNull().orEmpty()
                    synchronized(lock){
                        if(active[q.tuple]==q.id)flows[q.id]?.let{row->
                            flows[q.id]=row.copy(uid=uid,packages=packages,attribution=if(packages.size==1)"OS_CONFIRMED_UID"else "SHARED_OR_UNRESOLVED_UID")
                            dirty+=q.id
                        }
                    }
                }
            }
        }
        scope.launch(Dispatchers.IO){while(isActive){
            runCatching{
                val mode=repo.access.selected(repo.settings.flow.first().mode)
                if(mode!=ScanMode.STANDARD){val text=repo.access.execute(mode,listOf("ss","-tunape")).checked();shellOwners=System.currentTimeMillis() to SocketOwnership.parseSs(text)}
                else shellOwners=0L to emptyMap()
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
            val tuple="${p.protocol}|$local|$lp|$remote|$rp"
            synchronized(lock) {
                val previous=active[tuple]?.let{flows[it]}
                val syn=p.protocol==6 && outbound && p.tcpFlags and 2!=0 && p.tcpFlags and 16==0
                val newFlow=previous==null || (syn && (previous.state in setOf("RESET","CLOSING") || now-previous.lastSeen>60000)) || (p.protocol==17 && now-previous.lastSeen>60000)
                val old=if(newFlow)null else previous
                val id=old?.id ?: "$session:${++sequence}"
                active[tuple]=id
                val snapshot=shellOwners
                // Only use a socket snapshot captured after this flow began.
                val shellUid=if(old!=null && old.uid==null && snapshot.first>=old.firstSeen && now-snapshot.first<15000)SocketOwnership.key(p.protocol,local,lp,remote,rp)?.let{snapshot.second[it]}else null
                val uid=old?.uid ?: shellUid
                // Binder/PackageManager calls are intentionally kept off the native forwarding thread.
                if(uid==null && outbound && Build.VERSION.SDK_INT>=29 && requested.add(id)){
                    if(!queries.trySend(OwnerQuery(tuple,id,p.protocol,local,lp,remote,rp)).isSuccess)requested.remove(id)
                }
                var query:List<String> = emptyList()
                if((lp==53 || rp==53) && p.payloadOffset<=data.size) {
                    val payload=data.copyOfRange(p.payloadOffset,minOf(data.size,p.length))
                    val messages=if(p.protocol==17)listOfNotNull(Packets.dns(payload))else tcpDns.feed("$tuple:$outbound",p.tcpSequence,payload,p.tcpFlags and 2!=0,now)
                    messages.forEach{message->query=(query+message.names).distinct();if(message.response)message.addresses.forEach{(name,ips)->ips.forEach{dns[it]=name to now}}}
                    if(p.protocol==6 && p.tcpFlags and 5!=0)tcpDns.clear("$tuple:$outbound")
                }
                val dnsHost=dns[remote]?.takeIf{now-it.second<300000}?.first
                val domain=dnsHost ?: query.firstOrNull() ?: old?.domain
                val rule=if(old!=null && old.domain==domain)DomainRule(domain ?: remote,old.provider,old.category,old.tracker)else DomainRules.classify(domain ?: remote)
                val state=when {p.protocol==6 && p.tcpFlags and 4!=0 -> "RESET";p.protocol==6 && (p.tcpFlags and 1!=0 || old?.state=="CLOSING") -> "CLOSING";else->"ACTIVE"}
                val record=(old ?: ConnectionRecord(id,now,now,if(p.protocol==6)"TCP"else"UDP",local,lp,remote,rp)).copy(
                    lastSeen=now,uid=uid,packages=old?.packages.orEmpty(),attribution=if(shellUid!=null)"SHELL_SOCKET_UID"else old?.attribution ?: "UNKNOWN",
                    domain=domain,provider=rule.provider,category=rule.category,tracker=rule.tracker,
                    uploaded=(old?.uploaded ?: 0)+if(outbound)p.length else 0,
                    downloaded=(old?.downloaded ?: 0)+if(outbound)0 else p.length,
                    packets=(old?.packets ?: 0)+1,connections=1,
                    dnsQueries=(old?.dnsQueries.orEmpty()+query).distinct().take(100),state=state,
                    domainEvidence=if(dnsHost!=null)"DNS_ANSWER_IP_ASSOCIATION: shared IPs may be ambiguous"else if(query.isNotEmpty())"DNS_QUESTION_ONLY: no connection to named host is proven"else old?.domainEvidence)
                flows[id]=record;dirty+=id
                if(shellUid!=null){
                    // Resolve a shell-derived UID asynchronously as well.
                    scope.launch(Dispatchers.IO){val packages=runCatching{context.packageManager.getPackagesForUid(shellUid)?.toList()}.getOrNull().orEmpty();synchronized(lock){flows[id]?.let{flows[id]=it.copy(packages=packages);dirty+=id}}}
                }
                if(flows.size>10000){
                    val evict=flows.entries.firstOrNull{it.key !in dirty}
                    if(evict!=null){flows.remove(evict.key);requested.remove(evict.key);active.entries.removeAll{it.value==evict.key}}
                    else{flows.remove(id);dirty.remove(id);active.remove(tuple);requested.remove(id);missed.incrementAndGet()}
                }
                while(dns.size>10000)dns.remove(dns.keys.first())
            }
        }catch(_:Exception){missed.incrementAndGet()}
    }
    suspend fun flush() {
        val rows=synchronized(lock){dirty.mapNotNull{flows[it]}.also{dirty.clear()}}
        try{if(rows.isNotEmpty())repo.dao.connections(rows.map{ConnectionEntity(it.id,it.lastSeen,ReportWriter.json.encodeToString(it))})}
        catch(e:Exception){synchronized(lock){rows.filter{it.id in flows}.forEach{dirty.add(it.id)}};throw e}
        repo.dao.capConnections()
    }
}
