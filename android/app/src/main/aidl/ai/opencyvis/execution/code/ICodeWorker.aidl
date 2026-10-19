package ai.opencyvis.execution.code;

import ai.opencyvis.execution.code.ICodeHostBridge;
import ai.opencyvis.execution.code.ICodeResultCallback;

interface ICodeWorker {
    int processId();
    oneway void execute(String executionId, String codeFile, String workspace, String paramsJson,
        String resultFile, String stdoutFile, String stderrFile, ICodeHostBridge host, ICodeResultCallback callback);
}
