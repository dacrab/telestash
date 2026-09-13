package com.telestash.td

import android.content.Context
import com.telestash.data.DbKeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

class TdException(
    val code: Int,
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
        private const val APP_VERSION = "0.3.0"
        private const val TOPICS_COLLECTION_TIMEOUT_MS = 1_000L
        private const val TOPICS_PAGE_LIMIT = 100
        private const val DOWNLOAD_PRIORITY = 16

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
            replay = 1,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    /** True after SetTdlibParameters succeeded; TDLib is then authorized-restorable. */
    @Volatile var isStarted = false
        private set

    private var cachedChatId = 0L

    internal val native: Client =
        Client.create(
            { update -> if (update is TdApi.Update) scope.launch { _updates.emit(update) } },
            { /* update handler exception */ },
            { /* default exception */ },
        )

    init {
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
    }

    /** Configure TDLib; safe to call exactly once per client instance. */
    suspend fun start() {
        send(tdlibParameters())
        isStarted = true
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
            APP_VERSION,
        )
    }

    // ---------- auth ----------

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

    // ---------- messaging ----------

    /** Saved Messages chat id, resolved once via my_id -> createPrivateChat(force). */
    suspend fun savedMessagesChatId(): Long {
        if (cachedChatId != 0L) return cachedChatId
        val myId = (send(TdApi.GetOption("my_id")) as TdApi.OptionValueInteger).value
        cachedChatId = send(TdApi.CreatePrivateChat(myId, true)).id
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
            collector.launch {
                updates
                    .filterIsInstance<TdApi.UpdateSavedMessagesTopic>()
                    .collect { u -> collected.add(u.topic) }
            }
            // 404 means "all loaded" -> expected terminal state
            runCatching { send(TdApi.LoadSavedMessagesTopics(TOPICS_PAGE_LIMIT)) }
            withTimeoutOrNull(TOPICS_COLLECTION_TIMEOUT_MS) { delay(Long.MAX_VALUE) }
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
        return send(
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

    // ---------- plumbing ----------

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T {
        val d = CompletableDeferred<TdApi.Object>()
        native.send(function) { result ->
            if (result is TdApi.Error) {
                d.completeExceptionally(TdException(result.code, result.message))
            } else {
                d.complete(result)
            }
        }
        return d.await() as T
    }
}
