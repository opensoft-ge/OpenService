package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.Map;

/** Optional create/update scope never bypasses the account's business permissions. */
final class ApiKeyWrites {
    private ApiKeyWrites() {}

    static Map<String, Object> issue(Store.Db db, String actor, String target, Object body)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        if (!Service.account(db, target).get("role").equals("agent"))
            throw new Failure(409, "Create/update keys require an agent account");
        var issued = ApiKeys.issue(db, actor, target, body);
        db.update("UPDATE os_api_key SET can_write=1 WHERE key_id=?", issued.get("key_id"));
        SecurityEvents.record(
                db,
                actor,
                target,
                "account.api_key_write_scope_issued",
                map("key_id", issued.get("key_id")));
        issued.put("access", "read_create_update");
        return issued;
    }
}
