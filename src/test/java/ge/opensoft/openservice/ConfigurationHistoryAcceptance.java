package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

final class ConfigurationHistoryAcceptance {
    static final String API="/api/openservice/configuration/history";
    static final String PAGE="/admin/configuration/history";
    static Map<String,Object> history(WorkflowSupport h,String query) throws Exception {
        var response=h.get(API+query,auth("admin"));
        assertEquals(200,response.statusCode(),"Configuration installations must remain reviewable: "+html(response));
        return parsed(response);
    }
    static void run(WorkflowSupport h) throws Exception {
        assertEquals(0,((Number)history(h,"").get("total")).intValue());
        h.account("HistoryReader","reader");
        var fixture=new ConfigurationAcceptance(h);
        try {
            var definition=ConfigurationAcceptance.definition();
            definition.put("label","Retained <script> history");
            var document=ConfigurationAcceptance.bundle(fixture.table,definition);
            fixture.result(fixture.install(document,true),200);
            assertEquals(0,((Number)history(h,"").get("total")).intValue());
            var installed=fixture.result(fixture.install(document,false),200);
            String id=installed.get("installation_id").toString();
            var original=object(parsed(h.get(API+"/"+id,auth("admin"))).get("result"));
            var detail=object(original.get("detail"));
            assertNull(object(detail.get("before")).get(fixture.table));
            assertTrue(object(detail.get("before")).containsKey(fixture.table));
            assertEquals(1,object(object(detail.get("after")).get(fixture.table)).get("version"));
            assertEquals(installed.get("changes"),detail.get("changes"));
            assertEquals("admin",original.get("actor_name"));
            assertEquals(1,original.get("table_count"));
            var next=ConfigurationChangesAcceptance.current(h,fixture.table);
            next.put("label","Later definition");
            var second=fixture.result(fixture.install(ConfigurationAcceptance.bundle(fixture.table,next),false),200);
            String secondId=second.get("installation_id").toString();
            var later=object(object(parsed(h.get(API+"/"+secondId,auth("admin"))).get("result")).get("detail"));
            assertEquals("Retained <script> history",object(object(later.get("before")).get(fixture.table)).get("label"));
            assertEquals("Later definition",object(object(later.get("after")).get(fixture.table)).get("label"));
            next=ConfigurationChangesAcceptance.current(h,fixture.table);
            fixture.result(fixture.install(ConfigurationAcceptance.bundle(fixture.table,next),false),200);
            var invalid=ConfigurationAcceptance.definition();invalid.put("prefix","invalid");
            assertEquals(400,fixture.install(ConfigurationAcceptance.bundle(fixture.table+"bad",invalid),false).statusCode());
            assertEquals(400,fixture.install(map("format",Configuration.FORMAT,"tables",map(fixture.table+"first",ConfigurationAcceptance.definition(),fixture.table+"bad",invalid)),false).statusCode());
            try(var db=h.store.open()){assertNull(db.one("SELECT name FROM os_schema WHERE name=?",fixture.table+"first"));}
            assertEquals(409,fixture.install(document,false).statusCode());
            try(var db=h.store.open()) {
                String actor=PlatformFoundationTest.bind(db);
                Configuration.install(db,ConfigurationAcceptance.bundle(fixture.table,Service.schema(db,fixture.table)),actor,false);
                db.connection.rollback();
            }
            new Store(h.environment);
            assertEquals(3,((Number)history(h,"").get("total")).intValue());
            assertEquals(original,object(parsed(h.get(API+"/"+id,auth("admin"))).get("result")));
            var first=history(h,"?limit=1");assertEquals(1,first.get("next_offset"));
            var secondPage=history(h,"?limit=1&offset=1");
            assertNotEquals(objects(first.get("result")).getFirst().get("install_id"),objects(secondPage.get("result")).getFirst().get("install_id"));
            assertEquals(-1,history(h,"?offset=2").get("next_offset"));
            assertTrue(objects(history(h,"?offset=1000000").get("result")).isEmpty());
            for(String path:List.of(API,API+"/"+id,API+"/"+id+"/download",PAGE,PAGE+"/"+id,PAGE+"/"+id+"/download")) {
                assertEquals(path.startsWith("/api/")?401:302,h.get(path,null).statusCode());
                assertEquals(403,h.get(path,auth("HistoryReader")).statusCode());
                assertEquals(405,h.protectedForm(path,map(),auth("admin")).statusCode());
            }
            assertEquals(200,h.get(PAGE,auth("admin")).statusCode());
            String html=html(h.get(PAGE+"/"+id,auth("admin")));
            assertTrue(html.contains("Retained &lt;script&gt; history"));assertFalse(html.contains("Retained <script> history"));
            for(String root:List.of(API,PAGE)) {
                var download=h.get(root+"/"+id+"/download",auth("admin"));
                assertEquals(200,download.statusCode());assertTrue(download.headers().firstValue("Content-Disposition").orElseThrow().contains("attachment"));
                assertEquals(original,object(parsed(download).get("result")));
                assertEquals(404,h.get(root+"/"+id(),auth("admin")).statusCode());
                assertEquals(400,h.get(root+"/"+id+"?extra=x",auth("admin")).statusCode());
            }
            for(String query:List.of("?limit=0","?limit=101","?offset=-1","?offset=1000001","?extra=x","?limit=1&limit=2"))assertEquals(400,h.get(API+query,auth("admin")).statusCode());
        } finally { fixture.cleanup(); }
    }
}
