package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class NotificationBatchAcceptance {
 static void run(WorkflowSupport h) throws Exception {
  String api="/api/openservice/me/notifications",form="/account/notifications";h.account("BatchNoticeOwner","reader");h.account("BatchNoticeOther","requester");var ids=new ArrayList<String>();String foreign=id(),later=id();
  try(var db=h.store.open()){for(int i=0;i<105;i++){String notice=id();ids.add(notice);Notifications.deliver(db,notice,map("username","BatchNoticeOwner","message","Reviewed batch "+i),i);}Notifications.deliver(db,foreign,map("username","BatchNoticeOther","message","Another account's private message"),999);db.connection.commit();}
  var page=parsed(h.get(api+"?q=Reviewed&state=unread&limit=100",auth("BatchNoticeOwner")));var shown=objects(page.get("result")).stream().map(row->row.get("notice_id").toString()).toList();assertEquals(100,shown.size());
  // A delivery after the page was read must not be swept into the batch.
  try(var db=h.store.open()){Notifications.deliver(db,later,map("username","BatchNoticeOwner","message","Reviewed later arrival"),1000);db.connection.commit();}
  var body=map("acknowledge_many",shown);assertEquals(200,h.json("POST",api,body,auth("BatchNoticeOwner")).statusCode());assertEquals(200,h.json("POST",api,body,auth("BatchNoticeOwner")).statusCode());
  var unread=parsed(h.get(api+"?state=unread",auth("BatchNoticeOwner")));assertEquals(6,((Number)unread.get("total")).intValue());assertTrue(objects(unread.get("result")).stream().anyMatch(row->later.equals(row.get("notice_id"))));
  assertEquals(404,h.json("POST",api,map("acknowledge_many",List.of(later,foreign)),auth("BatchNoticeOwner")).statusCode());assertEquals(404,h.json("POST",api,map("acknowledge_many",List.of(later,id())),auth("BatchNoticeOwner")).statusCode());
  try(var db=h.store.open()){assertEquals(0,db.count("SELECT acknowledged FROM os_notice WHERE notice_id=?",later));assertEquals(0,db.count("SELECT acknowledged FROM os_notice WHERE notice_id=?",foreign));}
  for(Object bad:List.of("ids",List.of(),ids,List.of(later,later),List.of(3),List.of("bad"),Arrays.asList((Object)null)))assertEquals(400,h.json("POST",api,map("acknowledge_many",bad),auth("BatchNoticeOwner")).statusCode());
  assertEquals(400,h.json("POST",api,map("acknowledge_many",List.of(later),"user_id","other"),auth("BatchNoticeOwner")).statusCode());assertEquals(401,h.json("POST",api,body,null).statusCode());assertEquals(400,h.json("POST",api+"?q=x",body,auth("BatchNoticeOwner")).statusCode());
  assertEquals(403,h.form(form,map("operation","batch","notice_ids",later),auth("BatchNoticeOwner")).statusCode());assertEquals(400,h.protectedForm(form,map("operation","batch","notice_ids",later,"return_limit","0"),auth("BatchNoticeOwner")).statusCode());
  try(var db=h.store.open()){assertEquals(0,db.count("SELECT acknowledged FROM os_notice WHERE notice_id=?",later));}
  assertEquals(200,h.protectedForm(form,map("operation","batch","notice_ids",later,"return_q","Reviewed","return_state","unread","return_limit","2"),auth("BatchNoticeOwner")).statusCode());
  assertEquals(200,h.json("POST",api,map("acknowledge_many",List.of(foreign)),auth("BatchNoticeOther")).statusCode());new Store(h.environment);
  assertEquals(5,((Number)parsed(h.get(api+"?state=unread",auth("BatchNoticeOwner"))).get("total")).intValue());assertEquals(0,((Number)parsed(h.get(api+"?state=unread",auth("BatchNoticeOther"))).get("total")).intValue());
 }
}
