package com.telestash.td

import android.content.Context
import com.telestash.BuildConfig
import com.telestash.data.DbKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TdException(
    override val message: String,
) : Exception(message) {
    val isFloodWait: Boolean get() = message.startsWith("FLOOD_WAIT_")
    val floodWaitSeconds: Long
        get() =
            if (isFloodWait) {
                message.removePrefix("FLOOD_WAIT_").substringBefore(' ').toLongOrNull() ?: 0L
            } else {
                0L
            }
}

/**
 * Coroutines wrapper around TDLib with a re-usable update stream.
 * Process-wide singleton; media is sent as documents (lossless).
 */
class TgClient private constructor(
    private val tdlibDir: String,
    private val apiId: Int,
    private val apiHash: String,
    private val dbKey: ByteArray,
) {
    companion object {
        const val ALBUM_CAPTION_PREFIX = "telestash-album:"
        private const val TOPICS_COLLECTION_TIMEOUT_MS = 3_000L
        private const val TOPICS_QUIET_PERIOD_MS = 250L
        private const val TOPICS_PAGE_LIMIT = 100
        private const val DOWNLOAD_PRIORITY = 16
        private const val LOG_MAX_SIZE_BYTES = 1L shl 22
        private const val UPDATE_REPLAY_BUFFER = 32

        /**
         * Uploads of large files take minutes; give the server enough time to
         * acknowledge the bytes before a pass gives up and marks the item failed.
         */
        private const val SEND_CONFIRM_TIMEOUT_MS = 30L * 60 * 1000
        private const val FORWARD_MESSAGE_LIMIT = 100
        private const val CLOSE_TIMEOUT_MS = 5_000L
        private const val HISTORY_RETRY_LIMIT = 3
        private const val HISTORY_RETRY_BASE_DELAY_MS = 500L

        @Volatile private var instance: TgClient? = null

        fun getOrCreate(
            context: Context,
            apiId: Int,
            apiHash: String,
        ): TgClient =
            instance ?: synchronized(this) {
                instance ?: TgClient(
                    context.getDir("tdlib", Context.MODE_PRIVATE).absolutePath,
                    apiId,
                    apiHash,
                    DbKeyStore.tdlibKey(context),
                ).also { instance = it }
            }

        fun peek(): TgClient? = instance

        suspend fun shutdownAndWait() {
            val c = instance ?: return
            try {
                runCatching { c.native.send(TdApi.Close(), null, null) }
                withTimeoutOrNull(CLOSE_TIMEOUT_MS) { c.authorizationClosed.await() }
            } finally {
                c.scope.cancel()
                synchronized(this) {
                    if (instance === c) instance = null
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authorizationClosed = CompletableDeferred<Unit>()

    private val _updates =
        MutableSharedFlow<TdApi.Update>(
            // The startup burst (options + auth states) can exceed 1 replay slot
            // before any subscriber attaches.
            replay = UPDATE_REPLAY_BUFFER,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    /** True once TDLib confirmed a signed-in session (server-side or replayed). */
    @Volatile var isAuthorized = false
        private set

    /** Guards [start] against the UI and the backup worker racing on cold start. */
    private val startAttempted = AtomicBoolean(false)

    private var cachedChatId = 0L
    private val sendMutex = Mutex()

    internal val native: Client =
        Client.create(
            // tryEmit, not scope.launch { emit }: a progress flood would otherwise
            // spawn one coroutine per update and race concurrent sends into the flow.
            { update -> if (update is TdApi.Update) _updates.tryEmit(update) },
            { /* update handler exception */ },
            { /* default exception */ },
        )

    init {
        // Track the session's own auth state: lets background callers (the
        // backup worker) tell a replayed sign-in from one that needs a login.
        scope.launch {
            updates
                .filterIsInstance<TdApi.UpdateAuthorizationState>()
                .collect { u ->
                    isAuthorized = u.authorizationState is TdApi.AuthorizationStateReady
                    if (u.authorizationState is TdApi.AuthorizationStateClosed) {
                        authorizationClosed.complete(Unit)
                    }
                }
        }
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
        runCatching { Client.execute(TdApi.SetOption("redact_phone_numbers", TdApi.OptionValueBoolean(true))) }
        runCatching {
            val logDir = File("$tdlibDir-logs").apply { mkdirs() }
            Client.execute(
                TdApi.SetLogStream(
                    TdApi.LogStreamFile(
                        logDir.absolutePath + "/tdlib.log",
                        LOG_MAX_SIZE_BYTES,
                        false,
                    ),
                ),
            )
        }
    }

    /**
     * Configure TDLib; safe to call once per client instance (guarded, so the
     * UI and the backup worker can both call it on a cold start). Sending
     * SetTdlibParameters is what triggers the session replay from the local
     * database; authorization then arrives asynchronously as
     * [TdApi.AuthorizationStateReady] and flips [isAuthorized].
     */
    suspend fun start() {
        if (!startAttempted.compareAndSet(false, true)) return
        try {
            send(tdlibParameters())
        } catch (e: CancellationException) {
            startAttempted.set(false)
            throw e
        } catch (e: Exception) {
            startAttempted.set(false)
            throw e
        }
    }

    private fun tdlibParameters(): TdApi.SetTdlibParameters {
        val useFileDatabase = true
        val useChatInfoDatabase = true
        val useMessageDatabase = true
        val useSecretChats = false
        return TdApi.SetTdlibParameters(
            false,
            tdlibDir,
            "$tdlibDir/files",
            dbKey,
            useFileDatabase,
            useChatInfoDatabase,
            useMessageDatabase,
            useSecretChats,
            apiId,
            apiHash,
            "en",
            android.os.Build.MODEL,
            android.os.Build.VERSION.RELEASE,
            BuildConfig.APP_VERSION,
        )
    }

    suspend fun setPhone(phone: String) {
        send(
            TdApi.SetAuthenticationPhoneNumber(
                phone,
                TdApi.PhoneNumberAuthenticationSettings(
                    false,
                    false,
                    false,
                    false,
                    false,
                    null,
                    arrayOf(),
                ),
            ),
        )
    }

    suspend fun checkCode(code: String) = send(TdApi.CheckAuthenticationCode(code))

    suspend fun checkPassword(pw: String) = send(TdApi.CheckAuthenticationPassword(pw))

    suspend fun registerUser(firstName: String) = send(TdApi.RegisterUser(firstName, "", false))

    suspend fun logout() {
        send(TdApi.LogOut())
        cachedChatId = 0L
        isAuthorized = false
    }

    suspend fun refreshAuthorizationState(): TdApi.AuthorizationState {
        val state = send(TdApi.GetAuthorizationState())
        isAuthorized = state is TdApi.AuthorizationStateReady
        return state
    }

    suspend fun currentUserId(): Long =
        (send(TdApi.GetOption("my_id")) as? TdApi.OptionValueInteger)?.value
            ?: throw TdException("my_id option is unavailable")

    suspend fun savedMessagesChatId(): Long {
        if (cachedChatId != 0L) return cachedChatId
        cachedChatId = send(TdApi.CreatePrivateChat(currentUserId(), true)).id
        return cachedChatId
    }

    /**
     * Create an album topic. Saved Messages topics are created implicitly by sending
     * the first message into a MessageTopicSavedMessages id; the server confirms via
     * updateSavedMessagesTopic. Returns the topic id used.
     */
    suspend fun createAlbumTopic(albumName: String): Long {
        val topicId = System.currentTimeMillis()
        sendMutex.withLock {
            withSendOutcomeCollector { collector ->
                val sent =
                    send(
                        TdApi.SendMessage(
                            savedMessagesChatId(),
                            TdApi.MessageTopicSavedMessages(topicId),
                            null,
                            null,
                            null,
                            TdApi.InputMessageText(
                                TdApi.FormattedText("$ALBUM_CAPTION_PREFIX$albumName", arrayOf()),
                                null,
                                false,
                            ),
                        ),
                    )
                collector.await(sent.id)
            }
        }
        return topicId
    }

    /** List Saved Messages topics; they arrive via updateSavedMessagesTopic. */
    suspend fun loadTopics(): List<TdApi.SavedMessagesTopic> {
        val collected = mutableListOf<TdApi.SavedMessagesTopic>()
        coroutineScopeRun { collector ->
            val lastArrival = AtomicLong(System.currentTimeMillis())
            collector.launch {
                updates
                    .filterIsInstance<TdApi.UpdateSavedMessagesTopic>()
                    .collect { u ->
                        collected.add(u.topic)
                        // "now" restarts the quiet-period clock below.
                        lastArrival.set(System.currentTimeMillis())
                    }
            }
            try {
                send(TdApi.LoadSavedMessagesTopics(TOPICS_PAGE_LIMIT))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            // Wait until topic updates stop arriving for a short quiet period
            // (or the overall timeout hits), instead of a fixed one-second wait.
            withTimeoutOrNull(TOPICS_COLLECTION_TIMEOUT_MS) {
                while (System.currentTimeMillis() - lastArrival.get() < TOPICS_QUIET_PERIOD_MS) {
                    delay(TOPICS_QUIET_PERIOD_MS)
                }
            }
        }
        return collected.distinctBy { it.id }
    }

    private suspend fun coroutineScopeRun(block: suspend (CoroutineScope) -> Unit) {
        val s = CoroutineScope(Dispatchers.IO)
        try {
            block(s)
        } finally {
            s.cancel()
        }
    }

    suspend fun uploadDocument(
        path: String,
        fileName: String,
        topicId: Long = 0L,
    ): TdApi.Message =
        sendMutex.withLock {
            val topic = if (topicId > 0) TdApi.MessageTopicSavedMessages(topicId) else null
            withSendOutcomeCollector { collector ->
                val sent =
                    send(
                        TdApi.SendMessage(
                            savedMessagesChatId(),
                            topic,
                            null,
                            null,
                            null,
                            TdApi.InputMessageDocument(
                                TdApi.InputDocument(TdApi.InputFileLocal(path), null, false),
                                TdApi.FormattedText("telestash:$fileName", arrayOf()),
                            ),
                        ),
                    )
                collector.await(sent.id)
            }
        }

    private sealed interface SendEvent {
        data class Update(
            val value: TdApi.Update,
        ) : SendEvent

        data class Deleted(
            val messageId: Long,
        ) : SendEvent
    }

    private inner class SendOutcomeCollector {
        private val events = Channel<SendEvent>(Channel.UNLIMITED)
        private val buffered = mutableMapOf<Long, SendEvent>()
        private val collector =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                updates.collect { update ->
                    when (update) {
                        is TdApi.UpdateMessageSendSucceeded,
                        is TdApi.UpdateMessageSendFailed,
                        -> events.trySend(SendEvent.Update(update))
                        is TdApi.UpdateDeleteMessages ->
                            update.messageIds.forEach { id -> events.trySend(SendEvent.Deleted(id)) }
                        else -> Unit
                    }
                }
            }

        init {
            collector.invokeOnCompletion { cause -> events.close(cause) }
        }

        suspend fun await(messageId: Long): TdApi.Message {
            val outcome =
                withTimeoutOrNull(SEND_CONFIRM_TIMEOUT_MS) {
                    buffered.remove(messageId) ?: receiveOutcome(messageId)
                } ?: throw TdException("SEND_TIMEOUT")
            return confirmedMessage(outcome)
        }

        private fun confirmedMessage(outcome: SendEvent): TdApi.Message =
            when (outcome) {
                is SendEvent.Update -> {
                    val update = outcome.value
                    when (update) {
                        is TdApi.UpdateMessageSendSucceeded -> update.message
                        is TdApi.UpdateMessageSendFailed ->
                            throw TdException(update.error.message)
                        else -> error("unreachable")
                    }
                }
                is SendEvent.Deleted -> throw TdException("send was deleted before completion")
            }

        private suspend fun receiveOutcome(messageId: Long): SendEvent {
            while (true) {
                val event = events.receive()
                val eventId = eventId(event) ?: continue
                if (eventId == messageId) return event
                buffered[eventId] = event
            }
        }

        private fun eventId(event: SendEvent): Long? =
            when (event) {
                is SendEvent.Deleted -> event.messageId
                is SendEvent.Update -> {
                    when (val update = event.value) {
                        is TdApi.UpdateMessageSendSucceeded ->
                            update.oldMessageId.takeIf { it != 0L } ?: update.message.id
                        is TdApi.UpdateMessageSendFailed ->
                            update.oldMessageId.takeIf { it != 0L } ?: update.message.id
                        else -> null
                    }
                }
            }

        fun close() {
            buffered.clear()
            collector.cancel()
            events.cancel()
        }
    }

    private suspend fun <T> withSendOutcomeCollector(block: suspend (SendOutcomeCollector) -> T): T {
        val collector = SendOutcomeCollector()
        return try {
            block(collector)
        } finally {
            collector.close()
        }
    }

    suspend fun moveToTopic(
        messageIds: LongArray,
        topicId: Long,
    ): List<TdApi.Message> {
        if (messageIds.isEmpty()) return emptyList()
        val sortedIds = messageIds.sorted()
        return sendMutex.withLock {
            val chatId = savedMessagesChatId()
            val forwarded = mutableListOf<TdApi.Message>()
            sortedIds.chunked(FORWARD_MESSAGE_LIMIT).forEach { chunk ->
                val returned =
                    withSendOutcomeCollector { collector ->
                        val response =
                            send(
                                TdApi.ForwardMessages(
                                    chatId,
                                    TdApi.MessageTopicSavedMessages(topicId),
                                    chatId,
                                    chunk.toLongArray(),
                                    null,
                                    true,
                                    false,
                                ),
                            )
                        val messages = response.messages.filterNotNull()
                        if (messages.size != chunk.size) {
                            throw TdException("forward returned ${messages.size} of ${chunk.size} message(s)")
                        }
                        messages.map { collector.await(it.id) }
                    }
                forwarded += returned
            }
            send(TdApi.DeleteMessages(chatId, sortedIds.toLongArray(), true))
            forwarded
        }
    }

    /** Newest-first history page of Saved Messages. */
    suspend fun history(
        fromMessageId: Long,
        limit: Int,
    ): TdApi.Messages =
        retryAbortedRequest {
            send(TdApi.GetChatHistory(savedMessagesChatId(), fromMessageId, 0, limit, false))
        }

    /** Newest-first history page of a Saved Messages topic (album). */
    suspend fun topicHistory(
        topicId: Long,
        fromMessageId: Long,
        limit: Int,
    ): TdApi.Messages =
        retryAbortedRequest {
            send(TdApi.GetSavedMessagesTopicHistory(topicId, fromMessageId, 0, limit))
        }

    private suspend fun <T> retryAbortedRequest(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: TdException) {
                val aborted = e.message.orEmpty().contains("request aborted", ignoreCase = true)
                if (!aborted || attempt >= HISTORY_RETRY_LIMIT) throw e
                attempt++
                delay(HISTORY_RETRY_BASE_DELAY_MS * attempt)
            }
        }
    }

    suspend fun resolveRemoteFile(
        remoteFileId: String,
        type: TdApi.FileType,
    ): TdApi.File = send(TdApi.GetRemoteFile(remoteFileId, type))

    suspend fun messageExists(
        chatId: Long,
        messageId: Long,
    ): Boolean =
        try {
            send(TdApi.GetMessage(chatId, messageId))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: TdException) {
            if (e.isFloodWait) throw e
            false
        }

    /** Download a file synchronously (priority 16); returns the local path. */
    suspend fun download(fileId: Int): String {
        val local = send(TdApi.DownloadFile(fileId, DOWNLOAD_PRIORITY, 0, 0, true)).local
        if (!local.isDownloadingCompleted || local.path.isBlank() || !File(local.path).exists()) {
            throw TdException("download did not complete")
        }
        return local.path
    }

    suspend fun deleteMessages(
        chatId: Long,
        messageIds: LongArray,
    ) {
        send(TdApi.DeleteMessages(chatId, messageIds, true))
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T {
        val d = CompletableDeferred<TdApi.Object>()
        native.send(function) { result ->
            if (result is TdApi.Error) {
                d.completeExceptionally(TdException(result.message))
            } else {
                d.complete(result)
            }
        }
        return d.await() as T
    }
}
