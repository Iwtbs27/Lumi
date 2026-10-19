import json
from pathlib import Path
import tempfile
import unittest

from tools.trace_viewer.records import RecordStore


class RecordStoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.folder = self.root / "run-one"
        self.write("run-one/run.json", {"run_id": "run-one", "instruction": "sample", "created_at": 1})
        self.store = RecordStore([self.root])

    def tearDown(self):
        self.temp.cleanup()

    def write(self, relative, value):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")

    def events(self, number, events):
        path = self.folder / "steps" / str(number) / "events.jsonl"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(json.dumps(e) for e in events), encoding="utf-8")

    def test_context_is_from_current_request_and_attempts_remain_distinct(self):
        self.events(1, [{"type": "state", "data": {"plan_version": 2}}])
        for attempt, version in ((1, 0), (2, 1)):
            self.write(f"run-one/steps/1/request_{attempt}.json", {"messages": [
                {"role": "system", "content": "system prompt"},
                {"role": "user", "content": "[cur_state]\n{\"old\": true}"},
                {"role": "user", "content": [
                    {"type": "text", "text": '[task]\nsample\n[long_term_memory: current]\n'
                     '{"id":"m1","revision":1,"summary":"fact"}\n[cur_state]\n'
                     + json.dumps({"plan_version": version}) + '\n[current_step=1; CURRENT observation]\n'
                     '[accessibility_tree]\nbutton'},
                    {"type": "image_url", "image_url": {"url": {
                        "archive_image": "steps/1/screenshot.jpg", "media_type": "image/jpeg"}}}
                ]}]})
        step = self.store.step("run-one", 1)
        self.assertEqual(step["state_before"], {"plan_version": 1})
        self.assertEqual(step["state_after"], {"plan_version": 2})
        self.assertEqual([r["attempt"] for r in step["requests"]], [1, 2])
        self.assertEqual(step["memory"]["injected"][0]["id"], "m1")
        self.assertEqual(step["requests"][1]["blocks"]["accessibility_tree"], "button")
        self.assertEqual(step["requests"][1]["messages"][-1]["images"],
                         ["/api/runs/run-one/files/steps/1/screenshot.jpg"])

    def test_effect_belongs_to_target_and_injection_does_not_imply_use(self):
        self.events(1, [{"type": "proposed", "data": {"action_type": "tap"}}])
        effect = {"target_step": 1, "evidence_step": 2, "status": "confirmed_success"}
        self.events(2, [{"type": "effect", "data": effect}])
        self.write("memory/run-one/usage.json", {"events": [
            {"step": 1, "memory_id": "m1", "event": "injected"},
            {"step": 2, "memory_id": "m1", "event": "selected"}]})
        self.assertEqual(self.store.step("run-one", 1)["effect"], [effect])
        self.assertEqual(self.store.step("run-one", 2)["effect"], [])
        self.assertEqual(len(self.store.step("run-one", 1)["memory"]["usage"]), 1)
        self.assertEqual(self.store.step("run-one", 1)["memory"]["usage"][0]["event"], "injected")

    def test_missing_usage_is_unknown_and_retry_tokens_are_summed(self):
        self.events(1, [{"type": "usage", "data": {"input_tokens": 10, "output_tokens": 2}},
                        {"type": "usage", "data": {"input_tokens": 15, "output_tokens": 3}}])
        self.events(2, [])
        run = self.store.list_runs()[0]
        self.assertEqual(run["token_usage"], {"input": 25, "output": 5})
        self.assertFalse(run["usage_complete"])
        self.assertIsNone(self.store.step("run-one", 2)["usage"]["input"])
        self.assertTrue(self.store.run("run-one")["warnings"])

    def test_memory_error_and_blocked_action_are_both_visible(self):
        self.events(1, [{"type": "memory_error", "data": {"stage": "usage_feedback", "message": "rejected"}}])
        self.events(2, [{"type": "execution", "data": {"execution_status": "blocked"}}])
        self.assertEqual(self.store.list_runs()[0]["error_count"], 2)
        step = self.store.step("run-one", 1)
        self.assertEqual(step["error"]["type"], "memory_error")
        self.assertEqual(step["memory"]["errors"][0]["stage"], "usage_feedback")

    def test_anthropic_system_and_image_are_preserved(self):
        self.events(1, [])
        self.write("run-one/steps/1/request_1.json", {"system": [{"type": "text", "text": "rules"}],
            "messages": [{"role": "user", "content": [{"type": "image", "source": {
                "data": {"archive_image": "steps/1/screenshot.jpg"}}}]}]})
        request = self.store.step("run-one", 1)["requests"][0]
        self.assertEqual(request["messages"][0]["text"], "rules")
        self.assertEqual(len(request["messages"][1]["images"]), 1)
        self.assertIn("system", request["raw"])

    def test_truncated_event_is_reported_and_asset_stays_inside_run(self):
        self.events(1, [])
        path = self.folder / "steps" / "1" / "events.jsonl"
        path.write_text('{"type":', encoding="utf-8")
        self.assertTrue(self.store.step("run-one", 1)["warnings"])
        (self.root / "private.txt").write_text("private", encoding="utf-8")
        with self.assertRaises(FileNotFoundError):
            self.store.asset("run-one", "../private.txt")


if __name__ == "__main__":
    unittest.main()
