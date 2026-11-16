package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import org.springframework.core.env.Environment;
import java.sql.SQLException;
import java.util.*;

/** Local configuration evidence only; never substitutes for qualification of the real deployment. */
final class DeploymentPreflight {
    private DeploymentPreflight() {}

    static boolean mailConfigured(Environment env) {
        if (!env.getProperty("DELIVERY_ENABLED", Boolean.class, false)) return false;
        try {
            if (!SmtpDelivery.base(env).startsWith("https://")) return false;
            String host=env.getProperty("MAIL_HOST", "");
            int port=env.getProperty("MAIL_PORT",Integer.class,587);
            if(!host.matches("[A-Za-z0-9._:-]{1,253}") || port<1 || port>65535) return false;
            new SmtpDelivery(env); // Validates configuration; does not contact the SMTP service.
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    static Map<String,Object> inspect(Store store, Store.Db db) throws SQLException {
        var env = store.environment;
        var checks = new ArrayList<Map<String,Object>>();
        checks.add(check("postgresql", store.postgres, "Use PostgreSQL for shared evaluation; concurrent SQLite qualification remains open."));
        checks.add(check("secure_cookies", env.getProperty("COOKIE_SECURE",Boolean.class,true), "Enable COOKIE_SECURE and verify cookies through the real HTTPS proxy."));
        checks.add(check("account_email", mailConfigured(env), "Configure HTTPS PUBLIC_BASE_URL and valid DELIVERY_ENABLED/MAIL_* settings, then test controlled inbox delivery."));
        checks.add(check("business_worker", env.getProperty("WORK_ENABLED",Boolean.class,false), "Enable WORK_ENABLED for durable work and service clocks."));
        checks.add(check("active_administrator", db.count("SELECT COUNT(*) FROM os_user WHERE active=1 AND role='admin'")>0, "Provision an approved active administrator and independently test recovery."));
        checks.add(check("failed_work", db.count("SELECT COUNT(*) FROM os_work WHERE state='failed'")==0, "Investigate failed work and retry or cancel it under current authority."));
        checks.add(check("expired_leases", db.count("SELECT COUNT(*) FROM os_work WHERE state='processing' AND available_at<=?",System.currentTimeMillis())==0, "Confirm that workers recover expired leases and that dependencies are healthy."));
        long required = checks.stream().filter(row -> row.get("status").equals("action_required")).count();
        return map("checks",checks,"actions_required",required,"configuration_ready",required==0,
                "shared_release_qualified",false,"external_verification",List.of(
                        "Verify actual TLS, proxy headers and network exposure.",
                        "Prove invitation and recovery email delivery to controlled inboxes.",
                        "Verify secret access, backup restoration and recovery objectives.",
                        "Approve users, operational ownership, monitoring and escalation."));
    }

    static Map<String,Object> check(String id, boolean configured, String action) {
        return map("id",id,"status",configured?"configured":"action_required","action",action);
    }
}
