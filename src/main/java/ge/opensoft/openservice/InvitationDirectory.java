package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class InvitationDirectory {
    private InvitationDirectory() {}

    static Map<String, Object> list(Store.Db db, String actor, Map<String, String> args)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        if (!Set.of("page", "q", "role", "state").containsAll(args.keySet())
                || !args.getOrDefault("page", "1").matches("[1-9][0-9]{0,5}"))
            throw new Failure(
                    400,
                    "Use supported invitation filters and a positive page of at most six digits");
        String search = args.getOrDefault("q", "").strip(),
                role = args.getOrDefault("role", ""),
                state = args.getOrDefault("state", "");
        if (search.codePointCount(0, search.length()) > 80
                || search.indexOf(0) >= 0
                || !Set.of("", "admin", "agent", "reader", "requester").contains(role)
                || !Set.of(
                                "",
                                "pending",
                                "expired",
                                "invalidated",
                                "unavailable",
                                "revoked",
                                "accepted")
                        .contains(state))
            throw new Failure(400, "Invalid invitation directory filter");
        var params =
                new ArrayList<Object>(
                        List.of(System.currentTimeMillis(), search.toLowerCase(Locale.ROOT)));
        String where = db.postgres ? "strpos(lower(name),?)>0" : "instr(lower(name),?)>0";
        if (!role.isEmpty()) {
            where += " AND role=?";
            params.add(role);
        }
        if (!state.isEmpty()) {
            where += " AND availability=?";
            params.add(state);
        }
        // One cutoff and identical SQL define both the count and each displayed state.
        String directory =
                "WITH directory AS (SELECT i.*, CASE WHEN i.state<>'pending' THEN i.state WHEN"
                    + " i.expires_at<=? THEN 'expired' WHEN NOT EXISTS (SELECT 1 FROM os_user u"
                    + " WHERE u.user_id=i.issuer_id AND u.active=1 AND u.role='admin' AND"
                    + " u.auth_revision=i.issuer_revision) THEN 'invalidated' WHEN EXISTS (SELECT 1"
                    + " FROM os_user u WHERE u.name=i.name) THEN 'unavailable' ELSE 'pending' END"
                    + " AS availability FROM os_invitation i) ";
        int page = Integer.parseInt(args.getOrDefault("page", "1"));
        long
                total =
                        db.count(
                                directory + "SELECT COUNT(*) FROM directory WHERE " + where,
                                params.toArray()),
                pages = Math.max(1, (total + 24) / 25);
        if (page > pages) throw new Failure(400, "Page is outside the invitation list");
        params.add((page - 1) * 25);
        var rows = new ArrayList<Map<String, Object>>();
        for (var row :
                db.all(
                        directory
                                + "SELECT * FROM directory WHERE "
                                + where
                                + " ORDER BY sequence DESC LIMIT 25 OFFSET ?",
                        params.toArray())) rows.add(Invitations.visible(db, row));
        return map(
                "invitations",
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
                "filterState",
                state);
    }
}
