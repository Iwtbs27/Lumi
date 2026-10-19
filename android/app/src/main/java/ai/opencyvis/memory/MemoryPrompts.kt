package ai.opencyvis.memory

object MemoryPrompts {
    fun rules(chinese: Boolean): String = if (chinese) """

长期记忆的使用：
- long_term_memory 是按当前目标检索的知识和方法。先看适用条件，再根据当前截图决定是否采用；user_confirmed 表示用户确认过内容，observed_once 表示有一次任务观察支持。当前要求始终优先，旧价格等历史结果不能当成实时事实。
- origin=learned 表示来自人类教学或纠错。人类演示和用户确认不会增加 Agent 独立执行次数；局部成功也只支持对应步骤。
- 采用记忆时，可以随当前动作填写 memory_use（id、revision，可选 node_id），也可以观察结果后直接报告 memory_feedback。无需单独占一轮声明采用；没用到的记忆不用报告。
- 页面类别或 App 变化时，可附带 memory_context（app、page、query_terms）。它描述当前图，程序会标注观察步骤；旧页面标签不是当前页面的证明。
- 缺少相关方法时用 search_memory(memory_query,memory_scope="memories")。查过去的任务结果用 memory_scope="episodes"；read_memory(memory_id,memory_scope,memory_detail) 展开详情或证据。工具占一轮，结果下轮返回；历史图只帮助理解，当前坐标仍从当前图取得。
- 采用参数化流程时，用 procedure_update.start 指定 id、revision、node_id 和 bindings。每个绑定含 name/value，以及 task_memory_key 或 user_source_index（0=原任务，1起=后续用户消息）。参数必须来自本次任务，缺失时先补齐。
- 当前节点的 data_dependencies 说明输入从哪里来、如何整理及写入哪里。运行中收集的信息用 memory_update 保存在本次任务记忆，用到时再读取或 rebind 可选参数；不要照抄演示中的商品、价格和正文。启动时只要求 required=true 的初始参数。
- current_method 标记 partial 时，按 read_memory 提示补读缺少的部分；preview 是明确截断的片段，不能据此猜测未显示的数据来源、条件或步骤。
- 一个节点可需要多个动作。观察到节点 expected 后，再用 advance/branch 指向已有下一节点并写 evidence_step/evidence；不要因为刚发出动作就推进。新要求或事实改变时 rebind；整体 completion 得到证据后 complete；不适用时 abort，继续正常完成用户任务。
- 用过的记忆有了可判断的结果时，随当前响应附带一次 memory_feedback：id/revision、applied_step（实际采用它的动作）、outcome、evidence_step/evidence（后续观察）。之前未填 memory_use 也可反馈。只引用当时看到的记忆版本；说清哪项做法得到支持或被反驳，不必等整项任务结束。
- 只有记忆本身被观察结果反驳时 cause=memory；网络或执行问题用 environment，原因不明用 unknown。只反馈实际执行过的动作；结果看不清时 outcome=unknown。动作成功不证明整条方法都正确，记忆被提供过也不代表采用过。
- remember 仅保存用户明确要求长期记住的信息，填写 memory_source_index 和 memory_source_quote 引用原话，并可填 memory_app。一次临时指示留在 task_memory。操作经验由任务后的整理保存，不需要每步另写一份。
- procedure_reference 是用户从已保存方法启动本次任务的引用；核对当前条件后使用，其中 parameters 仅用于这次执行。
""".trimIndent() else """

Using long-term memory:
- long_term_memory contains retrieved knowledge and methods. Check conditions against the current screenshot before using them. user_confirmed means the user reviewed its content; observed_once means one supporting task observation. Current instructions take priority; historical prices and results are not current facts.
- origin=learned identifies human teaching or correction. Demonstrations and user confirmation do not count as independent agent execution; local success supports only the corresponding step.
- When using a memory, optionally attach memory_use (id, revision, optional node_id) to the action, or report memory_feedback after observing the result. Do not spend a separate step declaring adoption. Omit reports for unused memories.
- On meaningful app/page changes, attach memory_context (app, page, query_terms) describing this observation. The engine stamps its step; an older page label does not establish the current page.
- Use search_memory(memory_query,memory_scope="memories") for methods, or scope="episodes" for past task results. read_memory(memory_id,memory_scope,memory_detail) expands details or evidence next turn. These tools consume a turn; historical images never supply current coordinates.
- To follow a procedure, use procedure_update.start with id/revision/node_id and bindings. Each binding has name/value and task_memory_key or user_source_index (0=task, 1+=user updates). Bind only this task's values; obtain missing inputs first.
- Current-node data_dependencies specify input sources, transformations and destinations. Store newly collected values with memory_update and read them when needed, or rebind declared optional parameters. Never copy products, prices or text from a demonstration. Only required=true initial parameters are needed to start.
- When current_method is marked partial, use its read_memory guidance to read omitted content. A preview is explicitly truncated; do not infer unseen input sources, conditions or steps from it.
- A node may take several actions. After observing its expected result, advance/branch to a declared node with evidence_step/evidence. Never advance merely because an action was sent. Rebind changed inputs; complete only with observed completion; abort an unsuitable method and continue the user's task normally.
- Once a used memory has an observable result, attach memory_feedback to the current response: id/revision, applied_step (the action that used it), outcome, and evidence_step/evidence (a later observation). No prior memory_use is required. Cite the version you saw at that time and explain which claim was supported or contradicted; do not wait for the whole task to finish.
- Use cause=memory only when observations contradict the memory, environment for execution/network issues, or unknown when unclear. Refer only to executed actions; use outcome=unknown if the result is unclear. One successful action does not validate an entire procedure, and receiving a memory does not mean using it.
- remember stores only an explicit persistent user instruction, quoting memory_source_quote from memory_source_index, optionally scoped by memory_app. Temporary instructions stay in task_memory. Task-end consolidation captures methods without per-step reflection calls.
- procedure_reference identifies a saved method chosen by the user for this run. Check its conditions; its parameters apply only to this task.
""".trimIndent()
}
