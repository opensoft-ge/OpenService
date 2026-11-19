package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

final class EnterpriseDepthAcceptance {
    static void inventory(WorkflowSupport h) throws Exception {
        String device = AssetCustodyAcceptance.setup(h)[0];
        String user = uid(h,"AssetUser"), other = uid(h,"AssetOther"), license = id();
        var found = map("asset_id",device,"user_id",user);
        var snapshot = map("operation","inventory","revision",0,"product","CAD","installations",List.of(found));
        rejectVariants(h,"assets","global",snapshot,List.of(map("installations","x"),map("installations",Collections.nCopies(101,found)),map("installations",List.of(found,found))));
        send(h,"assets","global",snapshot);
        bad(h,"assets","global",snapshot,409);
        var register = map("operation","license","product","CAD","key",license,"seats",2,"expires_on",LocalDate.now().plusDays(3).toString());
        send(h,"assets","global",register);
        var allocate = map("operation","allocate","license_id",license,"revision",1,"user_id",other);
        send(h,"assets","global",allocate);
        var reconciliation = objects(data(h,"assets","global").get("reconciliation")).getFirst();
        assertEquals(List.of(user),reconciliation.get("users_without_seats"));
        assertEquals(List.of(other),reconciliation.get("allocated_without_installation"));
        allocate.putAll(map("revision",2,"user_id",user));
        send(h,"assets","global",allocate);
        assertEquals(List.of(), objects(data(h,"assets","global").get("reconciliation")).getFirst().get("users_without_seats"));
        snapshot.putAll(map("revision",1,"installations",List.of()));
        send(h,"assets","global",snapshot);
        assertEquals(2,((List<?>)objects(data(h,"assets","global").get("reconciliation")).getFirst().get("allocated_without_installation")).size());
    }

    static void purchasing(WorkflowSupport h) throws Exception {
        var ids = AssetCustodyAcceptance.setup(h);
        String key = id();
        var laptop = map("model","Field laptop","quantity",1,"unit_cost","45","months",24,"salvage","5");
        var spare = new LinkedHashMap<>(laptop);
        spare.put("model","Spare device");
        var order = map("operation","multi_order","key",key,"supplier","Supplier","currency","USD",
                "lines",List.of(laptop,spare));
        rejectVariants(h,"assets","global",order,List.of(map("lines",List.of()),map("lines",List.of(laptop,laptop))));
        send(h,"assets","global",order);
        send(h,"assets","global",map("operation","approve","order_id",key,"revision",1));
        var receive = map("operation","receive","order_id",key,"asset_id",ids[0],"stockroom_id",ids[1],
                "revision",2,"received_on",LocalDate.now().toString());
        send(h,"assets","global",receive);
        var lines = object(object(doc(h,"purchase",key).get("definition")).get("lines"));
        assertEquals(List.of(ids[0]),object(lines.get("Field laptop")).get("received"));
        assertFalse(object(lines.get("Spare device")).containsKey("received"));
        receive.put("revision",3);bad(h,"assets","global",receive,400);
        String second = EmployeeCasesAcceptance.result(h.json("POST","/api/now/table/alm_asset",
                map("short_description","Spare equipment","asset_tag","SPARE", "model","Spare device","serial_number","SP2"),auth("AssetKeeper"))).get("sys_id").toString();
        receive.put("asset_id",second);send(h,"assets","global",receive);
        assertEquals(List.of(ids[0],second),object(doc(h,"purchase",key).get("definition")).get("received"));
    }

    static void feed(WorkflowSupport h) throws Exception {
        String incident = SecurityIncidentsAcceptance.setup(h)[0], digest = "ab".repeat(32);
        var indicator = map("sha256",digest.toUpperCase(Locale.ROOT),"label","Vendor report");
        var update = map("operation","feed","revision",0,"source","Analyst feed","indicators",List.of(indicator));
        var lookup = map("operation","match","sha256",digest);
        assertEquals(false, object(send(h,"security",incident,lookup).get("outcome")).get("matched"));
        rejectVariants(h,"security",incident,update,List.of(map("indicators",List.of(map("sha256","xyz","label","bad"))),map("indicators",List.of(indicator,indicator))));
        send(h,"security",incident,update);
        bad(h,"security",incident,update,409);
        assertEquals(404,h.json("POST",api("security",incident),lookup,auth("SecOther")).statusCode());
        var match = object(send(h,"security",incident,lookup).get("outcome"));
        assertEquals("Vendor report",match.get("label"));
        update.putAll(map("revision",1,"indicators",List.of()));
        send(h,"security",incident,update);
        assertEquals(false,object(send(h,"security",incident,lookup).get("outcome")).get("matched"));
        try (var provider = new RecordQuestionsAcceptance.Provider(h.store,h.environment)) {
            var response = new AtomicReference<>("invalid json");
            provider.server.createContext("/indicators", exchange -> {
                byte[] bytes = response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            var fetch = map("operation","fetch_feed","alias",provider.alias,"path","/indicators","revision",2);
            bad(h,"security",incident,fetch,502);
            response.set(JSON.writeValueAsString(List.of(indicator)));
            send(h,"security",incident,fetch);
            assertEquals(provider.alias+":/indicators", object(send(h,"security",incident,lookup).get("outcome")).get("source"));
            bad(h,"security",incident,fetch,409);
        }
    }

    static void scenarios(WorkflowSupport h) throws Exception {
        String a=project(h), b=project(h);
        var budget = map("operation","budget","revision",0,"budget","1000","currency","USD","remaining","125.05");
        send(h,"portfolio",a,budget);
        budget.put("remaining","200.01");
        send(h,"portfolio",b,budget);
        send(h,"portfolio",a,map("operation","actual","revision",1,"key",id(),"amount","25","note","Planning"));
        var compare = map("operation","compare","currency","USD","ceiling","300","option_a",List.of(a),"option_b",List.of(a,b));
        var report = object(send(h,"portfolio",a,compare).get("outcome"));
        assertEquals("200.01",report.get("additional_forecast_for_b"));
        assertEquals("-50.06",object(report.get("option_b")).get("funding_remaining"));
        rejectVariants(h,"portfolio",a,compare,List.of(map("currency","usd"),map("currency","EUR"),map("option_a",List.of(a,a))));
    }

    static void issues(WorkflowSupport h) throws Exception {
        String target=h.add("incident").get("sys_id").toString();
        try (var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment)) {
            var payload=new AtomicReference<>(map("title","Fix router","body","Review incident","id",42,"number",7,"updated_at","v1"));
            for(String path:List.of("/repos/acme/ops/issues/7","/api/v4/projects/17/issues/7")) {
                provider.server.createContext(path, e -> {
                    byte[] bytes=JSON.writeValueAsBytes(payload.get()); e.sendResponseHeaders(200,bytes.length);
                    e.getResponseBody().write(bytes);e.close();
                });
            }
            var request=map("operation","review_issue","adapter","github","alias",provider.alias,"path","/repos/acme/ops/issues/7","record_id",target);
            rejectVariants(h,"integrations","global",request,List.of(map("adapter","other"),map("path","/wrong"),map("extra",true)));
            var review=object(send(h,"integrations","global",request).get("outcome"));
            request.putAll(map("operation","apply_issue","confirm",true,"proof",review.get("proof")));
            payload.get().put("updated_at","v2");bad(h,"integrations","global",request,409);
            request.remove("confirm");request.remove("proof");request.put("operation","review_issue");
            payload.get().put("body",map());bad(h,"integrations","global",request,400);payload.get().put("body",null);send(h,"integrations","global",request);
            payload.get().put("pull_request",map());bad(h,"integrations","global",request,400);
            payload.set(map("id",6,"project_id",18,"iid",7,"title","GitLab incident","description","Imported details","updated_at","v3"));
            request.putAll(map("adapter","gitlab","path","/api/v4/projects/17/issues/7"));
            bad(h,"integrations","global",request,400);payload.get().put("project_id",17);
            payload.get().put("iid",8);bad(h,"integrations","global",request,400);payload.get().put("iid",7);
            review=object(send(h,"integrations","global",request).get("outcome"));
            request.putAll(map("operation","apply_issue","confirm",true,"proof",review.get("proof")));
            send(h,"integrations","global",request);
            assertEquals("GitLab incident",EmployeeCasesAcceptance.get(h,"/api/now/table/incident/"+target,"admin").get("short_description"));
        }
    }

    static void model(WorkflowSupport h) throws Exception {
        h.account("PlanDelegate","agent");String user=uid(h,"PlanDelegate"), target=h.add("incident").get("sys_id").toString();
        try(var db=h.store.open()){db.update("UPDATE os_user SET api_only=1 WHERE user_id=?",user);db.connection.commit();}
        send(h,"agents","global",map("operation","grant","revision",0,"delegate_id",user,"active",true,"targets",List.of(map("table","incident","record_id",target,"fields",List.of("short_description")))));
        var preview=map("operation","preview_model","delegate_id",user,"grant_revision",1,"key",id(),"goal","Set the incident title to Planned");
        try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment)){
            var outcome=object(send(h,"agents","global",preview).get("outcome"));
            assertEquals(0,provider.calls.get());
            var draft=new LinkedHashMap<>(preview);draft.putAll(map("operation","draft_model","confirm",true,"preview_token",outcome.get("preview_token")));
            rejectVariants(h,"agents","global",draft,List.of(map("confirm",false),map("unknown",true)));
            var stale=new LinkedHashMap<>(draft);stale.put("goal","Other");bad(h,"agents","global",stale,409);
            for(var setting:List.of(Map.entry("AI_ENABLED","false"),Map.entry("AI_MODEL",""),Map.entry("AI_CONNECTOR_ALIAS","missing"),Map.entry("CONNECTOR_ALLOWED_ORIGINS",""))){
                var before=provider.settings.put(setting.getKey(),setting.getValue());
                draft.put("preview_token",object(send(h,"agents","global",preview).get("outcome")).get("preview_token"));
                bad(h,"agents","global",draft,400);provider.settings.put(setting.getKey(),before);
            }
            draft.put("preview_token",outcome.get("preview_token"));provider.answer.set("no JSON");bad(h,"agents","global",draft,502);
            provider.answer.set("[]");bad(h,"agents","global",draft,400);
            var step=map("target",0,"patch",map("description","Outside grant"));
            provider.answer.set(JSON.writeValueAsString(List.of(step)));bad(h,"agents","global",draft,400);
            step.put("patch",map("short_description","Planned"));provider.answer.set(JSON.writeValueAsString(List.of(step)));
            send(h,"agents","global",draft);
            assertFalse(provider.captured.get().contains(target));
            assertEquals("reviewed",planState(h,preview.get("key").toString()).get("state"));
            assertNotEquals("Planned",EmployeeCasesAcceptance.get(h,"/api/now/table/incident/"+target,"admin").get("short_description"));
        }
    }
}
