package app.sososafe.mobile.platform.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import app.sososafe.core.*
import app.sososafe.mobile.data.ConnectionEntity
import app.sososafe.mobile.data.AuditRepository
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import java.net.InetSocketAddress
import java.util.LinkedHashMap
import java.util.UUID

class TrafficRecorder(private val context:Context,private val repo:AuditRepository,private val scope:CoroutineScope):PacketSink {
    private val lock=Any();private val flows=LinkedHashMap<String,ConnectionRecord>(256,0.75f,true)
    private val dirty=hashSetOf<String>();private val dns=LinkedHashMap<String,Pair<String,Long>>(256,0.75f,true)
    private val session=UUID.randomUUID().toString()
    private val cm=context.getSystemService(ConnectivityManager::class.java)
    @Volatile var dropped:Long=0
        private set
    init {scope.launch(Dispatchers.IO){while(isActive){delay(1500);flush()}}}
    override fun onPacket(data:ByteArray,wireLength:Int,outbound:Boolean) {
        try {
            val p=Packets.parse(data,wireLength) ?: return
            val now=System.currentTimeMillis()
            val local=if(outbound)p.source else p.destination;val remote=if(outbound)p.destination else p.source
            val lp=if(outbound)p.sourcePort else p.destinationPort;val rp=if(outbound)p.destinationPort else p.sourcePort
            val key="${p.protocol}|$local|$lp|$remote|$rp"
            synchronized(lock) {
                val old=flows[key]
                val uid=old?.uid ?: if(Build.VERSION.SDK_INT>=29 && outbound) runCatching { cm.getConnectionOwnerUid(p.protocol,InetSocketAddress(local,lp),InetSocketAddress(remote,rp)).takeIf{it>=0} }.getOrNull() else null
                val packages=old?.packages?.takeIf{it.isNotEmpty()} ?: uid?.let{context.packageManager.getPackagesForUid(it)?.toList()}.orEmpty()
                var query:List<String> = emptyList()
                if(p.protocol==17 && (lp==53 || rp==53) && p.payloadOffset<data.size) {
                    Packets.dns(data.copyOfRange(p.payloadOffset,minOf(data.size,p.length)))?.let { message ->
                        query=message.names
                        if(message.response) message.addresses.forEach{(name,ips)->ips.forEach{dns[it]=name to now}}
                    }
                }
                val domain=dns[remote]?.takeIf{now-it.second<300000}?.first ?: query.firstOrNull() ?: old?.domain
                val rule=DomainRules.classify(domain ?: remote)
                val syn=p.protocol==6 && outbound && p.tcpFlags and 2!=0 && p.tcpFlags and 16==0
                val state=when {p.protocol==6 && p.tcpFlags and 4!=0 -> "RESET";p.protocol==6 && p.tcpFlags and 1!=0 -> "CLOSING";else->"ACTIVE"}
                val record=(old ?: ConnectionRecord("$session:$key",now,now,if(p.protocol==6)"TCP" else "UDP",local,lp,remote,rp)).copy(
                    lastSeen=now,uid=uid,packages=packages,attribution=when{uid==null->"UNKNOWN";packages.size==1->"OS_CONFIRMED_UID";else->"SHARED_OR_UNRESOLVED_UID"},
                    domain=domain,provider=rule.provider,category=rule.category,tracker=rule.tracker,
                    uploaded=(old?.uploaded ?: 0)+if(outbound)p.length else 0,
                    downloaded=(old?.downloaded ?: 0)+if(outbound)0 else p.length,
                    packets=(old?.packets ?: 0)+1,connections=(old?.connections ?: 0)+if(old==null || (syn && now-old.lastSeen>10000))1 else 0,
                    dnsQueries=(old?.dnsQueries.orEmpty()+query).distinct().take(100),state=state)
                flows[key]=record;dirty+=key
                // Bounded memory. Dirty rows are flushed; admission pauses if the writer is behind.
                if(flows.size>10000){val first=flows.entries.firstOrNull{it.key !in dirty};if(first!=null)flows.remove(first.key)else{flows.remove(key);dirty.remove(key);dropped++}}
                while(dns.size>10000)dns.remove(dns.keys.first())
            }
        }catch(_:Exception){dropped++}
    }
    suspend fun flush() {
        val rows=synchronized(lock){dirty.mapNotNull{flows[it]}.also{dirty.clear()}}
        if(rows.isNotEmpty())repo.dao.connections(rows.map{ConnectionEntity(it.id,it.lastSeen,ReportWriter.json.encodeToString(it))})
        repo.dao.capConnections()
    }
}
