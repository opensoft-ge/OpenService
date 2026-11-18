package ge.opensoft.openservice;

import java.sql.SQLException;

/** Ordered, transactional upgrades. The caller commits schema and bootstrap data together. */
final class Migrations {
    static final int VERSION = 78;

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
        if(version<41){
            addColumn(db,"os_sla","purpose","VARCHAR(20) NOT NULL DEFAULT 'general' CHECK(purpose IN ('general','response','resolution'))");
            db.update("CREATE UNIQUE INDEX IF NOT EXISTS ix_os_sla_active_purpose ON os_sla(table_name,record_id,purpose) WHERE purpose<>'general' AND state IN ('running','paused')");
            db.update("INSERT INTO os_migration(version,name) VALUES(41,?)","separate response and resolution service clocks");
        }
        if(version<42){
            db.update("CREATE TABLE IF NOT EXISTS os_configuration_install(install_id VARCHAR(32) PRIMARY KEY,actor_id VARCHAR(32) NOT NULL,actor_name VARCHAR(80) NOT NULL,installed_at VARCHAR(26) NOT NULL,table_count INTEGER NOT NULL,detail TEXT NOT NULL)");
            db.update("INSERT INTO os_migration(version,name) VALUES(42,?)","retained configuration promotion receipts");
        }
        if(version<43){
            addColumn(db,"os_offering","group_id","VARCHAR(32) NOT NULL DEFAULT ''");
            db.update("INSERT INTO os_migration(version,name) VALUES(43,?)","live group eligibility for catalogue offerings");
        }
        if(version<44){
            addColumn(db,"os_offering","fulfilment_group_id","VARCHAR(32) NOT NULL DEFAULT ''");
            db.update("CREATE TABLE IF NOT EXISTS os_fulfilment(request_id VARCHAR(32) PRIMARY KEY,group_id VARCHAR(32) NOT NULL,assignee_id VARCHAR(32) NOT NULL,state VARCHAR(20) NOT NULL,outcome TEXT NOT NULL,revision INTEGER NOT NULL,updated_at VARCHAR(26) NOT NULL)");
            db.update("INSERT INTO os_migration(version,name) VALUES(44,?)","group fulfilment for routed catalogue orders");
        }
        if(version<45){
            addColumn(db,"os_offering","questions","TEXT NOT NULL DEFAULT '[]'");
            addColumn(db,"os_catalog_order","answers","TEXT NOT NULL DEFAULT '[]'");
            db.update("INSERT INTO os_migration(version,name) VALUES(45,?)","validated catalogue questions and retained answers");
        }
        if(version<46){
            db.update("CREATE TABLE IF NOT EXISTS os_work_attempt(job_id VARCHAR(32) NOT NULL,lease VARCHAR(32) NOT NULL,ordinal BIGINT NOT NULL,attempt_number INTEGER NOT NULL,started_at BIGINT NOT NULL,finished_at BIGINT NOT NULL,state VARCHAR(30) NOT NULL,error VARCHAR(80) NOT NULL,PRIMARY KEY(job_id,ordinal),UNIQUE(job_id,lease))");
            db.update("INSERT INTO os_migration(version,name) VALUES(46,?)","bounded durable work attempt history");
        }
        if(version<47){
            db.update("CREATE TABLE IF NOT EXISTS os_connector_action(action_id VARCHAR(32) PRIMARY KEY,name VARCHAR(160) NOT NULL,alias VARCHAR(80) NOT NULL,method VARCHAR(10) NOT NULL,path VARCHAR(2048) NOT NULL,inputs TEXT NOT NULL,active INTEGER NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_connector_resolution(job_id VARCHAR(32) PRIMARY KEY,decision VARCHAR(20) NOT NULL,note TEXT NOT NULL,actor_id VARCHAR(32) NOT NULL,recorded_at VARCHAR(26) NOT NULL)");
            db.update("INSERT INTO os_migration(version,name) VALUES(47,?)","reviewed connector writes and reconciliation");
        }
        if(version<48){
            db.update("CREATE TABLE IF NOT EXISTS os_notice_target(notice_id VARCHAR(32) PRIMARY KEY REFERENCES os_notice(notice_id) ON DELETE CASCADE,kind VARCHAR(20) NOT NULL,target_id VARCHAR(32) NOT NULL)");
            db.update("INSERT INTO os_migration(version,name) VALUES(48,?)","authorized notification resource targets");
        }
        if(version<49){
            db.update("CREATE TABLE IF NOT EXISTS os_attachment_scan(attachment_id VARCHAR(32) PRIMARY KEY REFERENCES os_attachment(sys_id) ON DELETE CASCADE,revision INTEGER NOT NULL,status VARCHAR(20) NOT NULL,sha256 VARCHAR(64) NOT NULL,engine VARCHAR(255) NOT NULL,completed_at BIGINT NOT NULL,hold_revision BIGINT NOT NULL,release_on_clean INTEGER NOT NULL)");
            db.update("INSERT INTO os_migration(version,name) VALUES(49,?)","durable current-content attachment scanning");
        }
        if(version<50){
            db.update("CREATE TABLE IF NOT EXISTS os_support_conversation(conversation_id VARCHAR(32) PRIMARY KEY,owner_id VARCHAR(32) NOT NULL REFERENCES os_user(user_id) ON DELETE CASCADE,article_id VARCHAR(32) NOT NULL,article_version INTEGER NOT NULL,revision INTEGER NOT NULL,state VARCHAR(20) NOT NULL,envelope TEXT NOT NULL,request_id VARCHAR(32) NOT NULL,updated_at BIGINT NOT NULL)");
            db.update("INSERT INTO os_migration(version,name) VALUES(50,?)","owned support conversations and reviewed request handoff");
        }
        if(version<51){
            db.update("CREATE TABLE IF NOT EXISTS os_report_measurement(sequence "+(db.postgres?"BIGSERIAL PRIMARY KEY":"INTEGER PRIMARY KEY AUTOINCREMENT")+",measurement_id VARCHAR(32) NOT NULL UNIQUE,report_id VARCHAR(32) NOT NULL REFERENCES os_saved_report(report_id) ON DELETE CASCADE,viewer_id VARCHAR(32) NOT NULL REFERENCES os_user(user_id) ON DELETE CASCADE,captured_at BIGINT NOT NULL,definition_hash VARCHAR(64) NOT NULL,result TEXT NOT NULL)");
            db.update("INSERT INTO os_migration(version,name) VALUES(51,?)","retained numeric report measurements");
        }
        if(version<52){
            db.update("CREATE TABLE IF NOT EXISTS os_problem_link(incident_id VARCHAR(32) PRIMARY KEY REFERENCES os_record(sys_id) ON DELETE CASCADE,problem_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id) ON DELETE CASCADE)");
            db.update("CREATE TABLE IF NOT EXISTS os_problem_analysis(problem_id VARCHAR(32) PRIMARY KEY REFERENCES os_record(sys_id) ON DELETE CASCADE,revision INTEGER NOT NULL,cause TEXT NOT NULL,workaround TEXT NOT NULL,published INTEGER NOT NULL)");
            db.update("INSERT INTO os_migration(version,name) VALUES(52,?)","incident-linked problem investigation and workaround");
        }
        if(version<53){
            db.update("CREATE TABLE IF NOT EXISTS os_change_plan(change_id VARCHAR(32) PRIMARY KEY REFERENCES os_record(sys_id) ON DELETE CASCADE,revision INTEGER NOT NULL,detail TEXT NOT NULL)");
            db.update("INSERT INTO os_migration(version,name) VALUES(53,?)","reviewed change planning and readiness");
        }
        if(version<54){
            db.update("CREATE TABLE IF NOT EXISTS os_record_scope(record_id VARCHAR(32) PRIMARY KEY REFERENCES os_record(sys_id) ON DELETE CASCADE,parent_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id),revision INTEGER NOT NULL)");
            db.update("CREATE INDEX IF NOT EXISTS ix_os_record_scope_parent ON os_record_scope(parent_id)");
            db.update("INSERT INTO os_migration(version,name) VALUES(54,?)","live parent permission boundaries for linked records");
        }
        if(version<55){
            db.update("CREATE TABLE IF NOT EXISTS os_task_plan(parent_id VARCHAR(32) PRIMARY KEY REFERENCES os_record(sys_id) ON DELETE CASCADE,revision INTEGER NOT NULL,close_at VARCHAR(80) NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_case_task(task_id VARCHAR(32) PRIMARY KEY,parent_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,summary VARCHAR(160) NOT NULL,details TEXT NOT NULL,group_id VARCHAR(32) NOT NULL,assignee_id VARCHAR(32) NOT NULL,state VARCHAR(20) NOT NULL,required INTEGER NOT NULL,predecessors TEXT NOT NULL,outcome TEXT NOT NULL,note TEXT NOT NULL,revision INTEGER NOT NULL,UNIQUE(parent_id,ordinal))");
            db.update("CREATE INDEX IF NOT EXISTS ix_os_case_task_group ON os_case_task(group_id,state)");
            db.update("INSERT INTO os_migration(version,name) VALUES(55,?)","dependent team work and parent completion gates");
        }
        if(version<56){
            db.update("CREATE TABLE IF NOT EXISTS os_catalog_workflow(offering_id VARCHAR(32) PRIMARY KEY REFERENCES os_offering(offering_id) ON DELETE CASCADE,enabled INTEGER NOT NULL,steps TEXT NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_catalog_workflow_order(request_id VARCHAR(32) PRIMARY KEY REFERENCES os_record(sys_id) ON DELETE CASCADE,offering_id VARCHAR(32) NOT NULL,offering_revision INTEGER NOT NULL,steps TEXT NOT NULL)");
            db.update("INSERT INTO os_migration(version,name) VALUES(56,?)","reviewed catalogue plans and multi-team fulfilment");
        }
        if(version<57){
            db.update("CREATE TABLE IF NOT EXISTS os_change_window(window_id VARCHAR(32) PRIMARY KEY,ci_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id) ON DELETE CASCADE,name VARCHAR(120) NOT NULL,kind VARCHAR(12) NOT NULL,calendar_id VARCHAR(80) NOT NULL,active INTEGER NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE INDEX IF NOT EXISTS ix_os_change_window_ci ON os_change_window(ci_id)");
            db.update("CREATE TABLE IF NOT EXISTS os_change_schedule(change_id VARCHAR(32) PRIMARY KEY REFERENCES os_record(sys_id) ON DELETE CASCADE,ci_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id),starts BIGINT NOT NULL,ends BIGINT NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE INDEX IF NOT EXISTS ix_os_change_schedule_ci ON os_change_schedule(ci_id,starts,ends)");
            db.update("INSERT INTO os_migration(version,name) VALUES(57,?)","change reservations with current maintenance and blackout windows");
        }
        if(version<58){
            db.update("CREATE TABLE IF NOT EXISTS os_major_incident(incident_id VARCHAR(32) PRIMARY KEY REFERENCES os_record(sys_id) ON DELETE CASCADE,revision INTEGER NOT NULL,status VARCHAR(20) NOT NULL,commander_id VARCHAR(32) NOT NULL,proposer_id VARCHAR(32) NOT NULL,proposal TEXT NOT NULL,decision TEXT NOT NULL,impact TEXT NOT NULL,restoration TEXT NOT NULL,review_cause TEXT NOT NULL,review_lessons TEXT NOT NULL,review_actions TEXT NOT NULL,created VARCHAR(40) NOT NULL,updated VARCHAR(40) NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_major_link(child_id VARCHAR(32) PRIMARY KEY REFERENCES os_record(sys_id),major_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id))");
            db.update("CREATE INDEX IF NOT EXISTS ix_os_major_link_root ON os_major_link(major_id)");
            db.update("CREATE TABLE IF NOT EXISTS os_major_update(update_id VARCHAR(32) PRIMARY KEY,parent_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id) ON DELETE CASCADE,message TEXT NOT NULL,actor_id VARCHAR(32) NOT NULL,ordinal INTEGER NOT NULL,created VARCHAR(40) NOT NULL,UNIQUE(parent_id,ordinal))");
            db.update("INSERT INTO os_migration(version,name) VALUES(58,?)","major incident command, restoration and post-incident review");
        }
        if(version<59){
            db.update("CREATE TABLE IF NOT EXISTS os_customer_account(account_id VARCHAR(32) PRIMARY KEY,name VARCHAR(160) NOT NULL,active INTEGER NOT NULL,group_id VARCHAR(32) NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_customer_contact(user_id VARCHAR(32) PRIMARY KEY REFERENCES os_user(user_id) ON DELETE CASCADE,account_id VARCHAR(32) NOT NULL REFERENCES os_customer_account(account_id),active INTEGER NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE INDEX IF NOT EXISTS ix_os_customer_contact_account ON os_customer_contact(account_id)");
            db.update("CREATE TABLE IF NOT EXISTS os_customer_entitlement(entitlement_id VARCHAR(32) PRIMARY KEY,account_id VARCHAR(32) NOT NULL REFERENCES os_customer_account(account_id),name VARCHAR(160) NOT NULL,description TEXT NOT NULL,active INTEGER NOT NULL,starts BIGINT NOT NULL,ends BIGINT NOT NULL,case_limit INTEGER NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE INDEX IF NOT EXISTS ix_os_customer_entitlement_account ON os_customer_entitlement(account_id)");
            db.update("CREATE TABLE IF NOT EXISTS os_customer_case(record_id VARCHAR(32) PRIMARY KEY,account_id VARCHAR(32) NOT NULL REFERENCES os_customer_account(account_id),user_id VARCHAR(32) NOT NULL,entitlement_id VARCHAR(32) NOT NULL REFERENCES os_customer_entitlement(entitlement_id),account_revision INTEGER NOT NULL,contact_revision INTEGER NOT NULL,entitlement_revision INTEGER NOT NULL,account_name VARCHAR(160) NOT NULL,entitlement_name VARCHAR(160) NOT NULL,intake_key VARCHAR(32) NOT NULL,fingerprint VARCHAR(64) NOT NULL,created VARCHAR(40) NOT NULL,UNIQUE(user_id,intake_key))");
            db.update("CREATE INDEX IF NOT EXISTS ix_os_customer_case_entitlement ON os_customer_case(entitlement_id)");
            db.update("INSERT INTO os_migration(version,name) VALUES(59,?)","current customer accounts, contacts and entitled case intake");
        }

        if(version<60){
            db.update("CREATE TABLE IF NOT EXISTS os_customer_delivery(user_id VARCHAR(32) PRIMARY KEY REFERENCES os_user(user_id) ON DELETE CASCADE,enabled INTEGER NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_customer_reply(reply_id VARCHAR(32) PRIMARY KEY,record_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id) ON DELETE CASCADE,actor_id VARCHAR(32) NOT NULL,actor_name VARCHAR(80) NOT NULL,ordinal INTEGER NOT NULL,body TEXT NOT NULL,created VARCHAR(40) NOT NULL,submission_key VARCHAR(32) NOT NULL,fingerprint VARCHAR(64) NOT NULL,job_id VARCHAR(32) NOT NULL,UNIQUE(record_id,actor_id,submission_key),UNIQUE(record_id,ordinal))");
            db.update("CREATE INDEX IF NOT EXISTS ix_os_customer_reply_record ON os_customer_reply(record_id)");
            db.update("INSERT INTO os_migration(version,name) VALUES(60,?)","published customer replies and current email preferences");
        }

        if(version<61){
            db.update("CREATE TABLE IF NOT EXISTS os_customer_resolution(resolution_id VARCHAR(32) PRIMARY KEY,record_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,revision INTEGER NOT NULL,status VARCHAR(16) NOT NULL,code VARCHAR(12) NOT NULL,solution TEXT NOT NULL,proposer_name VARCHAR(80) NOT NULL,reason TEXT NOT NULL,created VARCHAR(40) NOT NULL,decided VARCHAR(40) NOT NULL,snapshot VARCHAR(64) NOT NULL,reopened INTEGER NOT NULL DEFAULT 0,UNIQUE(record_id,ordinal))");
            db.update("INSERT INTO os_migration(version,name) VALUES(61,?)","reviewed customer solution decisions and guarded reopening");
        }

        if(version<62){
            db.update("CREATE TABLE IF NOT EXISTS os_field_availability(user_id VARCHAR(32) PRIMARY KEY REFERENCES os_user(user_id) ON DELETE CASCADE,calendar_id VARCHAR(80) NOT NULL,active INTEGER NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_field_dispatch(task_id VARCHAR(32) PRIMARY KEY REFERENCES os_case_task(task_id) ON DELETE CASCADE,technician_id VARCHAR(32) NOT NULL,starts BIGINT NOT NULL,ends BIGINT NOT NULL,location VARCHAR(240) NOT NULL,status VARCHAR(16) NOT NULL,reason TEXT NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE INDEX IF NOT EXISTS ix_os_field_dispatch_time ON os_field_dispatch(technician_id,status,starts,ends)");
            db.update("INSERT INTO os_migration(version,name) VALUES(62,?)","calendar-aware field task dispatch and technician decisions");
        }

        if(version<63){
            db.update("CREATE TABLE IF NOT EXISTS os_field_visit(task_id VARCHAR(32) PRIMARY KEY REFERENCES os_case_task(task_id) ON DELETE CASCADE,revision INTEGER NOT NULL,phase VARCHAR(12) NOT NULL,checklist TEXT NOT NULL,answers TEXT NOT NULL,parts TEXT NOT NULL,evidence TEXT NOT NULL,notes TEXT NOT NULL,travelled BIGINT NOT NULL,arrived BIGINT NOT NULL,ended BIGINT NOT NULL,evidence_required INTEGER NOT NULL)");
            db.update("INSERT INTO os_migration(version,name) VALUES(63,?)","field visit execution checks part usage and evidence receipts");
        }

        if(version<64){
            db.update("CREATE TABLE IF NOT EXISTS os_employee(user_id VARCHAR(32) PRIMARY KEY,department VARCHAR(80) NOT NULL,active INTEGER NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_hr_service(service_id VARCHAR(32) PRIMARY KEY,name VARCHAR(160) NOT NULL,description TEXT NOT NULL,department VARCHAR(80) NOT NULL,group_id VARCHAR(32) NOT NULL,active INTEGER NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_hr_case(record_id VARCHAR(32) PRIMARY KEY,service_id VARCHAR(32) NOT NULL REFERENCES os_hr_service(service_id),user_id VARCHAR(32) NOT NULL,employee_revision INTEGER NOT NULL,service_revision INTEGER NOT NULL,intake_key VARCHAR(32) NOT NULL,fingerprint VARCHAR(64) NOT NULL,created VARCHAR(40) NOT NULL,UNIQUE(user_id,intake_key))");
            db.update("CREATE TABLE IF NOT EXISTS os_hr_reply(reply_id VARCHAR(32) PRIMARY KEY,record_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id) ON DELETE CASCADE,actor_id VARCHAR(32) NOT NULL,actor_name VARCHAR(80) NOT NULL,ordinal INTEGER NOT NULL,body TEXT NOT NULL,created VARCHAR(40) NOT NULL,submission_key VARCHAR(32) NOT NULL,fingerprint VARCHAR(64) NOT NULL,UNIQUE(record_id,actor_id,submission_key),UNIQUE(record_id,ordinal))");
            db.update("INSERT INTO os_migration(version,name) VALUES(64,?)","private employee service intake and public case follow-up");
        }

        if(version<65){
            db.update("CREATE TABLE IF NOT EXISTS os_hr_onboarding(parent_id VARCHAR(32) PRIMARY KEY,revision INTEGER NOT NULL,launched INTEGER NOT NULL,steps TEXT NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_hr_onboarding_work(child_id VARCHAR(32) PRIMARY KEY,parent_id VARCHAR(32) NOT NULL,task_id VARCHAR(32) NOT NULL UNIQUE,ordinal INTEGER NOT NULL,group_id VARCHAR(32) NOT NULL,display_title VARCHAR(160) NOT NULL,status VARCHAR(16) NOT NULL,reason TEXT NOT NULL,updated VARCHAR(40) NOT NULL,UNIQUE(parent_id,ordinal))");
            db.update("INSERT INTO os_migration(version,name) VALUES(65,?)","reviewed department onboarding and private HR progress");
        }

        if(version<66){
            db.update("CREATE TABLE IF NOT EXISTS os_risk_assessment(record_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id) ON DELETE CASCADE,revision INTEGER NOT NULL,detail TEXT NOT NULL,PRIMARY KEY(record_id,revision))");
            db.update("INSERT INTO os_migration(version,name) VALUES(66,?)","native risk assessment history and reviewed treatment");
        }

        if(version<67){
            db.update("CREATE TABLE IF NOT EXISTS os_security_team(group_id VARCHAR(32) PRIMARY KEY,active INTEGER NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_security_case(record_id VARCHAR(32) PRIMARY KEY REFERENCES os_record(sys_id) ON DELETE CASCADE,group_id VARCHAR(32) NOT NULL,actor_id VARCHAR(32) NOT NULL,intake_key VARCHAR(32) NOT NULL,fingerprint VARCHAR(64) NOT NULL,analysis_epoch INTEGER NOT NULL DEFAULT 0,UNIQUE(actor_id,intake_key))");
            db.update("CREATE TABLE IF NOT EXISTS os_security_document(record_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id) ON DELETE CASCADE,revision INTEGER NOT NULL,detail TEXT NOT NULL,PRIMARY KEY(record_id,revision))");
            db.update("INSERT INTO os_migration(version,name) VALUES(67,?)","restricted native security investigations and containment");
        }

        if(version<68){
            db.update("CREATE TABLE IF NOT EXISTS os_vulnerability_scan(report_id VARCHAR(32) PRIMARY KEY,alias VARCHAR(80) NOT NULL,scan_id VARCHAR(160) NOT NULL,origin TEXT NOT NULL,path TEXT NOT NULL,source_revision INTEGER NOT NULL,group_id VARCHAR(32) NOT NULL,sha256 VARCHAR(64) NOT NULL,observed_at TEXT NOT NULL,imported_at TEXT NOT NULL,actor_id VARCHAR(32) NOT NULL,findings INTEGER NOT NULL,UNIQUE(alias,scan_id))");
            db.update("CREATE TABLE IF NOT EXISTS os_vulnerability(record_id VARCHAR(32) PRIMARY KEY REFERENCES os_record(sys_id) ON DELETE CASCADE,alias VARCHAR(80) NOT NULL,origin TEXT NOT NULL,path TEXT NOT NULL,asset_id VARCHAR(32) NOT NULL,vulnerability VARCHAR(160) NOT NULL,group_id VARCHAR(32) NOT NULL,verify_revision INTEGER NOT NULL,verify_at TEXT NOT NULL,task_boundary INTEGER NOT NULL,UNIQUE(alias,asset_id,vulnerability))");
            db.update("CREATE TABLE IF NOT EXISTS os_vulnerability_observation(record_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id) ON DELETE CASCADE,revision INTEGER NOT NULL,report_id VARCHAR(32) NOT NULL REFERENCES os_vulnerability_scan(report_id),observed_at TEXT NOT NULL,detail TEXT NOT NULL,PRIMARY KEY(record_id,revision))");
            db.update("INSERT INTO os_migration(version,name) VALUES(68,?)","asset-scoped vulnerability observations and remediation verification");
        }

        if(version<69){
            db.update("CREATE TABLE IF NOT EXISTS os_demand_assessment(record_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id) ON DELETE CASCADE,revision INTEGER NOT NULL,portfolio_id VARCHAR(32) NOT NULL,planning_year INTEGER NOT NULL,score INTEGER NOT NULL,detail TEXT NOT NULL,PRIMARY KEY(record_id,revision))");
            db.update("CREATE INDEX IF NOT EXISTS ix_os_demand_portfolio_year ON os_demand_assessment(portfolio_id,planning_year,score)");
            db.update("INSERT INTO os_migration(version,name) VALUES(69,?)","scored portfolio demands and retained reviewed decisions");
        }

        if(version<70){
            db.update("CREATE TABLE IF NOT EXISTS os_project_origin(project_id VARCHAR(32) PRIMARY KEY REFERENCES os_record(sys_id) ON DELETE CASCADE,demand_id VARCHAR(32) NOT NULL UNIQUE REFERENCES os_record(sys_id),demand_revision INTEGER NOT NULL,assessment_revision INTEGER NOT NULL,actor_id VARCHAR(32) NOT NULL,created TEXT NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_project_schedule(task_id VARCHAR(32) NOT NULL REFERENCES os_case_task(task_id) ON DELETE CASCADE,revision INTEGER NOT NULL,detail TEXT NOT NULL,PRIMARY KEY(task_id,revision))");
            db.update("INSERT INTO os_migration(version,name) VALUES(70,?)","demand delivery projects with reviewed task and milestone schedules");
        }

        if(version<71){
            db.update("CREATE TABLE IF NOT EXISTS os_asset_identity(record_id VARCHAR(32) PRIMARY KEY REFERENCES os_record(sys_id),asset_tag VARCHAR(80) NOT NULL UNIQUE,ci_id VARCHAR(32) NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_asset_movement(record_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id),revision INTEGER NOT NULL,state VARCHAR(20) NOT NULL,stockroom_id VARCHAR(32) NOT NULL,assignee_id VARCHAR(32) NOT NULL,detail TEXT NOT NULL,PRIMARY KEY(record_id,revision))");
            db.update("CREATE INDEX IF NOT EXISTS ix_asset_custody_stock ON os_asset_movement(stockroom_id,state)");
            db.update("INSERT INTO os_migration(version,name) VALUES(71,?)","distinct asset identity and retained inventory custody movements");
        }

        if(version<72){
            db.update("CREATE TABLE IF NOT EXISTS os_inbound_mailbox(mailbox_id VARCHAR(32) PRIMARY KEY,address VARCHAR(254) NOT NULL UNIQUE,sender_domain VARCHAR(253) NOT NULL,gateway_id VARCHAR(32) NOT NULL,active INTEGER NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_inbound_mail_thread(thread_id VARCHAR(32) PRIMARY KEY,mailbox_id VARCHAR(32) NOT NULL REFERENCES os_inbound_mailbox(mailbox_id),sender_id VARCHAR(32) NOT NULL,record_id VARCHAR(32) NOT NULL,created TEXT NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_inbound_mail_receipt(mailbox_id VARCHAR(32) NOT NULL REFERENCES os_inbound_mailbox(mailbox_id),delivery_id VARCHAR(160) NOT NULL,fingerprint VARCHAR(64) NOT NULL,sender_id VARCHAR(32) NOT NULL,record_id VARCHAR(32) NOT NULL,thread_id VARCHAR(32) NOT NULL REFERENCES os_inbound_mail_thread(thread_id),gateway_id VARCHAR(32) NOT NULL,mailbox_revision INTEGER NOT NULL,created TEXT NOT NULL,PRIMARY KEY(mailbox_id,delivery_id))");
            db.update("CREATE INDEX IF NOT EXISTS ix_mail_receipt_thread ON os_inbound_mail_receipt(thread_id)");
            db.update("INSERT INTO os_migration(version,name) VALUES(72,?)","authenticated inbound mailboxes and owned request reply threads");
        }

        if(version<73){
            db.update("CREATE TABLE IF NOT EXISTS os_ai_triage(run_id VARCHAR(32) PRIMARY KEY,owner_id VARCHAR(32) NOT NULL,record_id VARCHAR(32) NOT NULL,record_revision INTEGER NOT NULL,source_fields TEXT NOT NULL,envelope TEXT NOT NULL,state VARCHAR(20) NOT NULL,created_at BIGINT NOT NULL,action_fingerprint VARCHAR(64) NOT NULL)");
            db.update("CREATE INDEX IF NOT EXISTS ix_ai_triage_owner ON os_ai_triage(owner_id,record_id)");
            db.update("INSERT INTO os_migration(version,name) VALUES(73,?)","reviewed incident AI triage and retained execution receipts");
        }

        if(version<74){
            db.update("CREATE TABLE IF NOT EXISTS os_discovery_source(source_id VARCHAR(32) PRIMARY KEY,name VARCHAR(160) NOT NULL,alias VARCHAR(80) NOT NULL,path TEXT NOT NULL,priority INTEGER NOT NULL,attributes TEXT NOT NULL,allow_create INTEGER NOT NULL,adopt_existing INTEGER NOT NULL,active INTEGER NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_discovery_identity(record_id VARCHAR(32) PRIMARY KEY REFERENCES os_record(sys_id),serial_number VARCHAR(160) NOT NULL UNIQUE)");
            db.update("CREATE TABLE IF NOT EXISTS os_discovery_report(report_id VARCHAR(32) PRIMARY KEY,source_id VARCHAR(32) NOT NULL REFERENCES os_discovery_source(source_id),external_id VARCHAR(160) NOT NULL,sha256 VARCHAR(64) NOT NULL,provenance_hash VARCHAR(64) NOT NULL,observed_at TEXT NOT NULL,source_revision INTEGER NOT NULL,created TEXT NOT NULL,actor_id VARCHAR(32) NOT NULL,detail TEXT NOT NULL,UNIQUE(source_id,external_id))");
            db.update("CREATE TABLE IF NOT EXISTS os_discovery_field(record_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id),field VARCHAR(80) NOT NULL,source_id VARCHAR(32) NOT NULL REFERENCES os_discovery_source(source_id),priority INTEGER NOT NULL,observed_at TEXT NOT NULL,value_hash VARCHAR(64) NOT NULL,report_id VARCHAR(32) NOT NULL,released INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(record_id,field))");
            db.update("INSERT INTO os_migration(version,name) VALUES(74,?)","native CI discovery identity and per-field source reconciliation");
        }

        if(version<75){
            db.update("CREATE TABLE IF NOT EXISTS os_relation_type(type_id VARCHAR(32) PRIMARY KEY,source_table VARCHAR(80) NOT NULL,target_table VARCHAR(80) NOT NULL,forward_label VARCHAR(80) NOT NULL,inverse_label VARCHAR(80) NOT NULL,propagates INTEGER NOT NULL,active INTEGER NOT NULL,revision INTEGER NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_typed_relation(relation_id VARCHAR(32) PRIMARY KEY,type_id VARCHAR(32) NOT NULL REFERENCES os_relation_type(type_id),source_id VARCHAR(32) NOT NULL,target_id VARCHAR(32) NOT NULL,active INTEGER NOT NULL,revision INTEGER NOT NULL,updated TEXT NOT NULL,UNIQUE(type_id,source_id,target_id))");
            db.update("CREATE INDEX IF NOT EXISTS ix_typed_relation_target ON os_typed_relation(target_id,active)");
            db.update("CREATE TABLE IF NOT EXISTS os_relation_history(relation_id VARCHAR(32) NOT NULL REFERENCES os_typed_relation(relation_id),revision INTEGER NOT NULL,detail TEXT NOT NULL,PRIMARY KEY(relation_id,revision))");
            db.update("INSERT INTO os_migration(version,name) VALUES(75,?)","governed typed record relationships with retained reviewed changes");
        }

        if(version<76){
            db.update("CREATE TABLE IF NOT EXISTS os_ci_outage(outage_id VARCHAR(32) PRIMARY KEY,record_id VARCHAR(32) NOT NULL REFERENCES os_record(sys_id),request_id VARCHAR(32) NOT NULL,fingerprint VARCHAR(64) NOT NULL,kind VARCHAR(20) NOT NULL,summary VARCHAR(160) NOT NULL,begin_ms BIGINT NOT NULL,end_ms BIGINT NOT NULL,cancelled INTEGER NOT NULL,revision INTEGER NOT NULL,UNIQUE(record_id,request_id))");
            db.update("CREATE TABLE IF NOT EXISTS os_outage_history(outage_id VARCHAR(32) NOT NULL REFERENCES os_ci_outage(outage_id),revision INTEGER NOT NULL,detail TEXT NOT NULL,PRIMARY KEY(outage_id,revision))");
            db.update("INSERT INTO os_migration(version,name) VALUES(76,?)","retained CI outage evidence and exact-window availability");
        }

        if(version<77){
            db.update("CREATE TABLE IF NOT EXISTS os_sla_budget(sla_id VARCHAR(32) PRIMARY KEY REFERENCES os_sla(sla_id) ON DELETE CASCADE,budget_ms BIGINT NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_sla_escalation(sla_id VARCHAR(32) PRIMARY KEY REFERENCES os_sla(sla_id) ON DELETE CASCADE,definition TEXT NOT NULL,owner_id VARCHAR(32) NOT NULL,created_at BIGINT NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_sla_threshold(sla_id VARCHAR(32) NOT NULL REFERENCES os_sla(sla_id) ON DELETE CASCADE,percent INTEGER NOT NULL,detail TEXT NOT NULL,PRIMARY KEY(sla_id,percent))");
            db.update("INSERT INTO os_migration(version,name) VALUES(77,?)","known original SLA budgets and current-recipient warning receipts");
        }

        if(version<78){
            db.update("CREATE TABLE IF NOT EXISTS os_clock_policy(policy_id VARCHAR(32) PRIMARY KEY,name VARCHAR(160) NOT NULL,table_name VARCHAR(80) NOT NULL,definition TEXT NOT NULL,active INTEGER NOT NULL,revision INTEGER NOT NULL,owner_id VARCHAR(32) NOT NULL)");
            db.update("CREATE TABLE IF NOT EXISTS os_clock_binding(policy_id VARCHAR(32) NOT NULL REFERENCES os_clock_policy(policy_id),record_id VARCHAR(32) NOT NULL,sla_id VARCHAR(32) NOT NULL,definition TEXT NOT NULL,revision INTEGER NOT NULL,last_outcome VARCHAR(40) NOT NULL,PRIMARY KEY(policy_id,record_id))");
            db.update("CREATE TABLE IF NOT EXISTS os_clock_transition(policy_id VARCHAR(32) NOT NULL,record_id VARCHAR(32) NOT NULL,revision INTEGER NOT NULL,detail TEXT NOT NULL,PRIMARY KEY(policy_id,record_id,revision),FOREIGN KEY(policy_id,record_id) REFERENCES os_clock_binding(policy_id,record_id))");
            db.update("INSERT INTO os_migration(version,name) VALUES(78,?)","reusable state-based service clock policies with retained transitions");
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
