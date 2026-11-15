package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.Map;
import java.util.Set;

/** Zero inherits the deployment policy; positive overrides take effect at the next sign-in. */
final class AccountSessionLimit {
    private AccountSessionLimit() {}

    static Map<String, Object> state(Store.Db db, String target, int fallback) throws Exception {
        var row = db.one("SELECT browser_limit FROM os_user WHERE user_id=?", target);
        if (row == null) throw new Failure(404, "Account not found");
        int override = ((Number) row.get("browser_limit")).intValue();
        return map("maximum", override, "effective_maximum", override == 0 ? fallback : override);
    }

    static int effective(Store.Db db, String target, int fallback) throws Exception {
        return ((Number) state(db, target, fallback).get("effective_maximum")).intValue();
    }

    static int form(String input) {
        if (!input.matches("(?:0|[1-9][0-9]?|100)"))
            throw new Failure(400, "Choose 0 for the deployment default or a limit from 1 to 100");
        return Integer.parseInt(input);
    }

    static Map<String, Object> change(
            Store.Db db, String actor, String target, Object body, int fallback) throws Exception {
        Service.requireAccountAdministrator(db, actor);
        var value = object(body);
        if (!value.keySet().equals(Set.of("maximum"))
                || !(value.get("maximum") instanceof Integer maximum)
                || maximum < 0
                || maximum > 100)
            throw new Failure(400, "Provide an integer maximum from 0 to 100");
        var before = state(db, target, fallback);
        if (!before.get("maximum").equals(maximum)) {
            db.update("UPDATE os_user SET browser_limit=? WHERE user_id=?", maximum, target);
            SecurityEvents.record(
                    db,
                    actor,
                    target,
                    "account.session_limit_changed",
                    map("before", before.get("maximum"), "after", maximum));
        }
        return state(db, target, fallback);
    }
}
