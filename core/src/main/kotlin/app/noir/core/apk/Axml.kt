/* NOIR AXML reader, Copyright (c) 2026 Verlintas, MIT.
 * sososafe adaptation: strict chunk/string/attribute bounds and typed references. */
package app.noir.core.apk

object Axml {
    data class Node(val name:String,val attrs:Map<String,String?>,val depth:Int)
    fun parse(bytes:ByteArray):List<Node> {
        require(bytes.size>=8 && u16(bytes,0)==3){"Invalid binary XML header"}
        val rootSize=u32(bytes,4);require(rootSize in 8..bytes.size)
        var strings:List<String> = emptyList();val nodes=mutableListOf<Node>();var depth=0;var offset=u16(bytes,2)
        require(offset in 8..rootSize)
        while(offset<rootSize){
            require(offset+8<=rootSize)
            val type=u16(bytes,offset);val header=u16(bytes,offset+2);val size=u32(bytes,offset+4)
            require(header>=8 && size>=header && size<=rootSize-offset){"Malformed XML chunk"}
            when(type){
                1->strings=stringPool(bytes,offset,size,header)
                0x102->{
                    require(header>=16 && size>=36 && depth<=256 && nodes.size<100000)
                    val name=u32(bytes,offset+20);val start=u16(bytes,offset+24);val width=u16(bytes,offset+26);val count=u16(bytes,offset+28)
                    require(width>=20 && start>=20)
                    val first=offset+header+start
                    require(first<=offset+size && count.toLong()*width<=offset+size-first)
                    val attrs=linkedMapOf<String,String?>()
                    repeat(count){i->
                        val a=first+i*width;val namespace=u32(bytes,a);val key=strings.getOrNull(u32(bytes,a+4)) ?: error("Invalid attribute name")
                        val raw=u32(bytes,a+8);val kind=bytes[a+15].toInt() and 255;val data=u32(bytes,a+16)
                        if(namespace==-1 || strings.getOrNull(namespace)=="http://schemas.android.com/apk/res/android"){
                            attrs[key]=when(kind){3->strings.getOrNull(data) ?: error("Invalid string index");0x12->(data!=0).toString();0x10,0x11->data.toString();1->"@0x"+data.toUInt().toString(16).padStart(8,'0');else->strings.getOrNull(raw)}
                        }
                    }
                    nodes+=Node(strings.getOrNull(name) ?: error("Invalid element name"),attrs,depth);depth++
                }
                0x103->{require(depth>0);depth--}
            }
            offset+=size
        }
        require(depth==0){"Unclosed XML elements"};return nodes
    }
    private fun stringPool(b:ByteArray,o:Int,size:Int,header:Int):List<String>{
        require(header>=28 && o+28<=b.size)
        val count=u32(b,o+8);val utf8=u32(b,o+16) and 0x100!=0;val start=u32(b,o+20)
        require(count in 0..1000000 && count.toLong()*4<=size-header && start in header..size)
        val end=o+size
        fun decode(at:Int):String{
            var p=at;require(p in (o+start) until end)
            fun length8():Int{require(p<end);val first=b[p++].toInt() and 255;return if(first and 128==0)first else{require(p<end);((first and 127) shl 8) or (b[p++].toInt() and 255)}}
            fun length16():Int{require(p+2<=end);val first=u16(b,p);p+=2;return if(first and 0x8000==0)first else{require(p+2<=end);val next=u16(b,p);p+=2;((first and 0x7fff) shl 16) or next}}
            if(utf8){length8();val n=length8();require(n<=end-p);return String(b,p,n,Charsets.UTF_8)}
            val n=length16().toLong()*2;require(n in 0..(end-p).toLong());return String(b,p,n.toInt(),Charsets.UTF_16LE)
        }
        return (0 until count).map{i->val relative=u32(b,o+header+i*4);require(relative>=0 && relative<size-start);decode(o+start+relative)}
    }
    private fun u16(b:ByteArray,o:Int):Int{require(o>=0 && o+2<=b.size);return (b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8)}
    private fun u32(b:ByteArray,o:Int):Int{require(o>=0 && o+4<=b.size);return u16(b,o) or (u16(b,o+2) shl 16)}
}
