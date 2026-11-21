#!/usr/bin/env python3
"""Claim and execute one authorized permit job through the OpenService browser workspace."""
import argparse
import json
import os
from pathlib import Path
import runpy
import sys
from urllib.parse import urlsplit

ui = runpy.run_path(str(Path(__file__).with_name("attended-case-entry.py")))


def execute(page, url, pool):
    page.goto(url)
    claim = ui["submit_form"](page, url, "claim_robot_job", {"pool_id": pool})
    if claim["state"] == "idle":
        return claim
    return ui["submit_form"](page, url, "execute_robot_job",
                             {"pool_id": pool, "job_id": claim["job"], "lease_id": claim["lease"]})


def run(page, url, pool, account, password):
    site = urlsplit(url)
    page.goto(f"{site.scheme}://{site.netloc}/login")
    if page.url != f"{site.scheme}://{site.netloc}/login":
        raise ValueError("Login redirected from the designated service")
    page.get_by_label("Username", exact=True).fill(account)
    page.get_by_label("Password", exact=True).fill(password)
    page.get_by_role("button", name="Enter your workspace").click()
    return execute(page, url, pool)


def main(argv=None):
    from playwright.sync_api import sync_playwright
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("origin")
    parser.add_argument("pool")
    args = parser.parse_args(argv)
    url = ui["destination"](args.origin)
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        try:
            result = run(browser.new_page(), url, args.pool,
                         os.environ["OPENSERVICE_ROBOT_USER"], os.environ["OPENSERVICE_ROBOT_PASSWORD"])
        finally:
            browser.close()
    print(json.dumps(result))
    return int(result["state"] == "review")


if __name__ == "__main__":
    sys.exit(main())
