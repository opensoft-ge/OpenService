#!/usr/bin/env python3
"""Collect one complete inventory for the topology workspace. Writes JSON to stdout."""
import argparse
import ipaddress
import json
import re
import subprocess
from pathlib import Path


def read(*args):
    completed = subprocess.run(args, check=True, capture_output=True, timeout=90)
    data = json.loads(completed.stdout)
    if data.get("Marker") or data.get("NextToken") or data.get("NextMarker") or data.get("metadata", {}).get("continue"):
        raise ValueError("Incomplete inventory page")
    return data


def resource(identifier, title, kind, dependencies=()):
    return dict(id=identifier, title=title, type=kind, depends_on=sorted(set(dependencies)))


def kubernetes(context, namespace, vpc=None):
    args = ["kubectl", "--context", context, "--namespace", namespace, "get"]
    pods = read(*args, "pods", "-o", "json")["items"]
    services = read(*args, "services", "-o", "json")["items"]
    resources = {p["metadata"]["uid"]: resource(p["metadata"]["uid"], p["metadata"]["name"], "Pod") for p in pods}
    if vpc:
        if not re.fullmatch(r"vpc-[0-9a-f]+", vpc):
            raise ValueError("Use the AWS VPC ID for this cluster")
        for pod in pods:
            meta = pod["metadata"]
            address = pod.get("status", {}).get("podIP")
            if address:
                key = f"{vpc}/{ipaddress.ip_address(address)}"
                if key in resources:
                    raise ValueError("Ambiguous pod address in this snapshot")
                resources[key] = resource(key, address, "PodAddress", [meta["uid"]])
            dependency = meta.get("annotations", {}).get("opensoft.ge/rds-instance-arn")
            if dependency:
                if not re.fullmatch(r"arn:aws[a-z-]*:rds:[a-z0-9-]+:[0-9]{12}:db:[A-Za-z0-9-]+", dependency):
                    raise ValueError("Invalid declared RDS instance ARN")
                resources[dependency] = resource(dependency, dependency.split(":")[-1], "DeclaredDatabase")
                resources[meta["uid"]]["depends_on"].append(dependency)
    offerings = []
    for service in services:
        meta = service["metadata"]
        selector = service["spec"].get("selector", {})
        targets = [p["metadata"]["uid"] for p in pods if selector and
                   selector.items() <= p["metadata"].get("labels", {}).items()]
        resources[meta["uid"]] = resource(meta["uid"], meta["name"], "Service", targets)
        offerings.append(dict(id=meta["uid"], title=meta["name"], roots=[meta["uid"]]))
    return resources, offerings


def aws(profile, region, include_rds=False):
    args = ["aws", "--profile", profile, "--region", region, "--output", "json", "--no-cli-pager", "elbv2"]
    balancers = read(*args, "describe-load-balancers")["LoadBalancers"]
    groups = read(*args, "describe-target-groups")["TargetGroups"]
    resources = {}
    targets_by_balancer = {}
    for group in groups:
        arn = group["TargetGroupArn"]
        health = read(*args, "describe-target-health", "--target-group-arn", arn)["TargetHealthDescriptions"]
        ids = []
        for target in health:
            identifier = target["Target"]["Id"]
            # IP addresses can be reused in separate VPCs.
            key = (group.get("VpcId", "") + "/" + identifier) if group["TargetType"] == "ip" else identifier
            ids.append(key)
            resources[key] = resource(key, identifier, group["TargetType"])
        resources[arn] = resource(arn, group["TargetGroupName"], "TargetGroup", ids)
        for balancer in group["LoadBalancerArns"]:
            targets_by_balancer.setdefault(balancer, []).append(arn)
    services = []
    for balancer in balancers:
        arn, name = balancer["LoadBalancerArn"], balancer["LoadBalancerName"]
        resources[arn] = resource(arn, name, "LoadBalancer", targets_by_balancer.get(arn, []))
        services.append(dict(id=arn, title=name, roots=[arn]))
    if include_rds:
        databases = read(*args[:-1], "rds", "describe-db-instances")["DBInstances"]
        for database in databases:
            arn = database["DBInstanceArn"]
            resources[arn] = resource(arn, database["DBInstanceIdentifier"], "RDSInstance")
    return resources, services


def assets(path):
    """Require a complete, named inventory export before retiring missing equipment."""
    if path.stat().st_size > 1024 * 1024:
        raise ValueError("Asset inventory exceeds one megabyte")
    packet = json.loads(path.read_text(encoding="utf-8"))
    if packet.get("complete") is not True or not isinstance(packet.get("assets"), list):
        raise ValueError("Supply a complete asset inventory")
    rows = {}
    for asset in packet["assets"]:
        key = asset["id"]
        if not isinstance(key, str) or not key.strip() or key in rows:
            raise ValueError("Asset identifiers must be nonempty and unique")
        rows[key] = resource(key, asset["name"], asset["type"], asset.get("depends_on", []))
    return rows, []


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sequence", type=int, required=True)
    sub = parser.add_subparsers(dest="provider", required=True)
    cluster = sub.add_parser("kubernetes")
    cluster.add_argument("--context", required=True)
    cluster.add_argument("--namespace", required=True)
    cluster.add_argument("--vpc-id", help="Include VPC-scoped pod addresses and declared database dependencies")
    cloud = sub.add_parser("aws")
    cloud.add_argument("--profile", required=True)
    cloud.add_argument("--region", required=True)
    cloud.add_argument("--rds", action="store_true", help="Include RDS instances in the selected account and region")
    inventory = sub.add_parser("assets")
    inventory.add_argument("--name", required=True, help="Stable source name for this inventory")
    inventory.add_argument("--file", type=Path, required=True)
    args = parser.parse_args()
    if args.provider == "aws":
        identity = read("aws", "--profile", args.profile, "--region", args.region, "--output", "json", "--no-cli-pager", "sts", "get-caller-identity")
        source = f"aws/{identity['Account']}/{args.region}"
        resources, services = aws(args.profile, args.region, args.rds)
    elif args.provider == "assets":
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,60}", args.name):
            parser.error("Use a stable asset inventory name")
        source = f"assets/{args.name}"
        resources, services = assets(args.file)
    else:
        source = f"kubernetes/{args.context}/{args.namespace}"
        resources, services = kubernetes(args.context, args.namespace, args.vpc_id)
    if not 1 <= args.sequence <= 2147483647 or len(resources) > 200 or len(services) > 200:
        parser.error("Sequence or inventory exceeds workspace capacity")
    print(json.dumps(dict(operation="inventory", source=source, sequence=args.sequence,
                          resources=[resources[k] for k in sorted(resources)],
                          services=sorted(services, key=lambda s: s["id"]))))


if __name__ == "__main__":
    main()
