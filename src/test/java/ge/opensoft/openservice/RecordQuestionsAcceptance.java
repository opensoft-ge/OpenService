package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.springframework.core.env.*;
import com.sun.net.httpserver.HttpServer;
final class RecordQuestionsAcceptance {
 static final class Provider implements AutoCloseable {
  final HttpServer server;final Store store;final ConfigurableEnvironment env;final String name="questions-"+id(),alias="questions-"+id();
  final Map<String,Object> settings=new HashMap<>();final AtomicInteger calls=new AtomicInteger(),status=new AtomicInteger(200);final AtomicReference<String> answer=new AtomicReference<>("Evidence <script>not executed</script>"),captured=new AtomicReference<>(),authorization=new AtomicReference<>();
  Provider(Store store,org.springframework.core.env.Environment environment) throws Exception {
   this.store=store;env=(ConfigurableEnvironment)environment;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/v1/chat/completions",exchange->{calls.incrementAndGet();captured.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));byte[] bytes=JSON.writeValueAsBytes(map("choices",List.of(map("message",map("content",answer.get())))));exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
   settings.putAll(Map.of("AI_ENABLED","true","AI_MODEL","synthetic","AI_CONNECTOR_ALIAS",alias,"AI_ALLOWED_FIELDS","short_description,description","CONNECTOR_ALLOWED_ORIGINS",origin()));env.getPropertySources().addFirst(new MapPropertySource(name,settings));
   try(var db=store.open()){String actor=PlatformFoundationTest.bind(db);Connectors.save(store,db,actor,map("alias",alias,"origin",origin(),"token","synthetic-question-token"));db.connection.commit();}
  }
  String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}
  public void close() throws Exception {server.stop(0);env.getPropertySources().remove(name);try(var db=store.open()){db.update("DELETE FROM os_connector WHERE alias=?",alias);db.connection.commit();}}
 }
 static String token(String html){var match=java.util.regex.Pattern.compile("name=\"preview_token\"[^>]*value=\"([^\"]+)\"").matcher(html);assertTrue(match.find(),html);return match.group(1);}
 static Map<String,Object> ask(String question,String token){return map("operation","ask","question",question,"preview_token",token,"allow_external","true");}
 static String preview(WorkflowSupport h,String path,String question) throws Exception {var response=h.protectedForm(path,map("operation","preview","question",question),auth("admin"));assertEquals(200,response.statusCode(),html(response));return token(html(response));}
 static void run(WorkflowSupport h) throws Exception {
  h.account("QuestionReader","reader");h.account("OtherQuestionAdmin","admin");String id=h.add("incident").get("sys_id").toString(),path="/record/incident/"+id+"/assistant",question="What evidence is available?";
  try(var provider=new Provider(h.store,h.environment)){
   assertEquals(200,h.get(path,auth("admin")).statusCode());assertEquals(403,h.get(path,auth("QuestionReader")).statusCode());assertEquals(302,h.get(path,null).statusCode());assertEquals(400,h.get(path+"?q=x",auth("admin")).statusCode());assertEquals(404,h.get(path.replace(id,id()),auth("admin")).statusCode());
   assertEquals(403,h.form(path,map("operation","preview","question",question),auth("admin")).statusCode());
   for(var bad:List.of(map("operation","unknown","question",question),map("operation","preview","question",question,"extra","x"),map("operation","preview","question",""),map("operation","preview","question","x".repeat(2001))))assertEquals(400,h.protectedForm(path,bad,auth("admin")).statusCode());
   String token=preview(h,path,question);assertEquals(0,provider.calls.get());
   String duplicate="operation=preview&question=first&question=second&csrf="+java.net.URLEncoder.encode(h.basicCsrf(auth("admin")),StandardCharsets.UTF_8);assertEquals(400,h.request("POST",path,duplicate.getBytes(StandardCharsets.UTF_8),"application/x-www-form-urlencoded",auth("admin")).statusCode());
   var summaryPreview=h.json("POST","/api/openservice/operations/assist-preview",map("table","incident","record_id",id),auth("admin"));assertEquals(200,summaryPreview.statusCode());String summaryToken=object(parsed(summaryPreview).get("result")).get("preview_token").toString();assertEquals(409,h.protectedForm(path,ask(question,summaryToken),auth("admin")).statusCode());
   var missingConsent=ask(question,token);missingConsent.remove("allow_external");assertEquals(400,h.protectedForm(path,missingConsent,auth("admin")).statusCode());
   assertEquals(409,h.protectedForm(path,ask(question+" changed",token),auth("admin")).statusCode());assertEquals(409,h.protectedForm(path,ask(question,token),auth("OtherQuestionAdmin")).statusCode());assertEquals(0,provider.calls.get());
   var response=h.protectedForm(path,ask(question,token),auth("admin"));assertEquals(200,response.statusCode(),html(response));assertTrue(html(response).contains("Evidence &lt;script&gt;not executed&lt;/script&gt;"));assertTrue(html(response).contains("No actions executed"));assertEquals("Bearer synthetic-question-token",provider.authorization.get());
   var request=object(JSON.readValue(provider.captured.get(),Object.class));assertEquals(800,request.get("max_completion_tokens"));assertEquals(false,request.get("store"));assertFalse(request.containsKey("tools"));assertTrue(provider.captured.get().contains(question));assertFalse(provider.captured.get().contains("synthetic-question-token"));assertFalse(provider.captured.get().contains("priority"));
   assertEquals(200,h.protectedForm(path,ask(question,token),auth("admin")).statusCode());assertEquals(2,provider.calls.get());
   try(var db=h.store.open()){PlatformFoundationTest.bind(db);assertEquals(0,((Number)Service.record(db,"incident",id).get("revision")).intValue());String detail=db.one("SELECT detail FROM os_security_event WHERE action='ai.record_question_answered' ORDER BY sequence DESC LIMIT 1").get("detail").toString();assertFalse(detail.contains(question));assertFalse(detail.contains("Evidence"));Service.update(db,"incident",Service.record(db,"incident",id),map("description","Changed grounding"),"admin","admin",null);db.connection.commit();}
   assertEquals(409,h.protectedForm(path,ask(question,token),auth("admin")).statusCode());
   for(var change:List.of(Map.entry("AI_ENABLED","false"),Map.entry("AI_MODEL",""),Map.entry("AI_CONNECTOR_ALIAS","missing"),Map.entry("CONNECTOR_ALLOWED_ORIGINS",""))){Object old=provider.settings.put(change.getKey(),change.getValue());String next=preview(h,path,question);assertEquals(Set.of("AI_ENABLED","AI_CONNECTOR_ALIAS").contains(change.getKey())?503:400,h.protectedForm(path,ask(question,next),auth("admin")).statusCode());provider.settings.put(change.getKey(),old);}
   assertEquals(2,provider.calls.get());token=preview(h,path,"x".repeat(2000));provider.status.set(503);assertEquals(502,h.protectedForm(path,ask("x".repeat(2000),token),auth("admin")).statusCode());provider.status.set(200);provider.answer.set("x".repeat(8001));assertEquals(502,h.protectedForm(path,ask("x".repeat(2000),token),auth("admin")).statusCode());provider.answer.set("x".repeat(8000));assertEquals(200,h.protectedForm(path,ask("x".repeat(2000),token),auth("admin")).statusCode());
   Map<String,Object> original;try(var db=h.store.open()){original=Service.schema(db,"incident");}var changed=object(JSON.readValue(JSON.writeValueAsString(original),Object.class));changed.put("record_roles",map("read",List.of("agent")));ApprovalAcceptance.writeSchema(h,changed);try{assertEquals(403,h.json("POST","/api/openservice/operations/assist-preview",map("table","incident","record_id",id),auth("admin")).statusCode());assertEquals(403,h.json("POST","/api/openservice/operations/assist",map("table","incident","record_id",id,"allow_external",true,"preview_token",summaryToken),auth("admin")).statusCode());assertEquals(403,h.get(path,auth("admin")).statusCode());assertEquals(403,h.protectedForm(path,ask("x".repeat(2000),token),auth("admin")).statusCode());}finally{ApprovalAcceptance.writeSchema(h,original);}
  }
 }
}
