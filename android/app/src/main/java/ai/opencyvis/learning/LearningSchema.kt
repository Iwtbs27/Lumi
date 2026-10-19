package ai.opencyvis.learning

import ai.opencyvis.llm.LlmRequestSchema
import ai.opencyvis.memory.MemoryConsolidationSchema
import org.json.JSONArray
import org.json.JSONObject

/** Learning uses native function calls with its own protocol; it exposes no phone actions. */
internal object LearningSchema {
    private val string get() = JSONObject().put("type", "string")
    private fun array(item: JSONObject) = JSONObject().put("type", "array").put("items", item)
    private fun strings() = array(string)
    private fun obj(vararg fields: Pair<String, JSONObject>): JSONObject = JSONObject()
        .put("type", "object").put("properties", JSONObject(fields.toMap()))
        .put("required", JSONArray(fields.map { it.first }))
    private fun enum(vararg values: String) = string.put("enum", JSONArray(values.toList()))
    private fun tool(name: String, description: String, parameters: JSONObject) = LlmRequestSchema(
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name)
            .put("description", description).put("parameters", parameters)), maxOutputTokens = 6000)
    private fun questions() = array(obj("id" to string, "question" to string, "reason" to string)).put("maxItems", 1)

    fun validateQuestions(questions: JSONArray) {
        require(questions.length() <= 1) { "一次只提出最影响复用的一个问题" }
        for (i in 0 until questions.length()) {
            val question = questions.getJSONObject(i)
            require(question.getString("id").isNotBlank() && question.getString("question").isNotBlank()) { "问题缺少编号或内容" }
            question.getString("reason")
        }
    }

    fun reviewer(): LlmRequestSchema = tool("learning_review", "判断接管是否提供了值得学习的 GUI 困难纠正。",
        obj("decision" to enum("learn", "skip"), "category" to enum(*TaskReviewDecision.categories.toTypedArray()),
            "reason" to string, "evidence_ids" to strings().put("maxItems", 12)))

    val reviewPrompt = """
        你在复盘 GUI Agent 任务中的一次人工接管，判断是否值得进一步整理为可复用经验。调用 learning_review，只返回筛选结论，暂不写方法。
        同时检查接管前的实际尝试与失败、人的实际操作与画面、交还后 Agent 的执行与任务结果。handoff_reason/kind 是当时的声明，只是线索；不能因为 Agent 说困惑或用户点了接管就认定有人纠错。
        decision=learn 只用于 category=gui_difficulty：有证据表明 Agent 被入口、页面理解、操作顺序、反复无效操作等困难阻塞，而人工操作提供了具体解决办法或可解释的改进。引用至少一条接管前步骤和一条真实人工操作，在 reason 中说明对应关系和结果边界。
        验证码、滑块安全验证属于 captcha；登录密码、扫码身份确认等属于 account_access；受保护页面属于 protected_screen。均 skip，不从验证流程提炼可复用操作方法。
        用户改变目标或改做另一件事为 task_change；普通浏览、单纯更快地完成原操作但没有困难依据为 routine_takeover；无有效人工动作为 no_human_action；材料不足以解释是否修复为 insufficient_evidence。以上均 skip。人工把任务直接完成也可能值得学习，但仍须有接管前困难与人工解决过程的证据。
        continuation 展示接管后最早的恢复步骤以及跨整个后续过程的取样，omitted_steps 是未提供的数量；事件和长字段也可能取样/截取。只有 provided_image_ids 对应的图片真实提供，其他画面没有看过。不能根据未提供的内容断言修复成功。
        continuation_source=unavailable 表示恢复时缺少后续轨迹，空数组不代表 Agent 没有继续执行；根据现有人工操作和画面判断，缺少结果依据时保留未知，不编造恢复步骤。
        stopped_at_next_handoff=true 表示后续又发生了人工接管，当前接管的执行证据在下一次接管处截止。task_completion.outcome 仍是整项任务结果，不能把后来人工帮助后的成功归功于本次纠正。
        事件 injected=true 仅表示输入送达；任务 outcome=completed 是 Agent 终局声明，不等于独立验证。整体任务失败也不否定局部 GUI 修复，但需要画面或后续动作支持；仅停在某页面没有可辨变化时保留未知并 skip。
        evidence_ids 仅引用本次输入中的真实编号；不引用未看过的截图。reason 用简短中文说明依据与为何学习或跳过，清楚区分观察与推断。不要将普通人工接管都包装成纠错。
    """.trimIndent()

    fun parser(): LlmRequestSchema {
        val input = obj("name" to string, "value" to string, "source" to string, "source_ref" to string)
        val output = obj("name" to string, "value" to string, "source_ref" to string)
        val segment = obj("id" to string, "goal" to string, "before" to string, "action" to string,
            "after" to string, "result" to enum("success", "failure", "unknown"),
            "inputs" to array(input), "outputs" to array(output), "conditions" to strings(),
            "evidence_ids" to strings(), "uncertainties" to strings())
        val analysis = obj("summary" to string, "goal" to string,
            "goal_source" to enum("user", "task", "inferred", "unknown"),
            "outcome" to enum("observed_success", "user_reported", "partial", "unknown"),
            "segments" to array(segment), "unknowns" to strings())
        return tool("learning_analysis", "解析一段人类手机操作；回看证据或提交解析结果。",
            obj("action" to enum("read_learning_evidence", "submit_analysis"), "reason" to string,
                "evidence_ids" to strings().put("maxItems", 6), "analysis" to analysis, "questions" to questions()))
    }

    fun compiler(): LlmRequestSchema {
        val base = MemoryConsolidationSchema.request().tool.getJSONObject("function")
            .getJSONObject("parameters").getJSONObject("properties").getJSONObject("proposals")
            .getJSONObject("items")
        val proposal = JSONObject(base.toString())
        val properties = proposal.getJSONObject("properties")
        properties.remove("source_steps")
        properties.remove("outcome_step")
        properties.put("memory_id", string)
        properties.put("source_evidence_ids", strings())
        properties.put("support_scope", obj("nodes" to strings(), "fields" to strings(),
            "whole_procedure" to JSONObject().put("type", "boolean")))
        proposal.put("required", JSONArray(listOf("type", "title", "summary", "app_scope", "task_family",
            "reason", "body", "source_evidence_ids", "support_scope")))
        return tool("compile_learning", "将有来源的示范解析编译为方法、知识或已有方法的局部更新。",
            obj("summary" to string, "proposals" to array(proposal).put("maxItems", 8), "questions" to questions()))
    }

    val parserPrompt = """
        你在整理用户亲自操作手机的示范。先理解这段操作，再决定它支持哪些知识。
        通过 learning_analysis 返回结果。action=read_learning_evidence 时给出具体疑点及 evidence_ids；系统会提供事件文字、树或相应图片。较长文字会明确标记截取，不能把未看到的部分当成证据。action=submit_analysis 时提交 analysis。两种响应都填写其余字段；无内容用空数组/空字符串。
        events 是真实采集的操作，frames 是按时间记录的画面。注入成功只表示事件送达，不表示任务完成。视觉推断与真实事件分别描述。页面自动刷新、动画不算用户操作。
        将连续编辑、滚动和确认整理成有目的的短片段。说明操作前后发生什么，哪些数据从用户输入来，哪些从页面或前面的片段来。输入里的商品、标题、日期是这次实例，保留来源，不直接当成长期默认值。
        每个片段的 evidence_ids 必须引用本次提供的真实证据；不要编编号。看到的图片可以解释，只有索引但没看的画面不能充当结果依据。摘要里区分成功、试错和未完成部分。结束录制不代表成功。
        correction 中的人从Agent留下的状态继续操作；区分修复操作和改变需求。先根据实际动作和画面解释变化，不依赖旧方法的自我解释。找不到原因就写入 uncertainties，不硬编因果。
        correction_context.task_review 是之前的筛选结论，thought 是 Agent 当时的解释；两者都不是新的观察证据。纠正后的复用范围仍需由本批原始人工操作和已查看画面支持，片段不使用筛选阶段的 before/after 引用替代原始人类证据。
        observation 可以包含多个任务和随手浏览；按目标切分，目标不明确标 inferred/unknown。只产生本段支持的结论，不把所有点击当推荐步骤。
        如需判断按钮含义、结果是否保存、为什么发生偏差，先回看对应证据。每批最多两轮回看，总计最多12张不同图片；预算耗尽后保留未知。不要为了确认已清楚的动作反复请求图片。
        questions 最多一个，只问会影响方法适用范围或个人默认选择的问题。例如“提前10分钟是这次要求，还是以后工作会议的默认设置？”能从现有画面回答的先查证据。未演示的可选分支可以保留未知，不强迫用户逐步解释。
        analysis.segments 保持简短、覆盖本批有效操作与关键失败，不重复复制大段输入文本。没有足够依据学习的片段仍可记录经历和未知项。
    """.trimIndent()

    val compilerPrompt = """
        将人类演示解析整理成下次可用的方法或知识，调用 compile_learning。学习结果将由用户查看后保存，不在这里操作手机。
        本次材料包括一个解析批次、此前批次的工作草稿、用户补充及相关旧记忆。返回合并后的完整 proposals，保留草稿中仍成立的内容；最多8条，避免重复。没有新知识可以返回空数组。
        procedure 写可复用的目标、参数、前置条件、节点、分支、完成标准；按当前画面定位，不保存点击坐标、旧element id或示范里的具体商品/正文。
        初始任务输入设 required=true；运行中才观察到的输出保存在 task_memory，需要绑定时声明为 required=false。data_dependencies 使用 {source,transform,target_node,target_field}，说明本次数据来自哪里、怎样组织、交给哪个节点字段；它是Agent可读规则，不是可执行脚本。既有方法的依赖必须保留。
        例如商品比较：品类/预算/数量/标题来自新任务，价格和连接方式来自本次详情，正文由本次收集的信息形成。不要将所有动态值都写成启动时必填参数。
        fact 使用 domain=app_knowledge 或 user_preference。重复操作只是偏好线索；只有明确的长期表述或用户回答支持默认偏好。尚待确认的习惯可提出问题，在回答前不输出声称用户已偏好的Fact；可独立保存操作方法。明确偏好保留工作/生活等作用域，本次指令优先。
        更新旧记忆用 target_id、target_revision，保持未修改节点id稳定，返回完整body。只改变有证据涉及的节点、条件、依赖或完成检查；原因未知就写观察和适用边界。不要把局部修复泛化成每次必须做。
        已停用、已删除的记忆不能换名重建。用户编辑的内容可以提出明确的修改建议，但必须展示变化依据，交给用户决定。旧版本与当前版本不同时，在理由中说明并保留疑点。
        working_draft 中的新记忆继续保留原 memory_id。source_evidence_ids 引用原始人类事件/画面或用户说明，禁止使用模型摘要作原始证据。support_scope={nodes:[相关node_id],fields:[body顶层字段名，如parameters或completion],whole_procedure:布尔}表明证据支持范围，fields不写嵌套路径；人类完整演示也不等于Agent独立执行成功。
        只据当前已提供的解析和证据生成；一个批次未展示完整过程时可以先组织局部方法，不编造后半段。没有显示保存后的结果，就不把点击保存认定为已保存。
        summary 用几句话说明学到了什么、修改哪里、还有什么未知；questions 最多一个且必须对复用有实际影响。已回答的问题不要重复问。输出严格满足schema，title/summary/reason在提案外层，body中放对应知识内容。
    """.trimIndent()
}
