#!/usr/bin/env python3
"""Enter a reviewed CSV through an attended OpenService browser session."""
import argparse
import csv
import hashlib
import json
import uuid
from urllib.parse import urlsplit


def destination(origin):
    parts = urlsplit(origin)
    if (parts.scheme != "https" or not parts.hostname or parts.username is not None
            or parts.path not in ("", "/") or parts.query or parts.fragment):
        raise ValueError("Choose the HTTPS origin of the target OpenService installation")
    return origin.rstrip("/") + "/enterprise/apps/global"


def read_batch(file):
    reader = csv.DictReader(file)
    if reader.fieldnames != ["kind", "reference", "details"]:
        raise ValueError("Expected kind,reference,details columns")
    rows = []
    seen = set()
    for row in reader:
        kind, reference = row["kind"], row["reference"].strip()
        details = json.loads(row["details"])
        if (kind not in ("dispute", "turnaround", "permit") or not reference or
                not isinstance(details, dict) or (kind, reference) in seen or len(rows) >= 100):
            raise ValueError("Choose up to 100 distinct, identified industry case rows")
        seen.add((kind, reference))
        rows.append((kind, reference, details))
    return rows


def submit_form(page, url, operation, fields):
    if page.url != url:
        raise ValueError("Return to the designated OpenService workspace")
    selector = "input[name=operation][value=" + operation + "]"
    form = page.locator("form").filter(has=page.locator(selector))
    for name, value in fields.items():
        form.locator("[name=" + name + "]").fill(value)
    # Use the normal CSRF-protected form. Its business key survives a lost response.
    with page.expect_navigation():
        form.locator("button[type=submit],button:not([type])").last.click()
    if page.url != url:
        raise ValueError("The workspace did not acknowledge the submission")
    return json.loads(page.locator("[data-operation-result]").inner_text())


def enter(page, url, row):
    kind, reference, details = row
    fields = {"new_id": uuid.uuid4().hex, "kind": kind, "reference": reference,
              "manifest": json.dumps(details, ensure_ascii=False)}
    case = submit_form(page,url,"file_industry_case",fields)["definition"]
    if (case["program"], case["reference"]) != (kind, reference):
        raise ValueError("The case receipt did not match the submitted row")
    return {"reference": reference, "case": case["case"], "state": case["state"]}


def run(page, url, rows, confirm=input):
    page.goto(url)
    if confirm("Sign in, open the intake workspace, and review the CSV. Type SUBMIT to enter "
               + str(len(rows)) + " rows: ") != "SUBMIT":
        return
    entries = [{"kind": kind, "reference": ref, "manifest": body} for kind, ref, body in rows]
    manifest = json.dumps(entries, ensure_ascii=False, sort_keys=True)
    batch_id = hashlib.sha256(manifest.encode()).hexdigest()[:32]
    batch = submit_form(page,url,"open_attended_batch",{"new_id":batch_id,"entries":manifest})
    if batch["conflicts"]:
        raise ValueError("An existing case differs from this batch. Review the conflicting reference.")
    for number, (row, saved) in enumerate(zip(rows,batch["rows"],strict=True), start=1):
        receipt = saved if saved["status"] == "received" else enter(page, url, row)
        print(json.dumps(dict(receipt, batch=batch_id, row=number)), flush=True)
    final = submit_form(page,url,"attended_batch_status",{"new_id":batch_id})
    if final["received"] != len(rows):
        raise ValueError("Review the incomplete batch before resuming")


if __name__ == "__main__":
    from playwright.sync_api import sync_playwright
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("origin")
    parser.add_argument("csv_file")
    args = parser.parse_args()
    url = destination(args.origin)
    with open(args.csv_file, newline="", encoding="utf-8-sig") as source:
        batch = read_batch(source)
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=False)
        run(browser.new_page(), url, batch)
        browser.close()
