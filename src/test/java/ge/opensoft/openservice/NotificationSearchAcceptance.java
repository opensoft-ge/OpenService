package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

final class NotificationSearchAcceptance {
    static final String API="/api/openservice/me/notifications";
    static Map<String,Object> page(WorkflowSupport h,String query) throws Exception {
        var response=h.get(API+query,auth("NoticeOwner"));assertEquals(200,response.statusCode(),html(response));return parsed(response);
    }
    static void run(WorkflowSupport h) throws Exception {
        h.account("NoticeOwner","requester");h.account("NoticeOther","requester");
        assertTrue(objects(page(h,"?q=empty").get("result")).isEmpty());
        String literal=id(),foreign=id();
        try(var db=h.store.open()) {
            for(int i=0;i<105;i++)Notifications.deliver(db,id(),map("username","NoticeOwner","message","History item "+i),i);
            Notifications.deliver(db,literal,map("username","NoticeOwner","message","Literal 100%_!"),500);
            Notifications.deliver(db,foreign,map("username","NoticeOther","message","History item FOREIGN SECRET"),600);
            db.connection.commit();
        }
        var first=page(h,"?q=HISTORY&state=unread");assertEquals(105,((Number)first.get("total")).intValue());assertEquals(100,objects(first.get("result")).size());assertEquals(100,first.get("next_offset"));
        var last=page(h,"?q=history&offset=100");assertEquals(5,objects(last.get("result")).size());assertEquals(-1,last.get("next_offset"));
        var ids=new HashSet<String>();for(var row:objects(first.get("result")))assertTrue(ids.add(row.get("notice_id").toString()));for(var row:objects(last.get("result")))assertTrue(ids.add(row.get("notice_id").toString()));
        assertEquals(1,objects(page(h,"?q=100%25_%21&limit=1").get("result")).size());
        assertTrue(objects(page(h,"?q=FOREIGN").get("result")).isEmpty());assertEquals(106,((Number)page(h,"").get("total")).intValue());
        assertTrue(objects(page(h,"?offset=1000000").get("result")).isEmpty());
        assertEquals(200,h.json("POST",API,map("acknowledge",literal),auth("NoticeOwner")).statusCode());
        assertEquals(1,((Number)page(h,"?state=acknowledged").get("total")).intValue());assertEquals(105,((Number)page(h,"?state=unread").get("total")).intValue());
        assertEquals(404,h.json("POST",API,map("acknowledge",foreign),auth("NoticeOwner")).statusCode());
        assertEquals(401,h.get(API+"?q=x",null).statusCode());
        for(String query:List.of("unknown=x","user_id=x","q=a&q=b","q=%00","q="+"x".repeat(201),"state=invalid","offset=-1","offset=1000001","limit=0","limit=101"))assertEquals(400,h.get(API+"?"+query,auth("NoticeOwner")).statusCode(),query);
        assertEquals(400,h.json("POST",API+"?q=x",map("enabled",true),auth("NoticeOwner")).statusCode());
        assertEquals(302,h.form("/login",map("csrf",csrf(h.get("/login",null)),"username","NoticeOwner","password",PASSWORD),null).statusCode());
        String view="/account/notifications",csrf=csrf(h.get(view,null));
        assertEquals(403,h.form(view,map("notice_id",literal),null).statusCode());
        assertEquals(400,h.form(view,map("csrf",csrf,"operation","preference","enabled","false","return_limit","0"),null).statusCode());
        try(var db=h.store.open()){assertEquals(1,db.count("SELECT notifications FROM os_user WHERE name='NoticeOwner'"));}
        assertEquals(200,h.form(view,map("csrf",csrf,"operation","preference","enabled","false","return_q","History","return_state","unread","return_limit","1"),null).statusCode());
        try(var db=h.store.open()){assertEquals(0,db.count("SELECT notifications FROM os_user WHERE name='NoticeOwner'"));}

    }
}
