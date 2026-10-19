package ai.opencyvis.execution.program

import ai.opencyvis.memory.MemoryItem
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProgramRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sharedJsonContractPreservesSpecAndVersionMetadata() {
        val source = JSONObject().put("root_run_id", "run-1").put("child_id", "coding-1")
        val original = spec().copy(sources = listOf(source))
        val encoded = original.toJson()
        val decoded = ProgramSpec.fromJson(encoded)
        encoded.getJSONArray("sources").getJSONObject(0).put("child_id", "changed")
        assertEquals(original.parameters, decoded.parameters)
        assertEquals(original.stages, decoded.stages)
        assertEquals(original.requiredCapabilities, decoded.requiredCapabilities)
        assertEquals(original.completionConditions, decoded.completionConditions)
        assertEquals("coding-1", decoded.sources.single().getString("child_id"))
        val repository = ProgramRepository(temporary.newFolder())
        val version = repository.saveCode("pass", decoded)
        val metadata = version.toJson()
        assertEquals(version.ref, ProgramRef.fromJson(metadata))
        assertEquals(version.spec.stages, ProgramSpec.fromJson(metadata.getJSONObject("spec")).stages)
        assertEquals(version.codeRef, metadata.getString("code_ref"))
    }

    @Test fun codeVersionsRemainSeparateAndCandidateSurvivesRecreation() {
        val directory = temporary.newFolder("programs")
        val repository = ProgramRepository(directory)
        val source = JSONObject().put("run_id", "run-1").put("step", 3).put("child_id", "coding-1")
        val first = repository.saveCode("def main(ctx, params):\n    return 1\n", spec().copy(sources = listOf(source)))
        source.put("step", 99)
        val second = repository.saveCode("def main(ctx, params):\n    return 2\n", spec(), first.ref.programId)
        val restored = ProgramRepository(directory)
        assertEquals(1, first.ref.revision)
        assertEquals(2, second.ref.revision)
        assertEquals(first.ref.programId, second.ref.programId)
        assertTrue(restored.codeFile(first.ref).readText().endsWith("return 1\n"))
        assertTrue(restored.codeFile(second.ref).readText().endsWith("return 2\n"))
        assertEquals(3, restored.get(first.ref).spec.sources.single().getInt("step"))
        assertNull(restored.get(first.ref).procedure)
        assertTrue(restored.get(first.ref).enabled)
        assertTrue(restored.validations(first.ref).isEmpty())
        val metadata = File(directory, "${first.ref.programId}/1/metadata.json").readText()
        assertFalse(metadata.contains("def main"))
        assertEquals(first.codeRef, JSONObject(metadata).getString("code_ref"))
    }

    @Test fun bindingRequiresDeclaredTypesAndExplicitTextConversionWithSources() {
        val repository = ProgramRepository(temporary.newFolder())
        val version = repository.saveCode("pass", spec().copy(parameters = listOf(
            ProgramParameter("count", "integer"), ProgramParameter("ratio", "number"),
            ProgramParameter("enabled", "boolean"), ProgramParameter("label", "string", required = false))))
        val source = JSONObject().put("user_source_index", 0)
        val input = mapOf("count" to ProgramBinding("12", source, parseText = true),
            "ratio" to ProgramBinding(2.5, source), "enabled" to ProgramBinding("false", source, parseText = true))
        val bound = repository.bind(version.ref, input)
        source.put("user_source_index", 9)
        assertEquals(12L, bound.params.getLong("count"))
        assertEquals(2.5, bound.params.getDouble("ratio"), 0.0)
        assertFalse(bound.params.getBoolean("enabled"))
        assertFalse(bound.params.has("label"))
        assertEquals(0, bound.sources.getJSONObject("count").getInt("user_source_index"))
        assertParameterError("count") { repository.bind(version.ref, input - "count") }
        assertParameterError("count") { repository.bind(version.ref, input + ("count" to ProgramBinding("12", source))) }
        assertParameterError("count") { repository.bind(version.ref, input + ("count" to ProgramBinding("1.5", source, true))) }
        assertParameterError("enabled") { repository.bind(version.ref, input + ("enabled" to ProgramBinding("yes", source, true))) }
        assertParameterError("unknown") { repository.bind(version.ref, input + ("unknown" to ProgramBinding(1, source))) }
    }

    @Test fun structuredBindingsAreCopiedAndArtifactsKeepTheirExactVersion() {
        val repository = ProgramRepository(temporary.newFolder())
        val version = repository.saveCode("pass", spec().copy(parameters = listOf(
            ProgramParameter("input", "artifact"), ProgramParameter("rows", "array"), ProgramParameter("options", "object"))))
        val artifact = JSONObject().put("artifact_id", "input-1").put("version", 2)
        val rows = JSONArray().put(JSONObject().put("amount", 12))
        val options = JSONObject().put("sheet", "Sheet1")
        val source = JSONObject().put("run_id", "run-1").put("step", 4)
        val input = mapOf("input" to ProgramBinding(artifact, source), "rows" to ProgramBinding(rows, source),
            "options" to ProgramBinding(options, source))
        val bound = repository.bind(version.ref, input)
        artifact.put("version", 3)
        rows.getJSONObject(0).put("amount", 98)
        options.put("sheet", "Sheet2")
        assertEquals(2, bound.params.getJSONObject("input").getInt("version"))
        assertEquals(12, bound.params.getJSONArray("rows").getJSONObject(0).getInt("amount"))
        assertEquals("Sheet1", bound.params.getJSONObject("options").getString("sheet"))
        val rebound = repository.bind(version.ref, input + ("rows" to ProgramBinding("[1,2]", source, true)))
        assertEquals(2, rebound.params.getJSONArray("rows").length())
        assertEquals(2, bound.params.getJSONObject("input").getInt("version"))
        assertParameterError("input") { repository.bind(version.ref, input +
            ("input" to ProgramBinding(JSONObject().put("artifact_id", "input-1"), source))) }
    }

    @Test fun procedureLinkChecksRevisionNodesAndStagesWithoutInheritingMaturity() {
        val repository = ProgramRepository(temporary.newFolder())
        val version = repository.saveCode("pass", spec())
        val current = procedure()
        val linked = repository.linkProcedure(version.ref, current, listOf("filter"), "实际样例已核对")
        assertEquals(current.id, linked.procedure!!.procedureId)
        assertEquals(current.revision, linked.procedure!!.procedureRevision)
        assertEquals(listOf(version.ref), repository.listForProcedure(current).map { it.ref })
        assertTrue(repository.validations(version.ref).isEmpty())
        assertEquals(0, repository.card(version.ref).getInt("validation_count"))
        assertTrue(repository.listForProcedure(current.copy(revision = 2)).isEmpty())
        assertTrue(repository.listForProcedure(current.copy(status = "disabled")).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            repository.linkProcedure(version.ref, current, listOf("missing"), "错误节点")
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.linkProcedure(version.ref, current, listOf("save"), "阶段映射不一致")
        }
        val badMapping = repository.saveCode("pass", spec().copy(stages = listOf(
            ProgramStage("filter", "已读取数据", "完成过滤", procedureNodeIds = listOf("missing")))))
        assertThrows(IllegalArgumentException::class.java) {
            repository.linkProcedure(badMapping.ref, current, listOf("missing"), "不存在的映射")
        }
    }

    @Test fun hostValidationRecordsStayWithExactCodeAndProcedureVersionAndCanBeDisabled() {
        val directory = temporary.newFolder()
        val repository = ProgramRepository(directory)
        val first = repository.saveCode("pass", spec())
        repository.linkProcedure(first.ref, procedure(), listOf("filter"), "覆盖过滤节点")
        val firstValidation = validation("exec-1", 10)
        repository.recordValidation(first.ref, firstValidation)
        firstValidation.params.put("minimum", 999)
        repository.recordValidation(first.ref, validation("exec-2", 20))
        val restored = ProgramRepository(directory)
        val history = restored.validations(first.ref)
        assertEquals(listOf("exec-1", "exec-2"), history.map { it.executionId })
        assertEquals(10, history.first().params.getInt("minimum"))
        assertEquals(listOf("operation-exec-1"), history.first().operationIds)
        assertEquals(listOf("filter"), history.first().coveredNodes)
        assertEquals(1, history.first().procedureRevision)
        assertTrue(history.first().checks.single().passed)
        assertEquals("artifact:output-1:1", history.first().checks.single().evidenceRefs.single())
        val card = restored.card(first.ref)
        assertEquals(2, card.getInt("validation_count"))
        assertTrue(card.getBoolean("parameter_variation_observed"))
        assertFalse(card.has("generally_verified"))
        assertTrue(File(directory, "${first.ref.programId}/1/validations/exec-1.json").isFile)
        val second = repository.saveCode("# revised\npass", spec(), first.ref.programId)
        assertTrue(repository.validations(second.ref).isEmpty())
        assertNull(repository.get(second.ref).procedure)
        assertTrue(repository.listForProcedure(procedure().copy(revision = 2)).isEmpty())
        repository.setEnabled(first.ref, false, "新样例失败，停用实现")
        assertFalse(repository.get(first.ref).enabled)
        assertTrue(repository.listForProcedure(procedure()).isEmpty())
        assertTrue(repository.codeFile(first.ref).isFile)
        assertEquals(2, repository.validations(first.ref).size)
    }

    private fun spec() = ProgramSpec("筛选数据", "data",
        parameters = listOf(ProgramParameter("minimum", "integer", procedureParameter = "minimum")),
        requiredCapabilities = listOf("files.import", "files.export"), preconditions = listOf("输入副本可读"),
        completionConditions = listOf("输出行均达到阈值"), stages = listOf(
            ProgramStage("filter", "输入已暂存", "输出已逐行核对", procedureNodeIds = listOf("filter"))))

    private fun procedure() = MemoryItem(id = "filter-table", type = "procedure", title = "筛选表格",
        summary = "筛选并保存", status = "active", revision = 1, bodyJson = """{
          "goal":"筛选并保存","parameters":[{"name":"minimum","description":"最低值","required":true}],
          "preconditions":[],"nodes":[
            {"node_id":"filter","intent":"筛选","expected":"过滤后结果","next":"save"},
            {"node_id":"save","intent":"导出","expected":"导出文件可读","next":""}],
          "completion":"结果已保存","maturity":"validated"} """)

    private fun validation(execution: String, minimum: Int) = ProgramValidation(execution, 1, "run-1",
        JSONObject().put("minimum", minimum), inputArtifactRefs = listOf(
            JSONObject().put("artifact_id", "input-1").put("version", 1)),
        operationIds = listOf("operation-$execution"), checkedStageIds = listOf("filter"), coveredNodes = listOf("filter"),
        checks = listOf(ProgramCheck("输出逐行符合阈值", true, listOf("artifact:output-1:1"))),
        scope = JSONObject().put("sample", "fixture-table").put("rows_checked", 2),
        procedureId = "filter-table", procedureRevision = 1)

    private fun assertParameterError(name: String, block: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java) { block() }
        assertTrue("Expected parameter $name in ${error.message}", error.message.orEmpty().contains(name))
    }
}
