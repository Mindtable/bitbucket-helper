package com.mindtable.bitbuckethelper.adapter.outbound.notification

import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryFailureCategory
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryResult
import com.mindtable.bitbuckethelper.application.model.NotificationRequest
import com.mindtable.bitbuckethelper.application.model.NotificationSound
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationSender
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import com.mindtable.bitbuckethelper.observability.reportBackendEventRecorderFailure
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class DesktopNotificationsProcessAdapter(
    private val executable: Path,
    private val capture: BoundedProcessCapture = BoundedProcessCapture(),
    private val recorder: BackendEventRecorder = BackendEventRecorder.NONE,
    private val timeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
) : NotificationSender {
    init {
        require(executable.isAbsolute) { "Desktop notifications executable path must be absolute" }
        require(executable == executable.normalize()) { "Desktop notifications executable path must be normalized" }
        require(Files.isRegularFile(executable)) { "Desktop notifications executable path must be a regular file" }
        require(Files.isExecutable(executable)) { "Desktop notifications executable path must be executable" }
    }

    override suspend fun send(request: NotificationRequest): NotificationDeliveryResult {
        val startedAtNanos = timeSource.nanoTime()
        var processStarted = false
        val result = try {
            withTimeout(OUTER_DEADLINE.toMillis()) {
                coroutineContext.ensureActive()
                val command = commandFor(request)
                val process = try {
                    ProcessBuilder(command).start()
                } catch (_: IOException) {
                    return@withTimeout failure(NotificationDeliveryFailureCategory.PROCESS_NOT_STARTED, ambiguous = false)
                } catch (_: SecurityException) {
                    return@withTimeout failure(NotificationDeliveryFailureCategory.PROCESS_NOT_STARTED, ambiguous = false)
                }
                processStarted = true
                classify(capture.capture(process, OUTER_DEADLINE))
            }
        } catch (_: TimeoutCancellationException) {
            if (processStarted) {
                failure(NotificationDeliveryFailureCategory.DELIVERY_TIMEOUT, ambiguous = true)
            } else {
                failure(NotificationDeliveryFailureCategory.PROCESS_NOT_STARTED, ambiguous = false)
            }
        } catch (cancellation: CancellationException) {
            if (processStarted) {
                failure(NotificationDeliveryFailureCategory.AMBIGUOUS_PROCESS_FAILURE, ambiguous = true)
            } else {
                throw cancellation
            }
        }
        recordResult(result, startedAtNanos)
        return result
    }

    private fun recordResult(result: NotificationDeliveryResult, startedAtNanos: Long) {
        val durationMilliseconds =
            ((timeSource.nanoTime() - startedAtNanos).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND
        val event = when (result) {
            NotificationDeliveryResult.Accepted ->
                BackendLogEvent.NotificationProviderCompleted(durationMilliseconds)

            is NotificationDeliveryResult.Failed -> BackendLogEvent.NotificationProviderFailed(
                category = result.category.name.lowercase(Locale.ROOT),
                ambiguous = result.ambiguous,
                durationMilliseconds = durationMilliseconds,
            )
        }
        try {
            recorder.record(event)
        } catch (_: Throwable) {
            reportBackendEventRecorderFailure()
            // Logging must not change delivery classification or cancellation semantics.
        }
    }

    private fun commandFor(request: NotificationRequest): List<String> = buildList {
        add(executable.toString())
        add("send")
        add("--delivery-key")
        add(request.deliveryKey.value)
        add("--title")
        add(request.title)
        add("--body")
        add(request.body)
        request.openUrl?.let { openUrl ->
            add("--open-url")
            add(openUrl.toString())
        }
        add("--sound")
        add(PROVIDER_SOUNDS.getValue(request.sound))
    }

    private fun classify(result: NotificationProcessResult): NotificationDeliveryResult = when (result) {
        is NotificationProcessResult.TimedOut ->
            failure(NotificationDeliveryFailureCategory.DELIVERY_TIMEOUT, ambiguous = true)

        is NotificationProcessResult.Exited -> {
            if (result.derivedSignal != null) {
                failure(NotificationDeliveryFailureCategory.AMBIGUOUS_PROCESS_FAILURE, ambiguous = true)
            } else {
                classifyExited(result)
            }
        }
    }

    private fun classifyExited(result: NotificationProcessResult.Exited): NotificationDeliveryResult {
        if (result.exitCode != ACCEPTED_EXIT_CODE && result.exitCode != FAILED_EXIT_CODE) {
            return failure(NotificationDeliveryFailureCategory.UNEXPECTED_EXIT, ambiguous = true)
        }

        return when (val response = decodeResponse(result)) {
            ProviderResponse.Accepted ->
                if (result.exitCode == ACCEPTED_EXIT_CODE) NotificationDeliveryResult.Accepted
                else failure(NotificationDeliveryFailureCategory.UNEXPECTED_EXIT, ambiguous = false)

            is ProviderResponse.Failed ->
                if (result.exitCode == FAILED_EXIT_CODE) failure(response.category, ambiguous = false)
                else failure(NotificationDeliveryFailureCategory.UNEXPECTED_EXIT, ambiguous = false)

            null -> failure(NotificationDeliveryFailureCategory.MALFORMED_RESPONSE, ambiguous = false)
        }
    }

    private fun decodeResponse(result: NotificationProcessResult.Exited): ProviderResponse? {
        if (result.stdoutOverflowed) return null
        val output = decodeUtf8(result.stdout) ?: return null
        if (!output.endsWith(FINAL_LINE_FEED)) return null

        val document = output.dropLast(1)
        if (document.isEmpty() || document.first() != '{' || document.last() != '}') return null
        if (document.first() == BYTE_ORDER_MARK || document.last() == FINAL_LINE_FEED) return null

        val objectValue = runCatching {
            strictJson.parseToJsonElement(document) as? JsonObject
        }.getOrNull() ?: return null
        return decodeObject(objectValue)
    }

    private fun decodeObject(value: JsonObject): ProviderResponse? {
        val status = value["status"].stringValue() ?: return null
        return when (status) {
            "accepted" -> {
                if (value.keys == setOf("status")) ProviderResponse.Accepted else null
            }

            "failed" -> {
                if (value.keys != setOf("status", "error")) return null
                val error = value["error"] as? JsonObject ?: return null
                if (error.keys != setOf("code", "message")) return null
                val code = error["code"].stringValue() ?: return null
                if (error["message"].stringValue() == null) return null
                PROVIDER_FAILURE_CATEGORIES[code]?.let(ProviderResponse::Failed)
            }

            else -> null
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun JsonElement?.stringValue(): String? =
        (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun failure(
        category: NotificationDeliveryFailureCategory,
        ambiguous: Boolean,
    ): NotificationDeliveryResult.Failed = NotificationDeliveryResult.Failed(category, ambiguous)

    private sealed interface ProviderResponse {
        data object Accepted : ProviderResponse

        data class Failed(val category: NotificationDeliveryFailureCategory) : ProviderResponse
    }

    private companion object {
        val OUTER_DEADLINE: Duration = Duration.ofSeconds(15)
        const val ACCEPTED_EXIT_CODE = 0
        const val FAILED_EXIT_CODE = 1
        const val FINAL_LINE_FEED = '\n'
        const val BYTE_ORDER_MARK = '\uFEFF'
        const val NANOS_PER_MILLISECOND = 1_000_000L

        val strictJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }

        val PROVIDER_SOUNDS = mapOf(
            NotificationSound.DEFAULT to "default",
            NotificationSound.BASSO to "basso",
            NotificationSound.BLOW to "blow",
            NotificationSound.BOTTLE to "bottle",
            NotificationSound.FROG to "frog",
            NotificationSound.FUNK to "funk",
            NotificationSound.GLASS to "glass",
            NotificationSound.HERO to "hero",
            NotificationSound.MORSE to "morse",
            NotificationSound.PING to "ping",
            NotificationSound.POP to "pop",
            NotificationSound.PURR to "purr",
            NotificationSound.SOS to "sosumi",
            NotificationSound.SUBMARINE to "submarine",
            NotificationSound.TINK to "tink",
        )

        val PROVIDER_FAILURE_CATEGORIES = mapOf(
            "invalid_arguments" to NotificationDeliveryFailureCategory.INVALID_ARGUMENTS,
            "unsupported_platform" to NotificationDeliveryFailureCategory.UNSUPPORTED_PLATFORM,
            "dependency_unavailable" to NotificationDeliveryFailureCategory.DEPENDENCY_UNAVAILABLE,
            "delivery_timeout" to NotificationDeliveryFailureCategory.DELIVERY_TIMEOUT,
            "delivery_failed" to NotificationDeliveryFailureCategory.DELIVERY_FAILED,
            "internal_error" to NotificationDeliveryFailureCategory.INTERNAL_ERROR,
        )
    }
}
