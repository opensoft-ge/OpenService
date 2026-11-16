package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

final class EventSearchAcceptance {
    static final String API="/api/openservice/event-search";
    static Map<String,Object> page(WorkflowSupport h,String query) throws Exception {
        var response=h.get(API+query,auth("admin"));assertEquals(200,response.statusCode(),html(response));return parsed(response);
    }
    static void run(WorkflowSupport h) throws Exception {
        h.account("EventReader","reader");
        assertEquals(0,((Number)page(h,"").get("total")).intValue());
        String asset=h.add("cmdb_ci").get("sys_id").toString();
        try(var db=h.store.open()) {
            String actor=PlatformFoundationTest.bind(db);
            for(int i=0;i<105;i++) AssetOperations.event(db,actor,map("key","batch-"+i,"asset_id",asset,"severity","warning","summary","Queue pressure"),i);
            AssetOperations.event(db,actor,map("key","literal-%_!","asset_id",asset,"severity","critical","summary","Unique summary <script>"),500);
            EventActions.apply(db,actor,map("key","literal-%_!","operation","acknowledge","reason","Investigating"));
            db.connection.commit();
        }
        var first=page(h,"?q=BATCH&severity=warning&state=open");
        assertEquals(100,objects(first.get("result")).size());assertEquals(105,((Number)first.get("total")).intValue());assertEquals(100,first.get("next_offset"));
        var second=page(h,"?q=batch&offset=100");assertEquals(5,objects(second.get("result")).size());assertEquals(-1,second.get("next_offset"));
        var keys=new HashSet<String>();for(var row:objects(first.get("result")))assertTrue(keys.add(row.get("event_key").toString()));
        for(var row:objects(second.get("result")))assertTrue(keys.add(row.get("event_key").toString()));
        assertEquals(1,((Number)page(h,"?q=%25_%21&severity=critical&state=acknowledged&limit=1").get("total")).intValue());
        assertEquals(1,((Number)page(h,"?q=unique%20summary").get("total")).intValue());
        assertTrue(objects(page(h,"?offset=1000000").get("result")).isEmpty());
        assertTrue(objects(page(h,"?state=resolved").get("result")).isEmpty());
        assertEquals(401,h.get(API,null).statusCode());assertEquals(403,h.get(API,auth("EventReader")).statusCode());
        assertEquals(403,h.get("/admin/events",auth("EventReader")).statusCode());
        for(String query:List.of("unknown=x","q=a&q=b","q=%00","q="+"x".repeat(201),"severity=bad","state=bad","offset=-1","offset=1000001","limit=0","limit=101"))
            assertEquals(400,h.get(API+"?"+query,auth("admin")).statusCode(),query);
        var action=map("key","batch-0","operation","resolve","reason","Handled");
        assertEquals(302,h.protectedForm("/admin/events",action,auth("admin")).statusCode());
        assertEquals(1,((Number)page(h,"?state=resolved").get("total")).intValue());
        assertEquals(403,h.form("/admin/events",action,auth("admin")).statusCode());
        assertEquals(403,h.protectedForm("/admin/events",action,auth("EventReader")).statusCode());
        action.put("unexpected","x");assertEquals(400,h.protectedForm("/admin/events",action,auth("admin")).statusCode());
        action.remove("unexpected");assertEquals(400,h.protectedForm("/admin/events?q=x",action,auth("admin")).statusCode());
        assertEquals(405,h.json("POST",API,map(),auth("admin")).statusCode());
    }
}
