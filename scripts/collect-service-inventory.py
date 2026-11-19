#!/usr/bin/env python3
"""Collect one complete inventory for the topology workspace. Writes JSON to stdout."""
import argparse
import json
import subprocess


def read(*args):
    completed = subprocess.run(args, check=True, capture_output=True, timeout=90)
    data = json.loads(completed.stdout)
    if data.get("NextToken") or data.get("NextMarker") or data.get("metadata", {}).get("continue"):
        raise ValueError("Incomplete inventory page")
    return data


def resource(identifier, title, kind, dependencies=()):
    return dict(id=identifier, title=title, type=kind, depends_on=sorted(set(dependencies)))


def kubernetes(context, namespace):
    args = ["kubectl", "--context", context, "--namespace", namespace, "get"]
    pods = read(*args, "pods", "-o", "json")["items"]
    services = read(*args, "services", "-o", "json")["items"]
    resources = {p["metadata"]["uid"]: resource(p["metadata"]["uid"], p["metadata"]["name"], "Pod") for p in pods}
    offerings = []
    for service in services:
        meta = service["metadata"]
        selector = service["spec"].get("selector", {})
        targets = [p["metadata"]["uid"] for p in pods if selector and
                   selector.items() <= p["metadata"].get("labels", {}).items()]
        resources[meta["uid"]] = resource(meta["uid"], meta["name"], "Service", targets)
        offerings.append(dict(id=meta["uid"], title=meta["name"], roots=[meta["uid"]]))
    return resources, offerings


def aws(profile, region):
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
    return resources, services


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sequence", type=int, required=True)
    sub = parser.add_subparsers(dest="provider", required=True)
    cluster = sub.add_parser("kubernetes")
    cluster.add_argument("--context", required=True)
    cluster.add_argument("--namespace", required=True)
    cloud = sub.add_parser("aws")
    cloud.add_argument("--profile", required=True)
    cloud.add_argument("--region", required=True)
    args = parser.parse_args()
    if args.provider == "aws":
        identity = read("aws", "--profile", args.profile, "--region", args.region, "--output", "json", "--no-cli-pager", "sts", "get-caller-identity")
        source = f"aws/{identity['Account']}/{args.region}"
        resources, services = aws(args.profile, args.region)
    else:
        source = f"kubernetes/{args.context}/{args.namespace}"
        resources, services = kubernetes(args.context, args.namespace)
    if not 1 <= args.sequence <= 2147483647 or len(resources) > 200 or len(services) > 200:
        parser.error("Sequence or inventory exceeds workspace capacity")
    print(json.dumps(dict(operation="inventory", source=source, sequence=args.sequence,
                          resources=[resources[k] for k in sorted(resources)],
                          services=sorted(services, key=lambda s: s["id"]))))


if __name__ == "__main__":
    main()
