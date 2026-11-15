package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.SQLException;
import java.util.*;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Transactional, leased delivery. SMTP is at-least-once; message IDs remain stable on retry. */
final class Outbox {
    static final String PUBLIC_COLUMNS =
            "job_id,kind,state,attempts,available_at,expires_at,created_at,error";

    private Outbox() {}

    static String encrypt(String secret, String plaintext) throws Exception {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        byte[] encrypted =
                cipher(secret, nonce, Cipher.ENCRYPT_MODE)
                        .doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(nonce)
                + "."
                + Base64.getEncoder().encodeToString(encrypted);
    }

    static Map<String, Object> decrypt(String secret, String envelope) throws Exception {
        String[] parts = envelope.split("\\.", -1);
        if (parts.length != 2) throw new GeneralSecurityException("Invalid delivery envelope");
        byte[] bytes =
                cipher(secret, Base64.getDecoder().decode(parts[0]), Cipher.DECRYPT_MODE)
                        .doFinal(Base64.getDecoder().decode(parts[1]));
        return object(JSON.readValue(new String(bytes, StandardCharsets.UTF_8), Object.class));
    }

    private static Cipher cipher(String secret, byte[] nonce, int mode) throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] key =
                MessageDigest.getInstance("SHA-256")
                        .digest(
                                ("openservice-outbox-v1:" + secret)
                                        .getBytes(StandardCharsets.UTF_8));
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher;
    }

    static String enqueue(
            Store.Db db,
            String secret,
            String kind,
            String reference,
            Map<String, Object> payload,
            long expires)
            throws Exception {
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,6)");
        var existing =
                db.one(
                        "SELECT job_id FROM os_outbox WHERE kind=? AND reference_id=?",
                        kind,
                        reference);
        if (existing != null) return existing.get("job_id").toString();
        if (db.count(
                        "SELECT COUNT(*) FROM os_outbox WHERE state IN"
                                + " ('queued','processing','failed')")
                >= 1000) throw new Failure(429, "Delivery queue is full; contact an administrator");
        String id = id();
        long now = System.currentTimeMillis();
        db.update(
                "INSERT INTO"
                    + " os_outbox(job_id,kind,reference_id,envelope,state,attempts,available_at,expires_at,created_at,lease,error)"
                    + " VALUES(?,?,?,?,'queued',0,?,?,?,'','')",
                id,
                kind,
                reference,
                encrypt(secret, JSON.writeValueAsString(payload)),
                now,
                expires,
                now);
        return id;
    }

    static Map<String, Object> claim(Store.Db db, long now) throws SQLException {
        db.update(
                "UPDATE os_outbox SET state='cancelled',envelope='',lease='',error='EXPIRED' WHERE"
                        + " expires_at<=? AND state IN ('queued','processing','failed')",
                now);
        db.update(
                "UPDATE os_outbox SET state='failed',lease='',error='LEASE_EXHAUSTED' WHERE"
                        + " state='processing' AND attempts>=5 AND available_at<=?",
                now);
        var job =
                db.one(
                        "SELECT * FROM os_outbox WHERE state IN ('queued','processing') AND"
                                + " available_at<=? ORDER BY created_at,job_id LIMIT 1"
                                + (db.postgres ? " FOR UPDATE SKIP LOCKED" : ""),
                        now);
        if (job == null) return null;
        String lease = id();
        int attempts = ((Number) job.get("attempts")).intValue() + 1;
        db.update(
                "UPDATE os_outbox SET state='processing',attempts=?,available_at=?,lease=? WHERE"
                        + " job_id=?",
                attempts,
                now + 60_000,
                lease,
                job.get("job_id"));
        job.put("lease", lease);
        job.put("attempts", attempts);
        return job;
    }

    static void finish(Store.Db db, Map<String, Object> job, String state, String error, long now)
            throws SQLException {
        boolean retry = state.equals("queued");
        int attempts = ((Number) job.get("attempts")).intValue();
        if (retry && attempts >= 5) state = "failed";
        boolean terminal = state.equals("delivered") || state.equals("cancelled");
        db.update(
                "UPDATE os_outbox SET state=?,error=?,available_at=?,lease='',envelope="
                        + (terminal ? "''" : "envelope")
                        + " WHERE job_id=? AND lease=? AND state='processing'",
                state,
                error,
                now + Math.min(300_000L, 1000L << Math.min(attempts, 8)),
                job.get("job_id"),
                job.get("lease"));
    }

    static List<Map<String, Object>> list(Store.Db db) throws SQLException {
        return db.all(
                "SELECT "
                        + PUBLIC_COLUMNS
                        + " FROM os_outbox ORDER BY created_at DESC,job_id LIMIT 100");
    }

    static Map<String, Object> diagnostics(Store.Db db, long now) throws SQLException {
        var result =
                db.one(
                        "SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN state='queued' THEN 1"
                            + " ELSE 0 END),0) AS queued, COALESCE(SUM(CASE WHEN state='processing'"
                            + " THEN 1 ELSE 0 END),0) AS processing, COALESCE(SUM(CASE WHEN"
                            + " state='failed' THEN 1 ELSE 0 END),0) AS failed, COALESCE(SUM(CASE"
                            + " WHEN state='delivered' THEN 1 ELSE 0 END),0) AS delivered,"
                            + " COALESCE(SUM(CASE WHEN state='cancelled' THEN 1 ELSE 0 END),0) AS"
                            + " cancelled, COALESCE(SUM(CASE WHEN state='queued' AND"
                            + " available_at<=? AND expires_at>? THEN 1 ELSE 0 END),0) AS ready,"
                            + " COALESCE(SUM(CASE WHEN state='processing' AND available_at<=? AND"
                            + " expires_at>? THEN 1 ELSE 0 END),0) AS expired_leases,"
                            + " COALESCE(SUM(CASE WHEN state IN ('queued','processing','failed')"
                            + " AND expires_at<=? THEN 1 ELSE 0 END),0) AS expired_links, MIN(CASE"
                            + " WHEN state IN ('queued','processing') AND expires_at>? THEN"
                            + " created_at ELSE NULL END) AS oldest FROM os_outbox",
                        now,
                        now,
                        now,
                        now,
                        now,
                        now);
        var oldest = (Number) result.remove("oldest");
        result.put(
                "oldest_pending_age_seconds",
                oldest == null ? 0L : Math.max(0L, now - oldest.longValue()) / 1000);
        result.put("observed_at_epoch_ms", now);
        return result;
    }

    static void cancel(Store.Db db, String actor, String id) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        if (db.update(
                        "UPDATE os_outbox SET"
                            + " state='cancelled',envelope='',lease='',error='OPERATOR_CANCELLED'"
                            + " WHERE job_id=? AND state IN ('queued','failed')",
                        id)
                != 1) throw new Failure(409, "Only queued or failed deliveries can be cancelled");
        SecurityEvents.record(db, actor, actor, "delivery.cancelled", map("job_id", id));
    }

    static void retry(Store.Db db, String actor, String id) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        if (db.update(
                        "UPDATE os_outbox SET state='queued',attempts=0,available_at=?,error=''"
                                + " WHERE job_id=? AND state='failed' AND expires_at>?",
                        System.currentTimeMillis(),
                        id,
                        System.currentTimeMillis())
                != 1) throw new Failure(409, "Only an unexpired failed delivery can be retried");
        SecurityEvents.record(db, actor, actor, "delivery.retried", map("job_id", id));
    }
}
