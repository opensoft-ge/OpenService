package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class EnterpriseMobileAgentAcceptance {
    static HttpResponse<byte[]> upload(WorkflowSupport h,String path,String sheet,boolean photo) throws Exception {
        String user=auth("TaskOne"),boundary="OSfield"+id();
        StringBuilder body=new StringBuilder("--"+boundary+"\r\nContent-Disposition: form-data; name=\"csrf\"\r\n\r\n"+h.basicCsrf(user)+"\r\n");
        if(sheet!=null) body.append("--"+boundary+"\r\nContent-Disposition: form-data; name=\"worksheet\"; filename=\"visit.csv\"\r\nContent-Type: text/csv\r\n\r\n"+sheet+"\r\n");
        if(photo) body.append("--"+boundary+"\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"evidence.jpg\"\r\nContent-Type: image/jpeg\r\n\r\nfixture\r\n");
        body.append("--"+boundary+"--\r\n");
        return h.request("POST",path,body.toString().getBytes(StandardCharsets.UTF_8),"multipart/form-data; boundary="+boundary,user);
    }

    static void offline(WorkflowSupport h) throws Exception {
        var ids=FieldDispatchAcceptance.setup(h);
        String task=ids[1],parent=ids[0],path="/field-worksheet/"+task;
        assertEquals(400,h.get(path,auth("TaskOne")).statusCode());
        FieldVisitsAcceptance.accepted(h,parent,task);
        String raw=html(h.get(path,auth("TaskOne")));
        assertTrue(raw.contains("minutes,0"),raw);
        String filled=raw.replace("minutes,0","minutes,30").replace("evidence_note,","evidence_note,Technician observed damage");
        String ci=h.add("cmdb_ci").get("sys_id").toString();
        String part=filled.replace("part,,,","part,"+ci+",2,Replaced in field");
        assertEquals(415,h.protectedJson("POST",path,map(),auth("TaskOne")).statusCode());
        assertEquals(400,upload(h,path,null,false).statusCode());
        assertEquals(400,upload(h,path,"",false).statusCode());
        assertEquals(400,upload(h,path,"ticket,invalid",false).statusCode());
        assertEquals(400,upload(h,path,raw,false).statusCode());
        assertEquals(400,upload(h,path,filled+"unknown,value\r\n",false).statusCode());
        assertEquals(400,upload(h,path,filled+"part,x\r\n",false).statusCode());
        for(String invalid:List.of("ticket","ticket,value,extra","header,value","ticket,\"unclosed",filled+"invalid\r\n",
                filled.replace("minutes,30\r\n",""),filled.replace("evidence_note,Technician observed damage\r\n",""))) {
            assertEquals(400,upload(h,path,invalid,false).statusCode(),invalid);
        }
        try(var db=h.store.open()) {
            db.viewerId=RecordAccess.owner(db,"TaskOne");db.viewerRole="agent";
            byte[] csv=filled.getBytes(StandardCharsets.UTF_8);
            assertThrows(Failure.class,()->OfflineWorksheet.sync(h.store,db,task,new byte[64001],"",new byte[0]));
            assertThrows(Failure.class,()->OfflineWorksheet.sync(h.store,db,task,csv,"photo",new byte[5*1024*1024+1]));
            assertThrows(Failure.class,()->OfflineWorksheet.sync(h.store,db,id(),csv,"",new byte[0]));
            db.viewerId=RecordAccess.owner(db,"admin");db.viewerRole="admin";
            assertThrows(Failure.class,()->OfflineWorksheet.sync(h.store,db,task,csv,"",new byte[0]));
            assertThrows(Failure.class,()->OfflineWorksheet.download(h.store,db,task));
            db.viewerId=RecordAccess.owner(db,"TaskOne");db.viewerRole="agent";
            db.update("UPDATE os_field_dispatch SET technician_id=? WHERE task_id=?",RecordAccess.owner(db,"admin"),task);
            assertEquals("needs_review",OfflineWorksheet.sync(h.store,db,task,csv,"",new byte[0]).get("status"));
        }
        var first=upload(h,path,part,true);
        assertEquals(302,first.statusCode(),html(first));
        assertEquals(302,upload(h,path,part,true).statusCode());
        assertEquals(409,upload(h,path,filled,true).statusCode());
        var receipt=object(doc(h,"offline-task",task).get("definition"));
        assertEquals(30,receipt.get("minutes"));assertEquals(1,receipt.get("parts"));
        assertEquals(1,objects(FieldVisitsAcceptance.page(h,parent,task,"TaskOne").get("parts")).size());
        String onsite=html(h.get(path,auth("TaskOne"))).replace("minutes,0","minutes,5").replace("evidence_note,","evidence_note,Follow-up");
        assertEquals(302,upload(h,path,"\uFEFF"+onsite.replaceFirst("\\r\\n",",,\r\n"),false).statusCode());
        String other=WorkTasksAcceptance.create(h,parent,ids[2],List.of(),true);
        FieldVisitsAcceptance.act(h,parent,other,"configure","admin");
        // Use a nonoverlapping appointment for the second worksheet.
        var assign=FieldDispatchAcceptance.body(h,parent,other,"assign");assign.putAll(map("start","2030-01-08T09:00:00Z","end","2030-01-08T10:00:00Z"));
        FieldDispatchAcceptance.result(h.json("POST",FieldDispatchAcceptance.route(parent,other),assign,auth("admin")));
        FieldDispatchAcceptance.act(h,parent,other,"accept","TaskOne");
        String otherPath="/field-worksheet/"+other,old=html(h.get(otherPath,auth("TaskOne")));
        FieldDispatchAcceptance.act(h,parent,other,"release","admin");
        assertTrue(html(upload(h,otherPath,old,true)).contains("flagged for dispatcher review"));
        assertEquals(200,upload(h,otherPath,old,true).statusCode());
        assertEquals(1,((List<?>)data(h,"field",other).get("worksheet_conflicts")).size());
    }

    static void agents(WorkflowSupport h) throws Exception {
        h.account("OrderOwner","requester");h.account("ServiceAgent","agent");
        String group=h.add("sys_user_group").get("sys_id").toString(),user=uid(h,"ServiceAgent");
        FulfilmentAcceptance.member(h,group,"ServiceAgent",true);
        String offering=FulfilmentAcceptance.result(h.json("POST",FulfilmentAcceptance.CATALOG,CatalogueAcceptance.offering("Managed service",0,true),auth("admin")),201).get("offering_id").toString();
        FulfilmentAcceptance.result(h.json("PUT",FulfilmentAcceptance.CATALOG+"/"+offering+"/routing",map("group_id",group,"revision",1),auth("admin")),200);
        String request=FulfilmentAcceptance.order(h,offering,2),key=id();
        EnterpriseAgentFaults.user(h,"ServiceAgent","api_only",1);
        var target=map("table","sc_request","record_id",request,"fields",List.of("state"));
        send(h,"agents","global",map("operation","grant","revision",0,"active",true,"targets",List.of(target),"delegate_id",user));
        var step=map("table","sc_request","record_id",request,"patch",map("state","fulfilling"));
        try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment)) {
            provider.settings.put("AI_AGENT_EXECUTION_ENABLED","true");
            var preview=map("operation","preview_model","key",key,"delegate_id",user,"grant_revision",1,"goal","Claim the service request");
            var token=object(send(h,"agents","global",preview).get("outcome")).get("preview_token");
            provider.answer.set(JSON.writeValueAsString(List.of(map("target",0,"patch",step.get("patch")))));
            preview.putAll(map("operation","draft_model","confirm",true,"preview_token",token));
            send(h,"agents","global",preview);
            var review=objects(planState(h,key).get("steps")).getFirst();
            assertEquals(user,object(review.get("assignment")).get("assignee"));
            var evaluation=object(send(h,"agents","global",map("operation","evaluate","key",key,"revision",1)).get("outcome"));
            for(String test:List.of("task_success","revocation_rejected","stop_prevented_effects"))
                assertEquals(true,object(evaluation.get(test)).get("passed"),evaluation.toString());
            assertEquals("queued",FulfilmentAcceptance.get(h,request,"admin").get("state"));
            assertEquals("reviewed",planState(h,key).get("state"));
            send(h,"agents","global",map("operation","start","key",key,"revision",1,"confirm",true));assertTrue(AgentPlans.runOne(h.store));
            assertEquals(user,FulfilmentAcceptance.get(h,request,"admin").get("assignee_id"));
            for(Object malformed:List.of(map(),map("prompt_tokens",1),map("prompt_tokens",-1,"completion_tokens",1),map("prompt_tokens",1,"completion_tokens",-1)))
                assertNull(PlanEvaluations.meter(h.store,malformed,2).get("estimated_cost_usd"));
            var usage=map("prompt_tokens",100,"completion_tokens",10);
            assertNull(PlanEvaluations.meter(h.store,usage,2).get("estimated_cost_usd"));
            provider.settings.put("AI_PLAN_INPUT_USD_PER_MILLION","2");
            assertNull(PlanEvaluations.meter(h.store,usage,2).get("estimated_cost_usd"));
            provider.settings.put("AI_PLAN_OUTPUT_USD_PER_MILLION","5");
            assertEquals("0.000250",PlanEvaluations.meter(h.store,usage,2).get("estimated_cost_usd"));
        }
    }
}
