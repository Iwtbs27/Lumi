"""One process, one main(ctx, params) invocation. Host calls use the same Kotlin capability host."""

import contextlib
import json
import os
from pathlib import Path
import sys
import tokenize
import traceback


class PhoneContext:
    def __init__(self, workspace, bridge):
        self.workspace = workspace
        self._bridge = bridge

    def path(self, relative_path):
        """Return a path in the task's staged working directory."""
        return str(Path(self.workspace) / relative_path)

    def call(self, capability, arguments=None):
        response = json.loads(str(self._bridge.call(
            capability, json.dumps({} if arguments is None else arguments, ensure_ascii=False))))
        if not response["ok"]:
            raise RuntimeError(response["error"])
        return response["value"]


@contextlib.contextmanager
def _capture(stdout_path, stderr_path):
    # Capture Python prints and writes from native packages in this dedicated process.
    with open(stdout_path, "w", encoding="utf-8", buffering=1) as stdout:
        with open(stderr_path, "w", encoding="utf-8", buffering=1) as stderr:
            previous_out, previous_err = os.dup(1), os.dup(2)
            try:
                os.dup2(stdout.fileno(), 1)
                os.dup2(stderr.fileno(), 2)
                with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    yield
            finally:
                stdout.flush()
                stderr.flush()
                os.dup2(previous_out, 1)
                os.dup2(previous_err, 2)
                os.close(previous_out)
                os.close(previous_err)


def execute(code_file, workspace, params_json, result_file, stdout_file, stderr_file, bridge):
    previous_directory = os.getcwd()
    with _capture(stdout_file, stderr_file):
        try:
            os.chdir(workspace)
            sys.path.insert(0, str(Path(code_file).parent))
            with tokenize.open(code_file) as source:
                namespace = {"__name__": "__opencyvis_program__", "__file__": code_file}
                exec(compile(source.read(), code_file, "exec"), namespace)
            entry = namespace.get("main")
            if not callable(entry):
                raise TypeError("Program must define main(ctx, params)")
            value = entry(PhoneContext(workspace, bridge), json.loads(params_json))
            with open(result_file, "w", encoding="utf-8") as output:
                json.dump({"result": value}, output, ensure_ascii=False, allow_nan=False)
            return json.dumps({"status": "completed"})
        except BaseException as error:
            traceback.print_exc()
            return json.dumps({"status": "failed", "error": f"{type(error).__name__}: {error}"}, ensure_ascii=False)
        finally:
            os.chdir(previous_directory)
