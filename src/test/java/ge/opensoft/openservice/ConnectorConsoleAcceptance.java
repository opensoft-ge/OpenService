package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class ConnectorConsoleAcceptance {
 static String review(String html){var match=java.util.regex.Pattern.compile("name=\"review\"[^>]*value=\"([^\"]+)\"").matcher(html);assertTrue(match.find(),html);return match.group(1);}
 static Map<String,Object> probe(String review,String key){return map("operation","probe","path","/probe","review",review,"key",key);}
 static void run(WorkflowSupport h) throws Exception {
  h.account("ConnectorReader","reader");h.account("ConnectorAgent","agent");
  try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment)){
   provider.server.createContext("/probe",exchange->{provider.calls.incrementAndGet();provider.authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));byte[] bytes="private-provider-response".getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
   String path="/admin/connectors/"+provider.alias;var page=h.get(path,auth("admin"));assertEquals(200,page.statusCode());String review=review(html(page)),key=id();assertFalse(html(page).contains("synthetic-question-token"));
   assertEquals(403,h.get(path,auth("ConnectorReader")).statusCode());assertEquals(403,h.get(path,auth("ConnectorAgent")).statusCode());assertEquals(302,h.get(path,null).statusCode());assertEquals(400,h.get(path+"?x=y",auth("admin")).statusCode());assertEquals(404,h.get("/admin/connectors/missing",auth("admin")).statusCode());
   assertEquals(403,h.form(path,probe(review,key),auth("admin")).statusCode());
   var queued=h.protectedForm(path,probe(review,key),auth("admin"));assertEquals(302,queued.statusCode(),html(queued));String location=queued.headers().firstValue("Location").orElseThrow();assertEquals(location,h.protectedForm(path,probe(review,key),auth("admin")).headers().firstValue("Location").orElseThrow());assertEquals(0,provider.calls.get());
   assertTrue(new WorkWorker(h.store).runOne(System.currentTimeMillis()+100));assertEquals(1,provider.calls.get());assertEquals("Bearer synthetic-question-token",provider.authorization.get());String receipt=html(h.get(location,auth("admin")));assertTrue(receipt.contains("succeeded"));assertTrue(receipt.contains("sha256"));assertFalse(receipt.contains("private-provider-response"));assertFalse(receipt.contains("synthetic-question-token"));
   for(var bad:List.of(map("operation","other","review",review),map("operation","probe","review",review,"key",id()),map("operation","retire","review",review,"reason",""),map("operation","retire","review",review,"reason","r","extra","x"))){assertEquals(400,h.protectedForm(path,bad,auth("admin")).statusCode());}
   for(String invalid:List.of("relative","//other","/path#fragment","/path\\file")){var bad=probe(review,id());bad.put("path",invalid);assertEquals(400,h.protectedForm(path,bad,auth("admin")).statusCode());}
   assertEquals(302,h.protectedForm(path,probe(review,id()),auth("admin")).statusCode());
   try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);Connectors.retire(db,actor,provider.alias,map("reason","Rotate by replacement"));Connectors.save(h.store,db,actor,map("alias",provider.alias,"origin",provider.origin(),"token","replacement-token"));db.connection.commit();}
   assertEquals(409,h.protectedForm(path,probe(review,id()),auth("admin")).statusCode());assertTrue(new WorkWorker(h.store).runOne(System.currentTimeMillis()+200));assertEquals(1,provider.calls.get());
   try(var db=h.store.open()){assertEquals(1,db.count("SELECT COUNT(*) FROM os_work WHERE state='cancelled'"));}
   String current=review(html(h.get(path,auth("admin"))));assertEquals(302,h.protectedForm(path,map("operation","retire","review",current,"reason","No longer required"),auth("admin")).statusCode());assertEquals(404,h.get(path,auth("admin")).statusCode());
  }
 }
}
