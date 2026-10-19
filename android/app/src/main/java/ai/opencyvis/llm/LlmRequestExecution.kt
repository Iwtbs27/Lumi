package ai.opencyvis.llm

import ai.opencyvis.engine.ContextTokenEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** A client owns one serial decision stream; cancellation invalidates only the current round. */
internal class LlmRequestExecution(private val scope: LlmExecutionScope?) {
    private val serial = Mutex()
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private var activeRound: Job? = null
    private var activeCall: Call? = null
    private val estimator = ContextTokenEstimator()
    private val governor = scope?.governor ?: LlmRequestGovernor.processWide

    suspend fun <T> runRound(block: suspend (Round) -> T): T = coroutineScope {
        val expected = synchronized(lock) { generation }
        serial.withLock {
            val job = requireNotNull(currentCoroutineContext()[Job])
            synchronized(lock) {
                checkGeneration(expected)
                activeRound = job
            }
            try { block(Round(expected)) }
            finally { synchronized(lock) { if (activeRound === job) activeRound = null } }
        }
    }

    fun cancelRequests() {
        val targets = synchronized(lock) {
            generation++
            activeRound to activeCall
        }
        targets.first?.cancel(CancellationException("Model request round cancelled"))
        targets.second?.cancel()
    }

    fun shutdown() {
        synchronized(lock) { closed = true }
        cancelRequests()
    }

    private fun checkGeneration(expected: Long) {
        if (closed || generation != expected) throw CancellationException("Model request round cancelled")
    }

    inner class Round internal constructor(private val expected: Long) {
        suspend fun ensureActive() {
            currentCoroutineContext().ensureActive()
            synchronized(lock) { checkGeneration(expected) }
        }

        suspend fun <T> attempt(
            httpClient: OkHttpClient, request: Request, payload: JSONObject,
            messages: List<Map<String, Any>>, schema: LlmRequestSchema, outputLimitTokens: Int,
            attempt: Int, tracker: LlmCallTracker, consume: (Response) -> T
        ): T {
            ensureActive()
            require(schema.purpose != LlmCallPurpose.FINALIZATION || scope != null) {
                "最终汇总必须由已配置任务预算的父任务发起"
            }
            val estimate = estimator.estimate(messages, schema.tool)
            val reservation = scope?.budget?.reserve(scope.ownerId, scope.role, schema.purpose,
                estimate.totalTokens.toLong(), outputLimitTokens.toLong())
            var sent = false
            try {
                return governor.withRequest {
                    ensureActive()
                    val call = httpClient.newCall(request)
                    synchronized(lock) {
                        checkGeneration(expected)
                        activeCall = call
                    }
                    try {
                        // Await is cancellable even while synchronous OkHttp is reading a body.
                        // The scope waits for that reader to exit before releasing the permit.
                        coroutineScope {
                            val reading = async(Dispatchers.IO) {
                                this@Round.ensureActive()
                                tracker.begin(payload, attempt, reservation?.id, scope?.ownerId, schema.purpose)
                                sent = true
                                call.execute().use(consume)
                            }
                            try { reading.await() }
                            finally { if (!currentCoroutineContext().isActive) call.cancel() }
                        }
                    } finally {
                        synchronized(lock) { if (activeCall === call) activeCall = null }
                    }
                }
            } finally {
                if (sent) {
                    val info = tracker.lastCallInfo
                    estimator.calibrate(estimate, info?.usage?.inputTokens)
                    if (reservation != null) scope!!.budget.settle(reservation.id, info?.usage, info?.usageComplete == true)
                } else if (reservation != null) {
                    scope!!.budget.releaseUnsent(reservation.id)
                }
            }
        }
    }
}
