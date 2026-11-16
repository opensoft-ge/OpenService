#!/usr/bin/env bash
# Bounded, read-only load smoke for a disposable local installation.
set -euo pipefail
load_base=${1:?Usage: LOAD_PASSWORD=... bash scripts/load-smoke.sh http://127.0.0.1:PORT [requests] [concurrency]}
load_requests=${2:-100}
load_concurrency=${3:-8}
load_user=${LOAD_USERNAME:-admin}
load_secret=${LOAD_PASSWORD:?Set the disposable installation password in LOAD_PASSWORD}
unset LOAD_PASSWORD
[[ "$load_base" =~ ^http://(127\.0\.0\.1|localhost):[0-9]{1,5}$ ]] || { echo 'Use a disposable loopback HTTP installation.' >&2; exit 2; }
[[ "$load_requests" =~ ^[1-9][0-9]{0,3}$ && "$load_requests" -le 1000 ]] || { echo 'Use 1–1000 requests.' >&2; exit 2; }
[[ "$load_concurrency" =~ ^[1-9][0-9]?$ && "$load_concurrency" -le 16 ]] || { echo 'Use 1–16 concurrent requests.' >&2; exit 2; }
[[ "$load_user" =~ ^[A-Za-z0-9]{1,80}$ && "$load_secret" != *$'\n'* && "$load_secret" != *$'\r'* ]] || { echo 'Unsupported credential format for this smoke test.' >&2; exit 2; }
umask 077
load_directory=$(mktemp -d)
trap 'rm -rf "$load_directory"' EXIT
trap 'exit 1' HUP INT TERM
load_secret=${load_secret//\\/\\\\}
load_secret=${load_secret//\"/\\\"}
printf 'user = "%s:%s"\n' "$load_user" "$load_secret" > "$load_directory/curl.conf"
unset load_secret
export OPEN_SERVICE_LOAD_BASE="$load_base" OPEN_SERVICE_LOAD_DIRECTORY="$load_directory"
load_failed=0
seq 1 "$load_requests" | xargs -P "$load_concurrency" -I '{}' sh -c '
    curl --silent --show-error --fail --max-time 15 --config "$OPEN_SERVICE_LOAD_DIRECTORY/curl.conf" \
        --output /dev/null --write-out "%{http_code} %{time_total}\n" \
        "$OPEN_SERVICE_LOAD_BASE/api/now/table/incident?sysparm_limit=10" > "$OPEN_SERVICE_LOAD_DIRECTORY/request-$1.txt"
' smoke '{}' || load_failed=1
sort -n -k2 "$load_directory"/request-*.txt | awk -v expected="$load_requests" -v parallel="$load_concurrency" '
    { n++; latency[n]=$2; total+=$2; if($1!=200) failures++; }
    END {
        if(n!=expected || n==0) { print "Incomplete load evidence"; exit 1; }
        rank=int(n*0.95); if(rank<n*0.95) rank++;
        printf "Requests=%d concurrency=%d failures=%d mean_seconds=%.6f p95_seconds=%.6f max_seconds=%.6f\n", n,parallel,failures,total/n,latency[rank],latency[n];
        if(failures) exit 1;
    }'
exit "$load_failed"
