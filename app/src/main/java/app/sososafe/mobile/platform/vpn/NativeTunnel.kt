package app.sososafe.mobile.platform.vpn

interface PacketSink { fun onPacket(data:ByteArray,wireLength:Int,outbound:Boolean) }
object NativeTunnel {
    init {System.loadLibrary("hev-socks5-tunnel")}
    @JvmStatic external fun run(config:String,fd:Int,session:Long):Int
    @JvmStatic external fun drain(sink:PacketSink,session:Long):Long
    @JvmStatic external fun stop()
}
