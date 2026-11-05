package app.sososafe.core

/** Bounded, sequence-aware DNS framing. Gaps invalidate observation, never forwarding. */
class DnsTcpAssembler {
    private data class State(var next:Long,var last:Long,var valid:Boolean=true,var bytes:ByteArray=byteArrayOf())
    private val states=LinkedHashMap<String,State>(16,0.75f,true)
    fun clear(key:String){states.remove(key)}
    fun feed(key:String,sequence:Long,payload:ByteArray,syn:Boolean=false,now:Long=System.currentTimeMillis()):List<DnsMessage>{
        states.entries.removeAll{now-it.value.last>60000}
        val start=(sequence+if(syn)1 else 0) and 0xffffffffL
        if(syn){states[key]=State(start,now);while(states.size>128)states.remove(states.keys.first())}
        val state=states[key] ?: return emptyList();state.last=now
        if(!state.valid || payload.isEmpty())return emptyList()
        val ahead=(start-state.next) and 0xffffffffL
        if(ahead in 1..0x7fffffffL){state.valid=false;state.bytes=byteArrayOf();return emptyList()}
        val overlap=((state.next-start) and 0xffffffffL).coerceAtMost(payload.size.toLong()).toInt()
        if(overlap==payload.size)return emptyList()
        val incoming=payload.copyOfRange(overlap,payload.size)
        if(state.bytes.size+incoming.size>131074){state.valid=false;state.bytes=byteArrayOf();return emptyList()}
        state.bytes+=incoming;state.next=(start+payload.size) and 0xffffffffL
        val result=mutableListOf<DnsMessage>();var offset=0
        while(state.bytes.size-offset>=2){
            val length=Packets.u16(state.bytes,offset)
            if(length<12){state.valid=false;state.bytes=byteArrayOf();return emptyList()}
            if(state.bytes.size-offset<length+2)break
            Packets.dns(state.bytes.copyOfRange(offset+2,offset+2+length))?.let{result+=it}
            offset+=length+2
        }
        if(offset>0)state.bytes=state.bytes.copyOfRange(offset,state.bytes.size)
        return result
    }
}
