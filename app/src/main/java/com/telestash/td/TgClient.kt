package com.telestash.td

import android.content.Context
import com.telestash.data.DbKeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

class TdException(val code: Int, override val message: String) : Exception(message) {
    val isFloodWait: Boolean get() = message.startsWith("FLOOD_WAIT_")
    val floodWaitSeconds: Long
        get() = if (isFloodWait) {
            message.removePrefix("FLOOD_WAIT_").substringBefore(' ').toLongOrNull() ?: 0L
        } else 0L
}

/**
 * Coroutines wrapper around TDLib with a re-usable update stream.
 * Single instance per app process; photos are sent as documents (lossless).
 */
class TgClient private constructor(
    private val tdlibDir: String,
    private val apiId: Int,
    private val apiHash: String,
    dbKey: ByteArray,
) {
    companion object {
        @Volatile private var instance: TgClient? = null
        @Volatile var started = false
            private set

        fun getOrCreate(context: Context, apiId: Int, apiHash: String): TgClient =
            instance ?: synchronized(this) {
                if (instance == null) started = false
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
            started = false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _updates = MutableSharedFlow<TdApi.Update>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    private val pending = ConcurrentHashMap<Unit, CompletableDeferred<TdApi.Object>>()

    internal val native: Client = Client.create(
        { update ->
            if (update is TdApi.Update) {
                scope.launch { _updates.emit(update) }
            }
        },
        { /* update handler exception */ },
        { /* default exception */ }
    )

    init {
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
    }

    /** Send SetTdlibParameters; safe to call exactly once per client lifetime. */
    suspend fun start() {
        send(
            TdApi.SetTdlibParameters(
                false,
                tdlibDir,
                "$tdlibDir/files",
                dbEncryptionKey,
                true,   // file db
                true,   // chat info db
                true,   // message db
                false,  // secret chats
                apiId,
                apiHash,
                "en",
                android.os.Build.MODEL,
                android.os.Build.VERSION.RELEASE,
                "0.2.0"
            )
        )
        started = true
    }

    private val dbEncryptionKey: ByteArray = dbKey

    // ---------- auth ----------

    suspend fun setPhone(phone: String) {
        send(
            TdApi.SetAuthenticationPhoneNumber(
                phone,
                TdApi.PhoneNumberAuthenticationSettings(
                    false, false, false, false, false, null, arrayOf()
                )
            )
        )
    }

    suspend fun checkCode(code: String) = send(TdApi.CheckAuthenticationCode(code))
    suspend fun checkPassword(pw: String) = send(TdApi.CheckAuthenticationPassword(pw))
    suspend fun registerUser(firstName: String) = send(TdApi.RegisterUser(firstName, "", false))
    suspend fun logout() {
        runCatching { send(TdApi.LogOut()) }
    }

    // ---------- messaging ----------

    /** Resolve Saved Messages chat: my_id -> createPrivateChat(force). */
    suspend fun savedMessagesChatId(): Long {
        val myId = (send(TdApi.GetOption("my_id")) as TdApi.OptionValueInteger).value
        return send(TdApi.CreatePrivateChat(myId, true)).id
    }

    /** Upload a file losslessly as document to Saved Messages. */
    suspend fun uploadDocument(path: String, fileName: String): TdApi.Message {
        val chatId = savedMessagesChatId()
        return send(
            TdApi.SendMessage(
                chatId,
                null, null, null, null,
                TdApi.InputMessageDocument(
                    TdApi.InputDocument(TdApi.InputFileLocal(path), null, false),
                    TdApi.FormattedText("telestash:$fileName", arrayOf())
                )
            )
        )
    }

    /** Newest-first history page of Saved Messages. */
    suspend fun history(chatId: Long, fromMessageId: Long, limit: Int): TdApi.Messages =
        send(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false))

    /** Download a file synchronously (priority 16); returns local path. */
    suspend fun download(fileId: Int): String =
        send(TdApi.DownloadFile(fileId, 16, 0, 0, true)).local.path

    suspend fun deleteMessages(chatId: Long, messageIds: LongArray) {
        send(TdApi.DeleteMessages(chatId, messageIds, true))
    }

    // ---------- plumbing ----------

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T {
        val d = CompletableDeferred<TdApi.Object>()
        native.send(function) { result ->
            if (result is TdApi.Error) d.completeExceptionally(TdException(result.code, result.message))
            else d.complete(result)
        }
        return d.await() as T
    }
}
