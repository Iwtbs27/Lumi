package ai.opencyvis.task

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.util.Log
import ai.opencyvis.AgentService
import ai.opencyvis.accessibility.VdAccessibilityService
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.config.ContextBudgetConfig
import ai.opencyvis.engine.AgentState
import ai.opencyvis.engine.TaskRunStore
import ai.opencyvis.engine.TaskState
import ai.opencyvis.memory.MemoryRepository
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.work.WorkManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real service, queue, engine, privilege backend and VD. Only model HTTP responses are scripted.
 * Run on the dedicated API 34 test device with its privilege backend and accessibility enabled.
 * For restart coverage, pass m2_restart_stage=prepare, wait for the manifest, force-stop the
 * still-running target process, then run recover with m2_restart_stage=recover. The prepare
 * instrumentation is expected to be aborted by that external stop; it does not return success.
 */
@RunWith(AndroidJUnit4::class)
class TaskServiceGuiDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val output get() = File(context.getExternalFilesDir(null), "m2-gui-e2e").apply { mkdirs() }
    private val restartManifest get() = File(output, "restart-prepared.json")

    @Test fun queueLifecycle(): Unit = runBlocking {
        session { service, model, ids ->
            val first = service.enqueueTask(TaskRequest("M2 GUI FIFO first: open Settings and wait", source = SOURCE))
                .also { ids += it.id }
            val second = service.enqueueTask(TaskRequest("M2 GUI FIFO second: must be cancelled", source = SOURCE))
                .also { ids += it.id }
            val third = service.enqueueTask(TaskRequest("M2 GUI FIFO third: open Settings then Back", source = SOURCE))
                .also { ids += it.id }
            val initial = model.next(first.instruction)
            initial.reply("open_app", "app_name" to "settings")
            val onSettings = model.next(first.instruction)
            val firstDisplay = captureSettings(service, "first-settings")
            onSettings.reply("ask_user", "question" to QUESTION)
            val waiting = awaitStatus(service, first.id, TaskStatus.WAITING_USER)
            val firstRun = requireNotNull(waiting.runId)
            assertTrue(waiting.guiHeld)
            assertEquals(first.id, service.activeTaskId.value)
            service.cancelTask(second.id)
            val cancelled = awaitStatus(service, second.id, TaskStatus.CANCELLED)
            assertNull(cancelled.runId)
            assertNull(cancelled.conversationId)
            assertEquals(TaskStatus.QUEUED, service.getTask(third.id)!!.status)

            // Ordinary pause/resume retains both the display and the unanswered question.
            service.pauseTask(first.id)
            awaitStatus(service, first.id, TaskStatus.PAUSED)
            assertEquals(firstDisplay, service.getVirtualDisplayManager()!!.displayId)
            service.resumeTask(first.id)
            awaitStatus(service, first.id, TaskStatus.WAITING_USER)
            assertTrue(service.getEngine()!!.state.value is AgentState.WaitingForUser)
            delay(500)
            assertEquals(2, model.requests.size)
            assertEquals(TaskStatus.QUEUED, service.getTask(third.id)!!.status)

            service.suspendTaskAndRunNext(first.id)
            model.next(third.instruction).reply("open_app", "app_name" to "settings")
            val thirdSettings = model.next(third.instruction)
            val parked = service.getTask(first.id)!!
            assertEquals(TaskStatus.PAUSED, parked.status)
            assertFalse(parked.guiHeld)
            val thirdDisplay = captureSettings(service, "third-settings")
            val thirdRun = requireNotNull(service.getTask(third.id)!!.runId)
            thirdSettings.reply("key_event", "key" to "back")
            val afterBack = model.next(third.instruction)
            waitUntil { VdAccessibilityService.getTopPackageOnDisplay(thirdDisplay)?.let { it != "com.android.settings" } == true }
            capturePage(service, "third-after-back")
            afterBack.reply("finish")
            awaitStatus(service, third.id, TaskStatus.SUCCEEDED)

            val checkpoint = requireNotNull(TaskRunStore.readCheckpoint(context, firstRun))
            service.recoverTask(first.id)
            val resumed = model.next(first.instruction)
            val newRun = requireNotNull(service.getTask(first.id)!!.runId)
            assertNotEquals(firstRun, newRun)
            assertFreshObservation(resumed, newRun, checkpoint.nextStep)
            resumed.reply("recall_history", "recall_steps" to listOf(1), "recall_content" to "text")
            val recalled = model.next(first.instruction)
            assertTrue("Original action must be readable from the source run", recalled.text.contains(OPEN_THOUGHT))
            recalled.reply("finish")
            val completed = awaitStatus(service, first.id, TaskStatus.SUCCEEDED)
            assertEquals(waiting.conversationId, completed.conversationId)
            assertRun(firstRun, first.id, listOf("open_app"))
            assertRun(thirdRun, third.id, listOf("open_app", "key_event"))
            assertRun(newRun, first.id, emptyList())
            assertEquals(listOf(first.instruction, first.instruction, third.instruction,
                third.instruction, third.instruction, first.instruction, first.instruction),
                model.requests.map { it.expectedInstruction })
            File(output, "queue-result.json").writeText(JSONObject().put("task_ids", JSONArray(ids))
                .put("first_run", firstRun).put("third_run", thirdRun).put("resumed_run", newRun)
                .put("first_display", firstDisplay).put("third_display", thirdDisplay)
                .put("backend", service.activeBackendName).put("result", "passed").toString(2))
        }
    }

    @Test fun prepareProcessRestart(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m2_restart_stage") == "prepare")
        withTimeout(180_000) { session(leaveRunning = true) { service, model, ids ->
            check(!restartManifest.exists()) { "Finish or remove this test's previous restart manifest before preparing again" }
            val task = service.enqueueTask(TaskRequest("M2 GUI process restart: retain pending Settings action", source = SOURCE))
                .also { ids += it.id }
            model.next(task.instruction).reply("open_app", "app_name" to "settings")
            val opened = model.next(task.instruction)
            val display = captureSettings(service, "before-process-stop")
            opened.reply("ask_user", "question" to QUESTION)
            val waiting = awaitStatus(service, task.id, TaskStatus.WAITING_USER)
            val runId = requireNotNull(waiting.runId)
            val saved = requireNotNull(TaskRunStore.readCheckpoint(context, runId))
            assertEquals("unknown", TaskState.restore(saved).steps.getValue(1).effect!!["status"])
            restartManifest.writeText(JSONObject().put("task_id", task.id).put("run_id", runId)
                .put("instruction", task.instruction).put("conversation_id", waiting.conversationId)
                .put("process_id", Process.myPid()).put("display_id", display)
                .put("next_step", saved.nextStep).put("prepared_at", System.currentTimeMillis()).toString(2))
            awaitCancellation()
        } }
    }

    @Test fun recoverAfterProcessRestart(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m2_restart_stage") == "recover")
        check(restartManifest.isFile) { "Run prepareProcessRestart and force-stop the target app first" }
        val prepared = JSONObject(restartManifest.readText())
        assertNotEquals("This stage requires an actual new Android process", prepared.getInt("process_id"), Process.myPid())
        session(recovering = true) { service, model, ids ->
            val id = prepared.getLong("task_id").also { ids += it }
            val oldRun = prepared.getString("run_id")
            val interrupted = awaitStatus(service, id, TaskStatus.INTERRUPTED)
            assertFalse(interrupted.guiHeld)
            assertEquals(oldRun, interrupted.runId)
            assertEquals(prepared.getLong("conversation_id"), interrupted.conversationId)
            val oldCheckpoint = requireNotNull(TaskRunStore.readCheckpoint(context, oldRun))
            assertEquals("unknown", TaskState.restore(oldCheckpoint).steps.getValue(1).effect!!["status"])
            service.recoverTask(id)
            val fresh = model.next(prepared.getString("instruction"))
            val newRun = requireNotNull(service.getTask(id)!!.runId)
            assertNotEquals(oldRun, newRun)
            assertFreshObservation(fresh, newRun, prepared.getInt("next_step"))
            assertTrue(fresh.text.contains(QUESTION))
            fresh.reply("recall_history", "recall_steps" to listOf(1), "recall_content" to "text")
            val recalled = model.next(prepared.getString("instruction"))
            assertTrue(recalled.text.contains(OPEN_THOUGHT))
            recalled.reply("finish")
            awaitStatus(service, id, TaskStatus.SUCCEEDED)
            assertRun(oldRun, id, listOf("open_app"))
            assertRun(newRun, id, emptyList())
            File(output, "restart-result.json").writeText(JSONObject(prepared.toString())
                .put("new_process_id", Process.myPid()).put("resumed_run", newRun)
                .put("result", "passed").toString(2))
            check(restartManifest.delete())
        }
    }

    private suspend fun session(leaveRunning: Boolean = false, recovering: Boolean = false,
        block: suspend (AgentService, LocalModel, MutableList<Long>) -> Unit) {
        rebindAccessibilityForInstrumentation()
        if (!recovering) {
            val current = TaskRepository(context).tasks.first()
            check(current.none { it.status == TaskStatus.QUEUED || it.status in TaskStatus.ACTIVE && it.guiHeld }) {
                "Use a test device without other active or queued tasks"
            }
        }
        val ids = mutableListOf<Long>()
        val model = LocalModel()
        val connected = CompletableDeferred<AgentService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete((binder as AgentService.AgentBinder).getService())
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        var service: AgentService? = null
        var bound = false
        var keep = false
        var primaryFailure: Throwable? = null
        try {
            configureLocalModel(model.port)
            withContext(Dispatchers.Main) {
                val intent = Intent(context, AgentService::class.java)
                context.startForegroundService(intent)
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                check(bound)
            }
            val active = withTimeout(60_000) { connected.await() }
            service = active
            waitUntil { active.activeBackendName != null && VdAccessibilityService.instance != null }
            val capabilities = requireNotNull(active.getInputBackend()).capabilities
            // The old probe uses DisplayManager, while creation uses DisplayManagerGlobal.
            // Prove VD support below through the real display, frame and input result.
            assertTrue("Input injection is required: $capabilities", capabilities.canInjectInput)
            block(active, model, ids)
            keep = leaveRunning
        } catch (failure: Throwable) {
            primaryFailure = failure
            recordFailure("scenario", failure)
        } finally {
            withContext(NonCancellable) {
                suspend fun cleanup(phase: String, action: suspend () -> Unit) {
                    try { action() }
                    catch (failure: Throwable) {
                        recordFailure(phase, failure)
                        val original = primaryFailure
                        if (original == null) primaryFailure = failure else original.addSuppressed(failure)
                    }
                }
                if (!keep) {
                    cleanup("cancel-tasks") {
                        service?.let { active -> ids.forEach { withTimeout(30_000) { active.cancelTaskAndWait(it) } } }
                    }
                    // Stopping a task can open ViewActivity, which holds its own service binding.
                    cleanup("finish-activities") { finishTestActivities() }
                    cleanup("unbind") { if (bound) withContext(Dispatchers.Main) { context.unbindService(connection) } }
                    cleanup("stop-service") {
                        service?.let { active ->
                            withContext(Dispatchers.Main) { context.stopService(Intent(context, AgentService::class.java)) }
                            waitUntil { active.getInputBackend() == null }
                        }
                    }
                    cleanup("cancel-memory-jobs") {
                        // Only this test's jobs are cancelled before restoring the former endpoint.
                        val runs = runDirectories(ids).map { it.name }.toSet()
                        val memory = MemoryRepository(context)
                        memory.listJobs().filter { it.runId in runs }.forEach { job ->
                            memory.updateJob(job.copy(status = "cancelled", updatedAt = System.currentTimeMillis()))
                            withContext(Dispatchers.IO) {
                                WorkManager.getInstance(context).cancelUniqueWork("memory:${job.id}").result.get(10, TimeUnit.SECONDS)
                            }
                        }
                    }
                    cleanup("restore-config") { restoreConfig() }
                } else cleanup("unbind") { if (bound) withContext(Dispatchers.Main) { context.unbindService(connection) } }
                cleanup("close-model") { model.close() }
            }
        }
        primaryFailure?.let { throw it }
    }

    private fun recordFailure(phase: String, failure: Throwable) {
        Log.e("M2GuiAcceptance", "$phase failed", failure)
        try { File(output, "failure-$phase.txt").writeText(failure.stackTraceToString()) }
        catch (archiveFailure: Exception) { Log.e("M2GuiAcceptance", "Unable to save failure details", archiveFailure) }
    }

    private suspend fun finishTestActivities() {
        val activities = withContext(Dispatchers.Main) {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().filter { it != Stage.DESTROYED }
                .flatMap { monitor.getActivitiesInStage(it) }
                .filter { it.packageName == context.packageName }.distinct().onEach { it.finish() }
        }
        withTimeout(10_000) {
            while (withContext(Dispatchers.Main) { activities.any { !it.isDestroyed } }) delay(100)
        }
    }

    /** Instrumentation restarts the app process; reconnect its already-enabled accessibility service. */
    private suspend fun rebindAccessibilityForInstrumentation() {
        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver = context.contentResolver
        val original = requireNotNull(Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        val target = ComponentName(context, VdAccessibilityService::class.java)
        val entries = original.split(':')
        check(entries.any { ComponentName.unflattenFromString(it) == target }) {
            "Enable the real VdAccessibilityService on this test device before running this test"
        }
        val accessibilityEnabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                entries.filter { ComponentName.unflattenFromString(it) != target }.joinToString(":")))
            delay(500)
        } finally {
            try {
                check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original))
                check(Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, accessibilityEnabled))
            } finally { automation.dropShellPermissionIdentity() }
        }
        waitUntil { VdAccessibilityService.instance != null }
    }

    private suspend fun awaitStatus(service: AgentService, id: Long, expected: String): TaskRecord = withTimeout(60_000) {
        service.tasks.first { records -> records.any { it.id == id && it.status == expected } }.single { it.id == id }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(60_000) {
        while (!predicate()) delay(100)
    }

    private suspend fun captureSettings(service: AgentService, name: String): Int {
        val display = requireNotNull(service.getVirtualDisplayManager())
        assertTrue(display.displayId > 0)
        waitUntil { VdAccessibilityService.getTopPackageOnDisplay(display.displayId) == "com.android.settings" }
        return capturePage(service, name)
    }

    private fun capturePage(service: AgentService, name: String): Int {
        val display = requireNotNull(service.getVirtualDisplayManager())
        val tree = requireNotNull(VdAccessibilityService.captureViewTree(display.displayId, display.width, display.height))
        assertTrue(tree.isNotBlank())
        File(output, "$name-tree.txt").writeText(tree)
        val frame = requireNotNull(display.captureFrame()) { "Real VD capture is unavailable" }
        try {
            assertEquals(display.displayId, frame.displayId)
            assertTrue(frame.bitmap.width > 0 && frame.bitmap.height > 0)
            File(output, "$name.png").outputStream().use { assertTrue(frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { frame.bitmap.recycle() }
        return display.displayId
    }

    private fun assertFreshObservation(request: LocalModel.Request, run: String, step: Int) {
        assertTrue(request.text.contains("current_step=$step"))
        assertTrue(request.hasImage)
        val observation = JSONObject(File(context.filesDir, "agent_runs/$run/steps/$step/observation.json").readText())
        assertTrue(observation.getInt("display_id") > 0)
        assertTrue(File(context.filesDir, "agent_runs/$run/steps/$step/screenshot.jpg").length() > 0)
    }

    private fun assertRun(run: String, taskId: Long, expectedIssued: List<String>) {
        val directory = File(context.filesDir, "agent_runs/$run")
        assertEquals(taskId, JSONObject(File(directory, "run.json").readText()).getLong("task_id"))
        val events = File(directory, "steps").listFiles().orEmpty().sortedBy { it.name.toInt() }
            .flatMap { File(it, "events.jsonl").takeIf(File::isFile)?.readLines().orEmpty() }.map(::JSONObject)
        val issued = events.filter { it.getString("type") == "operation_issued" }
            .map { it.getJSONObject("data").getString("action_type") }
        val executed = events.filter { it.getString("type") == "execution" &&
            it.getJSONObject("data").optString("execution_status") == "executed" }
            .map { it.getJSONObject("data").getString("action_type") }
        assertEquals(expectedIssued, issued)
        assertEquals(expectedIssued, executed)
    }

    private fun runDirectories(ids: List<Long>): List<File> = File(context.filesDir, "agent_runs").listFiles().orEmpty()
        .filter { folder -> File(folder, "run.json").takeIf(File::isFile)?.let {
            JSONObject(it.readText()).optLong("task_id", -1) in ids
        } == true }

    private val configBackup get() = File(context.cacheDir, "m2-gui-config-backup.json")
    private fun configureLocalModel(port: Int) {
        val preferences = context.getSharedPreferences("opencyvis_config", Context.MODE_PRIVATE)
        if (!configBackup.exists()) configBackup.writeText(JSONObject().apply {
            CONFIG_KEYS.forEach { put(it, preferences.all[it] ?: JSONObject.NULL) }
        }.toString())
        ConfigRepository(context).apply {
            applyProfile(ConfigRepository.PROVIDER_OPENAI, "local-device-test", "local-m2-script",
                "http://127.0.0.1:$port/v1", "", ContextBudgetConfig.preset(128_000))
            maxSteps = 12
            debugMode = true
            blacklistedPackages = emptySet()
        }
    }

    private fun restoreConfig() {
        if (!configBackup.exists()) return
        val saved = JSONObject(configBackup.readText())
        val editor = context.getSharedPreferences("opencyvis_config", Context.MODE_PRIVATE).edit()
        CONFIG_KEYS.forEach { key ->
            editor.remove(key)
            when (val value = saved.get(key)) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
            }
        }
        check(editor.commit())
        check(configBackup.delete())
    }

    /** Minimal SSE fixture using the same phone_action wire contract as tests/e2e/mock_llm_server.py. */
    private class LocalModel : AutoCloseable {
        class Request(val body: JSONObject) {
            val response = CompletableDeferred<JSONObject>()
            var expectedInstruction = ""
            val text: String get() = body.getJSONArray("messages").let { messages ->
                (0 until messages.length()).joinToString("\n") { index ->
                    when (val content = messages.getJSONObject(index).get("content")) {
                        is JSONArray -> (0 until content.length()).map { content.getJSONObject(it) }
                            .filter { it.optString("type") == "text" }.joinToString("\n") { it.getString("text") }
                        else -> content.toString()
                    }
                }
            }
            val hasImage: Boolean get() {
                val messages = body.getJSONArray("messages")
                val content = messages.getJSONObject(messages.length() - 1).optJSONArray("content") ?: return false
                return (0 until content.length()).any { index ->
                    val block = content.getJSONObject(index)
                    if (block.optString("type") != "image_url") false else {
                        val url = block.getJSONObject("image_url").getString("url")
                        url.startsWith("data:image/") && url.substringAfter(";base64,", "").isNotBlank()
                    }
                }
            }
            fun reply(action: String, vararg values: Pair<String, Any>) {
                response.complete(JSONObject(mapOf("thought" to if (action == "open_app") OPEN_THOUGHT else "M2 local $action",
                    "action_type" to action, "memory_update" to emptyList<Any>()) + values.toMap()))
            }
        }

        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val requests = CopyOnWriteArrayList<Request>()
        private val pending = Channel<Request>(Channel.UNLIMITED)
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val executor = Executors.newCachedThreadPool { work -> Thread(work, "m2-local-model").apply { isDaemon = true } }
        init {
            executor.execute {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                    sockets += socket
                    executor.execute {
                        try { socket.use { handle(it) } }
                        catch (failure: Exception) { if (!server.isClosed) pending.close(failure) }
                        finally { sockets -= socket }
                    }
                }
            }
        }
        suspend fun next(instruction: String): Request = withTimeout(60_000) {
            pending.receive().also { request ->
                val taskMessage = request.body.getJSONArray("messages").getJSONObject(1)
                assertEquals("user", taskMessage.getString("role"))
                assertEquals("[task]\n$instruction", taskMessage.getString("content"))
                request.expectedInstruction = instruction
                assertTrue("Current observation must carry a nonempty image_url data URI", request.hasImage)
            }
        }
        private fun handle(socket: Socket) = runBlocking {
            val input = BufferedInputStream(socket.getInputStream())
            readLine(input) // Request line.
            var length = 0
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
            }
            check(length > 0)
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) { val size = input.read(bytes, read, length - read); check(size > 0); read += size }
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            val name = body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name")
            val output = socket.getOutputStream()
            if (name != "phone_action") {
                // This fixture does not generate long-term memories; background jobs terminate locally.
                val error = "{\"error\":{\"message\":\"Only GUI acceptance responses are scripted\"}}"
                output.write("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: ${error.length}\r\nConnection: close\r\n\r\n$error".toByteArray())
                return@runBlocking
            }
            val request = Request(body)
            requests += request
            pending.send(request)
            val response = request.response.await()
            val delta = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0)
                .put("id", "call_m2_local").put("type", "function").put("function", JSONObject()
                    .put("name", "phone_action").put("arguments", response.toString()))))
            val chunks = listOf(
                JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta).put("finish_reason", JSONObject.NULL))),
                JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", "tool_calls")))
            )
            val stream = chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(stream.toByteArray(Charsets.UTF_8))
            output.flush()
        }
        private fun readLine(input: BufferedInputStream): String {
            val line = StringBuilder()
            while (true) {
                val next = input.read()
                check(next >= 0)
                if (next == '\n'.code) return line.toString().removeSuffix("\r")
                line.append(next.toChar())
            }
        }
        override fun close() {
            server.close()
            sockets.forEach { it.close() }
            executor.shutdownNow()
        }
    }

    private companion object {
        const val SOURCE = "device:m2-gui"
        const val QUESTION = "M2 user decision: keep the Settings task parked?"
        const val OPEN_THOUGHT = "M2 local GUI evidence: open real Settings"
        val CONFIG_KEYS = listOf("api_provider", "api_key", "model", "base_url", "active_profile",
            "context_budget_json", "max_steps", "debug_mode", "blacklisted_packages")
    }
}
