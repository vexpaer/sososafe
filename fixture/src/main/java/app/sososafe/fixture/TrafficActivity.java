package app.sososafe.fixture;

import android.app.Activity;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** CI-only peer UID. This APK is never bundled with the product APK. */
public final class TrafficActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            JSONObject result=new JSONObject();
            try {
                byte[] request="soso-tcp".getBytes(StandardCharsets.UTF_8);
                try(Socket socket=new Socket()){
                    socket.setSoTimeout(5000);socket.connect(new InetSocketAddress("10.0.2.2",18080),5000);
                    socket.getOutputStream().write(request);
                    byte[] response=new byte[request.length];new DataInputStream(socket.getInputStream()).readFully(response);
                    result.put("tcp",new String(response,StandardCharsets.UTF_8));
                }
                try(DatagramSocket socket=new DatagramSocket()){
                    socket.setSoTimeout(5000);socket.connect(InetAddress.getByName("10.0.2.2"),18081);
                    byte[] bytes="soso-udp".getBytes(StandardCharsets.UTF_8);socket.send(new DatagramPacket(bytes,bytes.length));
                    DatagramPacket response=new DatagramPacket(new byte[100],100);socket.receive(response);
                    result.put("udp",new String(response.getData(),0,response.getLength(),StandardCharsets.UTF_8));
                    // Keep the original socket alive for the VPN owner-UID API check.
                    Thread.sleep(2000);
                }
                try(DatagramSocket socket=new DatagramSocket()){
                    ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream dns=new DataOutputStream(buffer);
                    dns.writeShort(37);dns.writeShort(0x100);dns.writeShort(1);dns.writeShort(0);dns.writeShort(0);dns.writeShort(0);
                    for(String label:"sososafe-ci.invalid".split("\\.")){dns.writeByte(label.length());dns.writeBytes(label);}
                    dns.writeByte(0);dns.writeShort(1);dns.writeShort(1);
                    byte[] query=buffer.toByteArray();socket.send(new DatagramPacket(query,query.length,InetAddress.getByName("10.82.0.2"),53));
                    result.put("dnsSent",true);Thread.sleep(200);
                }
            }catch(Exception e){try{result.put("error",e.toString());}catch(Exception ignored){}}
            try {
                result.put("done",true);
                File output=new File(getExternalFilesDir(null),"result.json");
                try(Writer writer=new OutputStreamWriter(new FileOutputStream(output),StandardCharsets.UTF_8)){writer.write(result.toString());}
            }catch(Exception ignored){}
            runOnUiThread(this::finish);
        },"fixture-traffic").start();
    }
}
