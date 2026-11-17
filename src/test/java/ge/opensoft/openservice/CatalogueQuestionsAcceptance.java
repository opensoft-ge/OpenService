package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.net.http.HttpResponse;
import java.util.*;

final class CatalogueQuestionsAcceptance {
    static final String CATALOG="/api/openservice/catalogue";
    static Map<String,Object> result(HttpResponse<byte[]> r,int status){assertEquals(status,r.statusCode(),html(r));return object(parsed(r).get("result"));}
    static Map<String,Object> question(String key,String type,boolean required,List<String> choices){return map("key",key,"label","Question "+key,"type",type,"required",required,"choices",choices);}
    static Map<String,Object> schema(int revision,Object questions){return map("revision",revision,"questions",questions);}
    static List<Map<String,Object>> answers(WorkflowSupport h,String id,String user)throws Exception{var r=h.get("/api/openservice/requests/"+id+"/answers",auth(user));assertEquals(200,r.statusCode(),html(r));return objects(parsed(r).get("result"));}
    static void run(WorkflowSupport h)throws Exception {
        h.account("QuestionBuyer","requester");h.account("QuestionOther","requester");h.account("QuestionReader","reader");h.account("QuestionAgent","agent");
        String offering=result(h.json("POST",CATALOG,CatalogueAcceptance.offering("Question service",0,true),auth("admin")),201).get("offering_id").toString(),api=CATALOG+"/"+offering+"/questions",order=CATALOG+"/"+offering+"/order",admin="/admin/catalogue/"+offering+"/questions";
        var legacy=map("revision",1,"details","Before questions","key",id());String old=result(h.json("POST",order,legacy,auth("QuestionBuyer")),201).get("sys_id").toString();
        var questions=List.of(question("reason","string",true,List.of()),question("size","choice",true,List.of("Small","Large <script>")),question("needed","boolean",true,List.of()),question("start","date",true,List.of()),question("count","integer",false,List.of()));
        var saved=result(h.json("PUT",api,schema(1,questions),auth("admin")),200);assertEquals(5,objects(saved.get("questions")).size());
        assertEquals(5,objects(result(h.get(CATALOG+"/"+offering,auth("QuestionBuyer")),200).get("questions")).size());assertEquals(old,result(h.json("POST",order,legacy,auth("QuestionBuyer")),201).get("sys_id"));assertTrue(answers(h,old,"QuestionBuyer").isEmpty());
        var values=map("reason","Project <script>alert(1)</script>","size","Large <script>","needed","false","start","2026-11-18","count","-2");var body=map("revision",2,"details","Reviewed","key",id(),"answers",values);
        String request=result(h.json("POST",order,body,auth("QuestionBuyer")),201).get("sys_id").toString();var snapshot=answers(h,request,"QuestionBuyer");assertEquals(5,snapshot.size());assertEquals("Project <script>alert(1)</script>",snapshot.getFirst().get("value"));
        assertEquals("false",snapshot.get(2).get("value"));assertTrue(result(h.get("/api/now/table/sc_request/"+request,auth("QuestionBuyer")),200).get("description").toString().contains("Question size: Large <script>"));
        assertTrue(html(h.get("/record/sc_request/"+request,auth("QuestionBuyer"))).contains("&lt;script&gt;"));assertEquals(404,h.get("/api/openservice/requests/"+request+"/answers",auth("QuestionOther")).statusCode());
        var reverse=map("count","-2","start","2026-11-18","needed","false","size","Large <script>","reason","Project <script>alert(1)</script>");body.put("answers",reverse);assertEquals(request,result(h.json("POST",order,body,auth("QuestionBuyer")),201).get("sys_id"));
        var changed=new LinkedHashMap<>(reverse);changed.put("count","3");body.put("answers",changed);assertEquals(409,h.json("POST",order,body,auth("QuestionBuyer")).statusCode());body.put("answers",reverse);
        for(var entry:Map.of("reason","","size","Unknown","needed","yes","start","2026-02-30","count","1.5").entrySet()){var bad=new LinkedHashMap<>(values);bad.put(entry.getKey(),entry.getValue());assertEquals(400,h.json("POST",order,map("revision",2,"details","","key",id(),"answers",bad),auth("QuestionBuyer")).statusCode());}
        for(Object bad:List.of("wrong",map(),map("unknown","x"),map("Bad-key","x"),map("reason",42),map("reason","x".repeat(2001)),map("reason","\0")))assertEquals(400,h.json("POST",order,map("revision",2,"details","","key",id(),"answers",bad),auth("QuestionBuyer")).statusCode());
        var tooMany=map();for(int i=0;i<21;i++)tooMany.put("answer"+i,"x");assertEquals(400,h.json("POST",order,map("revision",2,"details","","key",id(),"answers",tooMany),auth("QuestionBuyer")).statusCode());
        var optional=new LinkedHashMap<>(values);optional.remove("count");String optionalRequest=result(h.json("POST",order,map("revision",2,"details","","key",id(),"answers",optional),auth("QuestionBuyer")),201).get("sys_id").toString();assertEquals("",answers(h,optionalRequest,"QuestionBuyer").getLast().get("value"));
        assertEquals(403,h.json("POST",order,body,auth("QuestionReader")).statusCode());assertEquals(403,h.json("PUT",api,schema(2,List.of()),auth("QuestionAgent")).statusCode());assertEquals(401,h.json("PUT",api,schema(2,List.of()),null).statusCode());assertEquals(405,h.get(api,auth("admin")).statusCode());assertEquals(400,h.json("PUT",api+"?x=y",schema(2,List.of()),auth("admin")).statusCode());
        assertEquals(409,h.json("PUT",api,schema(1,List.of()),auth("admin")).statusCode());
        var invalidSchemas=new ArrayList<Object>(List.of(map(),schema(2,"not a list"),schema(2,Collections.nCopies(21,questions.getFirst())),schema(2,List.of("not object")),schema(2,List.of(map())),schema(2,List.of(questions.getFirst(),questions.getFirst())),map("revision","2","questions",List.of()),schema(0,List.of())));
        for(var change:List.of(map("key","Bad-key"),map("label",""),map("type","script"),map("required","true"),map("choices","not list"),map("choices",Collections.nCopies(21,"x")),map("choices",List.of("x","x")),map("choices",List.of("")),map("choices",List.of("unexpected")))){var bad=question("valid","string",false,List.of());bad.putAll(change);invalidSchemas.add(schema(2,List.of(bad)));}
        invalidSchemas.add(schema(2,List.of(question("valid","choice",false,List.of()))));for(Object invalid:invalidSchemas)assertEquals(400,h.json("PUT",api,invalid,auth("admin")).statusCode(),invalid.toString());
        assertEquals(200,h.get(admin,auth("admin")).statusCode());assertEquals(200,h.get(admin+"?question=size",auth("admin")).statusCode());assertEquals(404,h.get(admin+"?question=missing",auth("admin")).statusCode());assertEquals(400,h.get(admin+"?extra=x",auth("admin")).statusCode());
        var form=map("operation","save","revision",2,"key","reason","label","New reason label","type","string","required","true","choices","");assertEquals(403,h.form(admin,form,auth("admin")).statusCode());assertEquals(302,h.protectedForm(admin,form,auth("admin")).statusCode());
        assertEquals("Question reason",answers(h,request,"QuestionBuyer").getFirst().get("label"));assertEquals(request,result(h.json("POST",order,body,auth("QuestionBuyer")),201).get("sys_id"));
        assertEquals(409,h.json("POST",order,map("revision",2,"details","","key",id(),"answers",values),auth("QuestionBuyer")).statusCode());
        for(var invalid:List.of(map("operation","other","revision",3),map("operation","save","revision",3),map("operation","save","revision",3,"key","reason","label","Reason","type","string","required","bad","choices","")))assertEquals(400,h.protectedForm(admin,invalid,auth("admin")).statusCode());
        assertEquals(404,h.protectedForm(admin,map("operation","remove","revision",3,"key","missing"),auth("admin")).statusCode());assertEquals(302,h.protectedForm(admin,map("operation","remove","revision",3,"key","count"),auth("admin")).statusCode());
        result(h.json("PUT",api,schema(4,List.of()),auth("admin")),200);assertEquals(5,answers(h,request,"QuestionBuyer").size());new Store(h.environment);assertEquals(snapshot,answers(h,request,"QuestionBuyer"));
        String noQuestions=result(h.json("POST",order,map("revision",5,"details","No questions","key",id(),"answers",map()),auth("QuestionBuyer")),201).get("sys_id").toString();assertTrue(answers(h,noQuestions,"QuestionBuyer").isEmpty());assertTrue(answers(h,h.add("sc_request").get("sys_id").toString(),"admin").isEmpty());
        assertEquals(405,h.json("POST","/api/openservice/requests/"+request+"/answers",map(),auth("admin")).statusCode());assertEquals(400,h.get("/api/openservice/requests/"+request+"/answers?x=y",auth("admin")).statusCode());
    }
}
