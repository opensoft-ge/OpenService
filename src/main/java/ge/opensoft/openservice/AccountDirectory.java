package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Sanitized administrator directory. Filters narrow results and never grant access. */
final class AccountDirectory {
    private AccountDirectory() {}

    static Map<String, Object> list(Store.Db db, Map<String, String> args) throws SQLException {
        if (!Set.of("page", "q", "role", "status", "channel", "basic").containsAll(args.keySet())
                || !args.getOrDefault("page", "1").matches("[1-9][0-9]{0,5}"))
            throw new Failure(
                    400,
                    "Use supported directory filters and a positive page of at most six digits");
        String search = args.getOrDefault("q", "").strip(),
                role = args.getOrDefault("role", ""),
                status = args.getOrDefault("status", ""),
                channel = args.getOrDefault("channel", ""),
                basic = args.getOrDefault("basic", "");
        if (search.codePointCount(0, search.length()) > 80
                || search.indexOf(0) >= 0
                || !Set.of("", "reader", "agent", "admin", "requester").contains(role)
                || !Set.of("", "active", "inactive").contains(status)
                || !Set.of("", "browser", "api").contains(channel)
                || !Set.of("", "allowed", "blocked").contains(basic))
            throw new Failure(400, "Invalid account directory filter");
        var parameters = new ArrayList<Object>(List.of(search.toLowerCase(Locale.ROOT)));
        String where = db.postgres ? "strpos(lower(name),?)>0" : "instr(lower(name),?)>0";
        if (!role.isEmpty()) {
            where += " AND role=?";
            parameters.add(role);
        }
        if (!status.isEmpty()) {
            where += " AND active=?";
            parameters.add(status.equals("active") ? 1 : 0);
        }
        if (!channel.isEmpty()) {
            where += " AND api_only=?";
            parameters.add(channel.equals("api") ? 1 : 0);
        }
        if (!basic.isEmpty()) {
            where += " AND allow_basic=?";
            parameters.add(basic.equals("allowed") ? 1 : 0);
        }
        int page = Integer.parseInt(args.getOrDefault("page", "1"));
        long total = db.count("SELECT COUNT(*) FROM os_user WHERE " + where, parameters.toArray()),
                pages = Math.max(1, (total + 24) / 25);
        if (page > pages) throw new Failure(400, "Page is outside the account list");
        parameters.add((page - 1) * 25);
        var rows =
                db.all(
                        "SELECT user_id,name,role,active,api_only,allow_basic FROM os_user WHERE "
                                + where
                                + " ORDER BY name,user_id LIMIT 25 OFFSET ?",
                        parameters.toArray());
        return map(
                "accounts",
                rows,
                "page",
                page,
                "pages",
                pages,
                "total",
                total,
                "search",
                search,
                "filterRole",
                role,
                "filterStatus",
                status,
                "filterChannel",
                channel,
                "filterBasic",
                basic);
    }
}
