package ge.opensoft.openservice;

import java.net.ServerSocket;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit local PostgreSQL alternative; every test JVM owns a disposable isolated cluster. */
final class LocalTestDatabase {
    static String start(String binaries) throws Exception {
        Path root=Files.createTempDirectory("openservice-auto50-postgres-");
        Path data=root.resolve("data"),log=root.resolve("postgres.log");
        int port;
        try(var socket=new ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        command(root,Path.of(binaries,"initdb").toString(),"-D",data.toString(),"-U","openservice","--auth=trust","--encoding=UTF8","--no-locale");
        Files.writeString(data.resolve("postgresql.conf"),"\nlisten_addresses='127.0.0.1'\nport="+port+"\nunix_socket_directories=''\n",StandardOpenOption.APPEND);
        command(root,Path.of(binaries,"pg_ctl").toString(),"-D",data.toString(),"-l",log.toString(),"-w","start");
        Runtime.getRuntime().addShutdownHook(new Thread(()->{try{command(root,Path.of(binaries,"pg_ctl").toString(),"-D",data.toString(),"-m","fast","-w","stop");try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}catch(Exception failure){System.err.println("Local test PostgreSQL cleanup needs attention: "+root);}}));
        try(var db=DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"+port+"/postgres","openservice","disposable-integration-password");var statement=db.createStatement()){statement.execute("CREATE DATABASE openservice");}
        return "postgresql+psycopg://openservice:disposable-integration-password@127.0.0.1:"+port+"/openservice";
    }
    private static void command(Path root,String...arguments)throws Exception{
        Path output=Files.createTempFile(root,"command-",".log");var process=new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Local PostgreSQL command timed out");}
        if(process.exitValue()!=0)throw new IllegalStateException("Local PostgreSQL setup failed: "+Files.readString(output));
    }
}
