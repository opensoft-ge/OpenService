package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

/** Native identity adapter contract. External IDs cannot take over existing local usernames. */
final class IdentityProvisioning {
    private IdentityProvisioning() {}

    static Map<String, Object> synchronize(Store.Db db, String actor, Object input)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        var body = object(input);
        require(
                body.keySet()
                        .equals(
                                Set.of(
                                        "source",
                                        "external_id",
                                        "username",
                                        "role",
                                        "active",
                                        "groups")),
                "Provide source, external_id, username, role, active and groups");
        String source = WorkJobs.text(body.get("source"), 80),
                external = WorkJobs.text(body.get("external_id"), 160),
                name = WorkJobs.text(body.get("username"), 80),
                role = WorkJobs.text(body.get("role"), 20);
        require(name.matches("[A-Za-z0-9]{1,80}"), "Use an ASCII alphanumeric username");
        require(
                Set.of("agent", "reader", "requester").contains(role),
                "External provisioning cannot grant administrator access");
        require(body.get("active") instanceof Boolean, "Active must be boolean");
        require(body.get("groups") instanceof List<?>, "Groups must be a list");
        var groups = (List<?>) body.get("groups");
        require(groups.size() <= 100, "At most 100 groups are supported");
        for (var group : groups) Service.record(db, "sys_user_group", WorkJobs.text(group, 32));
        var binding =
                db.one(
                        "SELECT user_id FROM os_identity WHERE source=? AND external_id=?",
                        source,
                        external);
        String target;
        if (binding == null) {
            var user =
                    Service.createManagedAccount(
                            db,
                            map(
                                    "name",
                                    name,
                                    "role",
                                    role,
                                    "password",
                                    Passwords.token().repeat(4),
                                    "require_password_change",
                                    true),
                            actor);
            target = RecordAccess.owner(db, name);
            db.update(
                    "INSERT INTO os_identity(source,external_id,user_id) VALUES(?,?,?)",
                    source,
                    external,
                    target);
        } else {
            target = binding.get("user_id").toString();
            var user = Service.account(db, target);
            require(
                    user.get("name").equals(name),
                    "Renaming externally bound usernames requires explicit reconciliation");
            require(
                    !user.get("role").equals("admin"),
                    "An external identity cannot modify a local administrator");
            Service.changeRole(db, actor, target, map("role", role));
        }
        boolean active = (Boolean) body.get("active");
        Service.changeActive(db, actor, target, map("active", active));
        for (var membership : GroupMembers.forUser(db, target)) {
            String group = membership.get("group_id").toString();
            if (!active || !groups.contains(group))
                GroupMembers.change(db, group, actor, map("user_id", target), false);
        }
        if (active)
            for (var group : groups)
                GroupMembers.change(db, group.toString(), actor, map("user_id", target), true);
        SecurityEvents.record(
                db,
                actor,
                target,
                "identity.synchronized",
                map("source", source, "active", active));
        return map(
                "account",
                Service.account(db, target),
                "groups",
                GroupMembers.forUser(db, target),
                "password_setup_required",
                ((Number)
                                        db.one(
                                                        "SELECT password_reset_required FROM"
                                                            + " os_user WHERE user_id=?",
                                                        target)
                                                .get("password_reset_required"))
                                .intValue()
                        == 1);
    }
}
