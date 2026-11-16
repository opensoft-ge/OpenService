package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.mock.env.MockEnvironment;

final class AiConsentAcceptance {
    static void run(Store store,Store.Db db,String actor,String id,Map<String,Object> request,
                    Map<String,Object> preview,AtomicReference<String> captured) throws Exception {
        String before=captured.get(),token=preview.get("preview_token").toString();
        assertFalse(token.contains("Summary grounded"));
        assertTrue(((Number)preview.get("preview_expires_at")).longValue()>System.currentTimeMillis());
        var missing=new LinkedHashMap<>(request);missing.remove("preview_token");
        assertThrows(Failure.class,()->ProviderAssist.summarize(store,db,actor,missing));
        for(Object bad:List.of("broken",token.substring(0,token.length()-3)+"xyz","x".repeat(4097),true)) {
            request.put("preview_token",bad);
            assertThrows(Failure.class,()->ProviderAssist.summarize(store,db,actor,request));
            assertEquals(before,captured.get());
        }
        for(var malformed:List.of(map(),map("expires_at",Long.MAX_VALUE),map("expires_at",0,"state","x"))) {
            request.put("preview_token",Outbox.encrypt("ai-consent-v1:"+store.secret,JSON.writeValueAsString(malformed)));
            assertThrows(Failure.class,()->ProviderAssist.summarize(store,db,actor,request));
            assertEquals(before,captured.get());
        }
        request.put("preview_token",token);
        var env=(MockEnvironment)store.environment;
        for(String setting:List.of("AI_MODEL","AI_ALLOWED_FIELDS","CONNECTOR_ALLOWED_ORIGINS")) {
            String old=env.getProperty(setting);
            env.setProperty(setting,old+",changed");
            assertThrows(Failure.class,()->ProviderAssist.summarize(store,db,actor,request));
            assertEquals(before,captured.get());env.setProperty(setting,old);
        }
        db.update("UPDATE os_connector SET revision=revision+1 WHERE alias='sample'");
        assertThrows(Failure.class,()->ProviderAssist.summarize(store,db,actor,request));
        db.update("UPDATE os_connector SET revision=revision-1 WHERE alias='sample'");
        var originalConnector=db.one("SELECT * FROM os_connector WHERE alias='sample'");
        db.update("UPDATE os_connector SET revision=1 WHERE alias='sample'");
        request.put("preview_token",ProviderAssist.preview(store,db,actor,map("table","incident","record_id",id)).get("preview_token"));
        Connectors.retire(db,actor,"sample",map("reason","Synthetic replacement test"));
        Connectors.save(store,db,actor,map("alias","sample","origin",originalConnector.get("origin"),"token","replacement-token"));
        assertEquals(409,assertThrows(Failure.class,()->ProviderAssist.summarize(store,db,actor,request)).status);
        db.update("UPDATE os_connector SET envelope=?,revision=? WHERE alias='sample'",originalConnector.get("envelope"),originalConnector.get("revision"));
        request.put("preview_token",token);
        db.update("UPDATE os_user SET auth_revision=auth_revision+1 WHERE user_id=?",actor);
        assertThrows(Failure.class,()->ProviderAssist.summarize(store,db,actor,request));
        db.update("UPDATE os_user SET auth_revision=auth_revision-1 WHERE user_id=?",actor);
        var record=Service.record(db,"incident",id);String original=JSON.writeValueAsString(record.get("data"));
        object(record.get("data")).put("short_description","Changed after review");Service.save(db,record);
        assertThrows(Failure.class,()->ProviderAssist.summarize(store,db,actor,request));
        record.put("data",JSON.readValue(original,Object.class));Service.save(db,record);
        Service.createAccount(db,map("name","OtherConsentAdmin","password",WorkflowSupport.PASSWORD,"role","admin"));
        String other=RecordAccess.owner(db,"OtherConsentAdmin");
        assertThrows(Failure.class,()->ProviderAssist.summarize(store,db,other,request));
        db.update("UPDATE os_user SET active=0 WHERE user_id=?",actor);
        assertThrows(Failure.class,()->ProviderAssist.summarize(store,db,actor,request));
        db.update("UPDATE os_user SET active=1 WHERE user_id=?",actor);
        assertEquals(before,captured.get());
    }
}
