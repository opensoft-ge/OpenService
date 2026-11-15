package ge.opensoft.openservice;

import java.util.Map;

/** Administrator investigation reuses the secret-free, account-bound event projection. */
final class AccountAuthentication {
    private AccountAuthentication() {}

    static Map<String, Object> page(
            Store.Db db, String actor, String target, Map<String, String> args) throws Exception {
        Service.requireAccountAdministrator(db, actor);
        var account = Service.account(db, target);
        var page = AuthenticationEvents.mine(db, target, args);
        page.put("account", account);
        return page;
    }
}
