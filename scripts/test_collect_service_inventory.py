import contextlib
import io
import json
import runpy
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("collect-service-inventory.py")


class InventoryTest(unittest.TestCase):
    def run_cli(self, arguments, replies):
        calls = []
        output = io.StringIO()

        def command(args, **kwargs):
            calls.append(list(args))
            if isinstance(replies[0], Exception):
                raise replies.pop(0)
            return subprocess.CompletedProcess(args, 0, json.dumps(replies.pop(0)).encode())

        with patch("subprocess.run", command), patch.object(sys, "argv", [str(SCRIPT), *arguments]), contextlib.redirect_stdout(output):
            runpy.run_path(str(SCRIPT), run_name="__main__")
        return json.loads(output.getvalue()), calls

    def test_cluster_scope_and_selectors(self):
        meta = lambda name, labels: {"uid": name, "name": name, "labels": labels}
        pods = [{"metadata": meta("serving", {"app": "api"})}, {"metadata": meta("unrelated", {})}]
        services = [{"metadata": meta("api", {}), "spec": {"selector": {"app": "api"}}},
                    {"metadata": meta("external", {}), "spec": {}}]
        packet, calls = self.run_cli(["--sequence", "12", "kubernetes", "--context", "test", "--namespace", "qa"],
                                     [{"items": pods}, {"items": services}])
        self.assertEqual("kubernetes/test/qa", packet["source"])
        self.assertTrue(all(call[:5] == ["kubectl", "--context", "test", "--namespace", "qa"] for call in calls))
        by_id = {row["id"]: row for row in packet["resources"]}
        self.assertEqual(["serving"], by_id["api"]["depends_on"])
        self.assertEqual([], by_id["external"]["depends_on"])

    def test_federated_discovery(self):
        arn = "arn:aws:rds:eu-west-1:012345678901:db:checkout"
        db = {"DBInstanceArn": arn, "DBInstanceIdentifier": "checkout"}
        aws_args = ["--sequence", "5", "aws", "--profile", "qa", "--region", "eu-west-1", "--rds"]
        result, calls = self.run_cli(aws_args, [{"Account": "012345678901"}, {"LoadBalancers": []}, {"TargetGroups": []}, {"DBInstances": [db]}])
        self.assertEqual("RDSInstance", result["resources"][0]["type"])
        self.assertIn("describe-db-instances", calls[-1])
        with self.assertRaises(ValueError):
            self.run_cli(aws_args, [{"Account": "012345678901"}, {"LoadBalancers": []}, {"TargetGroups": []}, {"Marker": "later"}])
        args = ["--sequence", "5", "kubernetes", "--context", "qa", "--namespace", "apps", "--vpc-id", "vpc-abc"]
        pod = {"metadata": {"name": "checkout", "uid": "pod-1", "annotations": {"opensoft.ge/rds-instance-arn": arn}}, "status": {"podIP": "10.0.1.5"}}
        no_ip = {"metadata": {"uid": "pod-2", "name": "pending"}}
        result, _ = self.run_cli(args, [{"items": [pod, no_ip]}, {"items": []}])
        nodes = {r["id"]: r for r in result["resources"]}
        self.assertEqual(["pod-1"], nodes["vpc-abc/10.0.1.5"]["depends_on"])
        self.assertEqual([arn], nodes["pod-1"]["depends_on"])
        with self.assertRaises(ValueError):
            self.run_cli(args, [{"items": [pod, pod]}, {"items": []}])
        with self.assertRaises(ValueError):
            self.run_cli([*args[:-1], "invalid"], [{"items": []}, {"items": []}])
        pod["metadata"]["annotations"]["opensoft.ge/rds-instance-arn"] = "invalid"
        with self.assertRaises(ValueError):
            self.run_cli(args, [{"items": [pod]}, {"items": []}])

    def test_load_balancer_targets(self):
        groups = [{"TargetGroupArn": "group-" + kind, "TargetGroupName": kind,
                   "TargetType": kind, "VpcId": "vpc", "LoadBalancerArns": ["lb"]} for kind in ("ip", "instance")]
        health = lambda target: {"TargetHealthDescriptions": [{"Target": {"Id": target}}]}
        packet, calls = self.run_cli(["--sequence", "2", "aws", "--profile", "qa", "--region", "test-1"],
                                     [{"Account": "123456789012"},
                                      {"LoadBalancers": [{"LoadBalancerArn": "lb", "LoadBalancerName": "api"}]},
                                      {"TargetGroups": groups}, health("10.0.0.1"), health("i-123")])
        resources = {row["id"]: row for row in packet["resources"]}
        self.assertEqual("aws/123456789012/test-1", packet["source"])
        self.assertEqual(["group-instance", "group-ip"], resources["lb"]["depends_on"])
        self.assertEqual(["vpc/10.0.0.1"], resources["group-ip"]["depends_on"])
        self.assertEqual(["i-123"], resources["group-instance"]["depends_on"])
        self.assertEqual(5, len(calls))

    def test_asset_refresh_requires_complete_unique_inventory(self):
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "inventory.json"
            args = ["--sequence", "1", "assets", "--name", "devices", "--file", str(path)]
            asset = {"id": "db", "name": "Orders", "type": "Database"}
            path.write_text(json.dumps({"complete": True, "assets": [asset]}))
            packet, _ = self.run_cli(args, [])
            self.assertEqual("assets/devices", packet["source"])
            self.assertEqual("db", packet["resources"][0]["id"])
            for packet in ({"complete": False}, {"complete": True, "assets": {}},
                           {"complete": True, "assets": [asset, asset]},
                           {"complete": True, "assets": [{**asset, "id": " "}]},
                           {"complete": True, "assets": [{**asset, "id": 12}]}):
                path.write_text(json.dumps(packet))
                with self.assertRaises(ValueError):
                    self.run_cli(args, [])
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                self.run_cli([*args[:4], "invalid/name", *args[5:]], [])
            path.write_bytes(b" " * (1024 * 1024 + 1))
            with self.assertRaisesRegex(ValueError, "megabyte"):
                self.run_cli(args, [])

    def test_partial_and_failed_collections(self):
        args = ["--sequence", "2", "kubernetes", "--context", "test", "--namespace", "qa"]
        for bad in ({"NextToken": "more"}, {"NextMarker": "more"}, {"metadata": {"continue": "more"}}):
            with self.assertRaisesRegex(ValueError, "Incomplete"):
                self.run_cli(args, [bad])
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_cli(args, [{"items": []}, subprocess.CalledProcessError(1, "kubectl")])
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.run_cli(["--sequence", "0", *args[2:]], [{"items": []}, {"items": []}])
        runpy.run_path(str(SCRIPT), run_name="inventory_test")


if __name__ == "__main__":
    unittest.main()
