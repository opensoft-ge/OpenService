package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

final class EnterpriseImportEdges {
    static void run(WorkflowSupport h,String incident,String feed,String schedule,String alias) throws Exception {
        String group=h.add("sys_user_group").get("sys_id").toString();
        SecurityIncidentsAcceptance.enroll(h,group,0,true);
        String hidden=EmployeeCasesAcceptance.result(h.json("POST","/api/openservice/security/intake",SecurityIncidentsAcceptance.intake(group),WorkflowSupport.auth("admin"))).get("sys_id").toString();
        send(h,"security",hidden,map("operation","link_feed","feed",feed,"revision",0));
        send(h,"security",hidden,map("operation","observe","sha256","da".repeat(32),"revision",1));
        var response=EmployeeCasesAcceptance.result(h.json("POST",api("security",incident),map("operation","shared_matches"),WorkflowSupport.auth("SecAnalyst")));
        var visible=objects(object(response.get("outcome")).get("matches"));
        assertFalse(visible.isEmpty());assertTrue(visible.stream().noneMatch(hit->hit.get("incident").equals(hidden)));
        bad(h,"security",incident,map("operation","link_feed","feed",id(),"revision",0),400);
        var missing=map("operation","schedule","kind","inventory","key",id(),"target","P","minutes",1,"enabled",true,"alias","missing","path","/p","revision",0);
        bad(h,"integrations","global",missing,400);
        try(var db=h.store.open()) {
            PlatformFoundationTest.bind(db);
            var job=object(EnterpriseData.get(db,"import-schedule",schedule).get("definition"));
            var changed=new LinkedHashMap<>(job);changed.put("authority",-1);
            assertThrows(Failure.class,()->RecurringImports.authority(db,changed));
            changed.put("owner",RecordAccess.owner(db,"SecAnalyst"));
            assertThrows(Failure.class,()->RecurringImports.authority(db,changed));
            PlatformFoundationTest.bind(db);
            for(String invalid:List.of("missing",alias))
                assertThrows(Failure.class,()->RecurringImports.checkedConnector(h.store,db,map("alias",invalid,"connector","changed")));
            var row=EnterpriseData.get(db,"feed-link",incident);
            var link=object(row.get("definition"));
            var observations=new ArrayList<String>();
            var items=new ArrayList<Map<String,Object>>();
            for(int i=0;i<100;i++) {
                String hash=String.format("%064x",i);observations.add(hash);
                items.add(map("sha256",hash,"label","Indicator "+i,"expires_on","2099-01-02"));
            }
            link.put("observations",observations);
            EnterpriseData.save(db,"feed-link",incident,row.get("revision"),link);
            assertThrows(Failure.class,()->SharedIntelligence.command(db,incident,map("operation","observe","sha256","ff".repeat(32),"revision",0)));
            String alternate=id();
            SharedIntelligence.replace(db,alternate,"fixture",items);
            SharedIntelligence.replace(db,alternate,"fixture",items);
            SharedIntelligence.replace(db,alternate,"fixture",List.of());
            var next=map("sha256","ff".repeat(32),"label","Late indicator","expires_on","2099-01-02");
            SharedIntelligence.replace(db,alternate,"fixture",List.of(next));
            SharedIntelligence.replace(db,alternate,"fixture",List.of());
            var tombstones=object(object(EnterpriseData.get(db,"shared-feed",alternate).get("definition")).get("withdrawn"));
            assertEquals(100,tombstones.size());
            assertTrue(object(tombstones.get(next.get("sha256"))).containsKey("withdrawn_at"));
            assertThrows(Failure.class,()->SharedIntelligence.replace(db,alternate,"fixture",List.of(next,next)));
            EnterpriseData.save(db,"feed-link",id(),0,map("feed",alternate,"observations",List.of()));
            SharedIntelligence.command(db,incident,map("operation","shared_matches"));
            var record=Service.record(db,"security_incident",incident);
            var data=object(record.get("data"));data.put("state","closed");
            db.update("UPDATE os_record SET data="+db.jsonParameter()+" WHERE sys_id=?",JSON.writeValueAsString(data),incident);
            assertThrows(Failure.class,()->SharedIntelligence.command(db,incident,map("operation","observe","revision",0,"sha256","00".repeat(32))));
            var inventory=map("kind","inventory","target","Design");
            var source=map("product","DESIGN PRO","installations",List.of());
            RecurringImports.applySnapshot(db,inventory,source);
            inventory.put("target_revision",-1);
            RecurringImports.applySnapshot(db,inventory,source);
        }
    }
}
