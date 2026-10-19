package ai.opencyvis.memory

import ai.opencyvis.llm.LlmRequestSchema
import org.json.JSONArray
import org.json.JSONObject

/** Background-only protocol. It has no phone actions and cannot operate the device. */
internal object MemoryConsolidationSchema {
    val prompt = """
        整理这次手机任务中值得下次复用的知识。调用 consolidate_memory 返回建议；没有值得保存的内容就返回空 proposals。

        返回参数固定为 {episode_summary, proposals, conflicts}。
        proposals 的每一项都必须在外层包含 type、title、summary、app_scope、task_family、source_steps、outcome_step、reason、body。
        title 是简短的方法或知识名称，summary 说明它能解决什么问题，reason 说明本次依据。title/summary/reason 都不能为空，不能放进 body 代替外层字段。
        app_scope 无法确认时用空字符串；target_id/target_revision 仅在更新已提供的旧记忆时填写。source_steps 引用实际步骤，不猜步骤编号。
        例如一条 App 知识：
        {"type":"fact","title":"笔记保存入口","summary":"编辑页通过完成入口保存笔记","app_scope":"原子笔记","task_family":"note","source_steps":[3,4],"outcome_step":4,"reason":"第4步确认保存后的内容仍存在","body":{"domain":"app_knowledge","value":"编辑页可通过完成入口保存笔记，保存后需确认内容保留","conditions":["处于笔记编辑页"],"aliases":["完成编辑","保存笔记"]}}
        例子的 App 和步骤仅用于说明格式；实际字段必须来自本批次观察。没有证据时不要照抄例子。

        只依据输入的观察、实际动作和后续结果。finish 只是 Agent 的结束声明，不证明任何方法成功。
        一条记忆解决一个局部问题。保留 App、页面条件和完成标准；步骤写操作意图，用当前截图重新定位，不保存坐标、旧 element id 或本次输入值。
        商品价格、临时正文、本次选项留在 episode 摘要，不变成长期事实。普通纠正不代表长期偏好；推断的习惯不能当成用户默认设置。

        fact 保存稳定 App 知识或明确长期偏好，body 包含 domain(user_preference/app_knowledge)、value、conditions、aliases。
        procedure 的 body 必须包含 goal、parameters[{name,description,required}]、preconditions、nodes、completion、failure_notes、aliases；没有参数或失败经验时仍填写空数组。
        每个 node 包含 node_id、intent、expected、next（最后一个为空）、branches[{condition,next}]。节点 id 稳定，分支仅记录亲眼观察过的恢复路径。
        参数用 {{name}} 表示，例如 {{title}}、{{body}}；下次运行取新参数。保留有用的失败条件，但原因未知时不要编造结论。
        procedure 可包含 data_dependencies[{source,transform,target_node,target_field}]，描述本次用户输入、当前页面或任务记忆如何经过简短处理传给某个节点字段。旧方法已有依赖时必须保留仍成立的依赖。
        source以文字说明用户输入、页面观察或前序步骤输出的来源。target_field是目标页面上的语义字段（如标题、正文），不是节点JSON属性名。
        初始输入才设 required=true；执行过程中采集的数据进入本次任务记忆，需要重新绑定时提前声明为 required=false 的可选参数。不要把演示中的旧值固定在依赖中。

        source_steps 指向本批次实际提供的源步骤。outcome_step 是包含后续确认结果的观察步骤；没有局部成功观测填 0，建议保留候选。
        成功观测必须确认此前的动作/子目标，不能拿同一步拟执行动作当成已经完成。为方法提供起始动作和结果观察两个步骤。
        对同目标、同 App、同条件的旧记忆，填 target_id 和 target_revision，返回合并后的完整正文，保留仍成立的条件和证据。
        memory_feedback_review 是系统已接收的使用反馈，包含当时的记忆版本、accepted_feedback 和对应的 observations。
        优先逐条处理这些旧记忆：本次证据足以支持原内容时，即使正文不变，也返回带 target_id/target_revision 的完整建议，以追加本次证据；有矛盾时缩小条件或修正内容；证据不足时保持原样，并在 episode_summary 说明尚不能确认什么。
        accepted_feedback.details.source 区分 memory_feedback（Agent 明确反馈这条记忆的使用结果）、procedure_completion（方法完成反馈）和 previous_action_result（仅动作结果）。反馈是观察线索，仍需核对对应动作与后续页面。
        scope=action 的成功只支持对应局部动作，不能证明整条方法的所有步骤。返回后看到笔记，不证明页面没有保存按钮，也不证明此前点击完成没有生效；存在先前保存动作时，不能据此确认“返回触发保存”。
        注入、检索、人工确认和任务整体完成都不等于本次实测成功。不要为了凑成功记录更新旧记忆。memory.current_revision 与 revision 不同时，反馈属于历史版本，不能直接套用到当前版本。
        条件不同就建立条件明确的变体。不要改写 explicit_user/user_edit 来源、停用或已删除记忆；不要通过换标题绕过停用。
        旧经验与新证据冲突时在 conflicts 中写 target_id 与原因；只有当前证据支持的新版本才提出更新，不擅自泛化。
        aliases 是少量实用的同义检索词。摘要短而具体，不写空泛建议，不堆重复条目。最多给 8 条建议。

        输入可能只是一部分任务，fragment 标记长事件的片段。只总结当前提供的部分，不宣称已读完未提供的内容。
        thought_excerpt/text_excerpt 是摘录，完整内容仍在源步骤；不要凭摘录推断省略的正文。执行坐标和重复反馈已省略。
        episode_summary 记录本批次关键经历和历史结果；历史价格等必须保持其“本次观察”的含义。
    """.trimIndent()

    fun request(): LlmRequestSchema {
        val string = JSONObject().put("type", "string")
        fun strings() = JSONObject().put("type", "array").put("items", string)
        fun obj(properties: JSONObject, required: List<String>) = JSONObject()
            .put("type", "object").put("properties", properties).put("required", JSONArray(required))
        fun array(items: JSONObject) = JSONObject().put("type", "array").put("items", items)
        val body = obj(JSONObject()
            .put("domain", string).put("value", string).put("conditions", strings())
            .put("aliases", strings()).put("goal", string).put("preconditions", strings())
            .put("completion", string).put("failure_notes", strings())
            .put("data_dependencies", array(obj(JSONObject().put("source", string).put("transform", string)
                .put("target_node", string).put("target_field", string), listOf("source", "transform", "target_node", "target_field"))))
            .put("parameters", array(obj(JSONObject().put("name", string).put("description", string)
                .put("required", JSONObject().put("type", "boolean")), listOf("name", "description", "required"))))
            .put("nodes", array(obj(JSONObject().put("node_id", string).put("intent", string)
                .put("expected", string).put("next", string)
                .put("branches", array(obj(JSONObject().put("condition", string).put("next", string), listOf("condition", "next")))),
                listOf("node_id", "intent", "expected", "next", "branches")))), listOf("aliases"))
        val proposal = obj(JSONObject()
            .put("type", JSONObject().put("type", "string").put("enum", JSONArray(listOf("fact", "procedure"))))
            .put("title", string).put("summary", string).put("app_scope", string).put("task_family", string)
            .put("target_id", string).put("target_revision", JSONObject().put("type", "integer"))
            .put("source_steps", array(JSONObject().put("type", "integer")))
            .put("outcome_step", JSONObject().put("type", "integer"))
            .put("reason", string).put("body", body),
            listOf("type", "title", "summary", "app_scope", "task_family", "source_steps", "outcome_step", "reason", "body"))
        val parameters = obj(JSONObject().put("episode_summary", string)
            .put("proposals", array(proposal).put("maxItems", 8))
            .put("conflicts", array(obj(JSONObject().put("target_id", string).put("reason", string), listOf("target_id", "reason")))),
            listOf("episode_summary", "proposals", "conflicts"))
        return LlmRequestSchema(JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "consolidate_memory")
            .put("description", "从已有手机执行证据整理长期知识、参数化方法和历史摘要。")
            .put("parameters", parameters)), maxOutputTokens = 6000)
    }
}
