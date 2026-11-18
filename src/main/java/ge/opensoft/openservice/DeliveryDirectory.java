package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Administrator metadata filters never change delivery eligibility or expose message bodies. */
final class DeliveryDirectory {
    private DeliveryDirectory() {}

    static Map<String, Object> page(Store.Db db, Map<String, String> args) throws SQLException {
        if (!Set.of("page", "state", "kind").containsAll(args.keySet())
                || !args.getOrDefault("page", "1").matches("[1-9][0-9]{0,5}"))
            throw new Failure(
                    400,
                    "Use supported delivery filters and a positive page of at most six digits");
        String state = args.getOrDefault("state", ""), kind = args.getOrDefault("kind", "");
        if (!Set.of("", "queued", "processing", "failed", "delivered", "cancelled").contains(state)
                || !Set.of("", "invitation", "recovery", "enrollment", "customer_reply").contains(kind))
            throw new Failure(400, "Invalid delivery state or message kind");
        var parameters = new ArrayList<Object>();
        String where = "1=1";
        if (!state.isEmpty()) {
            where += " AND state=?";
            parameters.add(state);
        }
        if (!kind.isEmpty()) {
            where += " AND kind=?";
            parameters.add("email." + kind);
        }
        int page = Integer.parseInt(args.getOrDefault("page", "1"));
        long total =
                db.count("SELECT COUNT(*) FROM os_outbox WHERE " + where, parameters.toArray());
        long pages = Math.max(1, (total + 99) / 100);
        if (page > pages)
            throw new Failure(400, "Page is outside the delivery list; refresh the first page");
        parameters.add((page - 1) * 100);
        var rows =
                db.all(
                        "SELECT "
                                + Outbox.PUBLIC_COLUMNS
                                + " FROM os_outbox WHERE "
                                + where
                                + " ORDER BY created_at DESC,job_id LIMIT 100 OFFSET ?",
                        parameters.toArray());
        return map(
                "result", rows, "page", page, "pages", pages, "total", total, "state", state,
                "kind", kind);
    }
}
