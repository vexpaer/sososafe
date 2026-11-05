package app.sososafe.core

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** RFC 1928/1929 local relay. No remote SOCKS server or TLS interception. */
class LocalSocksProxy(
    private val username:String,private val password:String,
    private val protectTcp:(Socket)->Unit={},private val protectUdp:(DatagramSocket)->Unit={},
    private val routeAddress:(InetAddress,Int)->InetAddress={address,_->address}
):Closeable {
    private val executor=Executors.newCachedThreadPool()
    private val resources=ConcurrentHashMap.newKeySet<Closeable>()
    private val limit=Semaphore(128)
    private val server=ServerSocket(0,64,InetAddress.getByName("127.0.0.1"))
    @Volatile private var running=true
    val port:Int get()=server.localPort
    init { resources+=server;executor.execute {
        while(running) {
            val s=try {server.accept()}catch(e:Exception){break}
            if(!limit.tryAcquire()){s.close();continue}
            resources+=s;executor.execute {try {serve(s)}catch(_:Exception){}finally{resources.remove(s);runCatching{s.close()};limit.release()}}
        }
    } }
    private fun DataInputStream.exact(n:Int):ByteArray { require(n in 0..65535);return ByteArray(n).also{readFully(it)} }
    private fun address(input:DataInputStream,type:Int):InetAddress=when(type) {
        1->InetAddress.getByAddress(input.exact(4));4->InetAddress.getByAddress(input.exact(16))
        // The TUN engine emits IP destinations; remote DNS lookups in the relay are forbidden.
        else->throw IllegalArgumentException("Only numeric IP destinations supported")
    }
    private fun reply(out:DataOutputStream,status:Int,port:Int=0){out.write(byteArrayOf(5,status.toByte(),0,1,127,0,0,1));out.writeShort(port);out.flush()}
    private fun serve(client:Socket) {
        client.soTimeout=15000;client.tcpNoDelay=true
        val input=DataInputStream(client.getInputStream());val output=DataOutputStream(client.getOutputStream())
        require(input.readUnsignedByte()==5);val methods=input.exact(input.readUnsignedByte())
        if(2.toByte() !in methods){output.write(byteArrayOf(5,0xff.toByte()));return};output.write(byteArrayOf(5,2));output.flush()
        require(input.readUnsignedByte()==1)
        val u=input.exact(input.readUnsignedByte());val p=input.exact(input.readUnsignedByte())
        val valid=MessageDigest.isEqual(u,username.toByteArray()) && MessageDigest.isEqual(p,password.toByteArray())
        output.write(byteArrayOf(1,if(valid)0 else 1));output.flush();if(!valid)return
        require(input.readUnsignedByte()==5);val command=input.readUnsignedByte();require(input.readUnsignedByte()==0)
        val host=address(input,input.readUnsignedByte());val port=input.readUnsignedShort()
        when(command){
            1 -> {
                val remote=Socket();resources+=remote
                var accepted=false
                try {
                    protectTcp(remote);remote.tcpNoDelay=true;remote.connect(InetSocketAddress(routeAddress(host,port),port),15000)
                    reply(output,0);accepted=true;client.soTimeout=0
                    val up=executor.submit {try{client.getInputStream().copyTo(remote.getOutputStream())}catch(_:Exception){}finally{runCatching{remote.shutdownOutput()}}}
                    try{remote.getInputStream().copyTo(output);output.flush()}finally{runCatching{client.shutdownOutput()};up.get(10,TimeUnit.SECONDS)}
                }catch(e:Exception){if(!accepted)runCatching{reply(output,5)}}finally{resources.remove(remote);remote.close()}
            }
            3 -> udpAssociation(client,output)
            else -> reply(output,7)
        }
    }
    private fun udpAssociation(control:Socket,out:DataOutputStream) {
        val relay=DatagramSocket(InetSocketAddress("127.0.0.1",0));resources+=relay
        val flows=ConcurrentHashMap<String,DatagramSocket>()
        val flowLimit=Semaphore(64)
        val active=java.util.concurrent.atomic.AtomicBoolean(true)
        val clientEndpoint=java.util.concurrent.atomic.AtomicReference<SocketAddress?>()
        reply(out,0,relay.localPort);control.soTimeout=0
        val worker=executor.submit {
            val buffer=ByteArray(65535)
            while(running && active.get()) {
                val packet=DatagramPacket(buffer,buffer.size)
                try {relay.receive(packet)}catch(_:Exception){break}
                if(!packet.address.isLoopbackAddress)continue
                clientEndpoint.compareAndSet(null,packet.socketAddress)
                if(clientEndpoint.get()!=packet.socketAddress)continue
                try {
                    val data=DataInputStream(java.io.ByteArrayInputStream(packet.data,packet.offset,packet.length))
                    require(data.readUnsignedShort()==0 && data.readUnsignedByte()==0)
                    val target=address(data,data.readUnsignedByte());val targetPort=data.readUnsignedShort()
                    val payload=ByteArray(data.available());data.readFully(payload)
                    val key="${target.hostAddress}:$targetPort"
                    var remote=flows[key]
                    if(remote==null) {
                        if(!flowLimit.tryAcquire())continue
                        val created=DatagramSocket(null);resources+=created
                        try {protectUdp(created);created.bind(InetSocketAddress(0));created.connect(routeAddress(target,targetPort),targetPort);created.soTimeout=30000}
                        catch(e:Exception){resources.remove(created);created.close();flowLimit.release();throw e}
                        flows[key]=created;remote=created
                        executor.execute {
                            try {
                                val bytes=ByteArray(65535)
                                while(running && active.get()) {
                                    val response=DatagramPacket(bytes,bytes.size);created.receive(response)
                                    val encoded=java.io.ByteArrayOutputStream();val encodedOut=DataOutputStream(encoded)
                                    encodedOut.writeShort(0);encodedOut.writeByte(0);encodedOut.writeByte(if(target.address.size==4)1 else 4);encodedOut.write(target.address);encodedOut.writeShort(targetPort);encodedOut.write(bytes,0,response.length)
                                    val b=encoded.toByteArray();val receiver=clientEndpoint.get() ?: continue
                                    relay.send(DatagramPacket(b,b.size,receiver))
                                }
                            }catch(_:Exception){}finally{flows.remove(key,created);resources.remove(created);created.close();flowLimit.release()}
                        }
                    }
                    remote.send(DatagramPacket(payload,payload.size))
                }catch(_:Exception){}
            }
        }
        try {while(running && control.getInputStream().read()!=-1){ /* SOCKS control connection lifetime */ }}
        finally{active.set(false);resources.remove(relay);relay.close();flows.values.forEach{it.close()};worker.cancel(true)}
    }
    fun resetUpstreams(){resources.toList().filter{it!==server}.forEach{runCatching{it.close()}}}
    override fun close(){running=false;resources.toList().forEach{runCatching{it.close()}};resources.clear();executor.shutdownNow()}
}
