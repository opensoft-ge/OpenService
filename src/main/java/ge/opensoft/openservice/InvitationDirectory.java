package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class InvitationDirectory {
    private InvitationDirectory() {}

    static Map<String, Object> list(Store.Db db, String actor, Map<String, String> args)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        if (!Set.of("page", "q", "role").containsAll(args.keySet())
                || !args.getOrDefault("page", "1").matches("[1-9][0-9]{0,5}"))
            throw new Failure(
                    400,
                    "Use supported invitation filters and a positive page of at most six digits");
        String search = args.getOrDefault("q", "").strip(), role = args.getOrDefault("role", "");
        if (search.codePointCount(0, search.length()) > 80
                || search.indexOf(0) >= 0
                || !Set.of("", "admin", "agent", "reader", "requester").contains(role))
            throw new Failure(400, "Invalid invitation directory filter");
        var params = new ArrayList<Object>(List.of(search.toLowerCase(Locale.ROOT)));
        String where = db.postgres ? "strpos(lower(name),?)>0" : "instr(lower(name),?)>0";
        if (!role.isEmpty()) {
            where += " AND role=?";
            params.add(role);
        }
        int page = Integer.parseInt(args.getOrDefault("page", "1"));
        long
                total =
                        db.count(
                                "SELECT COUNT(*) FROM os_invitation WHERE " + where,
                                params.toArray()),
                pages = Math.max(1, (total + 24) / 25);
        if (page > pages) throw new Failure(400, "Page is outside the invitation list");
        params.add((page - 1) * 25);
        var rows = new ArrayList<Map<String, Object>>();
        for (var row :
                db.all(
                        "SELECT * FROM os_invitation WHERE "
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
                role);
    }
}
