package ai.opencyvis.execution

import android.Manifest
import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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
import ai.opencyvis.debug.GuiNoteFixtureActivity
import ai.opencyvis.display.VirtualDisplayManager
import ai.opencyvis.execution.api.ArtifactRepository
import ai.opencyvis.execution.gui.GuiSelector
import ai.opencyvis.memory.MemoryRepository
import ai.opencyvis.task.TaskRepository
import ai.opencyvis.task.TaskRequest
import ai.opencyvis.task.TaskStatus
import ai.opencyvis.team.DelegationRecord
import ai.opencyvis.team.DelegationStatus
import ai.opencyvis.testing.LocalAgentModel
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

/** Full service/queue/engine routes. Only model HTTP is scripted; Python and GUI run on the device. */
@RunWith(AndroidJUnit4::class)
class HybridAgentDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val output get() = File(context.getExternalFilesDir(null), "m4-hybrid-agent").apply { mkdirs() }

    @Test fun dataCodingRepairsRealPythonAndParentGuiContinuesDuringItsExecution(): Unit = runBlocking {
        session("data") { service, model, ids ->
            val task = service.enqueueTask(TaskRequest("M4 data: sum 2, 3 and 4 while continuing the phone task",
                source = "device:m4-hybrid")).also { ids += it.id }
            model.nextParent(task.instruction).reply("open_app", "app_name" to "settings")
            val atSettings = model.nextParent(task.instruction)
            val display = requireNotNull(service.getVirtualDisplayManager())
            val displayId = display.displayId
            assertTrue(displayId > 0)
            waitUntil { VdAccessibilityService.getTopPackageOnDisplay(display.displayId) == "com.android.settings" }
            capture(display, "data-settings")
            atSettings.reply("delegate_coding", "child_goal" to DATA_GOAL, "coding_mode" to "data",
                "params" to mapOf("values" to listOf(2, 3, 4)), "completion_conditions" to listOf(DATA_CONDITION),
                "max_executions" to 3, "execution_timeout_ms" to 120_000)
            val first = model.nextChild()
            val assignment = JSONObject(first.body.getJSONArray("messages").getJSONObject(1).getString("content"))
            val childId = assignment.getString("child_id")
            val runId = assignment.getString("root_run_id")
            assertEquals(task.id, assignment.getLong("root_task_id"))
            assertEquals(2, assignment.getInt("source_step"))
            assertEquals(0, assignment.getJSONArray("materials").length())
            first.reply("inspect_sdk")
            val sdk = model.nextChild()
            val cards = sdk.lastUserObject().getJSONObject("sdk").getJSONArray("capabilities").objects()
            assertTrue(cards.any { it.getString("name") == "artifacts.capture_output" })
            assertTrue(cards.none { it.getString("name").startsWith("gui.") })
            sdk.reply("write_code", "code" to "def main(ctx, params):\n    raise ValueError('fixture incorrect sum')\n",
                "spec" to dataSpec())
            val firstVersion = model.nextChild()
            val programId = firstVersion.lastUserObject().getJSONObject("program").getString("program_id")
            firstVersion.reply("run_code")
            val failed = model.nextChild()
            val failedRun = failed.lastUserObject()
            assertEquals("failed", failedRun.getString("execution_status"))
            failed.reply("read_output", "reference" to failedRun.getString("stderr_ref"), "offset" to 0, "limit" to 4800)
            val errorRead = model.nextChild()
            assertTrue(errorRead.lastUserObject().getString("text").contains("ValueError: fixture incorrect sum"))
            assertTrue(errorRead.lastUserObject().getString("text").contains("line 2"))
            errorRead.reply("write_code", "code" to DATA_CODE, "spec" to dataSpec())
            val repaired = model.nextChild()
            assertEquals(programId, repaired.lastUserObject().getJSONObject("program").getString("program_id"))
            assertEquals(2, repaired.lastUserObject().getJSONObject("program").getInt("revision"))
            repaired.reply("run_code")

            // The marker/gate synchronize this fixture computation only; production execution is unchanged.
            val ready = withTimeout(45_000) {
                var file: File? = null
                while (file == null) {
                    file = programRuns(task.id).map { File(it, "workspace/ready.json") }.firstOrNull(File::isFile)
                    if (file == null) delay(50)
                }
                requireNotNull(file)
            }
            val workerPid = JSONObject(ready.readText()).getInt("pid")
            assertNotEquals(Process.myPid(), workerPid)
            assertTrue(alive(workerPid))
            val engine = requireNotNull(service.getEngine())
            assertEquals(DelegationStatus.RUNNING, engine.childAgents.value.single().status)
            model.nextParent(task.instruction).reply("key_event", "key" to "back")
            val afterBack = model.nextParent(task.instruction)
            waitUntil {
                val top = VdAccessibilityService.getTopPackageOnDisplay(displayId)
                !top.isNullOrBlank() && top != "com.android.settings"
            }
            assertTrue("Python is still executing while the parent completes Back", alive(workerPid))
            assertEquals("running", JSONObject(File(ready.parentFile!!.parentFile, "execution.json").readText()).getString("status"))
            assertEquals(TaskStatus.RUNNING, service.getTask(task.id)!!.status)
            capture(display, "data-parent-progress")
            File(ready.parentFile, "continue").writeText("parent GUI progress observed")

            val ran = model.nextChild()
            val execution = ran.lastUserObject()
            assertEquals("completed", execution.getString("execution_status"))
            assertEquals(2, execution.getInt("revision"))
            assertFalse(alive(workerPid))
            val artifact = execution.getJSONArray("artifact_refs").getString(0)
            ran.reply("read_output", "reference" to artifact, "offset" to 0, "limit" to 4800)
            val readback = model.nextChild()
            val actualText = readback.lastUserObject().getString("text")
            assertEquals(9, JSONObject(actualText).getInt("total"))
            val parts = artifact.split(':')
            val repository = ArtifactRepository(taskDirectory(task.id))
            val saved = repository.get(parts[1], parts[2].toInt())
            assertEquals(9, JSONObject(repository.resolve(saved).readText()).getInt("total"))
            readback.reply("submit_result", "status" to "completed", "summary" to "Total is 9, read from the archived JSON",
                "checks" to listOf(mapOf("name" to DATA_CONDITION, "passed" to true, "evidence_refs" to listOf(artifact))),
                "gaps" to emptyList<String>(), "collection_requests" to emptyList<String>())
            val done = awaitChild(service, childId, DelegationStatus.COMPLETED)
            assertEquals("child_review", done.result!!.outputs["verification_scope"])
            assertEquals("pending", done.decision)
            afterBack.reply("read_child", "child_id" to childId, "recall_limit" to 4800)
            val parentRead = model.nextParent(task.instruction)
            assertTrue(parentRead.text.contains("Total is 9"))
            parentRead.reply("review_child", "child_id" to childId, "child_accept" to true,
                "reason" to "Accepted the current program revision and archived total")
            model.nextParent(task.instruction).reply("finish")
            awaitSucceeded(service, task.id)
            val runs = programRuns(task.id).map { JSONObject(File(it, "execution.json").readText()) }
            assertEquals(2, runs.size)
            assertEquals(setOf("failed", "completed"), runs.map { it.getString("status") }.toSet())
            assertTrue(File(context.filesDir, "programs/$programId/1/main.py").readText().contains("raise ValueError"))
            assertTrue(File(context.filesDir, "programs/$programId/2/main.py").readText().contains("sum(params"))
            report("data-result.json", JSONObject().put("task_id", task.id).put("run_id", runId).put("child_id", childId)
                .put("program_id", programId).put("revision", 2).put("display_id", displayId)
                .put("worker_pid", workerPid).put("artifact", saved.toJson()).put("readback", JSONObject(actualText))
                .put("runs", JSONArray(runs)).put("parent_progress_during_python", true).put("result", "passed"))
        }
    }

    @Test fun guiCodingHandsItsCandidateToTheParentForRealStructuredPhoneExecution(): Unit = runBlocking {
        session("gui") { service, model, ids ->
            val task = service.enqueueTask(TaskRequest("M4 GUI: prepare and run a reusable note form program",
                source = "device:m4-hybrid")).also { ids += it.id }
            model.nextParent(task.instruction).reply("open_app", "app_name" to "settings")
            val readyParent = model.nextParent(task.instruction)
            val display = requireNotNull(service.getVirtualDisplayManager())
            val displayId = display.displayId
            assertTrue(displayId > 0)
            waitUntil { VdAccessibilityService.getTopPackageOnDisplay(display.displayId) == "com.android.settings" }
            // Engine already completed VD readiness; launch the existing offline fixture with its real action.
            val intent = Intent(context, GuiNoteFixtureActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            assertTrue(requireNotNull(service.getInputBackend()).startActivityOnDisplay(intent.toUri(Intent.URI_INTENT_SCHEME), display.displayId))
            waitUntil { query(display, R.id.gui_fixture_page) == "Note editor" }
            assertEquals(Intent.ACTION_MAIN, VdAccessibilityService.queryGui(selector(display, R.id.gui_fixture_page))
                .matches.single().contentDescription)
            readyParent.reply("wait")
            val title = "代码助手的学习记录"
            val body = "真实 GUI 程序读回：\n" + "参数内容完整保留。".repeat(12)
            model.nextParent(task.instruction).reply("delegate_coding", "child_goal" to GUI_GOAL, "coding_mode" to "gui",
                "params" to mapOf("title" to title, "body" to body), "completion_conditions" to listOf(GUI_CONDITION),
                "max_executions" to 2, "execution_timeout_ms" to 60_000)
            val child = model.nextChild()
            val request = JSONObject(child.body.getJSONArray("messages").getJSONObject(1).getString("content"))
            val childId = request.getString("child_id")
            val runId = request.getString("root_run_id")
            assertEquals(3, request.getInt("source_step"))
            child.reply("inspect_sdk")
            val sdk = model.nextChild()
            assertTrue(sdk.lastUserObject().getJSONObject("sdk").getJSONArray("capabilities").objects()
                .any { it.getString("name") == "gui.execute_block" })
            sdk.reply("write_code", "code" to guiCode(), "spec" to mapOf("name" to "填入并保存学习记录",
                "parameters" to listOf(mapOf("name" to "title", "type" to "string"), mapOf("name" to "body", "type" to "string")),
                "required_capabilities" to listOf("gui.execute_block", "gui.query")))
            model.nextChild().reply("submit_result", "status" to "needs_execution", "summary" to "参数化表单程序已准备好，等待主任务运行",
                "gaps" to emptyList<String>(), "collection_requests" to listOf("请主任务执行并核对保存后的标题和正文"),
                "checks" to emptyList<Any>())
            val candidate = awaitChild(service, childId, DelegationStatus.NEEDS_EXECUTION)
            assertTrue("GUI coding must not occupy the code slot or run a program", programRuns(task.id).isEmpty())
            assertEquals("Seed title", query(display, R.id.gui_fixture_title))
            val parentRequest = model.nextParent(task.instruction)
            assertFalse(parentRequest.response.isCompleted)
            showCandidateUi(task.instruction)
            assertEquals(display.displayId, requireNotNull(service.getVirtualDisplayManager()).displayId)
            parentRequest.reply("read_child", "child_id" to childId, "recall_limit" to 4800)
            val parentRead = model.nextParent(task.instruction)
            assertTrue(parentRead.text.contains("run_request"))
            parentRead.reply("review_child", "child_id" to childId, "child_accept" to true,
                "reason" to "The GUI candidate matches the requested fields; run and inspect it now")
            val runRequest = JSONObject(candidate.result!!.outputs["run_request"] as Map<*, *>)
            assertEquals(60_000L, runRequest.getLong("execution_timeout_ms"))
            val fields = runRequest.keys().asSequence().filter { it != "action_type" }.map { it to runRequest.get(it) }.toList()
            model.nextParent(task.instruction).reply("run_program", *fields.toTypedArray())
            val afterProgram = model.nextParent(task.instruction)
            assertEquals("Saved", query(display, R.id.gui_fixture_status))
            assertEquals(title, query(display, R.id.gui_fixture_saved_title))
            assertEquals(body, query(display, R.id.gui_fixture_saved_body))
            capture(display, "gui-saved-note")
            val execution = JSONObject(File(programRuns(task.id).single(), "execution.json").readText())
            assertEquals("completed", execution.getString("status"))
            val executionId = execution.getString("execution_id")
            val operations = OperationJournal(File(taskDirectory(task.id), "journal")).forExecution(executionId)
            val guiOperations = operations.filter { it.getString("capability") == "gui.action" }
            assertEquals(3, guiOperations.size)
            assertTrue(guiOperations.all { it.getString("status") == "succeeded" && it.getJSONObject("result").getBoolean("verified") })
            assertEquals(listOf(title, body), guiOperations.take(2).map { it.getJSONObject("result").getJSONObject("after").getString("text") })
            assertTrue(guiOperations.all { it.getJSONObject("result").getJSONObject("page").getInt("display_id") == display.displayId })
            val codeResult = execution.getJSONObject("code_result")
            val workerPid = codeResult.getInt("worker_pid")
            assertNotEquals(Process.myPid(), workerPid)
            assertFalse(alive(workerPid))
            val readback = JSONObject(File(codeResult.getString("result_ref")).readText()).getJSONObject("result")
            assertEquals(title, readback.getString("title"))
            assertEquals(body, readback.getString("body"))
            afterProgram.reply("read_execution", "execution_id" to executionId, "output_kind" to "result", "recall_limit" to 4800)
            val parentReadback = model.nextParent(task.instruction)
            assertTrue(parentReadback.text.contains(title))
            assertTrue(parentReadback.text.contains("参数内容完整保留"))
            parentReadback.reply("finish")
            awaitSucceeded(service, task.id)
            report("gui-result.json", JSONObject().put("task_id", task.id).put("run_id", runId).put("child_id", childId)
                .put("display_id", displayId).put("run_request", runRequest).put("execution", execution)
                .put("gui_operations", JSONArray(guiOperations)).put("readback", readback).put("result", "passed"))
        }
    }

    private fun dataSpec() = mapOf("name" to "汇总参数并归档 JSON", "parameters" to listOf(mapOf("name" to "values", "type" to "array")),
        "required_capabilities" to listOf("artifacts.capture_output"))

    private fun guiCode(): String {
        fun id(value: Int) = context.resources.getResourceName(value)
        return """
            def main(ctx, params):
                def selector(resource):
                    return {"package_name": "${context.packageName}", "resource_id": resource}
                anchor = selector("${id(R.id.gui_fixture_page)}")
                anchor["text"] = "Note editor"
                filled = ctx.call("gui.execute_block", {"page_anchor": anchor, "actions": [
                    {"type": "set_text", "selector": selector("${id(R.id.gui_fixture_title)}"), "text": params["title"]},
                    {"type": "set_text", "selector": selector("${id(R.id.gui_fixture_body)}"), "text": params["body"]}]})
                if filled["status"] != "completed":
                    raise RuntimeError("Form fill stopped: " + str(filled))
                saved = ctx.call("gui.execute_block", {"page_anchor": anchor, "actions": [
                    {"type": "tap", "selector": selector("${id(R.id.gui_fixture_save)}"),
                     "expect": {"selector": selector("${id(R.id.gui_fixture_status)}"), "text": "Saved"}}]})
                if saved["status"] != "completed":
                    raise RuntimeError("Save stopped: " + str(saved))
                title = ctx.call("gui.query", {"selector": selector("${id(R.id.gui_fixture_saved_title)}")})
                body = ctx.call("gui.query", {"selector": selector("${id(R.id.gui_fixture_saved_body)}")})
                return {"title": title["nodes"][0]["text"], "body": body["nodes"][0]["text"]}
        """.trimIndent()
    }

    private suspend fun session(name: String, block: suspend (AgentService, LocalAgentModel, MutableList<Long>) -> Unit) {
        rebindAccessibility()
        check(TaskRepository(context).tasks.first().none { it.status == TaskStatus.QUEUED || it.status in TaskStatus.ACTIVE && it.guiHeld })
        val ids = mutableListOf<Long>()
        val model = LocalAgentModel("coding_action")
        val connected = CompletableDeferred<AgentService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete((binder as AgentService.AgentBinder).getService()) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        var service: AgentService? = null
        var bound = false
        var failure: Throwable? = null
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
        } catch (error: Throwable) {
            failure = error
            File(output, "$name-failure.txt").writeText(error.stackTraceToString())
        } finally {
            withContext(NonCancellable) {
                suspend fun clean(phase: String, action: suspend () -> Unit) {
                    try { action() } catch (error: Throwable) {
                        File(output, "$name-cleanup-$phase.txt").writeText(error.stackTraceToString())
                        if (failure == null) failure = error else failure!!.addSuppressed(error)
                    }
                }
                clean("cancel") { service?.let { active -> ids.forEach { withTimeout(30_000) { active.cancelTaskAndWait(it) } } } }
                clean("activities") { finishActivities() }
                clean("unbind") { if (bound) withContext(Dispatchers.Main) { context.unbindService(connection) } }
                clean("service") { service?.let { active ->
                    withContext(Dispatchers.Main) { context.stopService(Intent(context, AgentService::class.java)) }
                    waitUntil { active.getInputBackend() == null }
                } }
                clean("memory") {
                    val runs = File(context.filesDir, "agent_runs").listFiles().orEmpty().filter { dir ->
                        File(dir, "run.json").takeIf(File::isFile)?.let { JSONObject(it.readText()).optLong("task_id", -1) in ids } == true
                    }.map { it.name }.toSet()
                    val memory = MemoryRepository(context)
                    memory.listJobs().filter { it.runId in runs }.forEach { job ->
                        memory.updateJob(job.copy(status = "cancelled", updatedAt = System.currentTimeMillis()))
                        withContext(Dispatchers.IO) { WorkManager.getInstance(context).cancelUniqueWork("memory:${job.id}").result.get(10, TimeUnit.SECONDS) }
                    }
                }
                clean("config") { restoreConfig() }
                clean("model") { model.close() }
            }
        }
        failure?.let { throw it }
    }

    private suspend fun rebindAccessibility() {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver = context.contentResolver
        val original = requireNotNull(Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        val target = ComponentName(context, VdAccessibilityService::class.java)
        val entries = original.split(':')
        check(entries.any { ComponentName.unflattenFromString(it) == target }) { "Enable the real VD accessibility service on this isolated device" }
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

    private suspend fun showCandidateUi(instruction: String) {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val intent = Intent(context, TaskQueueActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
        ActivityScenario.launch<TaskQueueActivity>(intent, options).use { scenario ->
            var menuTitle = ""
            var status = ""
            withTimeout(15_000) {
                var ready = false
                while (!ready) {
                    scenario.onActivity { activity ->
                        assertEquals(Display.DEFAULT_DISPLAY, activity.display!!.displayId)
                        menuTitle = activity.getString(R.string.team_task_title)
                        status = activity.getString(R.string.team_task_role_status, activity.getString(R.string.team_task_role_coding),
                            activity.getString(R.string.team_task_needs_execution))
                        val row = activity.findViewById<RecyclerView>(R.id.task_queue_list).findViewHolderForAdapterPosition(0)?.itemView
                        ready = row?.findViewById<TextView>(R.id.task_queue_instruction)?.text?.toString() == instruction
                    }
                    if (!ready) delay(100)
                }
            }
            scenario.onActivity { activity ->
                val row = requireNotNull(activity.findViewById<RecyclerView>(R.id.task_queue_list).findViewHolderForAdapterPosition(0)).itemView
                assertTrue(row.findViewById<View>(R.id.task_queue_more).performClick())
            }
            waitUntil { visible(automation, menuTitle) != null }
            var item = requireNotNull(visible(automation, menuTitle))
            while (!item.isClickable) item = requireNotNull(item.parent)
            assertTrue(item.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil { visible(automation, GUI_GOAL) != null && visible(automation, status) != null }
            instrumentation.waitForIdleSync()
            val bitmap = requireNotNull(automation.takeScreenshot())
            try { File(output, "gui-candidate-ui.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
    }

    private fun visible(automation: UiAutomation, text: String): AccessibilityNodeInfo? = automation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.isVisibleToUser && it.text?.toString() == text }

    private suspend fun finishActivities() {
        val activities = withContext(Dispatchers.Main) {
            Stage.values().filter { it != Stage.DESTROYED }.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                .filter { it.packageName == context.packageName }.distinct().onEach { it.finish() }
        }
        withTimeout(10_000) { while (withContext(Dispatchers.Main) { activities.any { !it.isDestroyed } }) delay(100) }
    }

    private suspend fun awaitChild(service: AgentService, id: String, status: String): DelegationRecord = withTimeout(60_000) {
        requireNotNull(service.getEngine()).childAgents.first { values -> values.any { it.request.childId == id && it.status == status } }
            .single { it.request.childId == id }
    }

    private suspend fun awaitSucceeded(service: AgentService, taskId: Long) = withTimeout(60_000) {
        service.tasks.first { values -> values.any { it.id == taskId && it.status == TaskStatus.SUCCEEDED } }
    }
    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(60_000) { while (!predicate()) delay(100) }
    private fun taskDirectory(id: Long) = File(context.filesDir, "task_execution/$id")
    private fun programRuns(id: Long) = File(taskDirectory(id), "program-runs").listFiles().orEmpty().filter { File(it, "execution.json").isFile }
    private fun selector(display: VirtualDisplayManager, id: Int) = GuiSelector(display.displayId, context.packageName, context.resources.getResourceName(id))
    private fun query(display: VirtualDisplayManager, id: Int) = VdAccessibilityService.queryGui(selector(display, id)).matches.singleOrNull()?.text
    private fun alive(pid: Int): Boolean = try { Os.kill(pid, 0); true }
    catch (error: ErrnoException) { if (error.errno == OsConstants.ESRCH) false else throw error }
    private fun report(name: String, value: JSONObject) = File(output, name).writeText(value.toString(2))
    private fun capture(display: VirtualDisplayManager, name: String) {
        val frame = requireNotNull(display.captureFrame())
        try {
            assertEquals(display.displayId, frame.displayId)
            File(output, "$name.png").outputStream().use { assertTrue(frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { frame.bitmap.recycle() }
        File(output, "$name-tree.txt").writeText(requireNotNull(VdAccessibilityService.captureViewTree(display.displayId, display.width, display.height)))
    }

    private val backup get() = File(context.cacheDir, "m4-hybrid-config-backup.json")
    private fun configure(port: Int) {
        val prefs = context.getSharedPreferences("opencyvis_config", Context.MODE_PRIVATE)
        if (!backup.exists()) backup.writeText(JSONObject().apply { CONFIG_KEYS.forEach { put(it, prefs.all[it] ?: JSONObject.NULL) } }.toString())
        ConfigRepository(context).apply {
            applyProfile(ConfigRepository.PROVIDER_OPENAI, "local-device-test", "local-m4-script",
                "http://127.0.0.1:$port/v1", "", ContextBudgetConfig.preset(128_000))
            teamConfig = TeamConfig(1_000_000L, 65_536L, maxActiveChildren = 1, childMaxSteps = 14)
            maxSteps = 18
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

    companion object {
        private const val DATA_GOAL = "计算输入数组之和并保存结果"
        private const val DATA_CONDITION = "输入数组之和为 9"
        private const val GUI_GOAL = "编写填写并保存学习记录的程序"
        private const val GUI_CONDITION = "已保存的标题和正文与参数完全一致"
        private val CONFIG_KEYS = listOf("api_provider", "api_key", "model", "base_url", "active_profile", "context_budget_json",
            "team_config_json", "max_steps", "debug_mode", "blacklisted_packages")
        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
        private val DATA_CODE = """
            import json
            import os
            import time
            def main(ctx, params):
                with open(ctx.path("ready.tmp"), "w", encoding="utf-8") as marker:
                    json.dump({"pid": os.getpid()}, marker)
                os.replace(ctx.path("ready.tmp"), ctx.path("ready.json"))
                deadline = time.monotonic() + 90
                while not os.path.exists(ctx.path("continue")):
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Parent GUI fixture did not release the computation")
                    time.sleep(0.05)
                result = {"total": sum(params["values"]), "count": len(params["values"])}
                with open(ctx.path("sum.json"), "w", encoding="utf-8") as output:
                    json.dump(result, output, ensure_ascii=False)
                saved = ctx.call("artifacts.capture_output", {"path": "sum.json", "name": "sum.json", "mime_type": "application/json"})
                return {"total": result["total"], "artifact": saved["artifact"]}
        """.trimIndent()
    }
}
