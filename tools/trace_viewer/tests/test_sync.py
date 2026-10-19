"""ADB synchronization tests; no phone or external service is needed."""

import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from tools.trace_viewer.sync import DeviceSync, SyncError


class DeviceSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.client = DeviceSync(self.root, adb="adb")
        self.devices = "List of devices attached\nphone-1\tdevice\n"
        self.runs = [{
            "run_id": "run-1", "conversation_id": 7,
            "instruction": "Create a note", "status": "completed",
            "created_at": 100, "finished_at": 200, "updated_at": 200,
            "step_count": 2, "memory_status": "pending", "memory_updated_at": 200,
        }]
        self.bridge_error = None
        self.archive = self.make_zip({"run-1/run.json": '{"status":"completed"}'})
        self.commands = []
        self.mock_run = patch("tools.trace_viewer.sync.subprocess.run", side_effect=self.adb).start()
        self.addCleanup(patch.stopall)

    @staticmethod
    def make_zip(files):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            for name, body in files.items():
                archive.writestr(name, body)
        return buffer.getvalue()

    def adb(self, args, **kwargs):
        self.commands.append(args)
        self.assertNotIn("shell", kwargs)
        if args[-1] == "devices":
            return subprocess.CompletedProcess(args, 0, self.devices.encode(), b"")
        if "content" in args:
            if self.bridge_error:
                return subprocess.CompletedProcess(args, 0, self.bridge_error.encode(), b"")
            if args[-1].endswith("/runs"):
                result = {"runs": self.runs}
            else:
                result = {"conversation_id": 7, "path": "cache/run_exports/conversation_7.zip", "size": len(self.archive)}
            return subprocess.CompletedProcess(args, 0, ("Row: 0 json=" + json.dumps(result)).encode(), b"")
        self.assertEqual(args[-4:], ["run-as", "ai.opencyvis.standard", "cat", "cache/run_exports/conversation_7.zip"])
        kwargs["stdout"].write(self.archive)
        return subprocess.CompletedProcess(args, 0, None, b"")

    def test_reports_legacy_app_without_bridge(self):
        self.bridge_error = "Error while accessing provider: Could not find provider"
        status = self.client.status()
        self.assertTrue(status["available"])
        self.assertFalse(status["bridge_ready"])
        self.assertEqual(status["serial"], "phone-1")
        self.assertIn("桥", status["message"])

    def test_multiple_devices_require_explicit_serial(self):
        self.devices += "phone-2\tdevice\n"
        with self.assertRaisesRegex(SyncError, "serial"):
            self.client.list_runs()
        self.client.serial = "phone-2"
        self.assertEqual(self.client.list_runs(), self.runs)
        self.assertIn("phone-2", self.commands[-1])

    def test_sync_binary_archive_and_metadata(self):
        result = self.client.sync()
        destination = Path(result["destination"])
        self.assertEqual(result["conversation_id"], 7)
        self.assertEqual(result["run_ids"], ["run-1"])
        self.assertTrue((destination / "run-1" / "run.json").is_file())
        self.assertTrue((destination / "_sync.json").is_file())
        self.assertTrue(any("exec-out" in command for command in self.commands))

    def test_resync_when_memory_finishes_and_skip_unchanged(self):
        self.assertEqual(len(self.client.sync_changed()), 1)
        self.assertEqual(self.client.sync_changed(), [])
        self.runs[0].update(memory_status="completed", memory_updated_at=300)
        self.archive = self.make_zip({
            "run-1/run.json": '{}',
            "memory/run-1/consolidation.json": '{"status":"completed"}',
        })
        self.assertEqual(len(self.client.sync_changed()), 1)
        self.assertTrue((self.root / "conversations/7/memory/run-1/consolidation.json").exists())
        self.assertEqual(self.client.sync_changed(), [])

    def test_does_not_auto_export_while_agent_running(self):
        self.runs.append(dict(self.runs[0], run_id="run-2", conversation_id=8, status="running", finished_at=None))
        self.assertEqual(self.client.sync_changed(), [])
        self.assertFalse(any("/export/" in command[-1] for command in self.commands))

    def test_invalid_archive_preserves_previous_sync(self):
        self.client.sync()
        old = self.root / "conversations/7/run-1/run.json"
        content = old.read_text()
        self.archive = self.make_zip({"../../escaped.txt": "bad"})
        with self.assertRaisesRegex(SyncError, "ZIP"):
            self.client.sync()
        self.assertEqual(old.read_text(), content)
        self.assertFalse((self.root / "escaped.txt").exists())

    def test_missing_run_does_not_replace_old_cache(self):
        self.client.sync()
        self.archive = self.make_zip({"other/run.json": '{}'})
        with self.assertRaisesRegex(SyncError, "run-1"):
            self.client.sync()
        self.assertTrue((self.root / "conversations/7/run-1/run.json").exists())

    def test_timeout_has_actionable_error(self):
        self.mock_run.side_effect = subprocess.TimeoutExpired("adb", 15)
        with self.assertRaisesRegex(SyncError, "超时"):
            self.client.list_runs()


if __name__ == "__main__":
    unittest.main()
