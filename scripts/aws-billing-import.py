#!/usr/bin/env python3
"""Convert an AWS Cost and Usage Report CSV into account-scoped OpenService import requests."""
import argparse
import csv
import json
import re
import sys


def batches(rows, account, month, source):
    if not re.fullmatch(r"[0-9]{12}", account) or not re.fullmatch(r"[0-9]{4}-(0[1-9]|1[0-2])", month):
        raise ValueError("Choose a usage account and billing month")
    pending = {}
    for row in rows:
        if row["lineItem/UsageAccountId"] != account or row["bill/BillingPeriodStartDate"][:7] != month:
            continue
        currency = row["lineItem/CurrencyCode"]
        entries = pending.setdefault(currency, [])
        entries.append({"line_id": row["identity/LineItemId"], "resource": row["lineItem/ResourceId"], "cost": row["lineItem/UnblendedCost"]})
        if len(entries) == 250:
            yield dict(operation="cloud_import", account=account, month=month, currency=currency, source=source, entries=entries)
            pending[currency] = []
    for currency, entries in pending.items():
        if entries:
            yield dict(operation="cloud_import", account=account, month=month, currency=currency, source=source, entries=entries)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("account", "month", "source"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    for batch in batches(csv.DictReader(sys.stdin), args.account, args.month, args.source):
        print(json.dumps(batch))
