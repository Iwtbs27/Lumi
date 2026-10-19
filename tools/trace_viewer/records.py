"""Read archived observations and actual model requests without changing them."""

import json
from pathlib import Path
import re
from urllib.parse import quote


BLOCK = re.compile(
    r"^\[(task|user_updates|long_term_memory|cur_state|current_step|"
    r"accessibility_tree|recalled_history)(?:[^\]]*)\]\s*$", re.MULTILINE
)


def read_json(path, default=None):
    return json.loads(path.read_text(encoding="utf-8-sig")) if path.is_file() else default


def events_at(folder):
    path = folder / "events.jsonl"
    if not path.is_file():
        return [], ["该步骤未保存事件记录。"]
    events, warnings = [], []
    for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if line.strip():
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                warnings.append(f"events.jsonl 第 {number} 行不完整，未解析。")
    return events, warnings


def last_event(events, kind):
    return next((e.get("data") for e in reversed(events) if e.get("type") == kind), None)


def token_usage(events):
    usages = [e["data"] for e in events if e.get("type") == "usage"]
    return {name: sum(values) if values else None for name, values in (
        (name, [u[key] for u in usages if isinstance(u.get(key), (int, float))])
        for name, key in (("input", "input_tokens"), ("output", "output_tokens"))
    )}


def split_blocks(text):
    matches = list(BLOCK.finditer(text))
    return {match.group(1): text[match.end():matches[i + 1].start()
            if i + 1 < len(matches) else len(text)].strip()
            for i, match in enumerate(matches)}


def memory_cards(text):
    cards = []
    for line in text.splitlines():
        try:
            card = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(card, dict) and ("id" in card or "memory_id" in card):
            cards.append(card)
    return cards


class RecordStore:
    def __init__(self, roots):
        self.roots = [Path(root).resolve() for root in roots]

    def index(self):
        # Later roots (synchronized exports) replace earlier imported copies.
        runs = {}
        for root in self.roots:
            if root.exists():
                for file in root.rglob("run.json"):
                    if any(part.startswith(".sync-") for part in file.parts):
                        continue
                    meta = read_json(file)
                    runs[meta["run_id"]] = (file.parent, meta)
        return runs

    def locate(self, run_id):
        try:
            return self.index()[run_id]
        except KeyError:
            raise FileNotFoundError(f"没有找到轨迹：{run_id}") from None

    @staticmethod
    def step_folders(folder):
        steps = folder / "steps"
        return sorted((p for p in steps.iterdir() if p.is_dir() and p.name.isdigit()),
                      key=lambda p: int(p.name)) if steps.is_dir() else []

    @staticmethod
    def memory(folder, run_id):
        location = folder.parent / "memory" / run_id
        if not location.is_dir():
            location = folder / "memory" / run_id
        return {key: read_json(location / f"{key}.json")
                for key in ("episode", "usage", "memories", "consolidation")}

    @staticmethod
    def summary(folder):
        events, warnings = events_at(folder)
        decision = last_event(events, "proposed") or {}
        execution = last_event(events, "execution") or {}
        errors = [{"type": e["type"], "data": e.get("data")} for e in events
                  if e.get("type") in ("error", "memory_error")]
        error = errors[-1] if errors else None
        return {"number": int(folder.name), "action": decision.get("action_type"),
                "thought": decision.get("thought"),
                "execution_status": execution.get("execution_status"), "error": error, "errors": errors,
                "elapsed_ms": (last_event(events, "timing") or {}).get("total_ms"),
                "token_usage": token_usage(events), "warnings": warnings}

    def list_runs(self):
        runs = []
        for run_id, (folder, meta) in self.index().items():
            steps = [self.summary(p) for p in self.step_folders(folder)]
            consolidation = self.memory(folder, run_id)["consolidation"] or {}
            usage = {}
            for key in ("input", "output"):
                values = [s["token_usage"][key] for s in steps
                          if s["token_usage"][key] is not None]
                usage[key] = sum(values) if values else None
            runs.append({**meta, "id": run_id, "step_count": len(steps),
                         "error_count": sum(s["error"] is not None or s["execution_status"] in
                                            ("failed", "error", "blocked") for s in steps),
                         "memory_status": consolidation.get("status"),
                         "token_usage": usage,
                         "usage_complete": all(s["token_usage"]["input"] is not None for s in steps)})
        return sorted(runs, key=lambda r: r.get("created_at", 0), reverse=True)

    def run(self, run_id):
        folder, meta = self.locate(run_id)
        memory = self.memory(folder, run_id)
        warnings = []
        if memory["usage"] is None:
            warnings.append("此归档未包含 Memory 使用记录；无法判断是否采用记忆。")
        return {"meta": {**meta, "id": run_id},
                "steps": [self.summary(p) for p in self.step_folders(folder)],
                "memory": memory, "warnings": warnings}

    @staticmethod
    def file_url(run_id, relative):
        return f"/api/runs/{quote(run_id, safe='')}/files/{quote(relative, safe='/')}"

    def asset(self, run_id, relative):
        folder, _ = self.locate(run_id)
        path = (folder / relative).resolve()
        if not path.is_relative_to(folder.resolve()) or not path.is_file():
            raise FileNotFoundError("归档文件不存在。")
        return path

    def request(self, run_id, path):
        raw = read_json(path)
        messages = []

        def unpack(content):
            texts, images = [], []
            if isinstance(content, str):
                texts.append(content)
            elif isinstance(content, list):
                for part in content:
                    t, i = unpack(part)
                    texts.extend(t)
                    images.extend(i)
            elif isinstance(content, dict):
                if "archive_image" in content:
                    images.append(self.file_url(run_id, content["archive_image"]))
                else:
                    for key, value in content.items():
                        if key in ("text", "content", "image_url", "url", "source", "data", "images"):
                            t, i = unpack(value)
                            texts.extend(t)
                            images.extend(i)
            return texts, images

        if "system" in raw:
            texts, images = unpack(raw["system"])
            messages.append({"role": "system", "text": "\n".join(texts), "images": images})
        for message in raw.get("messages", []):
            texts, images = unpack(message.get("content", ""))
            if message.get("images"):
                _, more = unpack(message["images"])
                images.extend(more)
            messages.append({"role": message.get("role", "unknown"),
                             "text": "\n".join(texts), "images": images,
                             "tool_calls": message.get("tool_calls")})
        # Only the last user message is the current observation/context.
        current = next((m["text"] for m in reversed(messages) if m["role"] == "user"), "")
        blocks = split_blocks(current)
        return {"attempt": int(path.stem.split("_")[-1]), "raw": raw,
                "messages": messages, "blocks": blocks,
                "memory_injected": memory_cards(blocks.get("long_term_memory", ""))}

    def step(self, run_id, number):
        folder, _ = self.locate(run_id)
        step_folder = folder / "steps" / str(number)
        if not step_folder.is_dir():
            raise FileNotFoundError(f"没有找到第 {number} 步。")
        events, warnings = events_at(step_folder)
        requests = [self.request(run_id, path) for path in sorted(
            step_folder.glob("request_*.json"), key=lambda p: int(p.stem.split("_")[-1]))]
        if not requests:
            warnings.append("该步骤未保存实际模型请求。")
        current = requests[-1] if requests else {}
        state_text = current.get("blocks", {}).get("cur_state")
        try:
            state_before = json.loads(state_text) if state_text else None
        except json.JSONDecodeError:
            state_before = state_text
        memory = self.memory(folder, run_id)
        usages = (memory["usage"] or {}).get("events", [])
        snapshots = (memory["memories"] or {}).get("revisions_used", [])
        injected = current.get("memory_injected", [])
        effects = []
        for other in self.step_folders(folder):
            other_events, _ = events_at(other)
            effects.extend(e["data"] for e in other_events
                           if e.get("type") == "effect" and e.get("data", {}).get("target_step") == number)
        next_folder = next((p for p in self.step_folders(folder) if int(p.name) > number), None)

        def screenshot(path):
            if path is not None and (path / "screenshot.jpg").is_file():
                return self.file_url(run_id, (path / "screenshot.jpg").relative_to(folder).as_posix())
            return None

        tree = step_folder / "tree.txt"
        return {**self.summary(step_folder), "observation": read_json(step_folder / "observation.json"),
                "screenshot_url": screenshot(step_folder), "next_screenshot_url": screenshot(next_folder),
                "tree": tree.read_text(encoding="utf-8") if tree.is_file() else None,
                "events": events, "decision": last_event(events, "proposed"),
                "execution": last_event(events, "execution"), "effect": effects,
                "state_before": state_before, "state_after": last_event(events, "state"),
                "requests": requests,
                "memory": {"injected": injected,
                           "usage": [u for u in usages if u.get("step") == number],
                           "errors": [e["data"] for e in events if e.get("type") == "memory_error"],
                           "snapshots": snapshots},
                "timing": last_event(events, "timing"), "usage": token_usage(events), "warnings": warnings}
