package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;

/** Short-lived authenticated consent for one exact read-only grounding and provider configuration. */
final class AiConsent {
    private AiConsent() {}

    static String state(Store store, Store.Db db, String actor, String table, String id,
                        Map<String,Object> selected) throws Exception {
        var env=store.environment;
        String alias=env.getProperty("AI_CONNECTOR_ALIAS","");
        return DigestUtils.sha256Hex(JSON.writeValueAsString(map(
                "actor",actor,"authority",db.one("SELECT auth_revision FROM os_user WHERE user_id=?",actor),
                "table",table,"record_id",id,"grounding",selected,
                "alias",alias,"connector",db.one("SELECT origin,revision,envelope FROM os_connector WHERE alias=?",alias),
                "model",env.getProperty("AI_MODEL",""),"enabled",env.getProperty("AI_ENABLED","false"),
                "fields",env.getProperty("AI_ALLOWED_FIELDS","short_description,description"),
                "origins",env.getProperty("CONNECTOR_ALLOWED_ORIGINS",""))));
    }

    static Map<String,Object> issue(Store store,String state) throws Exception {
        long expires=System.currentTimeMillis()+600000;
        return map("preview_token",Outbox.encrypt("ai-consent-v1:"+store.secret,
                JSON.writeValueAsString(map("state",state,"expires_at",expires))),"preview_expires_at",expires);
    }

    static void verify(Store store,Object input,String state) {
        String token=WorkJobs.text(input,4096);
        Map<String,Object> decoded;
        try { decoded=Outbox.decrypt("ai-consent-v1:"+store.secret,token); }
        catch(Exception invalid) { throw new Failure(400,"Invalid AI preview token"); }
        require(decoded.get("expires_at") instanceof Number && decoded.get("state") instanceof String,
                "Invalid AI preview token");
        if(((Number)decoded.get("expires_at")).longValue()<=System.currentTimeMillis())
            throw new Failure(409,"AI preview expired; review a fresh preview");
        if(!state.equals(decoded.get("state")))
            throw new Failure(409,"AI grounding or authority changed; review a fresh preview");
    }
}
