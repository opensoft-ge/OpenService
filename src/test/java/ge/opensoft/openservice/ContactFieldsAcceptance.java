package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class ContactFieldsAcceptance {
 static Map<String,Object> definition(){return map("version",0,"label","Contact endpoints","prefix","CONTACT","fields",map("short_description","Summary","email","Contact email","url","Reference URL"),"states",map(),"flow",map(),"field_rules",map("email",map("type","email"),"url",map("type","url","read_roles",List.of("admin","agent"))));}
 static Map<String,Object> bundle(String table,Map<String,Object> definition){return map("format",Configuration.FORMAT,"tables",map(table,definition));}
 static void run(WorkflowSupport h) throws Exception {String table="u_contact_"+id(),api="/api/now/table/"+table;h.account("ContactReader","reader");h.account("ContactAgent","agent");
  try{
   var d=definition();var document=bundle(table,d);assertEquals(200,h.json("POST",ConfigurationAcceptance.API,document,auth("admin")).statusCode());assertEquals(404,h.get(api,auth("admin")).statusCode());assertEquals(200,h.json("POST",ConfigurationAcceptance.API+"?dry_run=false",document,auth("admin")).statusCode());
   var value=map("short_description","Contact endpoint","email","User+tag@Example.COM","url","https://example.org/path?q=1&more=two#part");var response=h.json("POST",api,value,auth("ContactAgent"));assertEquals(201,response.statusCode(),html(response));String id=object(parsed(response).get("result")).get("sys_id").toString();assertEquals(value.get("email"),object(parsed(response).get("result")).get("email"));assertEquals(value.get("url"),object(parsed(response).get("result")).get("url"));assertFalse(object(parsed(h.get(api+"/"+id,auth("ContactReader"))).get("result")).containsKey("url"));
   for(String bad:List.of("plain",".start@example.com","end.@example.com","a..b@example.com","x@-example.com","x@example-.com","x@example..com","a".repeat(65)+"@example.com","a@"+"b".repeat(64)+".com","User <x@example.com>","x@example.com\r\nBcc: x@else.com","ü@example.com","x@local","x@"+"a.".repeat(125)+"com"))assertEquals(400,h.json("PATCH",api+"/"+id,map("email",bad),auth("admin")).statusCode(),bad);
   for(String bad:List.of("ftp://example.com","javascript:alert(1)","/relative","https:///missing","https://user:pass@example.com","https://example.com:0","https://example.com:65536","https://example.com/space here","https://example.com/"+"x".repeat(2048)))assertEquals(400,h.json("PATCH",api+"/"+id,map("url",bad),auth("admin")).statusCode(),bad);
   for(String good:List.of("HTTP://example.com:65535/path","https://example.com:1/","https://[::1]/","http://localhost/"))assertEquals(200,h.json("PATCH",api+"/"+id,map("url",good),auth("admin")).statusCode(),good);
   String imported=id();assertEquals(400,h.json("POST","/api/openservice/import/"+table+"?dry_run=false",List.of(map("sys_id",imported,"short_description","Invalid imported contact","email","bad")),auth("admin")).statusCode());assertEquals(404,h.get(api+"/"+imported,auth("admin")).statusCode());
   assertEquals(200,h.json("PATCH",api+"/"+id,map("email","","url",""),auth("admin")).statusCode());object(object(d.get("field_rules")).get("email")).put("required",true);d.put("version",1);assertEquals(400,h.json("POST",ConfigurationAcceptance.API+"?dry_run=false",document,auth("admin")).statusCode());
   assertEquals(200,h.json("PATCH",api+"/"+id,map("email",value.get("email")),auth("admin")).statusCode());assertEquals(200,h.json("POST",ConfigurationAcceptance.API+"?dry_run=false",document,auth("admin")).statusCode());assertEquals(400,h.json("PATCH",api+"/"+id,map("email",""),auth("admin")).statusCode());new Store(h.environment);assertEquals(value.get("email"),object(parsed(h.get(api+"/"+id,auth("admin"))).get("result")).get("email"));
   assertEquals(403,h.json("PATCH",api+"/"+id,map("email","reader@example.com"),auth("ContactReader")).statusCode());assertEquals(401,h.json("POST",api,value,null).statusCode());assertEquals(403,h.form("/record/"+table+"/"+id,map("short_description","X"),auth("admin")).statusCode());
  }finally{try(var db=h.store.open()){db.update("DELETE FROM os_record WHERE \"table\"=?",table);db.update("DELETE FROM os_schema WHERE name=?",table);db.connection.commit();}}
 }
}
