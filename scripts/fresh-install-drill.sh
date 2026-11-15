#!/usr/bin/env bash
# Exercise the documented setup and Compose path in a new, disposable project.
set -euo pipefail
cd "$(dirname "$0")/.."
install_image=${1:?Usage: bash scripts/fresh-install-drill.sh LOCAL_APPLICATION_IMAGE}
install_image=$(docker image inspect "$install_image" --format '{{.Id}}')
install_directory=$(mktemp -d)
install_project="openservice-install-$(date +%s)-$$"
compose() {
    docker compose --project-name "$install_project" --project-directory "$install_directory" \
        --env-file "$install_directory/.env" -f "$install_directory/compose.yaml" \
        -f "$install_directory/image.yaml" "$@"
}
cleanup() {
    compose down --volumes --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$install_directory"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
cp compose.yaml "$install_directory/compose.yaml"
printf 'services:\n  app:\n    image: %s\n' "$install_image" > "$install_directory/image.yaml"
docker run --rm --user "$(id -u):$(id -g)" -v "$install_directory:/setup" -w /setup "$install_image" setup
cp "$install_directory/.env" "$install_directory/original.env"
docker run --rm --user "$(id -u):$(id -g)" -v "$install_directory:/setup" -w /setup "$install_image" setup
cmp "$install_directory/.env" "$install_directory/original.env"
install_mode=$(stat -f '%Lp' "$install_directory/.env" 2>/dev/null || stat -c '%a' "$install_directory/.env")
[ "$install_mode" = 600 ]
printf 'PORT=0\n' >> "$install_directory/.env"
compose up --no-build --detach --wait --wait-timeout 120 >/dev/null
install_address="http://$(compose port app 8080)"
curl --max-time 10 -fsS "$install_address/healthz" | jq -e '.status == "ok"' >/dev/null
curl --max-time 10 -fsS "$install_address/readyz" | jq -e '.status == "ready"' >/dev/null
curl --max-time 10 -fsS "$install_address/login" > "$install_directory/login.html"
rg -q 'Enter your workspace' "$install_directory/login.html"
install_password=$(awk -F= '$1=="ADMIN_PASSWORD" {print $2}' "$install_directory/.env")
curl --max-time 10 -fsS --user "admin:$install_password" "$install_address/api/openservice/me" \
    | jq -e '.result.name == "admin" and .result.role == "admin"' >/dev/null
[ "$(compose exec -T app id -u)" = 10001 ]
echo 'PASS: fresh setup, private credentials, repeated setup preservation, Compose TCP readiness, health, login and administrator authentication.'
