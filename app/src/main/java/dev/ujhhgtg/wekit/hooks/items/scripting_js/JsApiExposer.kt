package dev.ujhhgtg.wekit.hooks.items.scripting_js

import android.os.Handler
import android.os.Looper
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.hooks.api.core.WeApi
import dev.ujhhgtg.wekit.hooks.api.core.WeMessageApi
import dev.ujhhgtg.wekit.hooks.api.net.WePacketHelper
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirectoriesNoThrow
import dev.ujhhgtg.wekit.utils.hookAfterDirectly
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.reflection.asMethod
import dev.ujhhgtg.wekit.utils.reflection.dexKit
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

object JsApiExposer {
    private val TAG = This.Class.simpleName
    private const val TAG_LOG_API = "JsApiExposer.LogApi"
    private const val TAG_HTTP_API = "JsApiExposer.HttpApi"
    private const val TAG_XPOSED_API = "JsApiExposer.XposedApi"
    private const val TAG_REFLECT_API = "JsApiExposer.ReflectApi"
    private const val TAG_DEXKIT_API = "JsApiExposer.DexKitApi"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun exposeApis(scope: ScriptableObject, talker: String? = null) {
        exposeHttpApis(scope)
        exposeLogApis(scope)
        exposeStorageApis(scope)
        exposeDateTimeApis(scope)
        exposeXposedApis(scope)
        exposeReflectApis(scope)
        exposeDexKitApis(scope)
        exposeTaskApis(scope)
        exposeWeChatApis(scope, talker)
    }

    private const val MAX_CACHE_SIZE_IN_MIB = 500

    @OptIn(ExperimentalPathApi::class)
    private fun exposeHttpApis(scope: ScriptableObject) {
        val httpObj = NativeObject()

        // http.get(url, params?, headers?)
        ScriptableObject.putProperty(
            httpObj, "get",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val url = args.getOrNull(0)?.toString() ?: return null
                    val params = args.getOrNull(1) as? NativeObject
                    val headers = args.getOrNull(2) as? NativeObject

                    WeLogger.i(
                        TAG_HTTP_API,
                        "http.get invoked: url=$url params=$params headers=$headers"
                    )

                    return try {
                        httpGet(url, params, headers)
                    } catch (e: Exception) {
                        WeLogger.e(TAG_HTTP_API, "http.get failed: $url", e)
                        createErrorResponse(e)
                    }
                }
            }
        )

        // http.post(url, form_data_body?, json_body?, headers?)
        ScriptableObject.putProperty(
            httpObj, "post",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val url = args.getOrNull(0)?.toString() ?: return null
                    val formData = args.getOrNull(1) as? NativeObject
                    val jsonBody = args.getOrNull(2) as? NativeObject
                    val headers = args.getOrNull(3) as? NativeObject

                    WeLogger.i(
                        TAG_HTTP_API,
                        "http.post invoked: url=$url formData=$formData jsonBody=$jsonBody headers=$headers"
                    )

                    return try {
                        httpPost(url, formData, jsonBody, headers)
                    } catch (e: Exception) {
                        WeLogger.e(TAG_HTTP_API, "http.post failed: $url", e)
                        createErrorResponse(e)
                    }
                }
            }
        )

        // http.download(url, filename?) -> path: String?
        ScriptableObject.putProperty(
            httpObj, "download",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val url = args.getOrNull(0)?.toString() ?: return null
                    var filename = args.getOrNull(1)?.toString()

                    WeLogger.i(TAG_HTTP_API, "http.download invoked: url=$url filename=$filename")

                    if (filename.isNullOrBlank()) {
                        filename = "download_${System.currentTimeMillis()}"
                        WeLogger.i(TAG_HTTP_API, "no filename provided, using default: $filename")
                    }

                    return try {
                        val cacheDir = (KnownPaths.moduleCache / "javascript_http_api").createDirectoriesNoThrow()

                        // drop cache if size too large
                        if (cacheDir.fileSize() / 1024 / 1024 >= MAX_CACHE_SIZE_IN_MIB) {
                            WeLogger.w(
                                TAG,
                                "http.download cache size too large, dropping cache..."
                            )
                            cacheDir.deleteRecursively()
                        }
                        cacheDir.createDirectoriesNoThrow()

                        val destFile = cacheDir.resolve(filename)

                        if (performDownload(url, destFile)) destFile.absolutePathString() else null
                    } catch (e: Exception) {
                        WeLogger.e(TAG_HTTP_API, "http.download failed: $url", e)
                        null
                    }
                }
            }
        )

        ScriptableObject.putProperty(scope, "http", httpObj)
    }

    private fun performDownload(url: String, destFile: Path): Boolean {
        val request = Request.Builder().url(url).build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false

            @Suppress("UNNECESSARY_SAFE_CALL")
            response.body?.byteStream()?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return true
    }

    private fun httpGet(
        urlString: String,
        params: NativeObject?,
        headers: NativeObject?
    ): NativeObject {
        // Build URL with query parameters
        val finalUrl = if (params != null) {
            val httpUrl =
                urlString.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid URL")
            val builder = httpUrl.newBuilder()
            params.keys.forEach { key ->
                val value = params[key]?.toString() ?: ""
                builder.addQueryParameter(key.toString(), value)
            }
            builder.build().toString()
        } else urlString

        val requestBuilder = Request.Builder().url(finalUrl)

        // Add headers
        headers?.let { applyHeaders(requestBuilder, it) }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        return createHttpResponse(response)
    }

    private fun httpPost(
        urlString: String,
        formData: NativeObject?,
        jsonBody: NativeObject?,
        headers: NativeObject?
    ): NativeObject {
        val requestBuilder = Request.Builder().url(urlString)

        // Build request body
        val body = when {
            jsonBody != null -> {
                val json = nativeObjectToJson(jsonBody)
                json.toRequestBody("application/json; charset=utf-8".toMediaType())
            }

            formData != null -> {
                val formBuilder = FormBody.Builder()
                formData.keys.forEach { key ->
                    val value = formData[key]?.toString() ?: ""
                    formBuilder.add(key.toString(), value)
                }
                formBuilder.build()
            }

            else -> {
                "".toRequestBody("text/plain; charset=utf-8".toMediaType())
            }
        }

        requestBuilder.post(body)

        // Add headers
        headers?.let { applyHeaders(requestBuilder, it) }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        return createHttpResponse(response)
    }

    private fun applyHeaders(requestBuilder: Request.Builder, headers: NativeObject) {
        headers.keys.forEach { key ->
            val value = headers[key]?.toString()
            if (value != null) {
                requestBuilder.addHeader(key.toString(), value)
            }
        }
    }

    private fun nativeObjectToJson(obj: NativeObject): String {
        val jsonObject = JSONObject()
        obj.keys.forEach { key ->
            val value = obj[key]
            jsonObject.put(key.toString(), convertJsValue(value))
        }
        return jsonObject.toString()
    }

    private fun convertJsValue(value: Any?): Any? {
        return when (value) {
            is NativeObject -> {
                val json = JSONObject()
                value.keys.forEach { key ->
                    json.put(key.toString(), convertJsValue(value[key]))
                }
                json
            }

            is NativeArray -> {
                val array = org.json.JSONArray()
                for (i in 0 until value.length) {
                    array.put(convertJsValue(value[i]))
                }
                array
            }

            is Number, is String, is Boolean -> value
            null -> JSONObject.NULL
            else -> value.toString()
        }
    }

    private fun createHttpResponse(response: okhttp3.Response): NativeObject {
        val cx = Context.getCurrentContext()!!
        val scope = cx.initStandardObjects()

        val statusCode = response.code
        val body = response.body.string()

        val responseObj = NativeObject()
        responseObj.put("status", responseObj, statusCode)
        responseObj.put("body", responseObj, body)
        responseObj.put("ok", responseObj, response.isSuccessful)

        // Try to parse as JSON if content-type indicates JSON
        val contentType = response.header("Content-Type") ?: ""
        if (contentType.contains("application/json", ignoreCase = true) && body.isNotEmpty()) {
            try {
                val jsonObj = cx.evaluateString(scope, "($body)", "response", 1, null)
                responseObj.put("json", responseObj, jsonObj)
            } catch (e: Exception) {
                // If parsing fails, json will be undefined
                WeLogger.w(TAG, "Failed to parse JSON response body", e)
            }
        }

        // Convert headers to JS object
        val headersObj = NativeObject()
        response.headers.names().forEach { name ->
            headersObj.put(name, headersObj, response.header(name))
        }
        responseObj.put("headers", responseObj, headersObj)

        response.close()
        return responseObj
    }

    private fun createErrorResponse(e: Exception): NativeObject {
        val response = NativeObject()
        response.put("status", response, 0)
        response.put("body", response, "")
        response.put("ok", response, false)
        response.put("error", response, e.message ?: "Unknown error")
        return response
    }

    private fun exposeLogApis(scope: ScriptableObject) {
        val logObj = NativeObject()

        // log.d(msg)
        ScriptableObject.putProperty(
            logObj, "d",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val msg = args.joinToString(" ") { it?.toString() ?: "null" }
                    WeLogger.d(TAG_LOG_API, msg)
                    return Undefined.instance
                }
            }
        )

        // log.i(msg)
        ScriptableObject.putProperty(
            logObj, "i",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val msg = args.joinToString(" ") { it?.toString() ?: "null" }
                    WeLogger.i(TAG_LOG_API, msg)
                    return Undefined.instance
                }
            }
        )

        // log.w(msg)
        ScriptableObject.putProperty(
            logObj, "w",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val msg = args.joinToString(" ") { it?.toString() ?: "null" }
                    WeLogger.w(TAG_LOG_API, msg)
                    return Undefined.instance
                }
            }
        )

        // log.e(msg)
        ScriptableObject.putProperty(
            logObj, "e",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val msg = args.joinToString(" ") { it?.toString() ?: "null" }
                    WeLogger.e(TAG_LOG_API, msg)
                    return Undefined.instance
                }
            }
        )

        ScriptableObject.putProperty(scope, "log", logObj)
    }

    private fun exposeDateTimeApis(scope: ScriptableObject) {
        val dtObj = NativeObject()

        ScriptableObject.putProperty(
            dtObj, "sleepS",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val seconds = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                    if (seconds > 0) {
                        try {
                            Thread.sleep(seconds * 1000)
                        } catch (e: InterruptedException) {
                            WeLogger.w(TAG_LOG_API, "datetime.sleep interrupted", e)
                            Thread.currentThread().interrupt()
                        }
                    }
                    return Undefined.instance
                }
            }
        )

        ScriptableObject.putProperty(
            dtObj, "sleepMs",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val ms = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                    if (ms > 0) {
                        try {
                            Thread.sleep(ms)
                        } catch (e: InterruptedException) {
                            WeLogger.w(TAG_LOG_API, "datetime.sleep interrupted", e)
                            Thread.currentThread().interrupt()
                        }
                    }
                    return Undefined.instance
                }
            }
        )

        ScriptableObject.putProperty(
            dtObj, "getCurrentUnixEpoch",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    return System.currentTimeMillis() / 1000
                }
            }
        )

        ScriptableObject.putProperty(scope, "datetime", dtObj)
    }

    @Suppress("JavaCollectionWithNullableTypeArgument")
    private val storage = ConcurrentHashMap<String, Any?>()

    private val DATA_DIR_PATH by lazy {
        (KnownPaths.moduleData / "data").createDirectoriesNoThrow()
    }

    private val storageFile get() = DATA_DIR_PATH.resolve("javascript_storage_api.json")

    init {
        loadStorageFromDisk()
    }

    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable {
        runCatching {
            storageFile.writeText(DefaultJson.encodeToString(storage.mapValues { it.value }))
        }.onFailure { WeLogger.e(TAG, "failed to save js storage to disk", it) }
    }

    private fun loadStorageFromDisk() {
        runCatching {
            if (!storageFile.exists()) return
            val map = DefaultJson.decodeFromString<Map<String, JsonElement>>(storageFile.readText())
            map.forEach { (k, v) ->
                storage[k] = when (v) {
                    is JsonPrimitive if v.isString -> v.content
                    is JsonPrimitive -> v.jsonPrimitive.contentOrNull
                    else -> v.toString()
                }
            }
        }.onFailure { WeLogger.e(TAG, "failed to load js storage from disk", it) }
    }

    // prevent blocking js execution if the file grows too large, but that would be a misuse of this API anyway
    private fun saveStorageToDisk() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, 500)
    }

    private fun exposeStorageApis(scope: ScriptableObject) {
        val storageObj = NativeObject()

        // storage.get(key) -> object
        ScriptableObject.putProperty(
            storageObj, "get",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val key = args.getOrNull(0)?.toString() ?: return null
                    val value = storage[key]

                    return value ?: Undefined.instance
                }
            }
        )

        // storage.getOrDefault(key, defaultValue) -> object
        ScriptableObject.putProperty(
            storageObj, "getOrDefault",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val key = args.getOrNull(0)?.toString() ?: return args.getOrNull(1)
                    return storage.getOrDefault(key, args.getOrNull(1))
                        ?: Undefined.instance
                }
            }
        )

        // storage.set(key, object)
        ScriptableObject.putProperty(
            storageObj, "set",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val key = args.getOrNull(0)?.toString() ?: return null
                    val value = args.getOrNull(1)

                    if (value is Undefined) {
                        WeLogger.w(
                            TAG,
                            "js tries to set undefined into cache, removing that key instead"
                        )
                        storage.remove(key)
                    } else {
                        storage[key] = value
                    }

                    saveStorageToDisk()
                    return null
                }
            }
        )

        // storage.clear()
        ScriptableObject.putProperty(
            storageObj, "clear",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    storage.clear()
                    saveStorageToDisk()
                    return Undefined.instance
                }
            }
        )

        // storage.remove(key)
        ScriptableObject.putProperty(
            storageObj, "remove",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val key = args.getOrNull(0)?.toString() ?: return Undefined.instance
                    return (storage.remove(key)
                        ?: Undefined.instance).also { saveStorageToDisk() }
                }
            }
        )

        // storage.hasKey(key) -> bool
        ScriptableObject.putProperty(
            storageObj, "hasKey",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val key = args.getOrNull(0)?.toString() ?: return false
                    return storage.containsKey(key)
                }
            }
        )

        // storage.isEmpty() -> bool
        ScriptableObject.putProperty(
            storageObj, "isEmpty",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    return storage.isEmpty()
                }
            }
        )

        // storage.keys() -> Array
        ScriptableObject.putProperty(
            storageObj, "keys",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    // Converts Kotlin Set to a JS Array
                    return cx.newArray(scope, storage.keys.toTypedArray())
                }
            }
        )

        // storage.size() -> int
        ScriptableObject.putProperty(
            storageObj, "size",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    return storage.size
                }
            }
        )

        ScriptableObject.putProperty(scope, "storage", storageObj)
    }

    private fun exposeWeChatApis(scope: ScriptableObject, talker: String? = null) {
        val weObj = NativeObject()

        ScriptableObject.putProperty(
            weObj, "sendText",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val to = args.getOrNull(0)?.toString() ?: return Undefined.instance
                    val text = args.getOrNull(1)?.toString() ?: return Undefined.instance
                    WeMessageApi.sendText(to, text)
                    return Undefined.instance
                }
            }
        )
        ScriptableObject.putProperty(
            weObj, "sendImage",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val to = args.getOrNull(0)?.toString() ?: return Undefined.instance
                    val path = args.getOrNull(1)?.toString() ?: return Undefined.instance
                    WeMessageApi.sendImage(to, path)
                    return Undefined.instance
                }
            }
        )
        ScriptableObject.putProperty(
            weObj, "sendFile",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val to = args.getOrNull(0)?.toString() ?: return Undefined.instance
                    val path = args.getOrNull(1)?.toString() ?: return Undefined.instance
                    val title = args.getOrNull(2)?.toString() ?: path.substringAfterLast('/')
                    WeMessageApi.sendFile(to, path, title)
                    return Undefined.instance
                }
            }
        )
        ScriptableObject.putProperty(
            weObj, "sendVoice",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val to = args.getOrNull(0)?.toString() ?: return Undefined.instance
                    val path = args.getOrNull(1)?.toString() ?: return Undefined.instance
                    val durationMs = (args.getOrNull(2) as? Number)?.toInt() ?: 0
                    WeMessageApi.sendVoice(to, path, durationMs)
                    return Undefined.instance
                }
            }
        )
        ScriptableObject.putProperty(
            weObj, "sendAppMsg",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val to = args.getOrNull(0)?.toString() ?: return Undefined.instance
                    val content = args.getOrNull(1)?.toString() ?: return Undefined.instance
                    WeMessageApi.sendXmlAppMsg(to, content)
                    return Undefined.instance
                }
            }
        )
        ScriptableObject.putProperty(
            weObj, "sendCgi",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val uri = args.getOrNull(0)?.toString() ?: return Undefined.instance
                    val cgiId = (args.getOrNull(1) as? Number)?.toInt() ?: return Undefined.instance
                    val funcId = (args.getOrNull(2) as? Number)?.toInt() ?: return Undefined.instance
                    val routeId = (args.getOrNull(3) as? Number)?.toInt() ?: return Undefined.instance
                    val jsonPayload = args.getOrNull(4)?.toString() ?: return Undefined.instance
                    val onSuccess = args.getOrNull(5) as? org.mozilla.javascript.Function ?: return Undefined.instance
                    val onFailure = args.getOrNull(6) as? org.mozilla.javascript.Function ?: return Undefined.instance

                    WePacketHelper.sendCgi(
                        uri, cgiId, funcId, routeId, jsonPayload
                    ) {
                        onSuccess { json, _ ->
                            onSuccess.call(cx, scope, thisObj, arrayOf(json))
                        }
                        onFailure { _, _, errMsg ->
                            onFailure.call(cx, scope, thisObj, arrayOf(errMsg))
                        }
                    }

                    return Undefined.instance
                }
            }
        )
        if (talker != null) {
            ScriptableObject.putProperty(
                weObj, "replyText",
                object : BaseFunction() {
                    override fun call(
                        cx: Context,
                        scope: Scriptable,
                        thisObj: Scriptable,
                        args: Array<Any?>
                    ): Any? {
                        val text = args.getOrNull(0)?.toString() ?: return Undefined.instance
                        WeMessageApi.sendText(talker, text)
                        return Undefined.instance
                    }
                }
            )
            ScriptableObject.putProperty(
                weObj, "replyImage",
                object : BaseFunction() {
                    override fun call(
                        cx: Context,
                        scope: Scriptable,
                        thisObj: Scriptable,
                        args: Array<Any?>
                    ): Any? {
                        val path = args.getOrNull(0)?.toString() ?: return Undefined.instance
                        WeMessageApi.sendImage(talker, path)
                        return Undefined.instance
                    }
                }
            )
            ScriptableObject.putProperty(
                weObj, "replyFile",
                object : BaseFunction() {
                    override fun call(
                        cx: Context,
                        scope: Scriptable,
                        thisObj: Scriptable,
                        args: Array<Any?>
                    ): Any? {
                        val path = args.getOrNull(0)?.toString() ?: return Undefined.instance
                        val title = args.getOrNull(1)?.toString() ?: path.substringAfterLast('/')
                        WeMessageApi.sendFile(talker, path, title)
                        return Undefined.instance
                    }
                }
            )
            ScriptableObject.putProperty(
                weObj, "replyVoice",
                object : BaseFunction() {
                    override fun call(
                        cx: Context,
                        scope: Scriptable,
                        thisObj: Scriptable,
                        args: Array<Any?>
                    ): Any? {
                        val path = args.getOrNull(0)?.toString() ?: return Undefined.instance
                        val durationMs = (args.getOrNull(1) as? Number)?.toInt() ?: 0
                        WeMessageApi.sendVoice(talker, path, durationMs)
                        return Undefined.instance
                    }
                }
            )
            ScriptableObject.putProperty(
                weObj, "replyAppMsg",
                object : BaseFunction() {
                    override fun call(
                        cx: Context,
                        scope: Scriptable,
                        thisObj: Scriptable,
                        args: Array<Any?>
                    ): Any? {
                        val content = args.getOrNull(0)?.toString() ?: return Undefined.instance
                        WeMessageApi.sendXmlAppMsg(talker, content)
                        return Undefined.instance
                    }
                }
            )
        }
        ScriptableObject.putProperty(weObj, "getSelfWxId", object : BaseFunction() {
            override fun call(
                cx: Context?,
                scope: Scriptable?,
                thisObj: Scriptable?,
                args: Array<Any?>?
            ): Any {
                return WeApi.selfWxId
            }
        })
        ScriptableObject.putProperty(weObj, "getSelfCustomWxId", object : BaseFunction() {
            override fun call(
                cx: Context?,
                scope: Scriptable?,
                thisObj: Scriptable?,
                args: Array<Any?>?
            ): Any {
                return WeApi.selfCustomWxId
            }
        })

        ScriptableObject.putProperty(scope, "wechat", weObj)
    }

    private fun exposeXposedApis(scope: ScriptableObject) {
        val xposedObj = NativeObject()

        ScriptableObject.putProperty(
            xposedObj, "hookBefore",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    // Support two overloads:
                    //   hookBefore(javaMethod: JavaMethod, hookFunc: Function)
                    //   hookBefore(className: string, methodName: string, hookFunc: Function)
                    val first = args.getOrNull(0)
                    if (first is NativeObject) {
                        val methodProp = try {
                            Context.jsToJava(
                                ScriptableObject.getProperty(first, "__method"),
                                Method::class.java
                            ) as? Method
                        } catch (_: Exception) {
                            null
                        }
                        if (methodProp != null) {
                            // JavaMethod overload
                            val hookFunc = args.getOrNull(1) as? org.mozilla.javascript.Function
                                ?: return Undefined.instance
                            try {
                                methodProp.isAccessible = true
                                methodProp.hookBeforeDirectly {
                                    val jsThis = thisObject?.let { Context.javaToJS(it, scope, cx) }
                                    val jsArgs = args.let { Context.javaToJS(it, scope, cx) }
                                        ?: Undefined.instance
                                    val hookResult = hookFunc.call(cx, scope, thisObj, arrayOf(jsThis, jsArgs))
                                    if (hookResult != null && hookResult !is Undefined) {
                                        result = hookResult
                                    }
                                }
                            } catch (e: Exception) {
                                WeLogger.e(TAG_XPOSED_API, "xposed.hookBefore (JavaMethod) failed", e)
                            }
                            return Undefined.instance
                        }
                    }

                    // Original (className, methodName, hookFunc) overload
                    val className = first?.toString() ?: return Undefined.instance
                    val methodName = args.getOrNull(1)?.toString() ?: return Undefined.instance
                    val hookFunc = args.getOrNull(2) as? org.mozilla.javascript.Function ?: return Undefined.instance

                    try {
                        val clazz = className.toClass()
                        val method = clazz.methods.firstOrNull { it.name == methodName }
                        if (method == null) {
                            WeLogger.e(TAG_XPOSED_API, "xposed.hookBefore: no method named $methodName in $className")
                            return Undefined.instance
                        }
                        method.hookBeforeDirectly {
                            val jsThis = thisObject?.let { Context.javaToJS(it, scope, cx) }
                            val jsArgs = args.let { Context.javaToJS(it, scope, cx) }
                                ?: Undefined.instance
                            val hookResult = hookFunc.call(cx, scope, thisObj, arrayOf(jsThis, jsArgs))
                            if (hookResult != null && hookResult !is Undefined) {
                                result = hookResult
                            }
                        }
                    } catch (e: Exception) {
                        WeLogger.e(TAG_XPOSED_API, "xposed.hookBefore failed", e)
                    }
                    return Undefined.instance
                }
            }
        )

        ScriptableObject.putProperty(
            xposedObj, "hookAfter",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    // Support two overloads:
                    //   hookBefore(javaMethod: JavaMethod, hookFunc: Function)
                    //   hookBefore(className: string, methodName: string, hookFunc: Function)
                    val first = args.getOrNull(0)
                    if (first is NativeObject) {
                        val methodProp = try {
                            Context.jsToJava(
                                ScriptableObject.getProperty(first, "__method"),
                                Method::class.java
                            ) as? Method
                        } catch (_: Exception) {
                            null
                        }
                        if (methodProp != null) {
                            // JavaMethod overload
                            val hookFunc = args.getOrNull(1) as? org.mozilla.javascript.Function
                                ?: return Undefined.instance
                            try {
                                methodProp.isAccessible = true
                                methodProp.hookAfterDirectly {
                                    val jsThis = thisObject?.let { Context.javaToJS(it, scope, cx) }
                                    val jsArgs = args.let { Context.javaToJS(it, scope, cx) }
                                        ?: Undefined.instance
                                    val jsResult = result?.let { Context.javaToJS(it, scope, cx) }
                                        ?: Undefined.instance
                                    val hookResult = hookFunc.call(cx, scope, thisObj, arrayOf(jsThis, jsArgs, jsResult))
                                    if (hookResult != null && hookResult !is Undefined) {
                                        result = hookResult
                                    }
                                }
                            } catch (e: Exception) {
                                WeLogger.e(TAG_XPOSED_API, "xposed.hookAfter (JavaMethod) failed", e)
                            }
                            return Undefined.instance
                        }
                    }

                    // Original (className, methodName, hookFunc) overload
                    val className = first?.toString() ?: return Undefined.instance
                    val methodName = args.getOrNull(1)?.toString() ?: return Undefined.instance
                    val hookFunc = args.getOrNull(2) as? org.mozilla.javascript.Function ?: return Undefined.instance

                    try {
                        val clazz = className.toClass()
                        val method = clazz.methods.firstOrNull { it.name == methodName }
                        if (method == null) {
                            WeLogger.e(TAG_XPOSED_API, "xposed.hookAfter: no method named $methodName in $className")
                            return Undefined.instance
                        }
                        method.hookAfterDirectly {
                            val jsThis = thisObject?.let { Context.javaToJS(it, scope, cx) }
                            val jsArgs = args.let { Context.javaToJS(it, scope, cx) }
                                ?: Undefined.instance
                            val jsResult = result?.let { Context.javaToJS(it, scope, cx) }
                                ?: Undefined.instance
                            val hookResult = hookFunc.call(cx, scope, thisObj, arrayOf(jsThis, jsArgs, jsResult))
                            if (hookResult != null && hookResult !is Undefined) {
                                result = hookResult
                            }
                        }
                    } catch (e: Exception) {
                        WeLogger.e(TAG_XPOSED_API, "xposed.hookAfter failed", e)
                    }
                    return Undefined.instance
                }
            }
        )

        ScriptableObject.putProperty(scope, "xposed", xposedObj)
    }

    private fun exposeTaskApis(scope: ScriptableObject) {
        val taskObj = NativeObject()

        ScriptableObject.putProperty(
            taskObj, "run",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val func = args.getOrNull(0) as? org.mozilla.javascript.Function
                        ?: return Undefined.instance

                    thread(name = "JsTaskThread") {
                        val threadCx = Context.enter()
                        try {
                            val threadScope = threadCx.init()
                            func.call(threadCx, threadScope, thisObj, emptyArray())
                        } catch (e: Exception) {
                            WeLogger.e(TAG, "task.run failed", e)
                        } finally {
                            Context.exit()
                        }
                    }

                    return Undefined.instance
                }
            }
        )

        ScriptableObject.putProperty(scope, "task", taskObj)
    }

    // --- Reflection API ---

    private fun getJvmDescriptor(type: Class<*>): String = when {
        type.isPrimitive -> when (type.name) {
            "void" -> "V"
            "int" -> "I"
            "boolean" -> "Z"
            "byte" -> "B"
            "short" -> "S"
            "long" -> "J"
            "float" -> "F"
            "double" -> "D"
            "char" -> "C"
            else -> error("Unknown primitive: ${type.name}")
        }

        type.isArray -> "[" + getJvmDescriptor(type.componentType!!)
        else -> "L${type.name.replace('.', '/')};"
    }

    private fun getMethodDescriptor(method: Method): String {
        val params = method.parameterTypes.joinToString("") { getJvmDescriptor(it) }
        return "($params)${getJvmDescriptor(method.returnType)}"
    }

    private fun getModifierStrings(mods: Int): Array<String> =
        Modifier.toString(mods).split(" ").toTypedArray()

    private fun createJavaFieldObject(
        field: Field,
        className: String,
        cx: Context,
        scope: Scriptable
    ): NativeObject {
        val obj = NativeObject()
        ScriptableObject.putProperty(obj, "name", field.name)
        ScriptableObject.putProperty(obj, "className", className)
        ScriptableObject.putProperty(obj, "type", field.type.name)
        ScriptableObject.putProperty(
            obj, "modifiers",
            cx.newArray(scope, getModifierStrings(field.modifiers))
        )

        ScriptableObject.putProperty(obj, "get", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                return try {
                    val instance = args.getOrNull(0)
                    field.isAccessible = true
                    val value = field.get(if (instance is Undefined) null else instance)
                    Context.javaToJS(value, scope, cx) ?: Undefined.instance
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect field.get failed on $className.${field.name}", e)
                    Undefined.instance
                }
            }
        })

        ScriptableObject.putProperty(obj, "set", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any {
                return try {
                    field.isAccessible = true
                    if (args.size >= 2) {
                        val instance = args[0]
                        val value = Context.jsToJava(args[1], field.type)
                        field.set(if (instance is Undefined) null else instance, value)
                    } else {
                        val value = Context.jsToJava(args.getOrNull(0), field.type)
                        field.set(null, value)
                    }
                    Undefined.instance
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect field.set failed on $className.${field.name}", e)
                    Undefined.instance
                }
            }
        })

        return obj
    }

    private fun createJavaMethodObject(
        method: Method,
        className: String,
        cx: Context,
        scope: Scriptable
    ): NativeObject {
        val obj = NativeObject()
        ScriptableObject.putProperty(obj, "name", method.name)
        ScriptableObject.putProperty(obj, "className", className)
        ScriptableObject.putProperty(obj, "descriptor", getMethodDescriptor(method))
        ScriptableObject.putProperty(
            obj, "paramTypes",
            cx.newArray(scope, method.parameterTypes.map { it.name }.toTypedArray())
        )
        ScriptableObject.putProperty(obj, "returnType", method.returnType.name)
        ScriptableObject.putProperty(
            obj, "modifiers",
            cx.newArray(scope, getModifierStrings(method.modifiers))
        )

        // Store hidden references for the xposed.* overload that accepts JavaMethod
        ScriptableObject.putProperty(obj, "__method", method)
        ScriptableObject.putProperty(obj, "__className", className)

        ScriptableObject.putProperty(obj, "hookBefore", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any {
                val hookFunc = args.getOrNull(0) as? org.mozilla.javascript.Function
                    ?: return Undefined.instance
                try {
                    method.isAccessible = true
                    method.hookBeforeDirectly {
                        val jsThis = thisObject?.let { Context.javaToJS(it, scope, cx) }
                        val jsArgs = args.let { Context.javaToJS(it, scope, cx) }
                            ?: Undefined.instance
                        val hookResult = hookFunc.call(cx, scope, thisObj, arrayOf(jsThis, jsArgs))
                        if (hookResult != null && hookResult !is Undefined) {
                            result = hookResult
                        }
                    }
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect method hookBefore failed on $className.${method.name}", e)
                }
                return Undefined.instance
            }
        })

        ScriptableObject.putProperty(obj, "hookAfter", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any {
                val hookFunc = args.getOrNull(0) as? org.mozilla.javascript.Function
                    ?: return Undefined.instance
                try {
                    method.isAccessible = true
                    method.hookAfterDirectly {
                        val jsThis = thisObject?.let { Context.javaToJS(it, scope, cx) }
                        val jsArgs = args.let { Context.javaToJS(it, scope, cx) }
                            ?: Undefined.instance
                        val jsResult = result?.let { Context.javaToJS(it, scope, cx) }
                            ?: Undefined.instance
                        val hookResult = hookFunc.call(cx, scope, thisObj, arrayOf(jsThis, jsArgs, jsResult))
                        if (hookResult != null && hookResult !is Undefined) {
                            result = hookResult
                        }
                    }
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect method hookAfter failed on $className.${method.name}", e)
                }
                return Undefined.instance
            }
        })

        return obj
    }

    private fun exposeReflectApis(scope: ScriptableObject) {
        val reflectObj = NativeObject()

        ScriptableObject.putProperty(reflectObj, "fields", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                val className = args.getOrNull(0)?.toString() ?: return cx.newArray(scope, emptyArray())
                val condition = args.getOrNull(1) as? org.mozilla.javascript.Function
                    ?: return cx.newArray(scope, emptyArray())
                return try {
                    val clazz = className.toClass()
                    val matches = clazz.declaredFields.filter { field ->
                        val modStrs = getModifierStrings(field.modifiers)
                        val jsModStrs = cx.newArray(scope, modStrs)
                        val condResult = condition.call(
                            cx, scope, thisObj,
                            arrayOf(field.name, field.type.name, jsModStrs)
                        )
                        Context.toBoolean(condResult)
                    }
                    val wrappers = matches.map { createJavaFieldObject(it, className, cx, scope) }
                    cx.newArray(scope, wrappers.toTypedArray())
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect.fields failed for $className", e)
                    cx.newArray(scope, emptyArray())
                }
            }
        })

        ScriptableObject.putProperty(reflectObj, "firstField", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                val className = args.getOrNull(0)?.toString() ?: return Undefined.instance
                val condition = args.getOrNull(1) as? org.mozilla.javascript.Function
                    ?: return Undefined.instance
                return try {
                    val clazz = className.toClass()
                    val match = clazz.declaredFields.firstOrNull { field ->
                        val modStrs = getModifierStrings(field.modifiers)
                        val jsModStrs = cx.newArray(scope, modStrs)
                        val condResult = condition.call(
                            cx, scope, thisObj,
                            arrayOf(field.name, field.type.name, jsModStrs)
                        )
                        Context.toBoolean(condResult)
                    }
                    match?.let { createJavaFieldObject(it, className, cx, scope) }
                        ?: Undefined.instance
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect.firstField failed for $className", e)
                    Undefined.instance
                }
            }
        })

        ScriptableObject.putProperty(reflectObj, "methods", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                val className = args.getOrNull(0)?.toString() ?: return cx.newArray(scope, emptyArray())
                val condition = args.getOrNull(1) as? org.mozilla.javascript.Function
                    ?: return cx.newArray(scope, emptyArray())
                return try {
                    val clazz = className.toClass()
                    val matches = clazz.declaredMethods.filter { method ->
                        val paramTypeNames = method.parameterTypes.map { it.name }.toTypedArray()
                        val jsParamTypes = cx.newArray(scope, paramTypeNames)
                        val modStrs = getModifierStrings(method.modifiers)
                        val jsModStrs = cx.newArray(scope, modStrs)
                        val condResult = condition.call(
                            cx, scope, thisObj,
                            arrayOf(method.name, jsParamTypes, method.returnType.name, jsModStrs)
                        )
                        Context.toBoolean(condResult)
                    }
                    val wrappers = matches.map { createJavaMethodObject(it, className, cx, scope) }
                    cx.newArray(scope, wrappers.toTypedArray())
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect.methods failed for $className", e)
                    cx.newArray(scope, emptyArray())
                }
            }
        })

        ScriptableObject.putProperty(reflectObj, "firstMethod", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                val className = args.getOrNull(0)?.toString() ?: return Undefined.instance
                val condition = args.getOrNull(1) as? org.mozilla.javascript.Function
                    ?: return Undefined.instance
                return try {
                    val clazz = className.toClass()
                    val match = clazz.declaredMethods.firstOrNull { method ->
                        val paramTypeNames = method.parameterTypes.map { it.name }.toTypedArray()
                        val jsParamTypes = cx.newArray(scope, paramTypeNames)
                        val modStrs = getModifierStrings(method.modifiers)
                        val jsModStrs = cx.newArray(scope, modStrs)
                        val condResult = condition.call(
                            cx, scope, thisObj,
                            arrayOf(method.name, jsParamTypes, method.returnType.name, jsModStrs)
                        )
                        Context.toBoolean(condResult)
                    }
                    match?.let { createJavaMethodObject(it, className, cx, scope) }
                        ?: Undefined.instance
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect.firstMethod failed for $className", e)
                    Undefined.instance
                }
            }
        })

        ScriptableObject.putProperty(scope, "reflect", reflectObj)
    }

    // --- DexKit API ---

    private fun getStringProperty(obj: NativeObject, key: String): String? {
        val v = ScriptableObject.getProperty(obj, key)
        return (v as? String)?.takeIf { it.isNotEmpty() }
    }

    private fun getStringArrayProperty(obj: NativeObject, key: String): Array<String>? {
        val v = ScriptableObject.getProperty(obj, key)
        if (v !is NativeArray || v.length.toInt() == 0) return null
        return (0 until v.length.toInt()).map { v[it]?.toString() ?: "" }.toTypedArray()
    }

    private fun getIntProperty(obj: NativeObject, key: String): Int? {
        val v = ScriptableObject.getProperty(obj, key)
        return (v as? Number)?.toInt()
    }

    private fun getNumberArrayProperty(obj: NativeObject, key: String): Array<Number>? {
        val v = ScriptableObject.getProperty(obj, key)
        if (v !is NativeArray || v.length.toInt() == 0) return null
        val nums = (0 until v.length.toInt()).mapNotNull { v[it] as? Number }
        if (nums.isEmpty()) return null
        return nums.toTypedArray()
    }

    private fun createDexMethodsResult(
        methodDataList: List<MethodData>,
        cx: Context,
        scope: Scriptable
    ): NativeObject {
        val result = NativeObject()
        val methods = methodDataList.map { md ->
            val method = md.asMethod
            createJavaMethodObject(method, method.declaringClass.name, cx, scope)
        }
        val jsArray = cx.newArray(scope, methods.toTypedArray())
        ScriptableObject.putProperty(result, "methods", jsArray)

        ScriptableObject.putProperty(result, "single", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                return when (methods.size) {
                    1 -> methods[0]
                    else -> Undefined.instance
                }
            }
        })
        return result
    }

    private fun createDexClassesResult(
        classDataList: List<ClassData>,
        cx: Context,
        scope: Scriptable
    ): NativeObject {
        val result = NativeObject()
        val names = classDataList.map { it.name }.toTypedArray()
        val jsArray = cx.newArray(scope, names)
        ScriptableObject.putProperty(result, "classes", jsArray)

        ScriptableObject.putProperty(result, "single", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                return when (names.size) {
                    1 -> names[0]
                    else -> Undefined.instance
                }
            }
        })
        return result
    }

    private fun exposeDexKitApis(scope: ScriptableObject) {
        val dexkitObj = NativeObject()

        ScriptableObject.putProperty(dexkitObj, "findMethods", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                val searcher = args.getOrNull(0) as? NativeObject
                    ?: return NativeObject()
                return try {
                    val results = dexKit.findMethod {
                        val pkgs = getStringArrayProperty(searcher, "searchPackages")
                        if (pkgs != null) searchPackages(*pkgs)
                        matcher {
                            getStringProperty(searcher, "declaringClass")?.let { declaredClass = it }
                            getStringProperty(searcher, "name")?.let { name = it }
                            getStringProperty(searcher, "returnType")?.let { returnType = it }
                            getIntProperty(searcher, "paramCount")?.let { paramCount = it }
                            getStringArrayProperty(searcher, "paramTypes")?.let { paramTypes(*it) }
                            getStringArrayProperty(searcher, "usingEqStrings")?.let { usingEqStrings(*it) }
                            getNumberArrayProperty(searcher, "usingNumbers")?.let { usingNumbers(*it) }
                        }
                    }
                    createDexMethodsResult(results.toList(), cx, scope)
                } catch (e: Exception) {
                    WeLogger.e(TAG_DEXKIT_API, "dexkit.findMethods failed", e)
                    createDexMethodsResult(emptyList(), cx, scope)
                }
            }
        })

        ScriptableObject.putProperty(dexkitObj, "findClasses", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any {
                val searcher = args.getOrNull(0) as? NativeObject
                    ?: return NativeObject()
                return try {
                    val results = dexKit.findClass {
                        val pkgs = getStringArrayProperty(searcher, "searchPackages")
                        if (pkgs != null) searchPackages(*pkgs)
                        matcher {
                            getStringProperty(searcher, "name")?.let { className = it }
                            getStringProperty(searcher, "superclass")?.let { superClass = it }
                            getStringArrayProperty(searcher, "usingEqStrings")?.let { usingEqStrings(*it) }
                            getStringArrayProperty(searcher, "interfaces")?.forEach { ifaceName ->
                                addInterface { className = ifaceName }
                            }
                        }
                    }
                    createDexClassesResult(results.toList(), cx, scope)
                } catch (e: Exception) {
                    WeLogger.e(TAG_DEXKIT_API, "dexkit.findClasses failed", e)
                    createDexClassesResult(emptyList(), cx, scope)
                }
            }
        })

        ScriptableObject.putProperty(scope, "dexkit", dexkitObj)
    }
}
