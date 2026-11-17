package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static ge.opensoft.openservice.CatalogueQuestionsAcceptance.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

final class CatalogueQuestionsBoundariesAcceptance {
    static void run(WorkflowSupport h)throws Exception {
        h.account("QuestionBuyer","requester");h.account("QuestionAgent","agent");String group=h.add("sys_user_group").get("sys_id").toString();
        String offering=result(h.json("POST",CATALOG,CatalogueAcceptance.offering("Routed question service",0,true),auth("admin")),201).get("offering_id").toString();result(h.json("PUT",CATALOG+"/"+offering+"/routing",map("revision",1,"group_id",group),auth("admin")),200);
        var qs=List.of(question("reason","string",true,List.of()));result(h.json("PUT",CATALOG+"/"+offering+"/questions",schema(2,qs),auth("admin")),200);
        String request=result(h.json("POST",CATALOG+"/"+offering+"/order",map("revision",3,"details","","key",id(),"answers",map("reason","Private retained answer")),auth("QuestionBuyer")),201).get("sys_id").toString();
        assertEquals("queued",FulfilmentAcceptance.get(h,request,"admin").get("state"));Map<String,Object> original;try(var db=h.store.open()){original=Service.schema(db,"sc_request");}
        try {
            var hidden=new LinkedHashMap<>(original);hidden.putIfAbsent("version",0);hidden.put("field_rules",map("description",map("type","string","read_roles",List.of("admin"))));result(h.json("POST","/api/openservice/configuration?dry_run=false",ConfigurationAcceptance.bundle("sc_request",hidden),auth("admin")),200);
            assertEquals(403,h.get("/api/openservice/requests/"+request+"/answers",auth("QuestionBuyer")).statusCode());assertFalse(html(h.get("/record/sc_request/"+request,auth("QuestionBuyer"))).contains("Private retained answer"));assertEquals("Private retained answer",answers(h,request,"admin").getFirst().get("value"));
        } finally {try(var db=h.store.open()){db.update("UPDATE os_schema SET definition="+db.jsonParameter()+" WHERE name='sc_request'",JSON.writeValueAsString(original));db.connection.commit();}}
        try(var db=h.store.open()){
            String actor=PlatformFoundationTest.bind(db);CatalogueQuestions.save(db,actor,offering,schema(3,List.of()));db.connection.rollback();PlatformFoundationTest.bind(db);assertEquals(1,objects(Catalogue.find(db,offering,true).get("questions")).size());
            assertEquals(400,assertThrows(Failure.class,()->CatalogueQuestions.edit(db,actor,offering,map("operation","save","revision",3,"key","reason","label","Reason","type","string","required","true","choices",42))).status);
            long requests=db.count("SELECT COUNT(*) FROM os_record WHERE \"table\"='sc_request'"),tasks=db.count("SELECT COUNT(*) FROM os_fulfilment");Catalogue.order(db,offering,map("revision",3,"details","Rollback","key",id(),"answers",map("reason","Rollback answer")));db.connection.rollback();assertEquals(requests,db.count("SELECT COUNT(*) FROM os_record WHERE \"table\"='sc_request'"));assertEquals(tasks,db.count("SELECT COUNT(*) FROM os_fulfilment"));
        }
        var form=map("revision",3,"details","Browser form","key",id(),"answer.reason","Submitted by protected form");assertEquals(302,h.protectedForm("/catalogue/"+offering,form,auth("QuestionBuyer")).statusCode());
        var extra=new LinkedHashMap<>(form);extra.put("unexpected","no");extra.put("key",id());assertEquals(400,h.protectedForm("/catalogue/"+offering,extra,auth("QuestionBuyer")).statusCode());
    }
}
