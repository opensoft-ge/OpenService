#!/usr/bin/env bash
# Disposable PostgreSQL backup/restore acceptance. Never targets an existing deployment.
set -euo pipefail
cd "$(dirname "$0")/.."
drill_image=${1:?Usage: bash scripts/restore-drill.sh LOCAL_APPLICATION_IMAGE}
drill_source_image=${2:-$drill_image}
for dependency in docker curl jq openssl; do command -v "$dependency" >/dev/null; done
drill_prefix="openservice-restore-$(date +%s)-$$"
drill_directory=$(mktemp -d)
drill_password=$(openssl rand -hex 24)
drill_secret=$(openssl rand -hex 32)
drill_started=$(date +%s)
cleanup() {
    docker rm -f "$drill_prefix-source-app" "$drill_prefix-restored-app" "$drill_prefix-source-db" "$drill_prefix-restored-db" >/dev/null 2>&1 || true
    docker volume rm "$drill_prefix-source-data" "$drill_prefix-restored-data" >/dev/null 2>&1 || true
    docker network rm "$drill_prefix" >/dev/null 2>&1 || true
    rm -rf "$drill_directory"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
drill_image=$(docker image inspect "$drill_image" --format '{{.Id}}')
drill_source_image=$(docker image inspect "$drill_source_image" --format '{{.Id}}')
if ! docker image inspect postgres:17-alpine >/dev/null 2>&1; then docker pull postgres:17-alpine >/dev/null; fi
drill_database_image=$(docker image inspect postgres:17-alpine --format '{{.Id}}')
docker network create "$drill_prefix" >/dev/null
start_database() {
    drill_side=$1
    docker volume create "$drill_prefix-$drill_side-data" >/dev/null
    docker run -d --name "$drill_prefix-$drill_side-db" --network "$drill_prefix" \
        -e POSTGRES_USER=openservice -e POSTGRES_DB=openservice -e POSTGRES_PASSWORD="$drill_password" \
        -v "$drill_prefix-$drill_side-data:/var/lib/postgresql/data" "$drill_database_image" >/dev/null
    drill_attempt=0
    until docker exec "$drill_prefix-$drill_side-db" pg_isready -h 127.0.0.1 -U openservice >/dev/null 2>&1; do
        drill_attempt=$((drill_attempt + 1))
        [ "$drill_attempt" -lt 90 ] || { echo 'Database readiness timed out' >&2; exit 1; }
        sleep 1
    done
}
start_application() {
    drill_side=$1
    drill_application_image=$drill_image
    drill_delivery=false
    if [ "$drill_side" = source ]; then
        drill_application_image=$drill_source_image
        # Loopback port 1 deliberately refuses SMTP; queued work must survive restore.
        drill_delivery=true
    fi
    docker run -d --name "$drill_prefix-$drill_side-app" --network "$drill_prefix" \
        -p 127.0.0.1::8080 -e ADMIN_PASSWORD="$drill_password" -e SECRET_KEY="$drill_secret" \
        -e DATABASE_URL="postgresql://openservice:$drill_password@$drill_prefix-$drill_side-db:5432/openservice" \
        -e DELIVERY_ENABLED="$drill_delivery" -e PUBLIC_BASE_URL=https://example.invalid \
        -e CONNECTOR_ALLOWED_ORIGINS=https://example.invalid \
        -e MAIL_HOST=127.0.0.1 -e MAIL_PORT=1 -e MAIL_FROM=accounts@example.invalid \
        "$drill_application_image" >/dev/null
    drill_address="http://$(docker port "$drill_prefix-$drill_side-app" 8080/tcp)"
    drill_attempt=0
    until curl --max-time 3 -fsS "$drill_address/healthz" >/dev/null 2>&1; do
        drill_attempt=$((drill_attempt + 1))
        [ "$drill_attempt" -lt 90 ] || { echo 'Application readiness timed out' >&2; exit 1; }
        sleep 1
    done
    [ "$(docker exec "$drill_prefix-$drill_side-app" id -u)" = 10001 ]
}
api() { curl --max-time 30 -fsS --user "admin:$drill_password" "$@"; }
snapshot() {
    api "$drill_address/api/openservice/configuration" | jq -S . > "$drill_directory/$1-configuration.json"
    api "$drill_address/api/now/table/u_service_case/$drill_case" | jq -S . > "$drill_directory/$1-record.json"
    api "$drill_address/api/openservice/security-events" | jq -S . > "$drill_directory/$1-security.json"
    api "$drill_address/api/openservice/analytics/u_service_case?field=estimate" | jq -S . > "$drill_directory/$1-metrics.json"
    api "$drill_address/api/now/attachment/$drill_attachment/file" > "$drill_directory/$1-attachment.bin"
}
start_database source
start_application source
drill_source_version=$(docker exec "$drill_prefix-source-db" psql -At -U openservice -d openservice -c 'SELECT MAX(version) FROM os_migration')
api -X POST -H 'Content-Type: application/json' --data-binary @examples/service-operations.configuration.json \
    "$drill_address/api/openservice/configuration?dry_run=false" | jq -e '.result.dry_run == false' >/dev/null
jq -n --arg password "$drill_password" '{name:"RestoreReader",password:$password,role:"reader"}' > "$drill_directory/account.json"
api -X POST -H 'Content-Type: application/json' --data-binary "@$drill_directory/account.json" \
    "$drill_address/api/openservice/users" | jq -e '.result.name == "RestoreReader"' >/dev/null
drill_asset=$(api -X POST -H 'Content-Type: application/json' \
    --data '{"short_description":"Restore asset","serial_number":"restore-001"}' \
    "$drill_address/api/now/table/u_service_asset" | jq -er '.result.sys_id')
jq -n --arg asset "$drill_asset" '{short_description:"Restore case",asset:$asset,estimate:"0.10"}' > "$drill_directory/case.json"
drill_case=$(api -X POST -H 'Content-Type: application/json' --data-binary "@$drill_directory/case.json" \
    "$drill_address/api/now/table/u_service_case" | jq -er '.result.sys_id')
drill_admin=$(api "$drill_address/api/openservice/me" | jq -er '.result.user_id')
jq -n --arg owner "$drill_admin" '{visibility:"private",owner_id:$owner}' > "$drill_directory/access.json"
api -X PATCH -H 'Content-Type: application/json' --data-binary "@$drill_directory/access.json" \
    "$drill_address/api/openservice/access/u_service_case/$drill_case" >/dev/null
printf '\000\001OpenService restore bytes\377\n' > "$drill_directory/payload.bin"
drill_attachment=$(api -X POST -H 'Content-Type: application/octet-stream' --data-binary "@$drill_directory/payload.bin" \
    "$drill_address/api/now/attachment/file?table_name=u_service_case&table_sys_id=$drill_case&file_name=restore.bin" | jq -er '.result.sys_id')
if [ "$drill_source_version" -ge 14 ]; then
    jq -n --arg password "$drill_password" '{name:"RestoreRequester",password:$password,role:"requester"}' > "$drill_directory/requester.json"
    api -X POST -H 'Content-Type: application/json' --data-binary "@$drill_directory/requester.json" "$drill_address/api/openservice/users" >/dev/null
    drill_request=$(curl --max-time 30 -fsS --user "RestoreRequester:$drill_password" -X POST -H 'Content-Type: application/json' \
        --data '{"short_description":"Restored own request"}' "$drill_address/api/openservice/requests" | jq -er '.result.sys_id')
    api -X POST -H 'Content-Type: application/json' --data '{"name":"RestoreInvite","role":"reader","email":"restore@example.invalid"}' \
        "$drill_address/api/openservice/email-invitations" | jq -e '.result.delivery_id != null and .result.accept_path == null' >/dev/null
fi
if [ "$drill_source_version" -ge 15 ]; then
    jq -n --arg password "$drill_password" '{email:"enrollment@example.invalid",current_password:$password}' > "$drill_directory/enrollment.json"
    api -X POST -H 'Content-Type: application/json' --data-binary "@$drill_directory/enrollment.json" \
        "$drill_address/api/openservice/me/email" | jq -e '.issued.delivery_id != null' >/dev/null
fi
if [ "$drill_source_version" -ge 16 ]; then
    api -X POST -H 'Content-Type: application/json' \
        --data '{"label":"Restore example","password":"restore-unsafe-password"}' \
        "$drill_address/api/openservice/password-exclusions" | jq -e '.result.exclusion_id != null' >/dev/null
fi
if [ "$drill_source_version" -ge 17 ]; then
    jq -n --arg password "$drill_password" '{name:"RestoreRequired",password:$password,role:"reader"}' > "$drill_directory/required.json"
    api -X POST -H 'Content-Type: application/json' --data-binary "@$drill_directory/required.json" "$drill_address/api/openservice/users" >/dev/null
    drill_required=$(docker exec "$drill_prefix-source-db" psql -At -U openservice -d openservice -c "SELECT user_id FROM os_user WHERE name='RestoreRequired'")
    api -X POST -H 'Content-Type: application/json' --data '{}' \
        "$drill_address/api/openservice/users/$drill_required/require-password-change" | jq -e '.result.required == true' >/dev/null
fi
if [ "$drill_source_version" -ge 18 ]; then
    jq -n --arg password "$drill_password" '{name:"RestoreApiOnly",password:$password,role:"reader"}' > "$drill_directory/api-only.json"
    api -X POST -H 'Content-Type: application/json' --data-binary "@$drill_directory/api-only.json" "$drill_address/api/openservice/users" >/dev/null
    drill_api_only=$(docker exec "$drill_prefix-source-db" psql -At -U openservice -d openservice -c "SELECT user_id FROM os_user WHERE name='RestoreApiOnly'")
    api -X POST -H 'Content-Type: application/json' --data '{"api_only":true}' \
        "$drill_address/api/openservice/users/$drill_api_only/access-mode" | jq -e '.result.api_only == true' >/dev/null
fi
if [ "$drill_source_version" -ge 19 ]; then
    jq -n --arg password "$drill_password" '{name:"RestoreReviewer",password:$password,role:"agent"}' > "$drill_directory/reviewer.json"
    api -X POST -H 'Content-Type: application/json' --data-binary "@$drill_directory/reviewer.json" "$drill_address/api/openservice/users" >/dev/null
    drill_review_record=$(api -X POST -H 'Content-Type: application/json' --data '{"short_description":"Restore approval"}' "$drill_address/api/now/table/incident" | jq -er '.result.sys_id')
    drill_approval=$(api -X POST -H 'Content-Type: application/json' --data '{"expected_revision":0,"approver":"RestoreReviewer","target_state":"2","reason":"Review after recovery"}' "$drill_address/api/openservice/records/incident/$drill_review_record/approvals" | jq -er '.result.approval_id')
    if [ "$drill_source_version" -ge 20 ]; then
        api -X POST -H 'Content-Type: application/json' --data '{"comment":"Keep history after restore"}' \
            "$drill_address/api/openservice/records/incident/$drill_review_record/approvals/$drill_approval/withdraw" | jq -e '.result.status == "withdrawn"' >/dev/null
        drill_approval=$(api -X POST -H 'Content-Type: application/json' --data '{"expected_revision":0,"approver":"RestoreReviewer","target_state":"2","reason":"Fresh review after withdrawal"}' "$drill_address/api/openservice/records/incident/$drill_review_record/approvals" | jq -er '.result.approval_id')
    fi
fi
if [ "$drill_source_version" -ge 33 ]; then
    api -X POST -H 'Content-Type: application/json' --data '{"kind":"notification","key":"restore-work","payload":{"username":"RestoreReader","message":"Queued through recovery"}}' \
        "$drill_address/api/openservice/operations/jobs" | jq -e '.result.job_id != null' >/dev/null
    api -X POST -H 'Content-Type: application/json' --data '{"alias":"restore","origin":"https://example.invalid","token":"synthetic-restore-token"}' \
        "$drill_address/api/openservice/operations/connectors" >/dev/null
    api -X POST -H 'Content-Type: application/json' --data '{"name":"restore","calendar":{"zone":"Europe/London","start":"09:00","end":"17:00","days":[1,2,3,4,5],"holidays":["2026-12-25"]}}' \
        "$drill_address/api/openservice/operations/calendars" >/dev/null
    jq -n --arg record "$drill_case" '{table:"u_service_case",record_id:$record,calendar:"restore",minutes:480}' > "$drill_directory/clock.json"
    drill_clock=$(api -X POST -H 'Content-Type: application/json' --data-binary "@$drill_directory/clock.json" \
        "$drill_address/api/openservice/operations/clocks" | jq -er '.result.sla_id')
    api -X POST -H 'Content-Type: application/json' --data '{"operation":"pause"}' \
        "$drill_address/api/openservice/operations/clocks/$drill_clock" >/dev/null
    drill_report=$(api -X POST -H 'Content-Type: application/json' --data '{"name":"Restore totals","table":"u_service_case","query":{"field":"estimate"},"shared":true}' \
        "$drill_address/api/openservice/operations/reports" | jq -er '.result.report_id')
    if [ "$drill_source_version" -ge 35 ]; then
        api "$drill_address/api/openservice/operations/reports/$drill_report" | jq -e '.result.rows[0].sum == "0.1"' >/dev/null
    fi
    drill_ci=$(api -X POST -H 'Content-Type: application/json' --data '{"short_description":"Restore monitored asset"}' \
        "$drill_address/api/now/table/cmdb_ci" | jq -er '.result.sys_id')
    jq -n --arg asset "$drill_ci" '{key:"restore-event",asset_id:$asset,severity:"warning",summary:"Preserve event through recovery"}' > "$drill_directory/event.json"
    api -X POST -H 'Content-Type: application/json' --data-binary "@$drill_directory/event.json" "$drill_address/api/openservice/operations/events" >/dev/null
    if [ "$drill_source_version" -ge 34 ]; then
        api -X POST -H 'Content-Type: application/json' --data '{"key":"restore-event","operation":"acknowledge","reason":"Resume operator review after recovery"}' \
            "$drill_address/api/openservice/operations/event-actions" >/dev/null
    fi
    drill_article=$(api -X POST -H 'Content-Type: application/json' --data '{"short_description":"Published recovery guide","text":"Preserve publication through recovery"}' \
        "$drill_address/api/now/table/kb_knowledge" | jq -er '.result.sys_id')
    api -X POST -H 'Content-Type: application/json' --data '{"operation":"publish"}' \
        "$drill_address/api/openservice/operations/knowledge/$drill_article" >/dev/null
    api -X POST -H 'Content-Type: application/json' --data '{"source":"restore-idp","external_id":"subject-1","username":"RestoredIdentity","role":"reader","active":true,"groups":[]}' \
        "$drill_address/api/openservice/operations/provisioning" >/dev/null
    drill_reader=$(docker exec "$drill_prefix-source-db" psql -At -U openservice -d openservice -c "SELECT user_id FROM os_user WHERE name='RestoreReader'")
    jq -n --arg user "$drill_reader" '{user_id:$user,enabled:true}' > "$drill_directory/audit-access.json"
    api -X POST -H 'Content-Type: application/json' --data-binary "@$drill_directory/audit-access.json" \
        "$drill_address/api/openservice/operations/audit-access" >/dev/null
    drill_digest=$(printf 'synthetic corpus restore value' | openssl dgst -sha256 | awk '{print $NF}')
    jq -n --arg digest "$drill_digest" '{minimum_length:15,source:"Synthetic restore fixture",version:"1",digests:[$digest]}' > "$drill_directory/corpus.json"
    api -X POST -H 'Content-Type: application/json' --data-binary "@$drill_directory/corpus.json" \
        "$drill_address/api/openservice/operations/password-policy" >/dev/null
fi
snapshot source
docker stop "$drill_prefix-source-app" >/dev/null
snapshot_delivery() {
    docker exec "$drill_prefix-$1-db" psql -At -U openservice -d openservice \
        -c 'SELECT row_to_json(q) FROM (SELECT * FROM os_outbox ORDER BY job_id) q' > "$drill_directory/$1-queue.jsonl"
    docker exec "$drill_prefix-$1-db" psql -At -U openservice -d openservice \
        -c 'SELECT row_to_json(q) FROM (SELECT * FROM os_requester_record ORDER BY record_id) q' > "$drill_directory/$1-requesters.jsonl"
}
if [ "$drill_source_version" -ge 14 ]; then snapshot_delivery source; fi
snapshot_enrollment() {
    drill_enrollment_columns='user_id,token_hash,email,auth_revision,expires_at,created_at,cancelled'
    if [ "$drill_source_version" -ge 28 ]; then
        drill_enrollment_columns="$drill_enrollment_columns,previous_email,old_token_hash,old_confirmed,new_confirmed"
    fi
    docker exec "$drill_prefix-$1-db" psql -At -U openservice -d openservice \
        -c "SELECT row_to_json(q) FROM (SELECT $drill_enrollment_columns FROM os_email_enrollment ORDER BY user_id) q" > "$drill_directory/$1-enrollment.jsonl"
}
if [ "$drill_source_version" -ge 15 ]; then snapshot_enrollment source; fi
snapshot_password_policy() {
    docker exec "$drill_prefix-$1-db" psql -At -U openservice -d openservice \
        -c 'SELECT row_to_json(q) FROM (SELECT * FROM os_password_exclusion ORDER BY exclusion_id) q' > "$drill_directory/$1-password-policy.jsonl"
}
if [ "$drill_source_version" -ge 16 ]; then snapshot_password_policy source; fi
snapshot_password_requirements() {
    docker exec "$drill_prefix-$1-db" psql -At -U openservice -d openservice \
        -c 'SELECT row_to_json(q) FROM (SELECT user_id,password_reset_required FROM os_user ORDER BY user_id) q' > "$drill_directory/$1-password-requirements.jsonl"
}
if [ "$drill_source_version" -ge 17 ]; then snapshot_password_requirements source; fi
snapshot_access_modes() {
    docker exec "$drill_prefix-$1-db" psql -At -U openservice -d openservice \
        -c 'SELECT row_to_json(q) FROM (SELECT user_id,api_only FROM os_user ORDER BY user_id) q' > "$drill_directory/$1-access-modes.jsonl"
}
if [ "$drill_source_version" -ge 18 ]; then snapshot_access_modes source; fi
snapshot_approvals() {
    docker exec "$drill_prefix-$1-db" psql -At -U openservice -d openservice \
        -c 'SELECT row_to_json(q) FROM (SELECT * FROM os_approval ORDER BY sequence) q' > "$drill_directory/$1-approvals.jsonl"
}
if [ "$drill_source_version" -ge 19 ]; then snapshot_approvals source; fi
snapshot_platform() {
    for drill_table in os_work os_notice os_connector os_calendar os_sla os_saved_report os_publication os_dependency os_operational_event os_identity os_password_policy os_password_corpus; do
        drill_columns='*'
        if [ "$drill_table" = os_operational_event ] && [ "$drill_source_version" -lt 34 ]; then
            drill_columns='event_key,asset_id,severity,summary,occurrences,first_seen,last_seen'
        fi
        docker exec "$drill_prefix-$1-db" psql -At -U openservice -d openservice \
            -c "SELECT row_to_json(q) FROM (SELECT $drill_columns FROM $drill_table ORDER BY 1) q" > "$drill_directory/$1-$drill_table.jsonl"
    done
    if [ "$drill_source_version" -ge 35 ]; then
        docker exec "$drill_prefix-$1-db" psql -At -U openservice -d openservice \
            -c 'SELECT row_to_json(q) FROM (SELECT * FROM os_report_run ORDER BY run_id) q' > "$drill_directory/$1-report-runs.jsonl"
    fi
    docker exec "$drill_prefix-$1-db" psql -At -U openservice -d openservice \
        -c 'SELECT row_to_json(q) FROM (SELECT user_id,audit_viewer,notifications FROM os_user ORDER BY user_id) q' > "$drill_directory/$1-platform-permissions.jsonl"
}
if [ "$drill_source_version" -ge 33 ]; then snapshot_platform source; fi
docker exec "$drill_prefix-source-db" pg_dump -U openservice -d openservice -Fc > "$drill_directory/backup.dump"
docker stop "$drill_prefix-source-db" >/dev/null
start_database restored
docker exec -i "$drill_prefix-restored-db" pg_restore --exit-on-error --no-owner -U openservice -d openservice < "$drill_directory/backup.dump"
start_application restored
curl --max-time 10 -fsS "$drill_address/readyz" | jq -e '.status == "ready"' >/dev/null
drill_restored_version=$(docker exec "$drill_prefix-restored-db" psql -At -U openservice -d openservice -c 'SELECT MAX(version) FROM os_migration')
[ "$drill_restored_version" -ge "$drill_source_version" ]
snapshot restored
if [ "$drill_source_version" -ge 33 ]; then
    snapshot_platform restored
    for drill_table in os_work os_notice os_connector os_calendar os_sla os_saved_report os_publication os_dependency os_operational_event os_identity os_password_policy os_password_corpus platform-permissions; do
        cmp "$drill_directory/source-$drill_table.jsonl" "$drill_directory/restored-$drill_table.jsonl"
    done
    curl --max-time 30 -fsS --user "RestoreReader:$drill_password" "$drill_address/api/openservice/knowledge" \
        | jq -e '.result | any(.title == "Published recovery guide")' >/dev/null
    curl --max-time 30 -fsS --user "RestoreReader:$drill_password" "$drill_address/api/openservice/operations/audit-checkpoint" \
        | jq -e '.result.count > 0' >/dev/null
    api "$drill_address/api/openservice/operations/jobs" | jq -e '.result | any(.state == "queued")' >/dev/null
    api "$drill_address/api/openservice/operations/clocks" | jq -e '.result | any(.state == "paused")' >/dev/null
    echo 'PASS: queued encrypted work/aliases, paused SLA calendars, saved reports, knowledge, identity bindings, audit grants and password corpus survive restore.'
    if [ "$drill_restored_version" -ge 34 ]; then
        drill_event_state=open
        if [ "$drill_source_version" -ge 34 ]; then drill_event_state=acknowledged; fi
        api "$drill_address/api/openservice/operations/events" | jq -e --arg state "$drill_event_state" '.result | any(.event_key == "restore-event" and .state == $state and .occurrences == 1)' >/dev/null
        echo 'PASS: operational event state and occurrences survive upgrade/restore.'
    fi
    if [ "$drill_source_version" -ge 35 ]; then
        cmp "$drill_directory/source-report-runs.jsonl" "$drill_directory/restored-report-runs.jsonl"
        api "$drill_address/api/openservice/operations/report-history/$drill_report" | jq -e '.result | length == 1' >/dev/null
        echo 'PASS: bounded per-viewer report execution history survives restore.'
    elif [ "$drill_restored_version" -ge 35 ]; then
        api "$drill_address/api/openservice/operations/report-history/$drill_report" | jq -e '.result | length == 0' >/dev/null
        api "$drill_address/api/openservice/operations/reports/$drill_report" | jq -e '.result.rows[0].sum == "0.1"' >/dev/null
        api "$drill_address/api/openservice/operations/report-history/$drill_report" | jq -e '.result | length == 1' >/dev/null
        echo 'PASS: upgraded saved report executes and records its first history entry.'
    fi
fi
if [ "$drill_source_version" -ge 19 ]; then
    snapshot_approvals restored
    cmp "$drill_directory/source-approvals.jsonl" "$drill_directory/restored-approvals.jsonl"
    api "$drill_address/api/openservice/records/incident/$drill_review_record/approvals" | jq -e '.pending_count == 1 and .result[0].current == true' >/dev/null
    curl --max-time 30 -fsS --user "RestoreReviewer:$drill_password" -X POST -H 'Content-Type: application/json' --data '{"decision":"approved","comment":"Reviewed after restore"}' \
        "$drill_address/api/openservice/records/incident/$drill_review_record/approvals/$drill_approval/decision" | jq -e '.result.status == "approved"' >/dev/null
    drill_status=$(curl --max-time 30 -sS -o /dev/null -w '%{http_code}' --user "RestoreReviewer:$drill_password" -X POST -H 'Content-Type: application/json' --data '{"decision":"approved","comment":""}' \
        "$drill_address/api/openservice/records/incident/$drill_review_record/approvals/$drill_approval/decision")
    [ "$drill_status" = 409 ]
    api "$drill_address/api/now/table/incident/$drill_review_record" | jq -e '.result.state == "1" and .result.sys_mod_count == "0"' >/dev/null
    echo 'PASS: revision-bound review survives restore and accepts one decision without executing a transition.'
fi
if [ "$drill_source_version" -ge 18 ]; then
    snapshot_access_modes restored
    cmp "$drill_directory/source-access-modes.jsonl" "$drill_directory/restored-access-modes.jsonl"
    drill_status=$(curl --max-time 30 -sS -o /dev/null -w '%{http_code}' --user "RestoreApiOnly:$drill_password" "$drill_address/account")
    [ "$drill_status" = 403 ]
    curl --max-time 30 -fsS --user "RestoreApiOnly:$drill_password" "$drill_address/api/openservice/me" | jq -e '.result.name == "RestoreApiOnly"' >/dev/null
    echo 'PASS: API-only access survives restore, denying browser access while permitting authorized API requests.'
fi
if [ "$drill_source_version" -ge 17 ]; then
    snapshot_password_requirements restored
    cmp "$drill_directory/source-password-requirements.jsonl" "$drill_directory/restored-password-requirements.jsonl"
    drill_status=$(curl --max-time 30 -sS -o /dev/null -w '%{http_code}' --user "RestoreRequired:$drill_password" \
        "$drill_address/api/openservice/me")
    [ "$drill_status" = 403 ]
    echo 'PASS: required password replacement survives restore and blocks ordinary API access.'
fi
if [ "$drill_source_version" -ge 16 ]; then
    snapshot_password_policy restored
    cmp "$drill_directory/source-password-policy.jsonl" "$drill_directory/restored-password-policy.jsonl"
    drill_status=$(curl --max-time 30 -sS -o /dev/null -w '%{http_code}' --user "admin:$drill_password" \
        -X POST -H 'Content-Type: application/json' \
        --data '{"name":"RejectedRestore","password":"restore-unsafe-password","role":"reader"}' \
        "$drill_address/api/openservice/users")
    [ "$drill_status" = 400 ]
    echo 'PASS: custom password exclusions survive restore and reject prohibited account creation.'
fi
if [ "$drill_source_version" -ge 15 ]; then
    snapshot_enrollment restored
    cmp "$drill_directory/source-enrollment.jsonl" "$drill_directory/restored-enrollment.jsonl"
fi
if [ "$drill_source_version" -ge 14 ]; then
    snapshot_delivery restored
    cmp "$drill_directory/source-queue.jsonl" "$drill_directory/restored-queue.jsonl"
    cmp "$drill_directory/source-requesters.jsonl" "$drill_directory/restored-requesters.jsonl"
    curl --max-time 30 -fsS --user "RestoreRequester:$drill_password" "$drill_address/api/now/table/sc_request/$drill_request" \
        | jq -e '.result.short_description == "Restored own request"' >/dev/null
    drill_status=$(curl --max-time 30 -sS -o /dev/null -w '%{http_code}' --user "RestoreRequester:$drill_password" \
        "$drill_address/api/now/table/u_service_case/$drill_case")
    [ "$drill_status" = 404 ]
    echo 'PASS: encrypted pending delivery, requester ownership and isolation survive restore; outbound delivery stays disabled.'
fi
for artifact in configuration.json record.json security.json metrics.json attachment.bin; do
    cmp "$drill_directory/source-$artifact" "$drill_directory/restored-$artifact"
done
cmp "$drill_directory/payload.bin" "$drill_directory/restored-attachment.bin"
curl --max-time 30 -fsS --user "RestoreReader:$drill_password" "$drill_address/api/openservice/me" | jq -e '.result.name == "RestoreReader"' >/dev/null
drill_status=$(curl --max-time 30 -sS -o /dev/null -w '%{http_code}' --user "RestoreReader:$drill_password" \
    "$drill_address/api/now/table/u_service_case/$drill_case")
[ "$drill_status" = 404 ]
jq -e '.result.rows[0].sum == "0.1" and .result.rows[0].count == "1"' "$drill_directory/restored-metrics.json" >/dev/null
echo "PASS: restored configuration, records, security events, metrics, attachment bytes, account credentials and private access."
echo "Disposable drill completed in $(($(date +%s) - drill_started)) seconds; this is not a production recovery SLA."
echo "Schema upgrade: $drill_source_version -> $drill_restored_version. Source image: $drill_source_image. Target image: $drill_image."
