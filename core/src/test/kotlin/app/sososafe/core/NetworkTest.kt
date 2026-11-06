package app.sososafe.core

import kotlin.test.*
import java.net.*
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkTest {
    @Test fun shellOwnershipRequiresUidAndExactTuple(){
        val records=SocketOwnership.parseSs("tcp ESTAB 0 0 10.82.0.1:12000 8.8.8.8:443 users:((app,pid=42)) uid:10123\nudp ESTAB 0 0 [::1]:14000 [2001:4860:4860::8888]:53 uid:10124\ntcp ESTAB 0 0 10.0.0.1:10 8.8.8.8:443 users:((x,pid=9))")
        assertEquals(2,records.size)
        assertEquals(10123,records[SocketOwnership.key(6,"10.82.0.1",12000,"8.8.8.8",443)])
        assertNull(records[SocketOwnership.key(6,"10.82.0.1",12001,"8.8.8.8",443)])
        assertNull(SocketOwnership.address("attacker.example"))
        assertNull(SocketOwnership.address("abc.de"));assertNull(SocketOwnership.address("999.1.2.3"))
    }
    @Test fun ipv4UdpAndIpv6TcpHeaders(){
        val udp=ByteArray(32);udp[0]=0x45;udp[3]=32;udp[9]=17;udp[12]=10;udp[15]=1;udp[16]=8;udp[17]=8;udp[18]=8;udp[19]=8;udp[20]=0x30;udp[21]=0x39;udp[23]=53;udp[25]=12
        val p=Packets.parse(udp)!!;assertEquals(12345,p.sourcePort);assertEquals(53,p.destinationPort);assertEquals("8.8.8.8",p.destination)
        val tcp=ByteArray(60);tcp[0]=0x60;tcp[5]=20;tcp[6]=6;tcp[23]=1;tcp[39]=2;tcp[41]=80;tcp[42]=1;tcp[43]=0xbb.toByte();tcp[52]=0x50;tcp[53]=2
        val v6=Packets.parse(tcp)!!;assertEquals(6,v6.version);assertEquals(443,v6.destinationPort);assertEquals(2,v6.tcpFlags)
    }
    @Test fun malformedPacketsNeverCrash(){
        (0..100).forEach{assertNull(Packets.parse(ByteArray(it)))}
        val fragment=ByteArray(28);fragment[0]=0x45;fragment[3]=28;fragment[7]=1;fragment[9]=17;assertNull(Packets.parse(fragment))
    }
    private fun query():ByteArray{val out=ByteArrayOutputStream();DataOutputStream(out).use{d->d.writeShort(42);d.writeShort(0x100);d.writeShort(1);repeat(3){d.writeShort(0)};"example.com".split('.').forEach{d.writeByte(it.length);d.writeBytes(it)};d.writeByte(0);d.writeShort(1);d.writeShort(1)};return out.toByteArray()}
    @Test fun dnsQueriesCompressedAnswersAndPointerCycles(){
        val q=query();assertEquals(listOf("example.com"),Packets.dns(q)?.names)
        val out=ByteArrayOutputStream();out.write(q);DataOutputStream(out).apply{writeShort(0xc00c);writeShort(1);writeShort(1);writeInt(30);writeShort(4);write(byteArrayOf(1,2,3,4))}
        val reply=out.toByteArray();reply[2]=0x81.toByte();reply[3]=0x80.toByte();reply[7]=1
        assertEquals(listOf("1.2.3.4"),Packets.dns(reply)?.addresses?.get("example.com"))
        val cycle=ByteArray(18);cycle[5]=1;cycle[12]=0xc0.toByte();cycle[13]=12;assertNull(Packets.dns(cycle))
    }
    @Test fun tcpDnsReassemblesAndRejectsGapsAndRetransmits(){
        val message=query();val framed=byteArrayOf((message.size ushr 8).toByte(),message.size.toByte())+message
        val parser=DnsTcpAssembler();parser.feed("a",100,byteArrayOf(),true)
        assertTrue(parser.feed("a",101,framed.copyOfRange(0,5)).isEmpty())
        assertTrue(parser.feed("a",101,framed.copyOfRange(0,5)).isEmpty())
        assertEquals(listOf("example.com"),parser.feed("a",106,framed.copyOfRange(5,framed.size)).single().names)
        parser.feed("b",100,byteArrayOf(),true);assertTrue(parser.feed("b",110,framed).isEmpty());assertTrue(parser.feed("b",101,framed).isEmpty())
        assertTrue(parser.feed("unknown",101,framed).isEmpty())
    }
    private fun auth(proxy:LocalSocksProxy):Socket {
        val s=Socket("127.0.0.1",proxy.port);s.soTimeout=5000;val out=DataOutputStream(s.getOutputStream());val input=DataInputStream(s.getInputStream())
        out.write(byteArrayOf(5,1,2));assertEquals(5,input.readUnsignedByte());assertEquals(2,input.readUnsignedByte())
        out.write(byteArrayOf(1,1,'u'.code.toByte(),1,'p'.code.toByte()));assertEquals(1,input.readUnsignedByte());assertEquals(0,input.readUnsignedByte());return s
    }
    @Test fun authenticatedTcpForwardsInBothDirectionsIpv4AndIpv6(){
        listOf("127.0.0.1","::1").forEach{host->
            val pool=Executors.newSingleThreadExecutor();val protected=AtomicInteger()
            ServerSocket(0,1,InetAddress.getByName(host)).use{echo->
                val task=pool.submit{echo.accept().use{s->val b=ByteArray(5);DataInputStream(s.getInputStream()).readFully(b);s.getOutputStream().write(b)}}
                LocalSocksProxy("u","p",{protected.incrementAndGet()}).use{proxy->auth(proxy).use{s->
                    val out=DataOutputStream(s.getOutputStream());val input=DataInputStream(s.getInputStream());val address=InetAddress.getByName(host).address
                    out.write(byteArrayOf(5,1,0,if(address.size==4)1 else 4));out.write(address);out.writeShort(echo.localPort);assertEquals(5,input.readUnsignedByte());assertEquals(0,input.readUnsignedByte());ByteArray(8).also{input.readFully(it)}
                    out.writeBytes("hello");out.flush();val result=ByteArray(5);input.readFully(result);assertEquals("hello",result.toString(Charsets.UTF_8));assertEquals(1,protected.get())
                }}
                task.get(5,TimeUnit.SECONDS)
            };pool.shutdownNow()
        }
    }
    @Test fun udpAssociationForwardsResponsesIpv4AndIpv6(){
        listOf("127.0.0.1","::1").forEach{host->
            val pool=Executors.newSingleThreadExecutor();val protected=AtomicInteger()
            DatagramSocket(InetSocketAddress(host,0)).use{echo->echo.soTimeout=5000
                val task=pool.submit{val b=ByteArray(100);val p=DatagramPacket(b,b.size);echo.receive(p);echo.send(p)}
                LocalSocksProxy("u","p",protectUdp={protected.incrementAndGet()}).use{proxy->auth(proxy).use{control->
                    val out=DataOutputStream(control.getOutputStream());val input=DataInputStream(control.getInputStream());out.write(byteArrayOf(5,3,0,1,0,0,0,0,0,0))
                    assertEquals(5,input.readUnsignedByte());assertEquals(0,input.readUnsignedByte());ByteArray(6).also{input.readFully(it)};val relayPort=input.readUnsignedShort()
                    DatagramSocket().use{client->client.soTimeout=5000;val bytes=ByteArrayOutputStream();val d=DataOutputStream(bytes);d.writeShort(0);d.writeByte(0);val addr=InetAddress.getByName(host).address;d.writeByte(if(addr.size==4)1 else 4);d.write(addr);d.writeShort(echo.localPort);d.writeBytes("echo")
                        val data=bytes.toByteArray();client.send(DatagramPacket(data,data.size,InetAddress.getByName("127.0.0.1"),relayPort));val received=DatagramPacket(ByteArray(100),100);client.receive(received);assertEquals("echo",received.data.copyOfRange(received.length-4,received.length).toString(Charsets.UTF_8));assertEquals(1,protected.get())}
                }};task.get(5,TimeUnit.SECONDS)
            };pool.shutdownNow()
        }
    }
    @Test fun socksRefusesUnauthenticatedClients(){LocalSocksProxy("u","p").use{proxy->Socket("127.0.0.1",proxy.port).use{s->s.soTimeout=3000;s.getOutputStream().write(byteArrayOf(5,1,0));val input=DataInputStream(s.getInputStream());assertEquals(5,input.readUnsignedByte());assertEquals(255,input.readUnsignedByte())}}}
}
