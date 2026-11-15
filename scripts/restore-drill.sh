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
    docker exec "$drill_prefix-$1-db" psql -At -U openservice -d openservice \
        -c 'SELECT row_to_json(q) FROM (SELECT * FROM os_email_enrollment ORDER BY user_id) q' > "$drill_directory/$1-enrollment.jsonl"
}
if [ "$drill_source_version" -ge 15 ]; then snapshot_enrollment source; fi
docker exec "$drill_prefix-source-db" pg_dump -U openservice -d openservice -Fc > "$drill_directory/backup.dump"
docker stop "$drill_prefix-source-db" >/dev/null
start_database restored
docker exec -i "$drill_prefix-restored-db" pg_restore --exit-on-error --no-owner -U openservice -d openservice < "$drill_directory/backup.dump"
start_application restored
drill_restored_version=$(docker exec "$drill_prefix-restored-db" psql -At -U openservice -d openservice -c 'SELECT MAX(version) FROM os_migration')
[ "$drill_restored_version" -ge "$drill_source_version" ]
snapshot restored
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
