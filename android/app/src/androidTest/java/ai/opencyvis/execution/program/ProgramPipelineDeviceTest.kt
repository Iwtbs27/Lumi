package ai.opencyvis.execution.program

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Process
import android.provider.CalendarContract
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.opencyvis.execution.ExecutionHost
import ai.opencyvis.execution.ExecutionOwner
import ai.opencyvis.execution.OperationJournal
import ai.opencyvis.execution.api.AndroidApiCapabilities
import ai.opencyvis.execution.api.ArtifactRef
import ai.opencyvis.execution.api.ArtifactRepository
import ai.opencyvis.execution.code.CodeExecutionManager
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Real CPython, Binder, SDK calls and provider readback; only fixture-owned data is used. */
@RunWith(AndroidJUnit4::class)
class ProgramPipelineDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun savedProgramMergesTwoCsvInputsAndReusesItsVersionWithAnotherRegion(): Unit = runBlocking {
        val fixture = fixture("csv-xlsx", 9001)
        try {
            val firstInput = fixture.input("first.csv", """
                product,region,quantity,unit_price
                A,east,2,100
                B,west,3,80
                C,east,1,250
            """.trimIndent() + "\n")
            val secondInput = fixture.input("second.csv", """
                product,region,quantity,unit_price
                A,east,4,100
                D,west,2,120
                E,east,5,50
            """.trimIndent() + "\n")
            val program = fixture.programs.saveCode(CSV_PROGRAM, ProgramSpec("筛选区域并合并销售数据", "data",
                parameters = listOf(ProgramParameter("first", "artifact"), ProgramParameter("second", "artifact"),
                    ProgramParameter("region", "string")),
                requiredCapabilities = listOf("artifacts.stage", "artifacts.capture_output"),
                preconditions = listOf("两个 CSV 均包含 product/region/quantity/unit_price"),
                completionConditions = listOf("仅保留目标区域，同产品数量合并，写入数值单元格"),
                stages = listOf(ProgramStage("workbook", "两个输入已暂存", "XLSX 已归档")),
                sources = listOf(JSONObject().put("fixture", "ProgramPipelineDeviceTest.CSV_PROGRAM"))))
            fun bindings(region: String) = mapOf("first" to fixture.binding(firstInput),
                "second" to fixture.binding(secondInput), "region" to parameter(region, "region"))
            val east = withTimeout(90_000) {
                fixture.runner.run(fixture.owner(1), program.ref, bindings("east"), 60_000)
            }
            assertCompleted(east)
            val eastFile = fixture.artifacts.resolve(east.artifacts.single())
            val eastRows = readWorkbook(eastFile)
            assertSheet(eastRows, listOf(
                listOf("A", "east", "6", "100", "600"),
                listOf("C", "east", "1", "250", "250"),
                listOf("E", "east", "5", "50", "250")))
            val west = withTimeout(90_000) {
                fixture.runner.run(fixture.owner(2), program.ref, bindings("west"), 60_000)
            }
            assertCompleted(west)
            val westFile = fixture.artifacts.resolve(west.artifacts.single())
            val westRows = readWorkbook(westFile)
            assertSheet(westRows, listOf(listOf("B", "west", "3", "80", "240"), listOf("D", "west", "2", "120", "240")))
            assertEquals(1, program.ref.revision)
            assertEquals(program.ref, east.programRef)
            assertEquals(program.ref, west.programRef)
            assertNotEquals(east.executionId, west.executionId)
            assertNotEquals(east.codeResult!!.workerPid, Process.myPid())
            assertEquals(2, fixture.programs.validations(program.ref).size)
            assertEquals(setOf("east", "west"), fixture.programs.validations(program.ref).map { it.params.getString("region") }.toSet())
            assertTrue(fixture.programs.card(program.ref).getBoolean("parameter_variation_observed"))
            assertTrue(fixture.programs.validations(program.ref).all {
                it.scope.getBoolean("mechanical_only") && !it.scope.getBoolean("semantic_completion_verified")
            })
            // The assertion reads ZIP/XML independently from the generating Python program.
            val verified = File(fixture.directory, "verified-output").apply { check(mkdirs()) }
            eastFile.copyTo(File(verified, "east.xlsx"))
            westFile.copyTo(File(verified, "west.xlsx"))
            fixture.report("xlsx-readback.json", JSONObject().put("result", "passed")
                .put("verification", "Kotlin ZipFile + XML cell values and numeric types")
                .put("east", east.toJson()).put("west", west.toJson()).put("east_cells", cellsJson(eastRows))
                .put("west_cells", cellsJson(westRows)).put("program_card", fixture.programs.card(program.ref)))
        } finally { fixture.exportEvidence() }
    }

    @Test fun courseCsvCreatesRealCalendarEventsAndDoesNotDuplicateExistingOnes(): Unit = runBlocking {
        val fixture = fixture("course-calendar", 9002)
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val account = "m4-program-fixture-${UUID.randomUUID()}"
        var calendarUri: Uri? = null
        try {
            calendarUri = createCalendar(account)
            val calendarId = ContentUris.parseId(calendarUri)
            val start = 1_800_000_000_000L
            val courses = listOf(Course("已有课程", start, start + 3_600_000, "Room A"),
                Course("数学课程", start + 7_200_000, start + 10_800_000, "Room B"),
                Course("历史课程", start + 86_400_000, start + 90_000_000, "Room C"))
            val seeded = requireNotNull(context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, courses[0].title)
                put(CalendarContract.Events.DTSTART, courses[0].start)
                put(CalendarContract.Events.DTEND, courses[0].end)
                put(CalendarContract.Events.EVENT_TIMEZONE, "Asia/Shanghai")
                put(CalendarContract.Events.EVENT_LOCATION, courses[0].location)
                put(CalendarContract.Events.ALL_DAY, 0)
            }))
            val seededId = ContentUris.parseId(seeded)
            assertEquals(1, readCalendarEvents(calendarId).size)
            val csv = "title,start_ms,end_ms,time_zone,location\n" + courses.joinToString("\n") {
                "${it.title},${it.start},${it.end},Asia/Shanghai,${it.location}"
            } + "\n"
            val input = fixture.input("courses.csv", csv)
            val program = fixture.programs.saveCode(CALENDAR_PROGRAM, ProgramSpec("导入单次课程到指定日历", "data",
                parameters = listOf(ProgramParameter("input", "artifact"), ProgramParameter("calendar_id", "integer")),
                requiredCapabilities = listOf("artifacts.stage", "artifacts.capture_output", "calendar.query", "calendar.get", "calendar.create"),
                preconditions = listOf("目标日历可写，CSV 包含标题、起止毫秒、时区、地点"),
                completionConditions = listOf("按标题与起止时间跳过已有课程，新增课程按真实 ID 读回"),
                stages = listOf(ProgramStage("courses", "输入已暂存且日历可访问", "课程清单已归档")),
                sources = listOf(JSONObject().put("fixture", "ProgramPipelineDeviceTest.CALENDAR_PROGRAM"))))
            val bindings = mapOf("input" to fixture.binding(input), "calendar_id" to parameter(calendarId, "calendar_id"))
            val first = withTimeout(90_000) { fixture.runner.run(fixture.owner(1), program.ref, bindings, 60_000) }
            assertCompleted(first)
            val actualFirst = readCalendarEvents(calendarId)
            assertCourses(courses, actualFirst)
            assertEquals(seededId, actualFirst.single { it.getString("title") == courses[0].title }.getLong("event_id"))
            val firstCreates = fixture.journal.forExecution(first.executionId).filter { it.getString("capability") == "calendar.create" }
            assertEquals(2, firstCreates.size)
            assertTrue(firstCreates.all { it.getString("status") == "succeeded" && it.getJSONObject("result").getBoolean("verified") })
            assertEquals(actualFirst.map { it.getLong("event_id") }.toSet() - seededId,
                firstCreates.map { it.getJSONObject("effect").getLong("event_id") }.toSet())
            val firstReport = JSONObject(fixture.artifacts.resolve(first.artifacts.single()).readText())
            assertEquals(2, firstReport.getJSONArray("created_ids").length())
            assertEquals(1, firstReport.getJSONArray("existing_ids").length())
            val second = withTimeout(90_000) { fixture.runner.run(fixture.owner(2), program.ref, bindings, 60_000) }
            assertCompleted(second)
            val actualSecond = readCalendarEvents(calendarId)
            assertCourses(courses, actualSecond)
            assertEquals(actualFirst.map { it.getLong("event_id") }.toSet(), actualSecond.map { it.getLong("event_id") }.toSet())
            assertTrue(fixture.journal.forExecution(second.executionId).none { it.getString("capability") == "calendar.create" })
            val secondReport = JSONObject(fixture.artifacts.resolve(second.artifacts.single()).readText())
            assertEquals(0, secondReport.getJSONArray("created_ids").length())
            assertEquals(3, secondReport.getJSONArray("existing_ids").length())
            assertEquals(2, fixture.programs.validations(program.ref).size)
            fixture.report("calendar-readback.json", JSONObject().put("result", "passed").put("calendar_id", calendarId)
                .put("seeded_event_id", seededId).put("verification", "Direct ContentResolver Events readback")
                .put("first_events", JSONArray(actualFirst)).put("second_events", JSONArray(actualSecond))
                .put("first", first.toJson()).put("second", second.toJson())
                .put("first_report", firstReport).put("second_report", secondReport))
        } finally {
            try {
                calendarUri?.let { uri ->
                    val deleteUri = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
                    assertEquals(1, context.contentResolver.delete(deleteUri, null, null))
                }
            } finally {
                automation.dropShellPermissionIdentity()
                fixture.exportEvidence()
            }
        }
    }

    private fun createCalendar(account: String): Uri {
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        return requireNotNull(context.contentResolver.insert(uri, ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, account)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "M4 program test calendar")
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "Asia/Shanghai")
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }))
    }

    private fun readCalendarEvents(calendarId: Long): List<JSONObject> {
        val columns = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND, CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.EVENT_LOCATION)
        return requireNotNull(context.contentResolver.query(CalendarContract.Events.CONTENT_URI, columns,
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0",
            arrayOf(calendarId.toString()), "${CalendarContract.Events.DTSTART} ASC")).use { cursor ->
            val events = mutableListOf<JSONObject>()
            while (cursor.moveToNext()) events += JSONObject().put("event_id", cursor.getLong(0)).put("title", cursor.getString(1))
                .put("start_ms", cursor.getLong(2)).put("end_ms", cursor.getLong(3)).put("time_zone", cursor.getString(4))
                .put("location", cursor.getString(5))
            events
        }
    }

    private data class Course(val title: String, val start: Long, val end: Long, val location: String)
    private fun assertCourses(expected: List<Course>, actual: List<JSONObject>) {
        assertEquals(expected.size, actual.size)
        expected.forEach { course ->
            val event = actual.single { it.getString("title") == course.title }
            assertTrue(event.getLong("event_id") > 0)
            assertEquals(course.start, event.getLong("start_ms"))
            assertEquals(course.end, event.getLong("end_ms"))
            assertEquals(course.location, event.getString("location"))
            assertEquals("Asia/Shanghai", event.getString("time_zone"))
        }
    }

    private data class Cell(val value: String, val type: String)

    /** Test-only OOXML readback, independent from Python and the host's artifact metadata. */
    private fun readWorkbook(file: File): List<List<Cell>> = ZipFile(file).use { zip ->
        val shared = mutableListOf<String>()
        zip.getEntry("xl/sharedStrings.xml")?.let { entry -> zip.getInputStream(entry).use { stream ->
            val parser = Xml.newPullParser().apply { setInput(stream, "UTF-8") }
            var current = StringBuilder()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "si") current = StringBuilder()
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "t") current.append(parser.nextText())
                if (parser.eventType == XmlPullParser.END_TAG && parser.name == "si") shared += current.toString()
                parser.next()
            }
        } }
        zip.getInputStream(requireNotNull(zip.getEntry("xl/worksheets/sheet1.xml"))).use { stream ->
            val parser = Xml.newPullParser().apply { setInput(stream, "UTF-8") }
            val rows = mutableListOf<List<Cell>>()
            var row = mutableListOf<Cell>()
            var coordinate = ""
            var kind = ""
            var value = StringBuilder()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "row" -> row = mutableListOf()
                        "c" -> { coordinate = parser.getAttributeValue(null, "r"); kind = parser.getAttributeValue(null, "t") ?: "n"; value = StringBuilder() }
                        "t", "v" -> value.append(parser.nextText())
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "c" -> {
                            val column = coordinate.takeWhile { it.isLetter() }.fold(0) { index, character -> index * 26 + (character - 'A' + 1) } - 1
                            while (row.size < column) row += Cell("", "empty")
                            row += Cell(if (kind == "s") shared[value.toString().toInt()] else value.toString(), kind)
                        }
                        "row" -> rows += row.toList()
                    }
                }
                parser.next()
            }
            rows
        }
    }

    private fun assertSheet(rows: List<List<Cell>>, expected: List<List<String>>) {
        assertEquals(listOf("product", "region", "quantity", "unit_price", "total"), rows.first().map { it.value })
        assertEquals(expected, rows.drop(1).map { row -> row.map { it.value } })
        rows.drop(1).forEach { row -> row.drop(2).forEach { assertEquals("n", it.type) } }
    }

    private fun cellsJson(rows: List<List<Cell>>) = JSONArray(rows.map { row -> JSONArray(row.map {
        JSONObject().put("value", it.value).put("type", it.type)
    }) })

    private fun assertCompleted(result: ProgramRunResult) {
        assertEquals(result.error ?: result.codeResult?.error ?: "Execution failed", "completed", result.status)
        assertEquals("completed", result.codeResult?.status)
        assertTrue(File(requireNotNull(result.codeResult).resultRef).isFile)
    }

    private fun parameter(value: Any, name: String) = ProgramBinding(value,
        JSONObject().put("kind", "instrumentation_parameter").put("name", name))

    private fun fixture(name: String, taskId: Long): Fixture {
        val id = "$name-${UUID.randomUUID()}"
        val directory = File(context.filesDir, "m4-program-fixtures/$id").apply { check(mkdirs()) }
        val artifacts = ArtifactRepository(directory)
        val programs = ProgramRepository(File(directory, "programs"))
        val journal = OperationJournal(File(directory, "journal"))
        val api = AndroidApiCapabilities(context, artifacts)
        val host = ExecutionHost(artifacts, journal, api::describe, { capability, args, effect -> api.call(capability, args, effect) }, null)
        val runner = ProgramRunner(taskId, directory, programs, host, CodeExecutionManager(context))
        return Fixture(id, directory, artifacts, programs, journal, runner)
    }

    private inner class Fixture(val id: String, val directory: File, val artifacts: ArtifactRepository,
        val programs: ProgramRepository, val journal: OperationJournal, val runner: ProgramRunner) {
        fun owner(step: Int) = ExecutionOwner(id, step, "data", 0)
        fun input(name: String, text: String): ArtifactRef {
            val source = File(directory, "inputs/$name").apply { parentFile!!.mkdirs(); writeText(text) }
            return artifacts.importFile(source, name, "text/csv", source.toURI().toString())
        }
        fun binding(ref: ArtifactRef) = ProgramBinding(JSONObject().put("artifact_id", ref.artifactId).put("version", ref.version),
            JSONObject().put("source_uri", ref.sourceUri).put("artifact_id", ref.artifactId).put("version", ref.version))
        fun report(name: String, value: JSONObject) { File(directory, name).writeText(value.toString(2)) }
        fun exportEvidence() {
            val destination = File(context.getExternalFilesDir(null), "m4-program-e2e/$id")
            check(directory.copyRecursively(destination)) { "Cannot export program execution evidence" }
        }
    }

    companion object {
        private val CSV_PROGRAM = """
            import csv
            from openpyxl import Workbook
            def main(ctx, params):
                merged = {}
                for key in ("first", "second"):
                    ctx.call("artifacts.stage", dict(params[key], path=key + ".csv"))
                    with open(ctx.path(key + ".csv"), encoding="utf-8", newline="") as source:
                        for row in csv.DictReader(source):
                            if row["region"] != params["region"]:
                                continue
                            product = row["product"]
                            quantity, price = int(row["quantity"]), int(row["unit_price"])
                            if product in merged:
                                assert merged[product][1] == price
                                merged[product][0] += quantity
                            else:
                                merged[product] = [quantity, price]
                book = Workbook()
                sheet = book.active
                sheet.title = "Filtered"
                sheet.append(["product", "region", "quantity", "unit_price", "total"])
                for product, (quantity, price) in sorted(merged.items()):
                    sheet.append([product, params["region"], quantity, price, quantity * price])
                book.save(ctx.path("filtered.xlsx"))
                output = ctx.call("artifacts.capture_output", {"path": "filtered.xlsx", "name": params["region"] + ".xlsx",
                    "mime_type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
                ctx.call("execution.checkpoint", {"stage": "workbook", "operation_ids": [output["operation_id"]],
                    "state": {"artifact": output["artifact"], "region": params["region"]}})
                print("region=" + params["region"] + " merged_rows=" + str(len(merged)))
                return {"artifact": output["artifact"], "rows": len(merged)}
        """.trimIndent()

        private val CALENDAR_PROGRAM = """
            import csv
            import json
            def main(ctx, params):
                ctx.call("artifacts.stage", dict(params["input"], path="courses.csv"))
                with open(ctx.path("courses.csv"), encoding="utf-8", newline="") as source:
                    courses = list(csv.DictReader(source))
                start = min(int(row["start_ms"]) for row in courses) - 1
                end = max(int(row["end_ms"]) for row in courses) + 1
                existing, offset = {}, 0
                while True:
                    page = ctx.call("calendar.query", {"calendar_id": params["calendar_id"], "start_ms": start,
                        "end_ms": end, "offset": offset, "limit": 200})
                    for event in page["events"]:
                        existing[(event["title"], event["start_ms"], event["end_ms"])] = event["event_id"]
                    if page["next_offset"] is None:
                        break
                    offset = page["next_offset"]
                created_ids, existing_ids, operations, confirmed = [], [], [], []
                for row in courses:
                    begin, finish = int(row["start_ms"]), int(row["end_ms"])
                    key = (row["title"], begin, finish)
                    if key in existing:
                        event_id = existing[key]
                        existing_ids.append(event_id)
                    else:
                        created = ctx.call("calendar.create", {"calendar_id": params["calendar_id"], "title": row["title"],
                            "start_ms": begin, "end_ms": finish, "time_zone": row["time_zone"], "location": row["location"]})
                        assert created["verified"]
                        event_id = created["event_id"]
                        created_ids.append(event_id)
                        operations.append(created["operation_id"])
                        existing[key] = event_id
                    actual = ctx.call("calendar.get", {"event_id": event_id})["event"]
                    assert (actual["title"], actual["start_ms"], actual["end_ms"]) == key
                    assert actual["calendar_id"] == params["calendar_id"]
                    confirmed.append(actual)
                report = {"created_ids": created_ids, "existing_ids": existing_ids, "events": confirmed}
                with open(ctx.path("course-report.json"), "w", encoding="utf-8") as output:
                    json.dump(report, output, ensure_ascii=False)
                archived = ctx.call("artifacts.capture_output", {"path": "course-report.json", "name": "course-report.json",
                    "mime_type": "application/json"})
                operations.append(archived["operation_id"])
                ctx.call("execution.checkpoint", {"stage": "courses", "operation_ids": operations,
                    "state": {"report_artifact": archived["artifact"], "event_ids": [row["event_id"] for row in confirmed]}})
                print("created=" + str(len(created_ids)) + " existing=" + str(len(existing_ids)))
                return report
        """.trimIndent()
    }
}
