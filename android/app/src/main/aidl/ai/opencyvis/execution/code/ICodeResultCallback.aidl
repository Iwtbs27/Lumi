package ai.opencyvis.execution.code;

oneway interface ICodeResultCallback {
    void completed(String executionId, String status, String error);
}
