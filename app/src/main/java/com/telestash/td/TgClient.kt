package com.telestash.td

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thin coroutines wrapper around TDLib with a re-usable update stream.
 * Photos are sent as documents (lossless) into Saved Messages.
 */
class TgClient private constructor(
    private val filesDir: String,
    private val apiId: Int,
    private val apiHash: String,
) {
    companion object {
        @Volatile private var instance: TgClient? = null

        fun get(context: Context, apiId: Int, apiHash: String): TgClient =
            instance ?: synchronized(this) {
                instance ?: TgClient(
                    context.getDir("tdlib", Context.MODE_PRIVATE).absolutePath,
                    apiId,
                    apiHash
                ).also { instance = it }
            }

        fun close() {
            instance?.let { c ->
                runCatching { c.native.send(TdApi.Close(), null) }
                c.scope.cancel()
            }
            instance = null
        }

        /** Access the running singleton without creating it. */
        fun peek(): TgClient? = instance
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _updates = MutableSharedFlow<TdApi.Update>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<TdApi.Object>>()
    private val reqId = AtomicLong(0)

    internal val native: Client = Client.create(
        { update ->
            if (update is TdApi.Update) {
                scope.launch { _updates.emit(update) }
            }
        },
        { /* update exception */ },
        { /* default exception */ }
    )

    init {
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
    }

    val authState: StateFlow<TdApi.AuthorizationState> = updates
        .filterIsInstance<TdApi.UpdateAuthorizationState>()
        .map { it.authorizationState }
        .stateIn(scope, SharingStarted.Eagerly, TdApi.AuthorizationStateWaitTdlibParameters() as TdApi.AuthorizationState)

    /** Initial auth state before any update arrives (TDLib always emits it on startup). */
    suspend fun currentAuthState(): TdApi.AuthorizationState =
        runCatching { send(TdApi.GetAuthorizationState()) as TdApi.AuthorizationState }.getOrNull()
            ?: authState.first()

    suspend fun start() {
        send(
            TdApi.SetTdlibParameters(
                false,
                filesDir,
                "$filesDir/files",
                ByteArray(0),
                true,
                true,
                true,
                false,
                apiId,
                apiHash,
                "en",
                android.os.Build.MODEL,
                android.os.Build.VERSION.RELEASE,
                "0.1.0"
            )
        )
        send(TdApi.SetDatabaseEncryptionKey(ByteArray(0)))
    }

    suspend fun setPhone(phone: String) {
        send(
            TdApi.SetAuthenticationPhoneNumber(
                phone,
                TdApi.PhoneNumberAuthenticationSettings(
                    false, false, false, false, false,
                    null, arrayOf()
                )
            )
        )
    }

    suspend fun checkCode(code: String) = send(TdApi.CheckAuthenticationCode(code))
    suspend fun checkPassword(pw: String) = send(TdApi.CheckAuthenticationPassword(pw))
    suspend fun registerUser(firstName: String) =
        send(TdApi.RegisterUser(firstName, "", false))

    suspend fun logout() {
        runCatching { send(TdApi.LogOut()) }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T {
        val d = CompletableDeferred<TdApi.Object>()
        native.send(function) { result ->
            if (result is TdApi.Error) d.completeExceptionally(TdException(result.code, result.message))
            else d.complete(result)
        }
        return d.await() as T
    }

    /** Saved Messages chat id — always 0 for the logged-in user. */
    suspend fun savedMessagesChatId(): Long = send(TdApi.GetChat(0)).id

    /** Upload a photo losslessly as a document to Saved Messages. */
    suspend fun uploadPhoto(path: String, fileName: String): TdApi.Message {
        val chatId = savedMessagesChatId()
        return send(
            TdApi.SendMessage(
                chatId,
                null,
                null,
                null,
                null,
                TdApi.InputMessageDocument(
                    TdApi.InputDocument(
                        TdApi.InputFileLocal(path),
                        null,
                        false
                    ),
                    TdApi.FormattedText("telestash:$fileName", arrayOf())
                )
            )
        )
    }

    /** List photos/videos from Saved Messages, newest first. */
    suspend fun listPhotos(limit: Int, fromMessageId: Long = 0): TdApi.Messages =
        send(TdApi.GetChatHistory(0, fromMessageId, 0, limit, false))

    /** Download a file by id; returns local path once complete. */
    suspend fun downloadPhoto(fileId: Int): String {
        val file = send(TdApi.DownloadFile(fileId, 16, 0, 0, true))
        return file.local.path
    }

    suspend fun deleteMessages(chatId: Long, messageIds: LongArray) {
        send(TdApi.DeleteMessages(chatId, messageIds, true))
    }
}

class TdException(val code: Int, override val message: String) : Exception(message) {
    val isFloodWait: Boolean get() = message.startsWith("FLOOD_WAIT_")
    val floodWaitSeconds: Long
        get() = if (isFloodWait) message.removePrefix("FLOOD_WAIT_").substringBefore(' ').toLongOrNull() ?: 0L else 0L
}
