import contextlib
import io
import json
import os
from pathlib import Path
import runpy
import sys
import unittest
from unittest.mock import MagicMock, patch

TOOL=Path(__file__).with_name("permit-robot.py")


class UnattendedRobotTest(unittest.TestCase):
    def test_filing_and_idle_results(self):
        robot=runpy.run_path(str(TOOL))
        page=MagicMock()
        submit=MagicMock(side_effect=[{"state":"leased","job":"job","lease":"token"},{"state":"done"},{"state":"idle"}])
        with patch.dict(robot["ui"],submit_form=submit):
            self.assertEqual("done",robot["execute"](page,"https://example.test/desk","pool")["state"])
            self.assertEqual("idle",robot["execute"](page,"https://example.test/desk","pool")["state"])
        self.assertEqual({"pool_id":"pool","job_id":"job","lease_id":"token"},submit.call_args_list[1].args[-1])
        page.url="https://wrong.test"
        with self.assertRaisesRegex(ValueError,"redirected"):
            robot["run"](page,"https://example.test/desk","pool","user","pass")
        page.get_by_label.assert_not_called()

    def test_cli_logs_receipt_and_signals_review(self):
        from types import ModuleType
        api=ModuleType("playwright.sync_api");api.sync_playwright=MagicMock()
        browser=api.sync_playwright.return_value.__enter__.return_value.chromium.launch.return_value
        page=browser.new_page.return_value
        page.goto.side_effect=lambda url:setattr(page,"url",url)
        receipt=page.locator.return_value.inner_text
        receipt.side_effect=[json.dumps({"state":"leased","job":"job","lease":"token"}),json.dumps({"state":"review"})]
        with patch.dict(sys.modules,{"playwright.sync_api":api}),patch.dict(os.environ,OPENSERVICE_ROBOT_USER="robot",OPENSERVICE_ROBOT_PASSWORD="test"),patch.object(sys,"argv",[str(TOOL),"https://desk.test","pool"]),contextlib.redirect_stdout(io.StringIO()) as capture,self.assertRaises(SystemExit) as status:
            runpy.run_path(str(TOOL),run_name="__main__")
        self.assertEqual(1,status.exception.code)
        self.assertEqual({"state":"review"},json.loads(capture.getvalue()))
        browser.close.assert_called_once()
