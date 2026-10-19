package ai.opencyvis.team

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import ai.opencyvis.AgentService
import ai.opencyvis.R
import ai.opencyvis.accessibility.VdAccessibilityService
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.config.ContextBudgetConfig
import ai.opencyvis.config.TeamConfig
import ai.opencyvis.memory.MemoryRepository
import ai.opencyvis.task.TaskRecord
import ai.opencyvis.task.TaskRepository
import ai.opencyvis.task.TaskRequest
import ai.opencyvis.testing.LocalAgentModel as LocalModel
import ai.opencyvis.task.TaskStatus
import ai.opencyvis.ui.TaskQueueActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real queue, service, engine, privilege backend and VD; only model HTTP is scripted.
 * Requires the dedicated test device's real privilege backend and accessibility service.
 */
@RunWith(AndroidJUnit4::class)
class TeamServiceGuiDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val output get() = File(context.getExternalFilesDir(null), "m3-team-e2e").apply { mkdirs() }

    @Test fun parentGuiContinuesWhileChildReadsThenReviews(): Unit = runBlocking {
        session { service, model, ids ->
            val task = service.enqueueTask(TaskRequest(
                "M3 collaboration: inspect Settings, keep operating while a child checks the captured evidence",
                source = "device:m3-team")).also { ids += it.id }
            model.nextParent(task.instruction).reply("open_app", "app_name" to "settings")
            val settings = model.nextParent(task.instruction)
            val displayId = captureSettings(service)
            // Step 2 becomes an immutable completed observation before step 3 delegates it.
            settings.reply("wait")
            model.nextParent(task.instruction).reply("delegate_task", "child_goal" to CHILD_GOAL,
                "evidence_steps" to listOf(2), "child_constraints" to listOf("Report only the supplied observation"))

            val childRead = model.nextChild()
            val frozen = JSONObject(childRead.body.getJSONArray("messages").getJSONObject(1).getString("content"))
            val childId = frozen.getString("child_id")
            val material = frozen.getJSONArray("materials").getJSONObject(0)
            val reference = material.getString("reference")
            val version = material.getInt("version")
            val runId = frozen.getString("root_run_id")
            assertEquals(task.id, frozen.getLong("root_task_id"))
            assertEquals(runId, material.getString("source_run_id"))
            assertEquals(2, material.getInt("source_step"))
            assertEquals(CHILD_GOAL, frozen.getString("goal"))
            val engine = requireNotNull(service.getEngine())
            childRead.reply("read_evidence", "reference" to reference, "version" to version,
                "offset" to 0, "limit" to 256)
            val childMore = model.nextChild()
            assertTrue("The child must receive the real archived screenshot", childMore.hasImage)
            val firstPage = childMore.lastUserObject()
            assertEquals(reference, firstPage.getJSONObject("material").getString("reference"))
            assertEquals(version, firstPage.getJSONObject("material").getInt("version"))
            val nextOffset = firstPage.getInt("next_offset")
            assertTrue(nextOffset > 0)

            // Keep this child HTTP request pending while the real parent dispatches Back.
            model.nextParent(task.instruction).reply("key_event", "key" to "back")
            val afterBack = model.nextParent(task.instruction)
            assertFalse("Child response must still be held during parent progress", childMore.response.isCompleted)
            assertEquals(displayId, requireNotNull(service.getVirtualDisplayManager()).displayId)
            waitUntil { VdAccessibilityService.getTopPackageOnDisplay(displayId) != "com.android.settings" }
            assertTrue(afterBack.hasImage)
            assertEquals(DelegationStatus.RUNNING, engine.childAgents.value.single().status)
            verifyRunningTeamUi(task.instruction)
            assertEquals(displayId, requireNotNull(service.getVirtualDisplayManager()).displayId)
            assertNotNull("Opening the main-screen UI must preserve accessibility", VdAccessibilityService.instance)
            assertFalse(childMore.response.isCompleted)
            assertFalse(afterBack.response.isCompleted)

            childMore.reply("read_evidence", "reference" to reference, "version" to version,
                "offset" to nextOffset, "limit" to 4800)
            val childSubmit = model.nextChild()
            assertEquals(nextOffset, childSubmit.lastUserObject().getInt("offset"))
            childSubmit.reply("submit_result", "status" to "completed", "facts" to listOf(mapOf(
                "text" to FACT, "refs" to listOf(mapOf("reference" to reference, "version" to version)))),
                "gaps" to emptyList<String>(), "collection_requests" to emptyList<String>())
            val completed = withTimeout(60_000) {
                engine.childAgents.first { records -> records.any { it.request.childId == childId && it.status == DelegationStatus.COMPLETED } }
                    .single { it.request.childId == childId }
            }
            assertEquals("pending", completed.decision)
            assertEquals(task.id, service.activeTaskId.value)
            assertEquals(TaskStatus.RUNNING, requireNotNull(service.getTask(task.id)).status)
            assertFalse("Child completion must not complete the parent", afterBack.response.isCompleted)

            afterBack.reply("read_child", "child_id" to childId, "recall_limit" to 4800)
            val readResult = model.nextParent(task.instruction)
            assertTrue("Parent receives the child's evidence-backed fact", readResult.text.contains(FACT))
            assertTrue(readResult.text.contains(reference))
            readResult.reply("review_child", "child_id" to childId, "child_accept" to true, "reason" to REVIEW_REASON)
            val accepted = model.nextParent(task.instruction)
            withTimeout(60_000) { engine.childAgents.first { it.singleOrNull()?.decision == "accepted" } }
            assertTrue(accepted.text.contains(REVIEW_REASON))
            accepted.reply("finish")
            awaitStatus(service, task.id, TaskStatus.SUCCEEDED)
            assertTrue(engine.childAgents.value.none { it.status in DelegationStatus.IN_FLIGHT })

            val archive = JSONObject(File(context.filesDir, "task_teams/${task.id}/children/delegations.json").readText())
            val child = archive.getJSONArray("children").getJSONObject(0)
            assertEquals(task.id, archive.getLong("root_task_id"))
            assertEquals("accepted", child.getString("decision"))
            assertEquals(REVIEW_REASON, child.getString("review_reason"))
            assertEquals(2, child.getJSONArray("reads").length())
            assertTrue(child.getJSONArray("reads").getJSONObject(0).getBoolean("included_image"))
            val citation = child.getJSONObject("result").getJSONArray("facts").getJSONObject(0)
                .getJSONArray("refs").getJSONObject(0)
            assertEquals(reference, citation.getString("reference"))
            assertEquals(version, citation.getInt("version"))
            assertEquals(runId, citation.getString("source_run_id"))
            assertEquals(2, citation.getInt("source_step"))
            val artifact = File(context.filesDir, "agent_runs/$runId/context_artifacts/${reference.substringAfterLast(':')}")
            assertEquals(version, JSONObject(File(artifact, "metadata.json").readText()).getInt("version"))
            assertTrue(File(artifact, "text.txt").length() > nextOffset)
            assertTrue(File(context.filesDir, "agent_runs/$runId/steps/2/screenshot.jpg").length() > 0)

            val events = runEvents(runId)
            val operations = events.filter { it.getString("type") == "operation_issued" }
                .map { it.getJSONObject("data").getString("action_type") }
            assertEquals(listOf("open_app", "key_event"), operations)
            val parentUsage = events.filter { it.getString("type") == "usage" }.map { it.getJSONObject("data") }
            val childUsage = child.getJSONArray("events").objects().filter { it.getString("type") == "usage_recorded" }
                .flatMap { it.getJSONObject("data").getJSONArray("call_attempts").objects() }
            assertEquals(7, parentUsage.size)
            assertEquals(3, childUsage.size)
            parentUsage.forEach { assertEquals("task:${task.id}:parent", it.getString("owner_id")) }
            childUsage.forEach {
                assertEquals("task:${task.id}:child:$childId", it.getString("owner_id"))
                assertEquals("CHILD_ANALYSIS", it.getString("purpose"))
            }
            val childRequests = childUsage.map { usage ->
                val file = File(context.filesDir, "task_teams/${task.id}/children/${usage.getString("request_ref")}")
                assertTrue("Each actual child request must remain readable", file.isFile)
                JSONObject(file.readText()).also { request ->
                    assertEquals("research_action", request.getJSONArray("tools").getJSONObject(0)
                        .getJSONObject("function").getString("name"))
                    assertTrue(request.getJSONArray("messages").length() >= 2)
                }
            }
            assertTrue("Archived child requests preserve the supplied image", childRequests.any {
                LocalModel.Request(it, "research_action").hasImage
            })
            val allUsage = parentUsage + childUsage
            assertTrue(allUsage.all { it.getBoolean("usage_complete") })
            assertEquals(10, allUsage.map { it.getLong("reservation_id") }.distinct().size)
            val budget = JSONObject(File(context.filesDir, "task_teams/${task.id}/budget.json").readText())
            assertEquals(10L * INPUT_TOKENS, budget.getLong("known_input_tokens"))
            assertEquals(10L * OUTPUT_TOKENS, budget.getLong("known_output_tokens"))
            assertEquals(0L, budget.getLong("pending_reserved_tokens"))
            assertEquals(0L, budget.getLong("unknown_reserved_tokens"))
            assertEquals(10, model.requests.size)
            File(output, "result.json").writeText(JSONObject().put("task_id", task.id).put("run_id", runId)
                .put("child_id", childId).put("display_id", displayId).put("parent_calls", parentUsage.size)
                .put("child_calls", childUsage.size).put("material", material).put("budget", budget)
                .put("result", "passed").toString(2))
        }
    }

    private suspend fun session(block: suspend (AgentService, LocalModel, MutableList<Long>) -> Unit) {
        rebindAccessibility()
        check(TaskRepository(context).tasks.first().none {
            it.status == TaskStatus.QUEUED || it.status in TaskStatus.ACTIVE && it.guiHeld
        }) { "Use a test device without other active or queued tasks" }
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
        var primaryFailure: Throwable? = null
        try {
            configure(model.port)
            withContext(Dispatchers.Main) {
                val intent = Intent(context, AgentService::class.java)
                context.startForegroundService(intent)
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                check(bound)
            }
            val active = withTimeout(60_000) { connected.await() }.also { service = it }
            waitUntil { active.activeBackendName != null && VdAccessibilityService.instance != null }
            assertTrue(requireNotNull(active.getInputBackend()).capabilities.canInjectInput)
            block(active, model, ids)
        } catch (failure: Throwable) {
            primaryFailure = failure
            recordFailure("scenario", failure)
        } finally {
            withContext(NonCancellable) {
                suspend fun cleanup(phase: String, action: suspend () -> Unit) {
                    try { action() } catch (failure: Throwable) {
                        recordFailure(phase, failure)
                        val original = primaryFailure
                        if (original == null) primaryFailure = failure else original.addSuppressed(failure)
                    }
                }
                cleanup("cancel-tasks") {
                    service?.let { active -> ids.forEach { withTimeout(30_000) { active.cancelTaskAndWait(it) } } }
                }
                cleanup("finish-activities") { finishTestActivities() }
                cleanup("unbind") { if (bound) withContext(Dispatchers.Main) { context.unbindService(connection) } }
                cleanup("stop-service") {
                    service?.let { active ->
                        withContext(Dispatchers.Main) { context.stopService(Intent(context, AgentService::class.java)) }
                        waitUntil { active.getInputBackend() == null }
                    }
                }
                cleanup("cancel-memory-jobs") {
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
                cleanup("close-model") { model.close() }
            }
        }
        primaryFailure?.let { throw it }
    }

    private fun recordFailure(phase: String, failure: Throwable) {
        Log.e("M3TeamAcceptance", "$phase failed", failure)
        try { File(output, "failure-$phase.txt").writeText(failure.stackTraceToString()) }
        catch (archiveFailure: Exception) { Log.e("M3TeamAcceptance", "Cannot save failure details", archiveFailure) }
    }

    private suspend fun finishTestActivities() {
        val activities = withContext(Dispatchers.Main) {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().filter { it != Stage.DESTROYED }.flatMap { monitor.getActivitiesInStage(it) }
                .filter { it.packageName == context.packageName }.distinct().onEach { it.finish() }
        }
        withTimeout(10_000) {
            while (withContext(Dispatchers.Main) { activities.any { !it.isDestroyed } }) delay(100)
        }
    }

    private suspend fun rebindAccessibility() {
        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver = context.contentResolver
        val original = requireNotNull(Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        val target = ComponentName(context, VdAccessibilityService::class.java)
        val entries = original.split(':')
        check(entries.any { ComponentName.unflattenFromString(it) == target }) {
            "Enable the real VdAccessibilityService on this test device before running this test"
        }
        val enabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                entries.filter { ComponentName.unflattenFromString(it) != target }.joinToString(":")))
            delay(500)
        } finally {
            try {
                check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original))
                check(Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, enabled))
            } finally { automation.dropShellPermissionIdentity() }
        }
        waitUntil { VdAccessibilityService.instance != null }
    }

    private suspend fun awaitStatus(service: AgentService, id: Long, status: String): TaskRecord = withTimeout(60_000) {
        service.tasks.first { records -> records.any { it.id == id && it.status == status } }.single { it.id == id }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(60_000) {
        while (!predicate()) delay(100)
    }

    private suspend fun captureSettings(service: AgentService): Int {
        val display = requireNotNull(service.getVirtualDisplayManager())
        assertTrue(display.displayId > 0)
        waitUntil { VdAccessibilityService.getTopPackageOnDisplay(display.displayId) == "com.android.settings" }
        val tree = requireNotNull(VdAccessibilityService.captureViewTree(display.displayId, display.width, display.height))
        assertTrue(tree.isNotBlank())
        File(output, "settings-tree.txt").writeText(tree)
        val frame = requireNotNull(display.captureFrame()) { "Real VD screenshot is required" }
        try {
            assertEquals(display.displayId, frame.displayId)
            assertTrue(frame.bitmap.width > 0 && frame.bitmap.height > 0)
            File(output, "settings.png").outputStream().use { assertTrue(frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { frame.bitmap.recycle() }
        return display.displayId
    }

    /** Main-screen acceptance while both model responses remain held; the agent's VD is untouched. */
    private suspend fun verifyRunningTeamUi(instruction: String) {
        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ActivityScenario.launch(TaskQueueActivity::class.java).use { scenario ->
            var title = ""
            var running = ""
            scenario.onActivity { activity ->
                assertEquals(Display.DEFAULT_DISPLAY, activity.display!!.displayId)
                title = activity.getString(R.string.team_task_title)
                running = activity.getString(R.string.team_task_role_status,
                    activity.getString(R.string.team_task_role_research), activity.getString(R.string.team_task_running))
            }
            // The currently running task is always the first row once the repository renders.
            withTimeout(10_000) {
                var rendered = false
                while (!rendered) {
                    scenario.onActivity { activity ->
                        val row = activity.findViewById<RecyclerView>(R.id.task_queue_list)
                            .findViewHolderForAdapterPosition(0)?.itemView
                        rendered = row != null && row.findViewById<TextView>(R.id.task_queue_instruction).text.toString() == instruction &&
                            row.findViewById<TextView>(R.id.task_queue_status).text.toString()
                                .contains(activity.getString(R.string.team_task_summary, 1, 0))
                    }
                    if (!rendered) delay(100)
                }
            }
            scenario.onActivity { activity ->
                val row = requireNotNull(activity.findViewById<RecyclerView>(R.id.task_queue_list)
                    .findViewHolderForAdapterPosition(0)).itemView
                assertEquals(instruction, row.findViewById<TextView>(R.id.task_queue_instruction).text.toString())
                assertTrue(row.findViewById<View>(R.id.task_queue_more).performClick())
            }
            waitUntil { visibleText(automation, title) != null }
            var menuItem = requireNotNull(visibleText(automation, title))
            while (!menuItem.isClickable) menuItem = requireNotNull(menuItem.parent)
            assertTrue(menuItem.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil { visibleText(automation, CHILD_GOAL) != null && visibleText(automation, running) != null }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val screenshot = requireNotNull(automation.takeScreenshot()) { "Main-screen team dialog screenshot is required" }
            try {
                File(output, "team-running-ui.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
        }
    }

    private fun visibleText(automation: UiAutomation, text: String): AccessibilityNodeInfo? =
        automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
            ?.firstOrNull { it.isVisibleToUser && it.text?.toString() == text }

    private fun runEvents(run: String): List<JSONObject> = File(context.filesDir, "agent_runs/$run/steps")
        .listFiles().orEmpty().sortedBy { it.name.toInt() }
        .flatMap { File(it, "events.jsonl").takeIf(File::isFile)?.readLines().orEmpty() }.map(::JSONObject)

    private fun runDirectories(ids: List<Long>): List<File> = File(context.filesDir, "agent_runs").listFiles().orEmpty()
        .filter { directory -> File(directory, "run.json").takeIf(File::isFile)?.let {
            JSONObject(it.readText()).optLong("task_id", -1) in ids
        } == true }

    private val backup get() = File(context.cacheDir, "m3-team-config-backup.json")
    private fun configure(port: Int) {
        val prefs = context.getSharedPreferences("opencyvis_config", Context.MODE_PRIVATE)
        if (!backup.exists()) backup.writeText(JSONObject().apply {
            CONFIG_KEYS.forEach { put(it, prefs.all[it] ?: JSONObject.NULL) }
        }.toString())
        ConfigRepository(context).apply {
            applyProfile(ConfigRepository.PROVIDER_OPENAI, "local-device-test", "local-m3-script",
                "http://127.0.0.1:$port/v1", "", ContextBudgetConfig.preset(128_000))
            teamConfig = TeamConfig(1_000_000L, 65_536L, maxActiveChildren = 1, childMaxSteps = 4)
            maxSteps = 12
            debugMode = true
            blacklistedPackages = emptySet()
        }
    }

    private fun restoreConfig() {
        if (!backup.exists()) return
        val saved = JSONObject(backup.readText())
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
        check(backup.delete())
    }

    private companion object {
        const val CHILD_GOAL = "Identify the application visible in observation 2"
        const val FACT = "The captured observation shows the Android Settings application."
        const val REVIEW_REASON = "Accepted because the fact cites observation 2 and its archived screenshot."
        const val INPUT_TOKENS = 100
        const val OUTPUT_TOKENS = 10
        val CONFIG_KEYS = listOf("api_provider", "api_key", "model", "base_url", "active_profile",
            "context_budget_json", "team_config_json", "max_steps", "debug_mode", "blacklisted_packages")
        fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    }
}
