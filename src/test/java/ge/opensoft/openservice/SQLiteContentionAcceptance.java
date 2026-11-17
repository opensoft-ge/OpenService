package ge.opensoft.openservice;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import java.net.http.*;
import java.util.concurrent.*;
final class SQLiteContentionAcceptance {
 static void run(WorkflowSupport h) throws Exception {
  try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
   java.util.List<HttpResponse<byte[]>> replies;
   try(var holder=h.store.open()){
    var read=executor.submit(()->h.get("/api/now/table/incident",auth("admin")));
    var write=executor.submit(()->h.json("POST","/api/now/table/incident",map("short_description","Not written while busy"),auth("admin")));
    replies=java.util.List.of(read.get(20,TimeUnit.SECONDS),write.get(20,TimeUnit.SECONDS));
   }
   for(var reply:replies){assertEquals(503,reply.statusCode(),html(reply));assertEquals("1",reply.headers().firstValue("Retry-After").orElseThrow());assertEquals("no-store",reply.headers().firstValue("Cache-Control").orElseThrow());assertFalse(html(reply).contains("SQLITE"));assertTrue(html(reply).contains("did not start"));}
   try(var db=h.store.open()){assertEquals(0,db.count("SELECT COUNT(*) FROM os_record"));}
   assertEquals(201,h.json("POST","/api/now/table/incident",map("short_description","Recovered write"),auth("admin")).statusCode());assertEquals(200,h.get("/api/now/table/incident",auth("admin")).statusCode());
  }
 }
}
