#!/usr/bin/env python3
"""Run a configured HTTPS availability check or collect the leaf certificate served at one endpoint."""
import argparse
import base64
import datetime
import http.client
import json
import os
import socket
import ssl
import sys
import time
import urllib.parse
import urllib.request


def https_target(url):
    target = urllib.parse.urlsplit(url)
    if target.scheme != "https" or not target.hostname or target.username or target.password or target.fragment:
        raise ValueError("Use an HTTPS endpoint without credentials or a fragment")
    return target


def sample(url, context):
    target = https_target(url)
    conn = http.client.HTTPSConnection(target.hostname, target.port, timeout=10, context=context)
    try:
        conn.request("GET", urllib.parse.urlunsplit(("", "", target.path or "/", target.query, "")))
        return "up" if 200 <= conn.getresponse().status < 400 else "down"
    except (OSError, http.client.HTTPException):
        return "down"
    finally:
        conn.close()


def transaction(url, context, expected):
    target = https_target(url)
    if not isinstance(expected, str) or not 1 <= len(expected) <= 240:
        raise ValueError("Provide expected response text up to 240 characters")
    started = time.monotonic()
    conn = http.client.HTTPSConnection(target.hostname, target.port, timeout=60, context=context)
    try:
        conn.request("GET", urllib.parse.urlunsplit(("", "", target.path or "/", target.query, "")))
        response = conn.getresponse()
        body = response.read(1048577)
        status = response.status
        matched = len(body) <= 1048576 and expected.encode("utf-8") in body
    except (OSError, http.client.HTTPException):
        status, matched = 0, False
    finally:
        conn.close()
    return dict(status=status, matched=matched, expected=expected, healthy=True,
                latency_ms=int((time.monotonic() - started) * 1000))


def certificate(url, context):
    target = https_target(url)
    with socket.create_connection((target.hostname, target.port or 443), timeout=10) as sock:
        with context.wrap_socket(sock, server_hostname=target.hostname) as tls:
            return base64.b64encode(tls.getpeercert(binary_form=True)).decode("ascii")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def report(config):
    context = ssl.create_default_context(cafile=config.get("ca_file"))
    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    if config["operation"] == "probe_sample":
        health = sample(config["location_health_url"], context)
        body = dict(operation="probe_sample", objective_id=config["objective_id"], location=config["location"],
                    result=sample(config["endpoint"], context) if health == "up" else "probe_error", observed_at=stamp)
    elif config["operation"] == "transaction_sample":
        healthy = sample(config["location_health_url"], context) == "up"
        expected = config["expected"]
        measured = transaction(config["endpoint"], context, expected) if healthy else dict(
            status=0, latency_ms=0, expected=expected, healthy=False, matched=False)
        body = dict(operation="transaction_sample", objective_id=config["objective_id"],
                    location=config["location"], observed_at=stamp, manifest=measured)
    elif config["operation"] == "verify_certificate":
        body = dict(operation="verify_certificate", fingerprint=config["fingerprint"], endpoint=config["endpoint"],
                    certificate=certificate(config["endpoint"], context), observed_at=stamp, revision=config["revision"])
    else:
        raise ValueError("Select probe_sample, transaction_sample or verify_certificate")
    origin = https_target(config["openservice_origin"])
    if origin.path not in ("", "/") or origin.query:
        raise ValueError("Use the OpenService HTTPS origin")
    url = urllib.parse.urlunsplit(("https", origin.netloc, "/api/openservice/enterprise/mapping/global", "", ""))
    request = urllib.request.Request(url, data=json.dumps(body).encode(), headers={
        "Content-Type": "application/json", "Authorization": os.environ[config["auth_env"]]})
    opener = urllib.request.build_opener(NoRedirect, urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=config.get("openservice_ca_file"))))
    with opener.open(request, timeout=20) as response:
        print(response.read().decode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", help="one operator-configured probe location in JSON")
    parser.add_argument("--every-minutes", type=int, choices=range(1, 61), metavar="1..60",
                        help="run the availability check each interval until stopped")
    args = parser.parse_args()
    with open(args.config, encoding="utf-8") as source:
        config = json.load(source)
    if args.every_minutes is None:
        report(config)
        return
    if config["operation"] not in ("probe_sample", "transaction_sample"):
        raise ValueError("Recurring operation requires an availability or transaction check")
    interval = args.every_minutes * 60
    while True:
        try:
            report(config)
        except (OSError, ValueError, KeyError) as exc:
            print(f"Observation was not recorded: {exc}", file=sys.stderr, flush=True)
        # Align both locations to the same UTC interval and skip elapsed windows.
        time.sleep(interval - time.time() % interval)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError) as exc:
        sys.exit(f"Probe failed: {exc}")
