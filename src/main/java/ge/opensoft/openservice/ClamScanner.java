package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
/** Bounded INSTREAM transport to a deployment-controlled, trusted-network clamd endpoint. */
final class ClamScanner {
    private ClamScanner() {}
    static Map<String,Object> scan(String host,int port,byte[] bytes){
        try{String version=exchange(host,port,null);if(!version.matches("ClamAV [ -~]{1,240}"))throw new IOException("Invalid engine response");String response=exchange(host,port,bytes);String status;if(response.equals("stream: OK"))status="clean";else if(response.matches("stream: [ -~]{1,200} FOUND"))status="infected";else throw new IOException("Unavailable verdict");return map("status",status,"engine",version);}
        catch(Exception unavailable){return map("status","unavailable","engine","");}
    }
    static String exchange(String host,int port,byte[] bytes)throws Exception{
        try(var executor=Executors.newVirtualThreadPerTaskExecutor();var socket=new Socket()){socket.connect(new InetSocketAddress(host,port),3000);socket.setSoTimeout(3000);return executor.submit(()->{var output=new DataOutputStream(socket.getOutputStream());output.write((bytes==null?"zVERSION\0":"zINSTREAM\0").getBytes(StandardCharsets.US_ASCII));if(bytes!=null){if(bytes.length>0){output.writeInt(bytes.length);output.write(bytes);}output.writeInt(0);}output.flush();var result=new ByteArrayOutputStream();var input=socket.getInputStream();for(int i=0;i<=1024;i++){int value=input.read();if(value==0)return result.toString(StandardCharsets.US_ASCII);if(value<0)throw new EOFException("Incomplete scanner reply");result.write(value);}throw new IOException("Scanner reply exceeds limit");}).get(3,TimeUnit.SECONDS);}
    }
}
