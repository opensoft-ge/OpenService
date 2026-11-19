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
