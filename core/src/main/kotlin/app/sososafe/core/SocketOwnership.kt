package app.sososafe.core

import java.net.InetAddress

object SocketOwnership {
    fun address(value:String):String? = runCatching{
        val ip=value.removePrefix("[").removeSuffix("]").substringBefore('%')
        require(ip.matches(Regex("[0-9a-fA-F:.]+")) && ('.' in ip || ':' in ip))
        InetAddress.getByName(ip).hostAddress
    }.getOrNull()
    fun key(protocol:Int,local:String,localPort:Int,remote:String,remotePort:Int):String? {
        val l=address(local) ?: return null;val r=address(remote) ?: return null
        return "$protocol|$l|$localPort|$r|$remotePort"
    }
    private fun endpoint(value:String):Pair<String,Int>? {
        val port=value.substringAfterLast(':').toIntOrNull() ?: return null
        val host=address(value.substringBeforeLast(':')) ?: return null
        return host to port
    }
    fun parseSs(output:String):Map<String,Int> = buildMap {
        output.lineSequence().forEach{line->
            val parts=line.trim().split(Regex("\\s+"));if(parts.size<6)return@forEach
            val protocol=when{parts[0].startsWith("tcp")->6;parts[0].startsWith("udp")->17;else->return@forEach}
            val uid=Regex("\\buid:(\\d+)\\b").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            val local=endpoint(parts[4]) ?: return@forEach;val remote=endpoint(parts[5]) ?: return@forEach
            val key=key(protocol,local.first,local.second,remote.first,remote.second) ?: return@forEach
            put(key,uid)
        }
    }
}
