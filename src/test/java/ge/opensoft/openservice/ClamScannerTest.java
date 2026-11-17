package ge.opensoft.openservice;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
class ClamScannerTest {
    static final String VERSION="ClamAV 1.5.4/12345/test definitions";
    static class Server implements AutoCloseable {
        final ServerSocket socket=new ServerSocket(0,8,InetAddress.getLoopbackAddress());final ExecutorService executor=Executors.newSingleThreadExecutor();final Future<?> task;volatile byte[] received;
        Server(String version,String verdict)throws Exception{task=executor.submit(()->{try{for(String response:new String[]{version,verdict}){try(var client=socket.accept()){var in=new DataInputStream(client.getInputStream());var command=new ByteArrayOutputStream();int b;while((b=in.read())>0)command.write(b);String name=command.toString(StandardCharsets.US_ASCII);if(name.equals("zINSTREAM")){var content=new ByteArrayOutputStream();for(int size;(size=in.readInt())!=0;)content.write(in.readNBytes(size));received=content.toByteArray();}else assertEquals("zVERSION",name);client.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));}if(!version.startsWith("ClamAV ") || !version.endsWith("\0"))break;}}catch(Exception e){throw new RuntimeException(e);}});}
        int port(){return socket.getLocalPort();}
        public void close()throws Exception{socket.close();executor.shutdownNow();try{task.get(5,TimeUnit.SECONDS);}catch(ExecutionException e){if(!(e.getCause().getCause() instanceof SocketException))throw e;}}
    }
    @Test void aScannerThatDoesNotReadOrReplyCannotHangTheWorker()throws Exception{
        try(var listener=new ServerSocket(0);var executor=Executors.newSingleThreadExecutor()){
            var future=executor.submit(()->{try(var client=listener.accept()){Thread.sleep(3500);}return null;});long start=System.nanoTime();assertThrows(Exception.class,()->ClamScanner.exchange("127.0.0.1",listener.getLocalPort(),new byte[5*1024*1024]));assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()<6000);future.get();
        }
    }
    @Test void streamedResultsRequireCompleteRecognizedReplies()throws Exception{
        for(String verdict:List.of("stream: OK\0","stream: Eicar-Signature FOUND\0","stream: size limit ERROR\0","stream: OK","x".repeat(1025)+"\0"))try(var server=new Server(VERSION+"\0",verdict)){byte[] bytes="bounded scan bytes".getBytes();var result=ClamScanner.scan("127.0.0.1",server.port(),bytes);assertEquals(verdict.equals("stream: OK\0")?"clean":verdict.contains(" FOUND")?"infected":"unavailable",result.get("status"));assertArrayEquals(bytes,server.received);assertFalse(result.toString().contains("Signature"));}
        try(var server=new Server(VERSION+"\0","stream: OK\0")){assertEquals("clean",ClamScanner.scan("127.0.0.1",server.port(),new byte[0]).get("status"));assertEquals(0,server.received.length);}
    }
    @Test void invalidEnginesAndConnectionFailuresRemainUnavailable()throws Exception{
        for(String version:List.of("wrong\0","ClamAV bad\nversion\0","ClamAV "+"x".repeat(241)+"\0"))try(var server=new Server(version,"")){assertEquals(Map.of("status","unavailable","engine",""),ClamScanner.scan("127.0.0.1",server.port(),new byte[]{1}));}
        int closed;try(var socket=new ServerSocket(0)){closed=socket.getLocalPort();}assertEquals("unavailable",ClamScanner.scan("127.0.0.1",closed,new byte[]{1}).get("status"));assertEquals("unavailable",ClamScanner.scan("127.0.0.1",-1,new byte[]{1}).get("status"));
    }
}
