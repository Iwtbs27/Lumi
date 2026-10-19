package ai.opencyvis.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContextTokenEstimatorTest {
    private fun messages(text: String): List<Map<String, Any>> =
        listOf(mapOf("role" to "user", "content" to text))

    private fun image(payload: String): Map<String, Any> = mapOf(
        "type" to "image_url",
        "image_url" to mapOf("url" to "data:image/jpeg;base64,$payload")
    )

    private fun tool(description: String = "Read task data"): JSONObject = JSONObject()
        .put("type", "function")
        .put("function", JSONObject().put("name", "phone_action").put("description", description))

    @Test fun estimateIncludesToolSchemaAndMessageEnvelopes() {
        val estimator = ContextTokenEstimator()
        val oneMessage = estimator.estimate(messages(""), tool())
        val twoMessages = estimator.estimate(messages("") + messages(""), tool())
        val largerTool = estimator.estimate(messages(""), tool("Read detailed task data. ".repeat(100)))

        assertTrue(oneMessage.textTokens > 0)
        assertTrue(oneMessage.toolTokens > 0)
        assertTrue(twoMessages.textTokens > oneMessage.textTokens)
        assertTrue(largerTool.toolTokens > oneMessage.toolTokens)
        assertEquals(oneMessage.textTokens, largerTool.textTokens)
        assertEquals(oneMessage.textTokens + oneMessage.toolTokens, oneMessage.totalTokens)
        assertEquals(0, oneMessage.imageCount)
    }

    @Test fun chineseAndOtherUnicodeTextAreNotEstimatedAsAsciiCharacters() {
        val estimator = ContextTokenEstimator()
        val ascii = estimator.estimate(messages("a".repeat(400)), tool())
        val chinese = estimator.estimate(messages("上下文管理".repeat(80)), tool())
        val unicode = estimator.estimate(messages("αβγδ".repeat(100)), tool())

        assertTrue(chinese.textTokens > ascii.textTokens * 2)
        assertTrue(unicode.textTokens > ascii.textTokens * 2)
    }

    @Test fun imageEstimateDependsOnImageCountNotEncodedPayloadLength() {
        val estimator = ContextTokenEstimator()
        val shortImage = listOf(mapOf("role" to "user", "content" to listOf(image("small"))))
        val largeImage = listOf(mapOf("role" to "user", "content" to listOf(image("x".repeat(100_000)))))
        val shortEstimate = estimator.estimate(shortImage, tool())
        val largeEstimate = estimator.estimate(largeImage, tool())

        assertEquals(shortEstimate, largeEstimate)
        assertEquals(1, largeEstimate.imageCount)
        assertEquals(4096, largeEstimate.imageTokens)
    }

    @Test fun everyRecalledAndCurrentImageContributesToBudget() {
        val content = listOf(
            mapOf("type" to "text", "text" to "历史观察与当前观察"),
            image("history-1"), image("history-2"), image("current")
        )
        val estimate = ContextTokenEstimator().estimate(
            listOf(mapOf("role" to "user", "content" to content)), tool()
        )

        assertEquals(3, estimate.imageCount)
        assertEquals(3 * 4096, estimate.imageTokens)
        assertEquals(estimate.textTokens + estimate.toolTokens + estimate.imageTokens, estimate.totalTokens)
    }

    @Test fun estimationDoesNotRewriteMessagesOrToolDefinitions() {
        val content = listOf(image("keep-original-payload"))
        val input = listOf(mapOf("role" to "user", "content" to content))
        val definition = tool()
        val originalMessages = JSONObject(mapOf("messages" to input)).toString()
        val originalTool = definition.toString()

        ContextTokenEstimator().estimate(input, definition)

        assertEquals(originalMessages, JSONObject(mapOf("messages" to input)).toString())
        assertEquals(originalTool, definition.toString())
    }

    @Test fun missingUsageDoesNotChangeCalibration() {
        val estimator = ContextTokenEstimator()
        val input = messages("本次请求")
        val initial = estimator.estimate(input, tool())
        estimator.calibrate(initial, null)
        assertEquals(initial, estimator.estimate(input, tool()))

        estimator.calibrate(initial, initial.totalTokens.toLong() * 3)
        val calibrated = estimator.estimate(input, tool())
        estimator.calibrate(calibrated, null)
        assertEquals(calibrated, estimator.estimate(input, tool()))
    }

    @Test fun calibrationUsesLargestObservedRatioWithoutRepeatedMultiplication() {
        val estimator = ContextTokenEstimator()
        val input = messages("读取资料并检查价格")
        val initial = estimator.estimate(input, tool())
        val actualInputTokens = initial.totalTokens.toLong() * 3

        estimator.calibrate(initial, actualInputTokens)
        val calibrated = estimator.estimate(input, tool())
        assertEquals(3.0, calibrated.calibrationFactor, 0.000001)
        assertEquals(actualInputTokens.toInt(), calibrated.totalTokens)

        estimator.calibrate(initial, actualInputTokens)
        estimator.calibrate(calibrated, actualInputTokens)
        estimator.calibrate(calibrated, initial.totalTokens.toLong())
        assertEquals(calibrated, estimator.estimate(input, tool()))
    }

    @Test fun observedUnderestimateIsNotHiddenBehindAFixedCalibrationCap() {
        val estimator = ContextTokenEstimator()
        val input = messages("输入")
        val initial = estimator.estimate(input, tool())
        estimator.calibrate(initial, initial.totalTokens.toLong() * 12)

        val calibrated = estimator.estimate(input, tool())
        assertEquals(12.0, calibrated.calibrationFactor, 0.000001)
        assertEquals(initial.totalTokens * 12, calibrated.totalTokens)
    }

    @Test fun usageBelowEstimateDoesNotReduceConservativeBudget() {
        val estimator = ContextTokenEstimator()
        val initial = estimator.estimate(messages("输入"), tool())
        estimator.calibrate(initial, initial.totalTokens.toLong() / 2)
        assertEquals(initial, estimator.estimate(messages("输入"), tool()))
    }

    @Test fun estimateExposesItsComponentsForRunRecords() {
        val estimate = ContextTokenEstimator().estimate(messages("任务"), tool())
        val record = estimate.toMap()

        assertEquals(estimate.totalTokens, record["total_tokens"])
        assertEquals(estimate.textTokens, record["text_tokens"])
        assertEquals(estimate.toolTokens, record["tool_tokens"])
        assertEquals(estimate.imageTokens, record["image_tokens"])
        assertEquals(estimate.calibrationFactor, record["calibration_factor"])
        assertEquals(estimate.imageCount, record["image_count"])
        assertNotNull(JSONObject(record).toString())
    }
}
