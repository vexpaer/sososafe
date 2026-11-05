package app.sososafe.mobile.platform.vpn

import android.app.*
import android.content.Intent
import android.net.*
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import app.sososafe.core.LocalSocksProxy
import app.sososafe.mobile.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.Socket
import java.net.DatagramSocket
import java.util.UUID

data class MonitorState(val running:Boolean=false,val status:String="STOPPED",val error:String?=null,val unrecorded:Long=0)
class MonitorVpnService:VpnService() {
    companion object {val state=MutableStateFlow(MonitorState());const val STOP="app.sososafe.STOP_VPN"}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var tun:ParcelFileDescriptor?=null;private var proxy:LocalSocksProxy?=null;private var nativeJob:Job?=null;private var recorder:TrafficRecorder?=null
    private val cm by lazy {getSystemService(ConnectivityManager::class.java)}
    @Volatile private var underlying:Network?=null
    private val callback=object:ConnectivityManager.NetworkCallback(){
        override fun onAvailable(network:Network){underlying=network;setUnderlyingNetworks(arrayOf(network));if(state.value.running)state.value=state.value.copy(status="ACTIVE · new connections use the current network")}
        override fun onLost(network:Network){if(underlying==network){underlying=null;setUnderlyingNetworks(null);state.value=state.value.copy(status="WAITING_FOR_NETWORK · existing connections may reconnect")}}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){stopMonitor();return START_NOT_STICKY}
        if(tun!=null)return START_NOT_STICKY
        try {
            if(android.os.Build.VERSION.SDK_INT>=37)check(checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==android.content.pm.PackageManager.PERMISSION_GRANTED){"Local network permission is required. Restart monitoring from the app."}
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("monitor",getString(R.string.monitor_channel),NotificationManager.IMPORTANCE_LOW))
            val stop=PendingIntent.getService(this,2,Intent(this,MonitorVpnService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n=NotificationCompat.Builder(this,"monitor").setSmallIcon(R.drawable.ic_shield).setContentTitle(getString(R.string.monitor_title)).setContentText(getString(R.string.monitor_body)).setContentIntent(open).setOngoing(true).addAction(0,getString(R.string.stop),stop).build()
            startForeground(17,n)
            cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),callback)
            underlying=cm.allNetworks.firstOrNull{cm.getNetworkCapabilities(it)?.let{c->c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)}==true}
            val user=UUID.randomUUID().toString();val pass=UUID.randomUUID().toString()
            fun tcp(s:Socket){check(protect(s)){"Cannot protect relay socket"};underlying?.bindSocket(s)}
            fun udp(s:DatagramSocket){check(protect(s)){"Cannot protect UDP relay"};underlying?.bindSocket(s)}
            proxy=LocalSocksProxy(user,pass,::tcp,::udp)
            val dns=underlying?.let{cm.getLinkProperties(it)?.dnsServers}.orEmpty().filter{!it.isAnyLocalAddress && !it.isLoopbackAddress}
            val builder=Builder().setSession("sososafe · local metadata monitor").setMtu(1500).addAddress("10.82.0.1",32).addAddress("fd82:5afe::1",128).addRoute("0.0.0.0",0).addRoute("::",0).addDisallowedApplication(packageName).setBlocking(false)
            check(dns.isNotEmpty()){ "No network DNS resolver available. Connect to Wi-Fi or mobile data and retry." }
            dns.forEach{builder.addDnsServer(it)}
            tun=checkNotNull(builder.establish()){"Android VPN consent is missing or another VPN conflicts"}
            setUnderlyingNetworks(underlying?.let{arrayOf(it)})
            val app=application as SosoApplication
            recorder=TrafficRecorder(this,app.repo,scope)
            val config=File(cacheDir,"tunnel.yml").apply{writeText("""
                tunnel:
                  name: sososafe
                  mtu: 1500
                  ipv4: 10.82.0.1
                  ipv6: 'fd82:5afe::1'
                socks5:
                  address: 127.0.0.1
                  port: ${checkNotNull(proxy).port}
                  udp: 'udp'
                  username: '$user'
                  password: '$pass'
                misc:
                  task-stack-size: 86016
                  max-session-count: 256
                  log-level: error
                  log-file: /dev/null
            """.trimIndent())}
            state.value=MonitorState(true,"ACTIVE")
            nativeJob=scope.launch{
                try {
                    val result=NativeTunnel.run(config.absolutePath,checkNotNull(tun).fd,checkNotNull(recorder))
                    if(state.value.running)state.value=MonitorState(false,"ERROR","Native tunnel exited ($result)")
                }catch(e:Throwable){state.value=MonitorState(false,"ERROR",e.message)}
                finally{config.delete();stopMonitor()}
            }
            scope.launch {while(isActive && state.value.running){delay(2000);state.value=state.value.copy(unrecorded=recorder?.dropped ?: 0)}}
        }catch(e:Exception){state.value=MonitorState(false,"ERROR",e.message);stopMonitor()}
        return START_NOT_STICKY
    }
    @Synchronized private fun stopMonitor() {
        val wasRunning=state.value.running
        state.value=state.value.copy(running=false,status=if(state.value.error!=null)"ERROR" else "STOPPED")
        if(wasRunning)runCatching{NativeTunnel.stop()}
        proxy?.close();proxy=null
        runCatching{tun?.close()};tun=null
        runCatching{cm.unregisterNetworkCallback(callback)}
        val pending=recorder;recorder=null
        scope.launch {runCatching{pending?.flush()}}
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onRevoke(){stopMonitor();super.onRevoke()}
    override fun onDestroy(){stopMonitor();scope.launch{delay(1000);scope.cancel()};super.onDestroy()}
}
