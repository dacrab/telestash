package com.telestash.td

import android.content.Context
import com.telestash.BuildConfig
import com.telestash.data.DbKeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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

        fun shutdown() {
            instance?.let { c ->
                runCatching { c.native.send(TdApi.Close(), null, null) }
                c.scope.cancel()
            }
            instance = null
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                    if (u.authorizationState is TdApi.AuthorizationStateReady) {
                        isAuthorized = true
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
        runCatching { send(tdlibParameters()) }
            .onFailure { e ->
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
        cachedChatId = 0L
        runCatching { send(TdApi.LogOut()) }
    }

    /** Saved Messages chat id, resolved once via my_id -> createPrivateChat(force). */
    suspend fun savedMessagesChatId(): Long {
        if (cachedChatId != 0L) return cachedChatId
        val option =
            send(TdApi.GetOption("my_id")) as? TdApi.OptionValueInteger
                ?: throw TdException("my_id option is unavailable")
        cachedChatId = send(TdApi.CreatePrivateChat(option.value, true)).id
        return cachedChatId
    }

    /**
     * Create an album topic. Saved Messages topics are created implicitly by sending
     * the first message into a MessageTopicSavedMessages id; the server confirms via
     * updateSavedMessagesTopic. Returns the topic id used.
     */
    suspend fun createAlbumTopic(albumName: String): Long {
        val topicId = System.currentTimeMillis()
        send(
            TdApi.SendMessage(
                savedMessagesChatId(),
                TdApi.MessageTopicSavedMessages(topicId),
                null,
                null,
                null,
                TdApi.InputMessageText(
                    TdApi.FormattedText("telestash-album:$albumName", arrayOf()),
                    null,
                    false,
                ),
            ),
        )
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
            runCatching { send(TdApi.LoadSavedMessagesTopics(TOPICS_PAGE_LIMIT)) }
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

    /** Upload a file losslessly as document, optionally into an album topic. */
    suspend fun uploadDocument(
        path: String,
        fileName: String,
        topicId: Long = 0L,
    ): TdApi.Message {
        val topic = if (topicId > 0) TdApi.MessageTopicSavedMessages(topicId) else null
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
        return confirmSent(sent.id)
    }

    /**
     * SendMessage returns the local placeholder as soon as the file is queued,
     * not when the server holds the bytes. Waiting for the send result means a
     * caller only records an item once Telegram actually has it — a process
     * death mid-upload then leaves the item pending instead of silently "done".
     */
    private suspend fun confirmSent(messageId: Long): TdApi.Message {
        val events = Channel<TdApi.Update>(Channel.BUFFERED)
        val collector =
            scope.launch {
                updates.collect { u ->
                    if (u is TdApi.UpdateMessageSendSucceeded || u is TdApi.UpdateMessageSendFailed) {
                        events.trySend(u)
                    }
                }
            }
        try {
            val outcome =
                withTimeoutOrNull(SEND_CONFIRM_TIMEOUT_MS) {
                    var found: TdApi.Update? = null
                    while (found == null) {
                        val u = events.receive()
                        val isOurs =
                            when (u) {
                                is TdApi.UpdateMessageSendSucceeded -> u.message.id == messageId
                                is TdApi.UpdateMessageSendFailed -> u.message.id == messageId
                                else -> false
                            }
                        if (isOurs) found = u
                    }
                    found
                } ?: throw TdException("UPLOAD_TIMEOUT")
            return when (outcome) {
                is TdApi.UpdateMessageSendSucceeded -> outcome.message
                is TdApi.UpdateMessageSendFailed ->
                    throw TdException("upload rejected: ${outcome.error.message}")
                else -> error("unreachable")
            }
        } finally {
            collector.cancel()
        }
    }

    /** Move messages into an album topic: forward as copies, then delete originals. */
    suspend fun moveToTopic(
        messageIds: LongArray,
        topicId: Long,
    ): List<TdApi.Message> {
        val chatId = savedMessagesChatId()
        val sendCopy = true
        val removeCaption = false
        val res =
            send(
                TdApi.ForwardMessages(
                    chatId,
                    TdApi.MessageTopicSavedMessages(topicId),
                    chatId,
                    messageIds,
                    null,
                    sendCopy,
                    removeCaption,
                ),
            )
        send(TdApi.DeleteMessages(chatId, messageIds, true))
        return res.messages.toList()
    }

    /** Newest-first history page of Saved Messages. */
    suspend fun history(
        fromMessageId: Long,
        limit: Int,
    ): TdApi.Messages = send(TdApi.GetChatHistory(savedMessagesChatId(), fromMessageId, 0, limit, false))

    /** Newest-first history page of a Saved Messages topic (album). */
    suspend fun topicHistory(
        topicId: Long,
        fromMessageId: Long,
        limit: Int,
    ): TdApi.Messages = send(TdApi.GetSavedMessagesTopicHistory(topicId, fromMessageId, 0, limit))

    /** Download a file synchronously (priority 16); returns the local path. */
    suspend fun download(fileId: Int): String =
        send(TdApi.DownloadFile(fileId, DOWNLOAD_PRIORITY, 0, 0, true)).local.path

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
