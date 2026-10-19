"""Pull trace exports through the app's debug-only ADB bridge."""

from datetime import datetime, timezone
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile
import threading
from typing import Optional
import zipfile


class SyncError(Exception):
    """An actionable device or export failure."""


class DeviceSync:
    def __init__(self, data_dir: Path, adb: Optional[str] = None,
                 package: str = "ai.opencyvis.standard", serial: Optional[str] = None):
        self.data_dir = Path(data_dir).resolve()
        self.adb = adb or self._find_adb()
        self.package = package
        self.serial = serial
        self._lock = threading.Lock()

    @staticmethod
    def _find_adb() -> Optional[str]:
        for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
            sdk = os.environ.get(variable)
            if sdk:
                candidate = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
                if candidate.is_file():
                    return str(candidate)
        return shutil.which("adb")

    def _run(self, args, *, timeout=15, stdout=subprocess.PIPE):
        if not self.adb:
            raise SyncError("未找到 ADB，请指定 --adb 或设置 ANDROID_HOME。")
        try:
            result = subprocess.run([self.adb, *args], stdout=stdout, stderr=subprocess.PIPE,
                                    timeout=timeout, check=False)
        except subprocess.TimeoutExpired as exc:
            raise SyncError(f"ADB 操作超时（{timeout} 秒），请检查手机连接后重试。") from exc
        except OSError as exc:
            raise SyncError(f"无法启动 ADB：{exc}") from exc
        if result.returncode:
            error = result.stderr.decode("utf-8", errors="replace").strip()
            raise SyncError(f"ADB 操作失败：{error}")
        return result

    def _devices(self):
        output = self._run(["devices"]).stdout.decode("utf-8", errors="replace")
        devices = []
        for line in output.splitlines():
            parts = line.split()
            if len(parts) >= 2 and parts[1] in {"device", "offline", "unauthorized", "recovery", "sideload", "no"}:
                devices.append({"serial": parts[0], "state": " ".join(parts[1:])})
        return devices

    def _choose_serial(self, devices):
        if self.serial:
            device = next((item for item in devices if item["serial"] == self.serial), None)
            if not device:
                raise SyncError(f"设备 {self.serial} 未连接。")
        else:
            if not devices:
                raise SyncError("未连接手机，请通过 USB 或无线 ADB 连接。")
            if len(devices) != 1:
                raise SyncError("检测到多台设备，请通过 --serial 明确选择手机。")
            device = devices[0]
        if device["state"] != "device":
            raise SyncError(f"设备 {device['serial']} 状态为 {device['state']}，请在手机上授权并确认 ADB 连接。")
        return device["serial"]

    def _query(self, serial, endpoint):
        uri = f"content://{self.package}.trace/{endpoint}"
        result = self._run(["-s", serial, "shell", "content", "query", "--uri", uri], timeout=120 if endpoint.startswith("export/") else 15)
        output = result.stdout.decode("utf-8", errors="replace")
        match = re.search(r"\bjson=", output)
        if not match:
            detail = output.strip() or result.stderr.decode("utf-8", errors="replace").strip()
            if "Could not find provider" in detail:
                raise SyncError("手机已连接，但当前 APK 尚无轨迹桥，请覆盖安装 2.1.4 或更新的调试包。")
            raise SyncError(f"轨迹桥不可用，请安装支持轨迹同步的调试 APK。设备返回：{detail[:500]}")
        try:
            payload, _ = json.JSONDecoder().raw_decode(output[match.end():].lstrip())
        except json.JSONDecodeError as exc:
            raise SyncError("轨迹桥返回的 JSON 无法读取。") from exc
        if "error" in payload:
            raise SyncError(f"轨迹桥导出失败：{payload['error']}")
        return payload

    def status(self) -> dict:
        state = {"available": False, "devices": [], "serial": self.serial,
                 "bridge_ready": False, "message": ""}
        try:
            state["devices"] = self._devices()
            state["available"] = True
            state["serial"] = self._choose_serial(state["devices"])
            self._query(state["serial"], "runs")
            state.update(bridge_ready=True, message="手机已连接，轨迹桥可用。")
        except SyncError as exc:
            state["message"] = str(exc)
        return state

    def _list_runs(self, serial):
        payload = self._query(serial, "runs")
        if not isinstance(payload.get("runs"), list):
            raise SyncError("轨迹桥未返回 runs 列表。")
        return payload["runs"]

    def list_runs(self) -> list:
        return self._list_runs(self._choose_serial(self._devices()))

    @staticmethod
    def _fingerprint(runs):
        keys = ("run_id", "status", "updated_at", "finished_at", "step_count", "memory_status", "memory_updated_at")
        return [{key: run.get(key) for key in keys} for run in sorted(runs, key=lambda run: run["run_id"])]

    def sync(self, conversation_id: Optional[int] = None) -> dict:
        with self._lock:
            serial = self._choose_serial(self._devices())
            runs = self._list_runs(serial)
            if not runs:
                raise SyncError("手机上暂无可导出的任务轨迹。")
            if conversation_id is None:
                conversation_id = max(runs, key=lambda run: run["created_at"])["conversation_id"]
            selected = [run for run in runs if run["conversation_id"] == conversation_id]
            if not selected:
                raise SyncError(f"未找到会话 {conversation_id} 的轨迹。")
            return self._sync(serial, int(conversation_id), selected)

    def sync_changed(self) -> list:
        with self._lock:
            serial = self._choose_serial(self._devices())
            runs = self._list_runs(serial)
            # Foreground execution has priority over archive generation on the phone.
            if any(not run.get("finished_at") for run in runs):
                return []
            conversations = {}
            for run in runs:
                conversations.setdefault(int(run["conversation_id"]), []).append(run)
            results = []
            for conversation_id, selected in conversations.items():
                metadata = self.data_dir / "conversations" / str(conversation_id) / "_sync.json"
                saved = json.loads(metadata.read_text(encoding="utf-8")) if metadata.exists() else {}
                if (saved.get("fingerprint") != self._fingerprint(selected)
                        or saved.get("serial") != serial or saved.get("package") != self.package):
                    results.append(self._sync(serial, conversation_id, selected))
            return results

    def _remove_staging(self, directory):
        resolved = directory.resolve()
        if resolved.parent != self.data_dir or not resolved.name.startswith(".sync-"):
            raise SyncError("同步临时目录不在预期位置，未执行清理。")
        shutil.rmtree(resolved)

    @staticmethod
    def _extract(archive_path, destination):
        try:
            with zipfile.ZipFile(archive_path) as archive:
                for member in archive.infolist():
                    target = (destination / member.filename.replace("\\", "/")).resolve()
                    if not target.is_relative_to(destination.resolve()):
                        raise SyncError(f"ZIP 包含目录外路径：{member.filename}")
                archive.extractall(destination)
        except (zipfile.BadZipFile, OSError) as exc:
            raise SyncError(f"ZIP 解压失败，之前的本地轨迹已保留：{exc}") from exc

    def _sync(self, serial, conversation_id, runs):
        export = self._query(serial, f"export/{conversation_id}")
        if export.get("conversation_id") != conversation_id:
            raise SyncError("轨迹桥返回的会话与请求不一致。")
        remote_path = export.get("path", "")
        parts = PurePosixPath(remote_path).parts
        if (parts[:2] != ("cache", "run_exports") or ".." in parts
                or not re.fullmatch(r"[A-Za-z0-9_./-]+", remote_path)):
            raise SyncError("轨迹桥返回的导出文件路径无效。")
        self.data_dir.mkdir(parents=True, exist_ok=True)
        destination = self.data_dir / "conversations" / str(conversation_id)
        if not destination.resolve().is_relative_to(self.data_dir):
            raise SyncError("会话目录不在同步数据目录内，未替换本地轨迹。")
        staging = Path(tempfile.mkdtemp(prefix=".sync-", dir=self.data_dir))
        try:
            archive_path = staging / "export.zip"
            with archive_path.open("wb") as stream:
                self._run(["-s", serial, "exec-out", "run-as", self.package, "cat", remote_path],
                          stdout=stream, timeout=120)
            if archive_path.stat().st_size != export.get("size"):
                raise SyncError("ZIP 传输不完整，之前的本地轨迹已保留。")
            extracted = staging / "content"
            extracted.mkdir()
            self._extract(archive_path, extracted)
            run_ids = [run["run_id"] for run in runs]
            for run_id in run_ids:
                if not (extracted / run_id / "run.json").is_file():
                    raise SyncError(f"ZIP 缺少任务 {run_id} 的 run.json，未替换本地轨迹。")
            result = {"conversation_id": conversation_id, "run_ids": run_ids,
                      "destination": str(destination), "synced_at": datetime.now(timezone.utc).isoformat()}
            metadata = {**result, "serial": serial, "package": self.package,
                        "fingerprint": self._fingerprint(runs)}
            (extracted / "_sync.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
            destination.parent.mkdir(parents=True, exist_ok=True)
            backup = staging / "previous"
            if destination.exists():
                destination.rename(backup)
            try:
                extracted.rename(destination)
            except OSError:
                if backup.exists():
                    backup.rename(destination)
                raise
            return result
        except OSError as exc:
            backup = staging / "previous"
            detail = f"；旧数据保留在 {backup}" if backup.exists() else ""
            raise SyncError(f"无法保存同步文件：{exc}{detail}") from exc
        finally:
            # Retain the backup if Windows also prevented rollback.
            if not ((staging / "previous").exists() and not destination.exists()):
                self._remove_staging(staging)
