package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class KnowledgeSearchAcceptance {
    static final String API = "/api/openservice/knowledge";

    static Map<String,Object> page(WorkflowSupport h, String query) throws Exception {
        var response=h.get(API+query,auth("KnowledgeRequester"));
        assertEquals(200,response.statusCode(),html(response));
        return parsed(response);
    }

    static String article(Store.Db db,String actor,String title,String content) throws Exception {
        var item=Service.create(db,"kb_knowledge",map("short_description",title,"text",content),"admin");
        String id=item.get("sys_id").toString();
        PublishedKnowledge.publish(db,actor,id);
        return id;
    }

    static void run(WorkflowSupport h) throws Exception {
        h.account("KnowledgeRequester","requester");
        assertTrue(objects(page(h,"?q=empty").get("result")).isEmpty());
        String special,hidden,deleted,retired;
        try(var db=h.store.open()) {
            String actor=PlatformFoundationTest.bind(db);
            for(int i=0;i<105;i++) article(db,actor,"Searchable answer "+i,"Published answer");
            special=article(db,actor,"Literal 100%_! <script>","Unique body needle");
            hidden=article(db,actor,"Hidden draft answer","Never visible");
            deleted=article(db,actor,"Deleted answer","Never visible");
            retired=article(db,actor,"Retired answer","Never visible");
            PublishedKnowledge.retire(db,actor,retired);
            db.update("DELETE FROM os_record WHERE sys_id=?",deleted);
            db.update("INSERT INTO os_record_access(record_id,visibility,owner_id,group_id) VALUES(?,'private',?,'')",hidden,actor);
            Service.create(db,"kb_knowledge",map("short_description","UNPUBLISHED SECRET","text","PRIVATE BODY"),"admin");
            db.connection.commit();
        }
        var first=page(h,"?q=SEARCHABLE&limit=100");
        assertEquals(100,objects(first.get("result")).size());
        assertEquals(105,((Number)first.get("total")).intValue());
        assertEquals(100,first.get("next_offset"));
        var last=page(h,"?q=searchable&offset=100&limit=100");
        assertEquals(5,objects(last.get("result")).size());
        assertEquals(-1,last.get("next_offset"));
        var ids=new HashSet<String>();
        for(var row:objects(first.get("result"))) assertTrue(ids.add(row.get("article_id").toString()));
        for(var row:objects(last.get("result"))) assertTrue(ids.add(row.get("article_id").toString()));
        assertEquals(105,ids.size());
        assertEquals(106,((Number)page(h,"").get("total")).intValue());
        assertEquals(200,h.json("PATCH","/api/now/table/kb_knowledge/"+special,map("text","UNPUBLISHED SECRET"),auth("admin")).statusCode());
        assertEquals(1,objects(page(h,"?q=100%25_%21").get("result")).size());
        assertEquals(special,objects(page(h,"?q=body%20needle").get("result")).getFirst().get("article_id"));
        assertTrue(objects(page(h,"?q=UNPUBLISHED").get("result")).isEmpty());
        assertTrue(objects(page(h,"?q=Never").get("result")).isEmpty());
        assertTrue(objects(page(h,"?offset=1000000").get("result")).isEmpty());
        assertEquals(1,objects(page(h,"?limit=1&q=%20needle%20").get("result")).size());
        try(var db=h.store.open()) {
            db.update("INSERT INTO os_record_access(record_id,visibility,owner_id,group_id) VALUES(?,'private',?,'')",special,RecordAccess.owner(db,"admin"));
            db.connection.commit();
        }
        assertTrue(objects(page(h,"?q=needle").get("result")).isEmpty());
        assertEquals(401,h.get(API+"?q=test",null).statusCode());
        assertEquals(200,h.get(API+"?q=test",auth("admin")).statusCode());
        for(String query:List.of("unknown=x","limit=0","limit=101","offset=-1","offset=1000001","limit=x","q=a&q=b","q=%00","q="+"x".repeat(201)))
            assertEquals(400,h.get(API+"?"+query,auth("KnowledgeRequester")).statusCode(),query);
        assertEquals(405,h.json("POST",API,map(),auth("admin")).statusCode());
    }
}
