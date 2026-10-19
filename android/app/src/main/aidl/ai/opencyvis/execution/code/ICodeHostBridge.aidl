package ai.opencyvis.execution.code;

interface ICodeHostBridge {
    String call(String executionId, String capability, String argumentsJson);
}
