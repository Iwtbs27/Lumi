package ai.opencyvis.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import ai.opencyvis.llm.ResponseParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One task's observations, requests and events. Call from the engine's IO dispatcher. */
class TaskRunStore(context: Context, val conversationId: Long? = null, instruction: String = "",
                   val taskId: Long? = null, resume: TaskCheckpoint? = null) {
    private val root = File(context.filesDir, ROOT)
    val runId: String = "${System.currentTimeMillis()}_${UUID.randomUUID()}"
    private val directory = File(root, runId)
    private val imageRefs = mutableMapOf<String, String>()
    private val contextArtifacts by lazy { ContextArtifactStore(File(directory, "context_artifacts"), runId) }
    private val sourceRuns = (resume?.let { it.sourceRuns + it.sourceRunId }.orEmpty()).distinct()
    private val stepSources = resume?.stepSources.orEmpty()
    private val sourceArtifacts = mutableMapOf<String, ContextArtifactStore>()

    init {
        synchronized(archiveLock) {
            sourceRuns.forEach { source ->
                val file = File(requireRun(root, source), "run.json")
                val metadata = JSONObject(file.readText())
                require(taskId == null || metadata.optLong("task_id", -1) == taskId) { "Source run belongs to another task" }
                if (taskId != null) writeAtomic(file, metadata.put("retain_for_task", taskId).toString(2))
            }
            prune(root)
            check(directory.mkdirs()) { "Cannot create task archive: $directory" }
            writeJson(File(directory, "run.json"), mapOf(
                "run_id" to runId,
                "conversation_id" to conversationId,
                "task_id" to taskId, "retain_for_task" to taskId, "source_runs" to sourceRuns,
                "instruction" to instruction,
                "created_at" to System.currentTimeMillis(),
                "status" to "running"
            ))
            activeRuns.add(runId)
        }
    }

    fun saveCheckpoint(checkpoint: TaskCheckpoint) = synchronized(archiveLock) {
        require(checkpoint.sourceRunId == runId) { "Checkpoint belongs to another run" }
        val step = steps(directory).maxOrNull() ?: 0
        val saved = checkpoint.copy(traceStep = step, traceEventCount = events(stepDirectory(step)).size)
        writeAtomic(File(directory, "checkpoint.json"), saved.toJson().toString())
    }

    /** Input arrives independently of the model; keep it durable before unblocking the loop. */
    fun updatePendingInputs(supplements: List<String>, answer: String?) = synchronized(archiveLock) {
        val file = File(directory, "checkpoint.json")
        val checkpoint = TaskCheckpoint.fromJson(JSONObject(file.readText()))
        writeAtomic(file, checkpoint.copy(pendingUserInputs = supplements.toList(), pendingUserAnswer = answer).toJson().toString())
    }

    /** Persist a granted takeover without traversing live task state from the control thread. */
    fun updateHumanIntervention(count: Int) = synchronized(archiveLock) {
        val file = File(directory, "checkpoint.json")
        val checkpoint = TaskCheckpoint.fromJson(JSONObject(file.readText()))
        writeAtomic(file, checkpoint.copy(
            state = checkpoint.state + ("human_intervention_count" to count),
            waitingContext = checkpoint.waitingContext + ("human_control_granted" to true)
        ).toJson().toString())
    }

    fun recordObservation(
        step: Int,
        screenshotBase64: String,
        viewTree: String?,
        metadata: Map<String, Any?>
    ) = synchronized(archiveLock) {
        if (!File(directory, "run.json").isFile) return@synchronized
        val folder = stepDirectory(step).apply { mkdirs() }
        val image = File(folder, "screenshot.jpg")
        val imageBytes = Base64.decode(screenshotBase64, Base64.DEFAULT)
        image.writeBytes(imageBytes)
        val imageSize = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, imageSize)
        imageRefs[screenshotBase64] = image.relativeTo(directory).invariantSeparatorsPath
        if (viewTree != null) File(folder, "tree.txt").writeText(viewTree)
        writeJson(File(folder, "observation.json"), metadata + mapOf(
            "step" to step,
            "recorded_at" to System.currentTimeMillis(),
            "image" to image.relativeTo(directory).invariantSeparatorsPath,
            "image_width" to imageSize.outWidth, "image_height" to imageSize.outHeight,
            "tree" to if (viewTree == null) null else "steps/$step/tree.txt"
        ))
    }

    /** Keep image positions in the request, replacing their bytes with archive references. */
    fun recordRequest(step: Int, request: Map<String, Any?>, attempt: Int = 1) =
        synchronized(archiveLock) {
            if (!File(directory, "run.json").isFile) return@synchronized
            val folder = stepDirectory(step).apply { mkdirs() }
            var imageNumber = 0
            fun imageReference(encoded: String, mime: String): Map<String, String> {
                val relative = imageRefs.getOrPut(encoded) {
                    imageNumber++
                    val extension = if (mime == "image/jpeg") "jpg" else mime.substringAfter("image/")
                    val file = File(folder, "request_${attempt}_image_$imageNumber.$extension")
                    file.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
                    file.relativeTo(directory).invariantSeparatorsPath
                }
                return mapOf("archive_image" to relative, "media_type" to mime)
            }

            fun archiveValue(value: Any?, imageData: Boolean = false): Any? = when (value) {
                is JSONObject -> archiveValue(value.keys().asSequence().associateWith { value.get(it) }, imageData)
                is JSONArray -> archiveValue((0 until value.length()).map { value.get(it) }, imageData)
                is Map<*, *> -> {
                    val isImageSource = value["type"] == "base64" &&
                        value["media_type"].toString().startsWith("image/")
                    value.entries.associate { (key, item) ->
                        key.toString() to if (isImageSource && key == "data" && item is String) {
                            imageReference(item, value["media_type"].toString())
                        } else archiveValue(item, key == "images" || key == "image_url" || (imageData && key == "url"))
                    }
                }
                is Iterable<*> -> value.map { archiveValue(it, imageData) }
                is String -> when {
                    imageData && value.startsWith("data:image/") && value.contains(";base64,") ->
                        imageReference(value.substringAfter(";base64,"), value.substringAfter("data:").substringBefore(';'))
                    imageData && !value.startsWith("https://") && !value.startsWith("http://") -> imageReference(value, "image/jpeg")
                    else -> value
                }
                else -> value
            }
            // Clients supply the JSON body, never HTTP headers. Preserve task/action text verbatim.
            val body = request.filterKeys {
                it.lowercase() !in setOf("headers", "authorization", "x-api-key", "api_key", "apikey")
            }
            @Suppress("UNCHECKED_CAST")
            writeJson(File(folder, "request_$attempt.json"), archiveValue(body) as Map<String, Any?>)
        }

    fun recordEvent(step: Int, type: String, data: Map<String, Any?>) = synchronized(archiveLock) {
        if (!File(directory, "run.json").isFile) return@synchronized
        val folder = stepDirectory(step).apply { mkdirs() }
        val event = mapOf("type" to type, "recorded_at" to System.currentTimeMillis(), "data" to data)
        File(folder, "events.jsonl").appendText(JSONObject(event).toString() + "\n")
    }

    fun finish(status: String) = synchronized(archiveLock) {
        activeRuns.remove(runId)
        imageRefs.clear()
        val file = File(directory, "run.json")
        if (file.exists()) {
            val metadata = JSONObject(file.readText())
                .put("status", status).put("finished_at", System.currentTimeMillis())
            file.writeText(metadata.toString(2))
            prune(root)
        }
    }

    fun readTree(step: Int): String? = synchronized(archiveLock) {
        File(sourceStepDirectory(step), "tree.txt").takeIf { it.exists() }?.readText()
    }

    fun readImage(step: Int): String? = synchronized(archiveLock) {
        File(sourceStepDirectory(step), "screenshot.jpg").takeIf { it.exists() }?.let {
            Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
        }
    }

    fun readStepDetails(step: Int): String? = synchronized(archiveLock) {
        events(sourceStepDirectory(step)).filter {
            it.getString("type") in setOf("proposed", "operation_issued", "execution", "effect", "state", "user_updates")
        }.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.toString() }
    }

    fun readStepDetailsPage(step: Int, offset: Int = 0, limit: Int = 4800): ContextTextPage? =
        synchronized(archiveLock) {
            readStepDetails(step)?.let {
                val ref = saveContextArtifact(it, step, "step_details")
                readContextArtifact(ref.artifactRef, offset, limit)
            }
        }

    fun saveContextArtifact(text: String, sourceStep: Int, label: String, version: Int = 1): ContextArtifactRef =
        synchronized(archiveLock) {
            check(File(directory, "run.json").isFile) { "Task archive is no longer available" }
            contextArtifacts.save(text, sourceStep, label, version)
        }

    fun readContextArtifact(artifactRef: String, offset: Int = 0, limit: Int = 4800): ContextTextPage =
        synchronized(archiveLock) {
            artifactStore(artifactRef).read(artifactRef, offset, limit)
        }

    fun contextArtifactReference(artifactRef: String): ContextArtifactRef =
        synchronized(archiveLock) { artifactStore(artifactRef).reference(artifactRef) }

    fun sourceRunForStep(step: Int): String = synchronized(archiveLock) {
        require(step in observationSteps()) { "No recorded observation for step $step" }
        stepSources[step] ?: runId
    }

    private fun artifactStore(artifactRef: String): ContextArtifactStore {
        if (artifactRef.startsWith("$runId:context:")) return contextArtifacts
        val source = requireNotNull(sourceRuns.firstOrNull { artifactRef.startsWith("$it:context:") }) {
            "Context artifact is not a source of this task"
        }
        return sourceArtifacts.getOrPut(source) {
            ContextArtifactStore(File(requireRun(root, source), "context_artifacts"), source)
        }
    }

    fun observationSteps(): List<Int> = synchronized(archiveLock) {
        (steps(directory) + stepSources.keys).distinct().sorted()
            .filter { File(sourceStepDirectory(it), "observation.json").exists() }
    }

    fun search(query: String, limit: Int = 3): List<Int> = synchronized(archiveLock) {
        require(query.isNotBlank()) { "History search query must not be blank" }
        val steps = observationSteps()
        val treeMatches = steps.filter { readTree(it)?.contains(query, ignoreCase = true) == true }
        val eventMatches = steps.filter { step ->
            events(sourceStepDirectory(step)).any { searchableText(it, step).contains(query, ignoreCase = true) }
        }
        (treeMatches + eventMatches).distinct().take(limit)
    }

    private fun searchableText(event: JSONObject, step: Int): String {
        val data = event.getJSONObject("data")
        return buildString {
            when (event.getString("type")) {
                "proposed" -> {
                    val updates = data.optJSONArray("memory_update")
                    for (index in 0 until (updates?.length() ?: 0)) {
                        val update = updates!!.optJSONObject(index) ?: continue
                        if (update.optString("op") == "upsert") {
                            appendLine(update.optString("key"))
                            appendLine(update.optString("value"))
                        }
                    }
                    if (data.optString("action_type") != "recall_history") {
                        listOf("text", "app_name", "keyword", "question", "note", "key", "value").forEach {
                            (data.opt(it) as? String)?.let { value -> appendLine(value) }
                        }
                    }
                }
                "user_updates" -> {
                    val updates = data.getJSONArray("updates")
                    for (index in 0 until updates.length()) {
                        val update = updates.getJSONObject(index)
                        if (update.getInt("step") == step) appendLine(update.getString("text"))
                    }
                }
                "effect" -> append(data.getString("evidence"))
                "execution" -> if (data.optString("action_type") == "list_apps") append(data.optString("feedback"))
            }
        }
    }

    private fun stepDirectory(step: Int) = File(directory, "steps/$step")

    private fun sourceStepDirectory(step: Int): File = stepSources[step]?.let { source ->
        require(source in sourceRuns) { "Step source is not registered for this task" }
        File(requireRun(root, source), "steps/$step")
    } ?: stepDirectory(step)

    companion object {
        private const val ROOT = "agent_runs"
        private const val KEEP_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_FINISHED_BYTES = 500L * 1024 * 1024
        private val archiveLock = Any()

        fun isRunActive(runId: String): Boolean = synchronized(archiveLock) { runId in activeRuns }

        fun readRunOutcome(context: Context, runId: String): String? = synchronized(archiveLock) {
            File(context.filesDir, "$ROOT/$runId/run.json").takeIf { it.isFile }?.let {
                JSONObject(it.readText()).getString("status")
            }
        }
        private val activeRuns = mutableSetOf<String>()

        private fun requireRun(root: File, runId: String): File = requireNotNull(
            root.listFiles().orEmpty().firstOrNull { it.isDirectory && it.name == runId }
        ) { "Task run archive is unavailable: $runId" }

        /** Only folds durable facts. A proposed action is never sent to an executor. */
        fun readCheckpoint(context: Context, runId: String): TaskCheckpoint? = synchronized(archiveLock) {
            val root = File(context.filesDir, ROOT)
            val folder = requireRun(root, runId)
            val file = File(folder, "checkpoint.json")
            if (!file.exists()) return@synchronized null
            val checkpoint = TaskCheckpoint.fromJson(JSONObject(file.readText()))
            val records = checkpoint.steps.associateBy { (it.getValue("number") as Number).toInt() }
                .mapValues { it.value.toMutableMap() }.toMutableMap()
            var state = checkpoint.state
            var updates = checkpoint.userUpdates
            val sources = checkpoint.stepSources.toMutableMap()
            for (step in steps(folder).filter { it >= checkpoint.traceStep }) {
                val tail = events(File(folder, "steps/$step")).drop(if (step == checkpoint.traceStep) checkpoint.traceEventCount else 0)
                if (tail.isEmpty()) continue
                val record = records.getOrPut(step) { mutableMapOf("number" to step,
                    "decision" to emptyMap<String, Any?>(), "execution" to emptyMap<String, Any?>(), "effect" to null) }
                sources[step] = runId
                for (event in tail) {
                    val data = ResponseParser.jsonObjectToMap(event.getJSONObject("data"))
                    when (event.getString("type")) {
                        "proposed" -> record["decision"] = data
                        "state" -> state = data + ("human_intervention_count" to maxOf(
                            (state.getValue("human_intervention_count") as Number).toInt(),
                            (data.getValue("human_intervention_count") as Number).toInt()))
                        "user_updates" -> {
                            @Suppress("UNCHECKED_CAST")
                            updates = data.getValue("updates") as List<Map<String, Any?>>
                        }
                        "operation_issued", "execution" -> {
                            record["execution"] = data
                            if (data["execution_status"] in TaskState.DISPATCHED && data["action_type"] in AgentEngine.SIDE_EFFECT_ACTIONS) {
                                record["effect"] = mapOf("status" to "unknown", "evidence" to "Execution needs a fresh observation")
                            }
                        }
                        "effect" -> (data["target_step"] as? Number)?.toInt()?.let { target ->
                            records[target]?.set("effect", data.filterKeys { it != "target_step" })
                        }
                    }
                }
            }
            checkpoint.copy(state = state, steps = records.toSortedMap().values.toList(), userUpdates = updates,
                stepSources = sources, nextStep = maxOf(checkpoint.nextStep, (records.keys.maxOrNull() ?: 0) + 1))
        }

        fun releaseTaskRetention(context: Context, taskId: Long) = synchronized(archiveLock) {
            val root = File(context.filesDir, ROOT)
            root.listFiles().orEmpty().forEach { folder ->
                val file = File(folder, "run.json")
                if (file.isFile) {
                    val metadata = JSONObject(file.readText())
                    if (metadata.optLong("retain_for_task", -1) == taskId) {
                        metadata.remove("retain_for_task")
                        writeAtomic(file, metadata.toString(2))
                    }
                }
            }
            prune(root)
        }

        internal fun writeAtomic(file: File, text: String) {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(temporary).use { output -> output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        private fun writeJson(file: File, value: Map<String, Any?>) {
            file.writeText(JSONObject(value).toString(2))
        }

        private fun steps(directory: File): List<Int> =
            File(directory, "steps").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }.sorted()

        private fun events(stepDirectory: File): List<JSONObject> {
            val file = File(stepDirectory, "events.jsonl")
            if (!file.exists()) return emptyList()
            // A killed process may leave the last append incomplete. The issued checkpoint
            // remains authoritative; only complete newline-terminated events can advance it.
            return file.readText().substringBeforeLast('\n', "").lineSequence()
                .filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
        }

        private fun runs(context: Context, conversationId: Long): List<File> =
            File(context.filesDir, ROOT).listFiles().orEmpty().filter { folder ->
                File(folder, "run.json").takeIf { it.isFile }?.let {
                    val metadata = JSONObject(it.readText())
                    !metadata.isNull("conversation_id") && metadata.getLong("conversation_id") == conversationId
                } == true
            }.sortedBy { it.name }

        fun deleteConversation(context: Context, conversationId: Long) = synchronized(archiveLock) {
            val archives = runs(context, conversationId)
            val taskIds = archives.mapNotNull { folder ->
                val metadata = JSONObject(File(folder, "run.json").readText())
                if (metadata.isNull("task_id")) null else metadata.getLong("task_id")
            }.distinct()
            archives.forEach { it.deleteRecursively() }
            taskIds.forEach {
                TaskTeamRuntime.delete(context, it)
                ai.opencyvis.execution.TaskExecutionRuntime.delete(context, it)
            }
            File(context.cacheDir, "run_exports").listFiles().orEmpty().filter {
                it.name.startsWith("conversation_${conversationId}_")
            }.forEach { it.delete() }
        }

        /** Called on application startup alongside stale database conversation cleanup. */
        fun markInterruptedRuns(context: Context) = synchronized(archiveLock) {
            val root = File(context.filesDir, ROOT)
            root.listFiles().orEmpty().forEach { folder ->
                val file = File(folder, "run.json")
                if (file.isFile) {
                    val metadata = JSONObject(file.readText())
                    if (metadata.optString("status") == "running" && folder.name !in activeRuns) {
                        metadata.put("status", "interrupted").put("finished_at", System.currentTimeMillis())
                        file.writeText(metadata.toString(2))
                    }
                }
            }
            prune(root)
        }

        private fun prune(root: File) {
            val completed = root.listFiles().orEmpty().mapNotNull { folder ->
                val file = File(folder, "run.json")
                if (!file.isFile) return@mapNotNull null
                val metadata = JSONObject(file.readText())
                if (metadata.optString("status") == "running" ||
                    metadata.has("retain_for_task") && !metadata.isNull("retain_for_task")) return@mapNotNull null
                folder to metadata.getLong("finished_at")
            }.sortedBy { it.second }
            val cutoff = System.currentTimeMillis() - KEEP_MILLIS
            val retained = completed.filter { (folder, finishedAt) ->
                if (finishedAt < cutoff) {
                    folder.deleteRecursively()
                    false
                } else true
            }
            val sizes = retained.map { (folder, _) -> folder to folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            var total = sizes.sumOf { it.second }
            sizes.forEach { (folder, bytes) ->
                if (total > MAX_FINISHED_BYTES) {
                    folder.deleteRecursively()
                    total -= bytes
                }
            }
        }

        /** Returns a ZIP ready to share through the app's cache-only FileProvider. */
        fun exportConversation(context: Context, conversationId: Long): File? = synchronized(archiveLock) {
            prune(File(context.filesDir, ROOT))
            val archives = runs(context, conversationId)
            if (archives.isEmpty() && !ai.opencyvis.memory.MemoryExport.hasRecords(context, conversationId)) return@synchronized null
            val exports = File(context.cacheDir, "run_exports").apply { mkdirs() }
            exports.listFiles().orEmpty().filter {
                it.name.startsWith("conversation_${conversationId}_") ||
                    it.lastModified() < System.currentTimeMillis() - KEEP_MILLIS
            }.forEach { it.delete() }
            val destination = File(exports, "conversation_${conversationId}_${System.currentTimeMillis()}.zip")
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                ai.opencyvis.memory.MemoryExport.write(context, conversationId, zip)
                archives.forEach { folder ->
                    val prefix = folder.name + "/"
                    folder.walkTopDown().filter { it.isFile }.forEach { file ->
                        zip.putNextEntry(ZipEntry(prefix + file.relativeTo(folder).invariantSeparatorsPath))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    val summary = buildString {
                        val metadata = JSONObject(File(folder, "run.json").readText())
                        appendLine("# 任务运行记录")
                        appendLine()
                        appendLine(metadata.getString("instruction"))
                        appendLine()
                        appendLine("- 状态：${metadata.getString("status")}")
                        appendLine("- 创建时间（Unix 毫秒）：${metadata.getLong("created_at")}")
                        appendLine("- [原始任务信息](run.json)")
                        appendLine("- token 用量见各步 usage 事件；服务端未返回时为未提供。")
                        steps(folder).forEach { step ->
                            val stepFolder = File(folder, "steps/$step")
                            appendLine()
                            appendLine("## 第 $step 步")
                            appendLine()
                            if (File(stepFolder, "screenshot.jpg").exists()) {
                                appendLine("![动作前观测](steps/$step/screenshot.jpg)")
                            }
                            if (File(stepFolder, "tree.txt").exists()) appendLine("- [无障碍树](steps/$step/tree.txt)")
                            if (File(stepFolder, "observation.json").exists()) appendLine("- [观测来源与时间](steps/$step/observation.json)")
                            stepFolder.listFiles().orEmpty().filter {
                                it.name.startsWith("request_") && it.extension == "json"
                            }.sortedBy { it.nameWithoutExtension.substringAfter("request_").toInt() }.forEach {
                                appendLine("- [模型输入 ${it.name}](steps/$step/${it.name})")
                            }
                            val events = events(stepFolder)
                            val overlay = clickOverlay(stepFolder, events)
                            if (overlay != null) {
                                zip.putNextEntry(ZipEntry("${prefix}steps/$step/clicks.png"))
                                overlay.compress(Bitmap.CompressFormat.PNG, 100, zip)
                                zip.closeEntry()
                                overlay.recycle()
                                appendLine("![实际执行位置](steps/$step/clicks.png)")
                            }
                            events.forEach { event ->
                                appendLine()
                                appendLine("### ${event.getString("type")}")
                                appendLine()
                                appendLine("```json")
                                appendLine(event.getJSONObject("data").toString(2))
                                appendLine("```")
                            }
                        }
                    }
                    zip.putNextEntry(ZipEntry(prefix + "summary.md"))
                    zip.write(summary.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            destination
        }

        private fun clickOverlay(folder: File, events: List<JSONObject>): Bitmap? {
            val execution = events.lastOrNull { event ->
                event.optString("type") == "execution" && event.getJSONObject("data").optBoolean("executed")
            }?.getJSONObject("data") ?: return null
            if (execution.optString("action_type") !in setOf("tap", "long_press", "swipe")) return null
            val points = execution.optJSONArray("normalized_points") ?: return null
            if (points.length() == 0) return null
            val source = BitmapFactory.decodeFile(File(folder, "screenshot.jpg").path) ?: return null
            val bitmap = checkNotNull(source.copy(Bitmap.Config.ARGB_8888, true))
            source.recycle()
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED
                strokeWidth = (bitmap.width / 180f).coerceAtLeast(3f)
                style = Paint.Style.STROKE
            }
            val pixels = execution.optJSONArray("pixel_points")
            val width = execution.optDouble("display_width", 0.0)
            val height = execution.optDouble("display_height", 0.0)
            var previous: Pair<Float, Float>? = null
            for (index in 0 until points.length()) {
                val point = points.getJSONArray(index)
                val pixel = pixels?.optJSONArray(index)
                val x = if (pixel != null && width > 0) pixel.getDouble(0) / width else point.getDouble(0) / 1000.0
                val y = if (pixel != null && height > 0) pixel.getDouble(1) / height else point.getDouble(1) / 1000.0
                val px = (x * bitmap.width).toFloat()
                val py = (y * bitmap.height).toFloat()
                val radius = (bitmap.width / 40f).coerceAtLeast(10f)
                previous?.let { canvas.drawLine(it.first, it.second, px, py, paint) }
                canvas.drawCircle(px, py, radius, paint)
                canvas.drawLine(px - radius, py, px + radius, py, paint)
                canvas.drawLine(px, py - radius, px, py + radius, paint)
                previous = px to py
            }
            return bitmap
        }
    }
}
