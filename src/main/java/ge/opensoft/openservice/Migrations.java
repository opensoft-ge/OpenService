package ge.opensoft.openservice;

import java.sql.SQLException;

/** Ordered, transactional upgrades. The caller commits schema and bootstrap data together. */
final class Migrations {
    static final int VERSION = 40;

    private Migrations() {}

    static void lock(Store.Db db) throws SQLException {
        // SQLite connections already begin IMMEDIATE transactions before any schema access.
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439, 1)");
    }

    static void apply(Store.Db db) throws SQLException {
        db.update(
                "CREATE TABLE IF NOT EXISTS os_migration (version INTEGER PRIMARY KEY, name"
                        + " VARCHAR(120) NOT NULL)");
        var history = db.all("SELECT version FROM os_migration ORDER BY version");
        int version = history.size();
        if (version > VERSION) throw new SQLException("Unsupported OpenService migration history");
        for (int i = 0; i < version; i++)
            if (((Number) history.get(i).get("version")).intValue() != i + 1)
                throw new SQLException("Incomplete OpenService migration history");
        if (version < 1) {
            addColumn(
                    db,
                    "os_attachment",
                    "content_type",
                    "VARCHAR(255) NOT NULL DEFAULT 'application/octet-stream'");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(1,?)",
                    "attachment content type");
        }
        if (version < 2) {
            addColumn(db, "os_user", "user_id", "VARCHAR(32) NOT NULL DEFAULT ''");
            for (var user : db.all("SELECT name FROM os_user WHERE user_id=''"))
                db.update(
                        "UPDATE os_user SET user_id=? WHERE name=?", Values.id(), user.get("name"));
            db.update("CREATE UNIQUE INDEX IF NOT EXISTS ix_os_user_identity ON os_user(user_id)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(2,?)", "stable user identities");
        }
        if (version < 3) {
            addColumn(db, "os_user", "auth_revision", "BIGINT NOT NULL DEFAULT 0");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(3,?)",
                    "revocable browser sessions");
        }
        if (version < 4) {
            addColumn(
                    db, "os_user", "active", "INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1))");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(4,?)",
                    "account activation state");
        }
        if (version < 5) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_security_event (sequence "
                            + (db.postgres
                                    ? "BIGSERIAL PRIMARY KEY"
                                    : "INTEGER PRIMARY KEY AUTOINCREMENT")
                            + ",event_id VARCHAR(32) NOT NULL UNIQUE,occurred_at VARCHAR(40) NOT"
                            + " NULL,actor_id VARCHAR(32) NOT NULL,actor_name VARCHAR(80) NOT"
                            + " NULL,target_id VARCHAR(32) NOT NULL,target_name VARCHAR(80) NOT"
                            + " NULL,action VARCHAR(64) NOT NULL,detail TEXT NOT NULL)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(5,?)",
                    "account security events");
        }
        if (version < 6) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_invitation (sequence "
                            + (db.postgres
                                    ? "BIGSERIAL PRIMARY KEY"
                                    : "INTEGER PRIMARY KEY AUTOINCREMENT")
                            + ",invite_id VARCHAR(32) NOT NULL UNIQUE,name VARCHAR(80) NOT"
                            + " NULL,role VARCHAR(20) NOT NULL,token_hash VARCHAR(64) NOT NULL"
                            + " UNIQUE,created_at VARCHAR(40) NOT NULL,expires_at BIGINT NOT"
                            + " NULL,issuer_id VARCHAR(32) NOT NULL,issuer_revision BIGINT NOT"
                            + " NULL,state VARCHAR(20) NOT NULL,revoked_by VARCHAR(32) NOT NULL"
                            + " DEFAULT '')");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(6,?)",
                    "manual invitation onboarding");
        }
        if (version < 7) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_recovery (user_id VARCHAR(32) PRIMARY"
                        + " KEY,token_hash VARCHAR(64) NOT NULL UNIQUE,issuer_id VARCHAR(32) NOT"
                        + " NULL,auth_revision BIGINT NOT NULL,issuer_revision BIGINT NOT"
                        + " NULL,expires_at BIGINT NOT NULL)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(7,?)",
                    "assisted password recovery");
        }
        if (version < 8) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_record_access(record_id VARCHAR(32) PRIMARY"
                        + " KEY,visibility VARCHAR(20) NOT NULL,owner_id VARCHAR(32) NOT NULL)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(8,?)",
                    "private record visibility");
        }
        if (version < 9) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_group_member(group_id VARCHAR(32) NOT"
                            + " NULL,user_id VARCHAR(32) NOT NULL,PRIMARY KEY(group_id,user_id))");
            db.update("INSERT INTO os_migration(version,name) VALUES(9,?)", "group membership");
        }
        if (version < 10) {
            addColumn(db, "os_record_access", "group_id", "VARCHAR(32) NOT NULL DEFAULT ''");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(10,?)",
                    "group record visibility");
        }
        if (version < 11) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_auth_attempt(identity_hash VARCHAR(64) PRIMARY"
                            + " KEY,failures INTEGER NOT NULL,expires_at BIGINT NOT NULL)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(11,?)",
                    "persistent authentication throttling");
        }
        if (version < 12) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_auth_event(sequence "
                            + (db.postgres
                                    ? "BIGSERIAL PRIMARY KEY"
                                    : "INTEGER PRIMARY KEY AUTOINCREMENT")
                            + ",event_id VARCHAR(32) NOT NULL UNIQUE,occurred_at VARCHAR(40) NOT"
                            + " NULL,user_id VARCHAR(32) NOT NULL,user_name VARCHAR(80) NOT"
                            + " NULL,identity_hash VARCHAR(64) NOT NULL,mechanism VARCHAR(16) NOT"
                            + " NULL,outcome VARCHAR(16) NOT NULL)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(12,?)",
                    "authentication event journal");
        }
        if (version < 13) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_transfer_job(job_id VARCHAR(32) PRIMARY"
                        + " KEY,source_url VARCHAR(300) NOT NULL,creator_id VARCHAR(32) NOT"
                        + " NULL,definition TEXT NOT NULL,detail TEXT NOT NULL,created VARCHAR(40)"
                        + " NOT NULL)");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_transfer_item(job_id VARCHAR(32) NOT"
                            + " NULL,source_table VARCHAR(80) NOT NULL,source_id VARCHAR(32) NOT"
                            + " NULL,data TEXT NOT NULL,content "
                            + (db.postgres ? "BYTEA" : "BLOB")
                            + ",PRIMARY KEY(job_id,source_table,source_id))");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_transfer_link(source_url VARCHAR(300) NOT"
                            + " NULL,source_table VARCHAR(80) NOT NULL,source_id VARCHAR(32) NOT"
                            + " NULL,target_id VARCHAR(64) NOT NULL,detail TEXT NOT NULL,PRIMARY"
                            + " KEY(source_url,source_table,source_id))");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(13,?)",
                    "resumable ServiceNow migrations");
        }
        if (version < 14) {
            addColumn(db, "os_user", "email", "VARCHAR(254) NOT NULL DEFAULT ''");
            addColumn(db, "os_user", "email_verified", "INTEGER NOT NULL DEFAULT 0");
            addColumn(db, "os_invitation", "email", "VARCHAR(254) NOT NULL DEFAULT ''");
            addColumn(db, "os_recovery", "mode", "VARCHAR(16) NOT NULL DEFAULT 'manual'");
            db.update(
                    "CREATE UNIQUE INDEX IF NOT EXISTS ix_os_user_email ON os_user(email) WHERE"
                            + " email<>''");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_outbox(job_id VARCHAR(32) PRIMARY KEY,kind"
                        + " VARCHAR(80) NOT NULL,reference_id VARCHAR(64) NOT NULL,envelope TEXT"
                        + " NOT NULL,state VARCHAR(20) NOT NULL,attempts INTEGER NOT"
                        + " NULL,available_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,created_at"
                        + " BIGINT NOT NULL,lease VARCHAR(32) NOT NULL,error VARCHAR(40) NOT"
                        + " NULL,UNIQUE(kind,reference_id))");
            db.update(
                    "CREATE INDEX IF NOT EXISTS ix_os_outbox_ready ON"
                            + " os_outbox(state,available_at)");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_recovery_budget(request_id VARCHAR(32) PRIMARY"
                            + " KEY,identity_hash VARCHAR(64) NOT NULL,remote_hash VARCHAR(64) NOT"
                            + " NULL,requested_at BIGINT NOT NULL)");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_requester_record(record_id VARCHAR(32) PRIMARY"
                            + " KEY,user_id VARCHAR(32) NOT NULL)");
            db.update(
                    "CREATE INDEX IF NOT EXISTS ix_os_requester_user ON"
                            + " os_requester_record(user_id)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(14,?)",
                    "durable identity delivery and requester ownership");
        }
        if (version < 15) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_email_enrollment(user_id VARCHAR(32) PRIMARY"
                        + " KEY,token_hash VARCHAR(64) NOT NULL UNIQUE,email VARCHAR(254) NOT"
                        + " NULL,auth_revision BIGINT NOT NULL,expires_at BIGINT NOT"
                        + " NULL,created_at BIGINT NOT NULL,cancelled INTEGER NOT NULL DEFAULT 0)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(15,?)",
                    "verified recovery-email enrollment");
        }
        if (version < 16) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_password_exclusion(exclusion_id VARCHAR(32)"
                            + " PRIMARY KEY,label VARCHAR(80) NOT NULL,digest VARCHAR(64) NOT NULL"
                            + " UNIQUE,created_at VARCHAR(40) NOT NULL)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(16,?)",
                    "organization password exclusions");
        }
        if (version < 17) {
            addColumn(db, "os_user", "password_reset_required", "INTEGER NOT NULL DEFAULT 0");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(17,?)",
                    "required password replacement");
        }
        if (version < 18) {
            addColumn(db, "os_user", "api_only", "INTEGER NOT NULL DEFAULT 0");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(18,?)",
                    "API-only account access");
        }
        if (version < 19) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_approval(sequence "
                            + (db.postgres
                                    ? "BIGSERIAL PRIMARY KEY"
                                    : "INTEGER PRIMARY KEY AUTOINCREMENT")
                            + ",approval_id VARCHAR(32) NOT NULL UNIQUE,record_id VARCHAR(32) NOT"
                            + " NULL,record_revision INTEGER NOT NULL,schema_version INTEGER NOT"
                            + " NULL,target_state VARCHAR(80) NOT NULL,requested_by VARCHAR(32) NOT"
                            + " NULL,requester_name VARCHAR(80) NOT NULL,approver_id VARCHAR(32)"
                            + " NOT NULL,approver_name VARCHAR(80) NOT NULL,status VARCHAR(20) NOT"
                            + " NULL,reason TEXT NOT NULL,comment TEXT NOT NULL,created_at"
                            + " VARCHAR(40) NOT NULL,decided_at VARCHAR(40) NOT"
                            + " NULL,decision_revision BIGINT NOT"
                            + " NULL,UNIQUE(record_id,record_revision,schema_version,target_state,approver_id))");
            db.update(
                    "CREATE INDEX IF NOT EXISTS ix_os_approval_record ON"
                            + " os_approval(record_id,sequence)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(19,?)",
                    "revision-bound staff review");
        }
        if (version < 20) {
            db.update(
                    "CREATE TABLE os_approval_next(sequence "
                            + (db.postgres
                                    ? "BIGSERIAL PRIMARY KEY"
                                    : "INTEGER PRIMARY KEY AUTOINCREMENT")
                            + ",approval_id VARCHAR(32) NOT NULL UNIQUE,record_id VARCHAR(32) NOT"
                            + " NULL,record_revision INTEGER NOT NULL,schema_version INTEGER NOT"
                            + " NULL,target_state VARCHAR(80) NOT NULL,requested_by VARCHAR(32) NOT"
                            + " NULL,requester_name VARCHAR(80) NOT NULL,approver_id VARCHAR(32)"
                            + " NOT NULL,approver_name VARCHAR(80) NOT NULL,status VARCHAR(20) NOT"
                            + " NULL,reason TEXT NOT NULL,comment TEXT NOT NULL,created_at"
                            + " VARCHAR(40) NOT NULL,decided_at VARCHAR(40) NOT"
                            + " NULL,decision_revision BIGINT NOT NULL)");
            db.update("INSERT INTO os_approval_next SELECT * FROM os_approval ORDER BY sequence");
            db.update("DROP TABLE os_approval");
            db.update("ALTER TABLE os_approval_next RENAME TO os_approval");
            if (db.postgres)
                db.all(
                        "SELECT setval(pg_get_serial_sequence('os_approval','sequence'),"
                                + " COALESCE(MAX(sequence),1), COUNT(*)>0) FROM os_approval");
            db.update("CREATE INDEX ix_os_approval_record ON os_approval(record_id,sequence)");
            db.update(
                    "CREATE UNIQUE INDEX ix_os_approval_proposal ON"
                        + " os_approval(record_id,record_revision,schema_version,target_state,approver_id)"
                        + " WHERE status<>'withdrawn'");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(20,?)",
                    "preserve withdrawn review attempts");
        }
        if (version < 21) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_browser_session(session_id VARCHAR(32) PRIMARY"
                        + " KEY,session_hash VARCHAR(64) NOT NULL UNIQUE,user_id VARCHAR(32) NOT"
                        + " NULL,auth_revision BIGINT NOT NULL,created_at BIGINT NOT NULL,last_seen"
                        + " BIGINT NOT NULL,expires_at BIGINT NOT NULL)");
            db.update(
                    "CREATE INDEX IF NOT EXISTS ix_os_browser_session_user ON"
                        + " os_browser_session(user_id)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(21,?)",
                    "browser session inventory");
        }
        if (version < 22) {
            addColumn(
                    db,
                    "os_user",
                    "allow_basic",
                    "INTEGER NOT NULL DEFAULT 1 CHECK(allow_basic IN (0,1))");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(22,?)",
                    "per-account Basic authentication policy");
        }
        if (version < 23) {
            addColumn(
                    db,
                    "os_user",
                    "browser_limit",
                    "INTEGER NOT NULL DEFAULT 0 CHECK(browser_limit BETWEEN 0 AND 100)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(23,?)",
                    "per-account browser session limits");
        }
        if (version < 24) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_api_key(key_id VARCHAR(32) PRIMARY"
                        + " KEY,token_hash VARCHAR(64) NOT NULL UNIQUE,user_id VARCHAR(32) NOT"
                        + " NULL,auth_revision BIGINT NOT NULL,label VARCHAR(160) NOT"
                        + " NULL,table_name VARCHAR(160) NOT NULL,created_at BIGINT NOT"
                        + " NULL,expires_at BIGINT NOT NULL)");
            db.update("CREATE INDEX IF NOT EXISTS ix_os_api_key_user ON os_api_key(user_id)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(24,?)",
                    "scoped integration API keys");
        }
        if (version < 25) {
            addColumn(
                    db,
                    "os_api_key",
                    "enabled",
                    "INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1))");
            db.update("INSERT INTO os_migration(version,name) VALUES(25,?)", "API key suspension");
        }
        if (version < 26) {
            addColumn(
                    db,
                    "os_api_key",
                    "can_write",
                    "INTEGER NOT NULL DEFAULT 0 CHECK(can_write IN (0,1))");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(26,?)",
                    "API key create and update scope");
        }
        if (version < 27) {
            addColumn(
                    db, "os_api_key", "use_count", "BIGINT NOT NULL DEFAULT 0 CHECK(use_count>=0)");
            addColumn(
                    db,
                    "os_api_key",
                    "last_used_at",
                    "BIGINT NOT NULL DEFAULT 0 CHECK(last_used_at>=0)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(27,?)",
                    "API key successful request activity");
        }
        if (version < 28) {
            addColumn(
                    db,
                    "os_email_enrollment",
                    "previous_email",
                    "VARCHAR(254) NOT NULL DEFAULT ''");
            addColumn(
                    db, "os_email_enrollment", "old_token_hash", "VARCHAR(64) NOT NULL DEFAULT ''");
            addColumn(db, "os_email_enrollment", "old_confirmed", "INTEGER NOT NULL DEFAULT 1");
            addColumn(db, "os_email_enrollment", "new_confirmed", "INTEGER NOT NULL DEFAULT 0");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(28,?)",
                    "dual-proof registered email replacement");
        }
        if (version < 29) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_work(job_id VARCHAR(32) PRIMARY KEY,owner_id"
                        + " VARCHAR(32) NOT NULL,auth_revision BIGINT NOT NULL,kind VARCHAR(40) NOT"
                        + " NULL,dedupe VARCHAR(80) NOT NULL,fingerprint VARCHAR(64) NOT"
                        + " NULL,envelope TEXT NOT NULL,state VARCHAR(20) NOT NULL,attempts INTEGER"
                        + " NOT NULL,available_at BIGINT NOT NULL,created_at BIGINT NOT NULL,lease"
                        + " VARCHAR(32) NOT NULL,error VARCHAR(40) NOT NULL,result TEXT NOT"
                        + " NULL,UNIQUE(owner_id,dedupe))");
            db.update("CREATE INDEX IF NOT EXISTS ix_os_work_ready ON os_work(state,available_at)");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_notice(notice_id VARCHAR(32) PRIMARY KEY,user_id"
                        + " VARCHAR(32) NOT NULL,message TEXT NOT NULL,created_at BIGINT NOT"
                        + " NULL,acknowledged INTEGER NOT NULL DEFAULT 0)");
            addColumn(
                    db,
                    "os_user",
                    "notifications",
                    "INTEGER NOT NULL DEFAULT 1 CHECK(notifications IN (0,1))");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_connector(alias VARCHAR(80) PRIMARY KEY,origin"
                        + " TEXT NOT NULL,envelope TEXT NOT NULL,revision INTEGER NOT NULL)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(29,?)",
                    "durable platform work and connector aliases");
        }
        if (version < 30) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_calendar(calendar_id VARCHAR(80) PRIMARY"
                        + " KEY,definition TEXT NOT NULL)");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_sla(sla_id VARCHAR(32) PRIMARY KEY,table_name"
                        + " VARCHAR(80) NOT NULL,record_id VARCHAR(32) NOT NULL,definition TEXT NOT"
                        + " NULL,remaining_ms BIGINT NOT NULL,last_tick BIGINT NOT NULL,state"
                        + " VARCHAR(20) NOT NULL,owner_id VARCHAR(32) NOT NULL,breached_at BIGINT"
                        + " NOT NULL DEFAULT 0)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(30,?)",
                    "business calendars and persistent SLA clocks");
        }
        if (version < 31) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_saved_report(report_id VARCHAR(32) PRIMARY"
                        + " KEY,owner_id VARCHAR(32) NOT NULL,name VARCHAR(160) NOT NULL,table_name"
                        + " VARCHAR(80) NOT NULL,definition TEXT NOT NULL,shared INTEGER NOT"
                        + " NULL)");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_publication(article_id VARCHAR(32) NOT"
                        + " NULL,version INTEGER NOT NULL,title TEXT NOT NULL,content TEXT NOT"
                        + " NULL,published_at VARCHAR(40) NOT NULL,retired INTEGER NOT NULL DEFAULT"
                        + " 0,PRIMARY KEY(article_id,version))");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_dependency(dependent VARCHAR(32) NOT"
                        + " NULL,dependency VARCHAR(32) NOT NULL,PRIMARY"
                        + " KEY(dependent,dependency))");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_operational_event(event_key VARCHAR(160) PRIMARY"
                        + " KEY,asset_id VARCHAR(32) NOT NULL,severity VARCHAR(20) NOT NULL,summary"
                        + " TEXT NOT NULL,occurrences BIGINT NOT NULL,first_seen BIGINT NOT"
                        + " NULL,last_seen BIGINT NOT NULL)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(31,?)",
                    "shared reports, published knowledge and operational dependencies");
        }
        if (version < 32) {
            addColumn(
                    db,
                    "os_user",
                    "audit_viewer",
                    "INTEGER NOT NULL DEFAULT 0 CHECK(audit_viewer IN (0,1))");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_identity(source VARCHAR(80) NOT NULL,external_id"
                        + " VARCHAR(160) NOT NULL,user_id VARCHAR(32) NOT NULL UNIQUE,PRIMARY"
                        + " KEY(source,external_id))");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(32,?)",
                    "identity provisioning and independent audit access");
        }
        if (version < 33) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_password_policy(policy_id INTEGER PRIMARY"
                        + " KEY,definition TEXT NOT NULL)");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_password_corpus(digest VARCHAR(64) PRIMARY"
                        + " KEY)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(33,?)",
                    "versioned organization password screening policy");
        }
        if (version < 34) {
            addColumn(db, "os_operational_event", "state",
                    "VARCHAR(20) NOT NULL DEFAULT 'open' CHECK(state IN ('open','acknowledged','resolved'))");
            db.update("INSERT INTO os_migration(version,name) VALUES(34,?)",
                    "operational event acknowledgment and resolution");
        }
        if (version < 35) {
            db.update("CREATE TABLE IF NOT EXISTS os_report_run(run_id VARCHAR(32) PRIMARY KEY,"
                    + "report_id VARCHAR(32) NOT NULL,viewer_id VARCHAR(32) NOT NULL,executed_at BIGINT NOT NULL,"
                    + "duration_ms BIGINT NOT NULL,definition_hash VARCHAR(64) NOT NULL)");
            db.update("CREATE INDEX IF NOT EXISTS ix_os_report_run_history ON os_report_run(report_id,viewer_id,executed_at)");
            db.update("INSERT INTO os_migration(version,name) VALUES(35,?)","bounded per-viewer report execution history");
        }
        if (version < 36) {
            addColumn(db,"os_user","display_name","VARCHAR(320) NOT NULL DEFAULT ''");
            addColumn(db,"os_user","job_title","VARCHAR(320) NOT NULL DEFAULT ''");
            db.update("INSERT INTO os_migration(version,name) VALUES(36,?)","owner-managed personal profile details");
        }
        if (version < 37) {
            db.update("CREATE TABLE IF NOT EXISTS os_offering(offering_id VARCHAR(32) PRIMARY KEY,name VARCHAR(160) NOT NULL,description TEXT NOT NULL,active INTEGER NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_catalog_order(user_id VARCHAR(32) NOT NULL,order_key VARCHAR(32) NOT NULL,offering_id VARCHAR(32) NOT NULL,offering_revision INTEGER NOT NULL,request_id VARCHAR(32) NOT NULL,fingerprint VARCHAR(64) NOT NULL,PRIMARY KEY(user_id,order_key))");
            db.update("INSERT INTO os_migration(version,name) VALUES(37,?)","reusable catalogue offerings and idempotent owned requests");
        }
        if(version<38){
            addColumn(db,"os_attachment","quarantined","INTEGER NOT NULL DEFAULT 0 CHECK(quarantined IN (0,1))");
            addColumn(db,"os_attachment","hold_revision","INTEGER NOT NULL DEFAULT 0");
            db.update("INSERT INTO os_migration(version,name) VALUES(38,?)","manual attachment holds and reviewed release");
        }
        if(version<39){
            db.update("CREATE TABLE IF NOT EXISTS os_knowledge_feedback(feedback_id VARCHAR(32) PRIMARY KEY,article_id VARCHAR(32) NOT NULL,version INTEGER NOT NULL,user_id VARCHAR(32) NOT NULL,helpful INTEGER NOT NULL CHECK(helpful IN(0,1)),details TEXT NOT NULL,updated_at BIGINT NOT NULL,revision INTEGER NOT NULL DEFAULT 1,reviewed INTEGER NOT NULL DEFAULT 0 CHECK(reviewed IN(0,1)),review_note TEXT NOT NULL DEFAULT '',reviewer_id VARCHAR(32) NOT NULL DEFAULT '',UNIQUE(article_id,version,user_id))");
            db.update("INSERT INTO os_migration(version,name) VALUES(39,?)","version-bound personal knowledge feedback and editorial review");
        }
        if(version<40){
            db.update("CREATE TABLE IF NOT EXISTS os_event_case(event_key VARCHAR(160) PRIMARY KEY,record_id VARCHAR(32) NOT NULL UNIQUE,created_at BIGINT NOT NULL,creator_id VARCHAR(32) NOT NULL)");
            db.update("INSERT INTO os_migration(version,name) VALUES(40,?)","reviewed operational event to private incident correlation");
        }
    }

    private static void addColumn(Store.Db db, String table, String column, String definition)
            throws SQLException {
        try (var columns =
                db.connection
                        .getMetaData()
                        .getColumns(null, db.connection.getSchema(), table, column)) {
            if (!columns.next()) {
                // SQLite may expose constraint validation as a result during ALTER TABLE.
                try (var query =
                        db.statement(
                                "ALTER TABLE "
                                        + table
                                        + " ADD COLUMN "
                                        + column
                                        + " "
                                        + definition)) {
                    query.execute();
                }
            }
        }
    }
}
