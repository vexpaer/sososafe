package app.sososafe.core

import java.net.InetAddress

data class PacketMeta(val version:Int,val protocol:Int,val source:String,val destination:String,val sourcePort:Int,val destinationPort:Int,val length:Int,val tcpFlags:Int,val payloadOffset:Int)
data class DnsMessage(val names:List<String>,val addresses:Map<String,List<String>>,val response:Boolean)
object Packets {
    fun u16(b:ByteArray,o:Int):Int { require(o>=0 && o+2<=b.size); return ((b[o].toInt() and 255) shl 8) or (b[o+1].toInt() and 255) }
    fun parse(b:ByteArray,wireLength:Int=b.size):PacketMeta? = runCatching {
        require(b.size>=20); val version=(b[0].toInt() and 255) ushr 4
        var offset:Int; var protocol:Int; val source:String; val destination:String; val len:Int
        if(version==4) {
            offset=(b[0].toInt() and 15)*4; require(offset>=20 && offset<=b.size)
            len=u16(b,2); require(len>=offset && len<=wireLength)
            require(u16(b,6) and 0x1fff==0) { "Noninitial fragment" }
            protocol=b[9].toInt() and 255
            source=InetAddress.getByAddress(b.copyOfRange(12,16)).hostAddress!!; destination=InetAddress.getByAddress(b.copyOfRange(16,20)).hostAddress!!
        } else {
            require(version==6 && b.size>=40); len=40+u16(b,4); require(len<=wireLength)
            source=InetAddress.getByAddress(b.copyOfRange(8,24)).hostAddress!!; destination=InetAddress.getByAddress(b.copyOfRange(24,40)).hostAddress!!
            protocol=b[6].toInt() and 255; offset=40; var extensions=0
            while(protocol in setOf(0,43,44,51,60)) {
                require(++extensions<=8 && offset+2<=b.size)
                val next=b[offset].toInt() and 255
                val n=when(protocol) { 44 -> { require(offset+8<=b.size && u16(b,offset+2) and 0xfff8==0);8 }; 51 -> ((b[offset+1].toInt() and 255)+2)*4; else -> ((b[offset+1].toInt() and 255)+1)*8 }
                offset+=n; require(offset<=b.size); protocol=next
            }
        }
        require(protocol==6 || protocol==17)
        require(offset+(if(protocol==6)20 else 8)<=b.size)
        val sp=u16(b,offset); val dp=u16(b,offset+2)
        val flags=if(protocol==6) b[offset+13].toInt() and 255 else 0
        val header=if(protocol==6) ((b[offset+12].toInt() and 255) ushr 4)*4 else 8
        require(header>=(if(protocol==6)20 else 8) && offset+header<=len)
        PacketMeta(version,protocol,source,destination,sp,dp,len,flags,offset+header)
    }.getOrNull()
    fun dns(b:ByteArray):DnsMessage? = runCatching {
        require(b.size>=12 && b.size<=65535)
        val questions=u16(b,4); val answers=u16(b,6); require(questions<=100 && answers<=200)
        var position=12
        fun nameAt(start:Int):Pair<String,Int> {
            val labels=mutableListOf<String>(); var p=start; var consumed=0; var jumped=false; val seen=hashSetOf<Int>()
            while(true) {
                require(p<b.size && seen.add(p) && seen.size<=128)
                val n=b[p].toInt() and 255
                if(n and 0xc0==0xc0) {
                    require(p+1<b.size); if(!jumped) consumed+=2
                    p=((n and 63) shl 8) or (b[p+1].toInt() and 255); jumped=true; continue
                }
                require(n<=63); p++; if(!jumped) consumed++
                if(n==0) break
                require(p+n<=b.size); val label=b.copyOfRange(p,p+n).toString(Charsets.US_ASCII)
                require(label.all { it.code in 33..126 }); labels+=label; p+=n; if(!jumped) consumed+=n
            }
            val result=labels.joinToString(".").lowercase(); require(result.length<=253); return result to consumed
        }
        val names=mutableListOf<String>(); val addresses=linkedMapOf<String,MutableList<String>>()
        repeat(questions) { val (name,size)=nameAt(position); position+=size; require(position+4<=b.size); position+=4; if(name.isNotEmpty()) names+=name }
        repeat(answers) {
            val (name,size)=nameAt(position); position+=size; require(position+10<=b.size)
            val type=u16(b,position); val length=u16(b,position+8); position+=10; require(position+length<=b.size)
            if((type==1 && length==4)||(type==28 && length==16)) addresses.getOrPut(name){mutableListOf()}.add(InetAddress.getByAddress(b.copyOfRange(position,position+length)).hostAddress!!)
            position+=length
        }
        DnsMessage(names,addresses,u16(b,2) and 0x8000!=0)
    }.getOrNull()
}
