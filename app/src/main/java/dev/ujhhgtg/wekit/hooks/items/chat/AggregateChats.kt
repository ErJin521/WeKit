package dev.ujhhgtg.wekit.hooks.items.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.hooks.api.core.models.IWeContact
import dev.ujhhgtg.wekit.hooks.api.ui.WeStartActivityApi
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.items.contacts.CustomLocalFriendAvatars
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier as JavaModifier

@HookItem(name = "对话归拢", categories = ["聊天"], description = "将多个对话归拢在一个文件夹内，设置对话头像时请同时打开自定义好友头像")
object AggregateChats : ClickableHookItem(),
    WeDatabaseListenerApi.IQueryListener,
    WeStartActivityApi.IStartActivityListener,
    IResolveDex {

    private val TAG = This.Class.simpleName
    private const val FOLDER_PREFIX = "wekit_fold_"
    private var folders by prefOption("chat_folders", "[]")
    private const val CONTAINER_ACTIVITY_FALLBACK = "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI"

    private val classMainUi by dexClass()
    private val classContainerActivity by dexClass()
    private val classMmActivity by dexClass()
    private val methodMainUiOnResume by dexMethod()
    private val methodLauncherStartChatting by dexMethod()
    private val methodBaseConversationStartChatting by dexMethod()
    private val methodContainerOnCreate by dexMethod()
    private val methodContainerOnResume by dexMethod()
    private val methodContainerOnDestroy by dexMethod()
    private val methodContainerFinish by dexMethod()
    private val methodSetMmTitle by dexMethod()
    private val methodSetConversationTitle by dexMethod()
    private val methodSqliteWrapperRawQuery by dexMethod()
    private val methodConversationStorageQueryByParent by dexMethod()

    @Volatile
    private var activeFolderId: String? = null

    @Volatile
    private var folderSchemaReady: Boolean? = null

    private val suppressQueryRewrite = ThreadLocal.withInitial { false }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        WeStartActivityApi.addListener(this)
        hookMainUiRefresh()
        hookOpenFolder()
        hookContainerPage()
        hookSqliteWrapperQuery()
        hookConversationStorageParentQuery()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        WeStartActivityApi.removeListener(this)
    }

    override fun onClick(context: Context) {
        showManagerDialog(context)
    }

    override fun onQuery(sql: String): String? {
        if (suppressQueryRewrite.get()!!) return null

        val folderId = activeFolderId
        if (folderId != null) {
            val containerSql = rewriteContainerSql(sql, folderId)
            if (containerSql != sql) return containerSql
        }

        if (!sql.contains(ConversationTable.NAME, ignoreCase = true)) return null
        if (sql.contains(FOLDER_PREFIX, ignoreCase = true)) return null
        if (!looksLikeConversationListQuery(sql)) return null
        return appendParentRefFilter(sql)
    }

    override fun onStartActivity(param: XC_MethodHook.MethodHookParam, intent: Intent) {
        val folderId = readFolderIdFromIntent(intent) ?: return
        val componentName = intent.component?.className
        if (componentName != containerActivityName()) {
            activeFolderId = folderId
            intent.setClassName(param.thisObject as? Context ?: return, containerActivityName())
        }
        applyFolderContainerIntent(intent, folderId)
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        classMainUi.find(dexKit) {
            matcher {
                className = "com.tencent.mm.ui.conversation.MainUI"
            }
        }

        classContainerActivity.find(dexKit) {
            matcher {
                className = CONTAINER_ACTIVITY_FALLBACK
            }
        }

        classMmActivity.find(dexKit) {
            matcher {
                className = "com.tencent.mm.ui.MMActivity"
            }
        }

        methodMainUiOnResume.find(dexKit) {
            matcher {
                declaredClass(classMainUi.clazz)
                name = "onResume"
                paramCount = 0
                returnType(Void.TYPE)
            }
        }

        methodLauncherStartChatting.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass = "com.tencent.mm.ui.LauncherUI"
                name = "startChatting"
                paramTypes("java.lang.String", "android.os.Bundle", "boolean")
                returnType(Void.TYPE)
            }
        }

        methodBaseConversationStartChatting.find(dexKit, allowFailure = true) {
            matcher {
                usingStrings("try startChatting, ishow:%b, post: %b")
                paramTypes("java.lang.String", "android.os.Bundle", "boolean", "boolean")
                returnType("void")
            }
        }

        methodContainerOnCreate.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass(classContainerActivity.clazz)
                name = "onCreate"
                paramTypes("android.os.Bundle")
                returnType(Void.TYPE)
            }
        }

        methodContainerOnResume.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass(classContainerActivity.clazz.superclass!!)
                name = "onResume"
                paramCount = 0
                returnType(Void.TYPE)
            }
        }

        methodContainerOnDestroy.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass(classContainerActivity.clazz.superclass!!)
                name = "onDestroy"
                paramCount = 0
                returnType(Void.TYPE)
            }
        }

        methodContainerFinish.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass(classContainerActivity.clazz)
                name = "finish"
                paramCount = 0
                returnType(Void.TYPE)
            }
        }

        methodSetMmTitle.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass(classMmActivity.clazz)
                name = "setMMTitle"
                paramTypes("java.lang.String")
                returnType(Void.TYPE)
            }
        }

        methodSetConversationTitle.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass(classContainerActivity.clazz.superclass!!)
                name = "setTitle"
                paramTypes("java.lang.String")
                returnType(Void.TYPE)
            }
        }

        methodSqliteWrapperRawQuery.find(dexKit, allowFailure = true) {
            matcher {
                modifiers = JavaModifier.PUBLIC
                usingEqStrings("sql is null ", "DB IS CLOSED ! {%s}")
                paramTypes("java.lang.String", "java.lang.String[]", "int")
                returnType("android.database.Cursor")
            }
        }

        methodConversationStorageQueryByParent.find(dexKit, allowFailure = true) {
            matcher {
                usingStrings(
                    "select * from rconversation where ",
                    " order by flag desc, conversationTime desc"
                )
                paramTypes("int", "java.util.List", "java.lang.String", "int")
                returnType("android.database.Cursor")
            }
        }
    }

    private fun hookMainUiRefresh() {
        methodMainUiOnResume.hookAfter {
            syncFoldersToDatabase()
        }
    }

    private fun hookOpenFolder() {
        if (!methodLauncherStartChatting.isPlaceholder) {
            methodLauncherStartChatting.hookBefore {
                interceptFolderChatOpen(args.firstOrNull() as? String, thisObject) {
                    result = null
                }
            }
        }
        if (methodBaseConversationStartChatting.isPlaceholder) return
        methodBaseConversationStartChatting.hookBefore {
            interceptFolderChatOpen(args.firstOrNull() as? String, thisObject) {
                result = null
            }
        }
    }

    private inline fun interceptFolderChatOpen(
        username: String?,
        source: Any?,
        cancelOriginal: () -> Unit
    ) {
        if (username == null || !isFolderId(username)) return
        activeFolderId = username
        launchFolderContainer(source, username)
        cancelOriginal()
    }

    private fun hookContainerPage() {
        if (!methodContainerOnCreate.isPlaceholder) methodContainerOnCreate.hookBefore {
            val activity = thisObject as? Activity ?: return@hookBefore
            if (!classContainerActivity.clazz.isInstance(activity)) return@hookBefore
            activeFolderId = readFolderIdFromIntent(activity.intent) ?: activeFolderId
        }
        if (!methodContainerOnResume.isPlaceholder) methodContainerOnResume.hookAfter {
            val activity = thisObject as? Activity ?: return@hookAfter
            if (!classContainerActivity.clazz.isInstance(activity)) return@hookAfter
            activeFolderId = activeFolderId ?: readFolderIdFromIntent(activity.intent)
            injectFolderTitle(activity)
        }
        if (!methodContainerOnDestroy.isPlaceholder) methodContainerOnDestroy.hookAfter {
            val activity = thisObject as? Activity ?: return@hookAfter
            if (!classContainerActivity.clazz.isInstance(activity)) return@hookAfter
            activeFolderId = null
        }
        if (!methodContainerFinish.isPlaceholder) methodContainerFinish.hookAfter {
            val activity = thisObject as? Activity ?: return@hookAfter
            if (!classContainerActivity.clazz.isInstance(activity)) return@hookAfter
            activeFolderId = null
        }
    }

    private fun hookSqliteWrapperQuery() {
        if (methodSqliteWrapperRawQuery.isPlaceholder) return
        methodSqliteWrapperRawQuery.hookBefore {
            if (suppressQueryRewrite.get()!!) return@hookBefore
            val sql = args.firstOrNull() as? String ?: return@hookBefore
            onQuery(sql)?.let { args[0] = it }
        }
    }

    private fun hookConversationStorageParentQuery() {
        if (methodConversationStorageQueryByParent.isPlaceholder) return
        methodConversationStorageQueryByParent.hookBefore {
            val folderId = activeFolderId ?: return@hookBefore
            val parentRef = args.getOrNull(2) as? String ?: return@hookBefore
            if (parentRef == WeChatFolderPlaceholder.CONVERSATION_BOX ||
                parentRef == WeChatFolderPlaceholder.MESSAGE_FOLD
            ) {
                args[2] = folderId
            }
        }
    }

    private fun launchFolderContainer(source: Any?, folderId: String) {
        val context = source as? Context ?: return
        val intent = Intent().apply {
            setClassName(context, containerActivityName())
            applyFolderContainerIntent(this, folderId)
        }
        context.startActivity(intent)
    }

    private fun applyFolderContainerIntent(intent: Intent, folderId: String) {
        intent.putExtra(WeChatIntentExtra.CONTACT_USER, folderId)
        intent.putExtra(WeChatIntentExtra.CONTACT_CHAT_ROOM_ID, folderId)
        intent.putExtra(WeChatIntentExtra.ROOM_NAME, folderId)
    }

    private fun injectFolderTitle(activity: Activity) {
        val folder = folderById(activeFolderId ?: return) ?: return
        val titleSet = runCatching {
            if (!methodSetConversationTitle.isPlaceholder) {
                methodSetConversationTitle.method.invoke(activity, folder.name)
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (titleSet) return

        runCatching {
            if (!methodSetMmTitle.isPlaceholder) methodSetMmTitle.method.invoke(activity, folder.name)
        }.onFailure {
            WeLogger.w(TAG, "failed to set folder title for ${folder.id}", it)
        }
    }

    private fun containerActivityName(): String {
        return classContainerActivity.clazz.name
    }

    private fun syncFoldersToDatabase() {
        val folders = loadFolders()
        runCatching {
            withQueryRewriteSuppressed {
                if (!isFolderSchemaReady()) return@withQueryRewriteSuppressed
                clearStaleFolderMappings()
                folders.forEach { syncFolder(it) }
            }
            WeLogger.i(TAG, "synced ${folders.size} folders")
        }.onFailure {
            WeLogger.e(TAG, "failed to sync folders", it)
        }
    }

    private fun clearStaleFolderMappings() {
        listOf(FOLDER_PREFIX).forEach { prefix ->
            WeDatabaseApi.execStatement(
                """
                DELETE FROM ${ConversationTable.NAME}
                WHERE ${ConversationTable.PARENT_REF} LIKE ?
                  AND ${ConversationTable.DIGEST}=''
                  AND ${ConversationTable.CONTENT}=''
                  AND ${ConversationTable.UNREAD_COUNT}=0
                  AND ${ConversationTable.CONVERSATION_TIME}=0
                  AND ${ConversationTable.FLAG}=0
                  AND ${ConversationTable.MSG_TYPE}=''
                  AND ${ConversationTable.STATUS}=0
                  AND ${ConversationTable.IS_SEND}=0
                """.trimIndent(),
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "UPDATE ${ConversationTable.NAME} SET ${ConversationTable.PARENT_REF}='' WHERE ${ConversationTable.PARENT_REF} LIKE ?",
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM ${ConversationTable.NAME} WHERE ${ConversationTable.USERNAME} LIKE ?",
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM ${ContactTable.NAME} WHERE ${ContactTable.USERNAME} LIKE ?",
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM img_flag WHERE username LIKE ?",
                arrayOf("$prefix%")
            )
        }
    }

    private fun syncFolder(folder: ChatFolder) {
        val members = folder.members.filterNot(::isFolderId).distinct()
        if (members.isNotEmpty()) {
            ensureMemberConversationRows(folder.id, members)
            val placeholders = members.joinToString(",") { "?" }
            WeDatabaseApi.execStatement(
                "UPDATE ${ConversationTable.NAME} SET ${ConversationTable.PARENT_REF}=? WHERE ${ConversationTable.USERNAME} IN ($placeholders)",
                arrayOf(folder.id, *members.toTypedArray())
            )
        }

        WeDatabaseApi.execStatement(
            """
            REPLACE INTO ${ContactTable.NAME} (${ContactTable.USERNAME}, ${ContactTable.NICKNAME}, ${ContactTable.TYPE}, ${ContactTable.VERIFY_FLAG})
            VALUES (?, ?, 3, 0)
            """.trimIndent(),
            arrayOf(folder.id, folder.name)
        )

        WeDatabaseApi.execStatement(
            """
            REPLACE INTO img_flag (username, imgflag, lastupdatetime, reserved1, reserved2)
            VALUES (?, 3, ?, 0, ?)
            """.trimIndent(),
            arrayOf(folder.id, System.currentTimeMillis() / 1000, "http://wekit.local/avatar/${folder.id}")
        )

        val summary = readFolderSummary(members)
        WeDatabaseApi.execStatement(
            """
            REPLACE INTO ${ConversationTable.NAME} (
                ${ConversationTable.USERNAME}, ${ConversationTable.DIGEST}, ${ConversationTable.DIGEST_USER}, ${ConversationTable.IS_SEND}, ${ConversationTable.STATUS},
                ${ConversationTable.CONVERSATION_TIME}, ${ConversationTable.FLAG}, ${ConversationTable.UNREAD_COUNT}, ${ConversationTable.CONTENT}, ${ConversationTable.MSG_TYPE}, ${ConversationTable.CHAT_MODE}
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                folder.id,
                summary.digest,
                summary.digestUser,
                summary.isSend,
                summary.status,
                summary.conversationTime,
                summary.flag,
                summary.unreadCount,
                summary.content,
                summary.msgType,
                summary.chatMode
            )
        )
    }

    private fun ensureMemberConversationRows(folderId: String, members: List<String>) {
        members.forEach { member ->
            WeDatabaseApi.execStatement(
                """
                INSERT OR IGNORE INTO ${ConversationTable.NAME} (
                    ${ConversationTable.USERNAME}, ${ConversationTable.PARENT_REF}, ${ConversationTable.DIGEST}, ${ConversationTable.DIGEST_USER}, ${ConversationTable.IS_SEND},
                    ${ConversationTable.STATUS}, ${ConversationTable.CONVERSATION_TIME}, ${ConversationTable.FLAG}, ${ConversationTable.UNREAD_COUNT}, ${ConversationTable.CONTENT},
                    ${ConversationTable.MSG_TYPE}, ${ConversationTable.CHAT_MODE}
                ) VALUES (?, ?, '', '', 0, 0, 0, 0, 0, '', '', 0)
                """.trimIndent(),
                arrayOf(member, folderId)
            )
        }
    }

    private fun readFolderSummary(members: List<String>): FolderSummary {
        if (members.isEmpty()) return FolderSummary()
        val placeholders = members.joinToString(",") { "?" }
        val cursor = WeDatabaseApi.rawQuery(
            """
            SELECT ${ConversationTable.DIGEST}, ${ConversationTable.DIGEST_USER}, ${ConversationTable.IS_SEND}, ${ConversationTable.STATUS}, ${ConversationTable.CONVERSATION_TIME},
                   ${ConversationTable.FLAG}, ${ConversationTable.UNREAD_COUNT}, ${ConversationTable.CONTENT}, ${ConversationTable.MSG_TYPE}, ${ConversationTable.CHAT_MODE}
            FROM ${ConversationTable.NAME}
            WHERE ${ConversationTable.USERNAME} IN ($placeholders)
            ORDER BY ${ConversationTable.CONVERSATION_TIME} DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(*members.toTypedArray())
        )
        val maxFlag = maxLongForMembers(members, ConversationTable.FLAG)
        val fallbackTime = System.currentTimeMillis()
        val latest = cursor.use { cursor ->
            if (!cursor.moveToFirst()) null else FolderSummary(
                digest = cursor.getStringOrEmpty(ConversationTable.DIGEST),
                digestUser = cursor.getStringOrEmpty(ConversationTable.DIGEST_USER),
                isSend = cursor.getIntOrZero(ConversationTable.IS_SEND),
                status = cursor.getIntOrZero(ConversationTable.STATUS),
                conversationTime = cursor.getLongOrZero(ConversationTable.CONVERSATION_TIME).takeIf { it > 0L }
                    ?: fallbackTime,
                flag = maxFlag.coerceAtLeast(cursor.getLongOrZero(ConversationTable.FLAG)).takeIf { it > 0L }
                    ?: fallbackTime,
                unreadCount = unreadCountForMembers(members),
                content = cursor.getStringOrEmpty(ConversationTable.CONTENT),
                msgType = cursor.getStringOrEmpty(ConversationTable.MSG_TYPE),
                chatMode = cursor.getIntOrZero(ConversationTable.CHAT_MODE)
            )
        }
        return latest ?: FolderSummary(
            conversationTime = fallbackTime,
            flag = maxFlag.takeIf { it > 0L } ?: fallbackTime,
            unreadCount = unreadCountForMembers(members)
        )
    }

    private fun maxLongForMembers(members: List<String>, column: String): Long {
        val placeholders = members.joinToString(",") { "?" }
        val cursor = WeDatabaseApi.rawQuery(
            "SELECT MAX($column) FROM ${ConversationTable.NAME} WHERE ${ConversationTable.USERNAME} IN ($placeholders)",
            arrayOf(*members.toTypedArray())
        )
        return cursor.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        }
    }

    private fun unreadCountForMembers(members: List<String>): Int {
        val placeholders = members.joinToString(",") { "?" }
        val cursor = WeDatabaseApi.rawQuery(
            "SELECT SUM(${ConversationTable.UNREAD_COUNT}) FROM ${ConversationTable.NAME} WHERE ${ConversationTable.USERNAME} IN ($placeholders)",
            arrayOf(*members.toTypedArray())
        )
        return cursor.use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun isFolderSchemaReady(): Boolean {
        folderSchemaReady?.let { return it }
        val result = runCatching {
            val conversationColumns = tableColumns(ConversationTable.NAME)
            val contactColumns = tableColumns(ContactTable.NAME)
            val missingConversationColumns = ConversationTable.REQUIRED_COLUMNS - conversationColumns
            val missingContactColumns = ContactTable.REQUIRED_COLUMNS - contactColumns
            if (missingConversationColumns.isNotEmpty() || missingContactColumns.isNotEmpty()) {
                WeLogger.w(
                    TAG,
                    "skip folders sync, schema mismatch: " +
                            "rconversation missing=${missingConversationColumns.joinToString()}, " +
                            "rcontact missing=${missingContactColumns.joinToString()}"
                )
                false
            } else {
                true
            }
        }.onFailure {
            WeLogger.w(TAG, "skip folders sync, failed to inspect WeChat database schema", it)
        }.getOrDefault(false)
        folderSchemaReady = result
        return result
    }

    private fun tableColumns(table: String): Set<String> {
        val columns = linkedSetOf<String>()
        val cursor = WeDatabaseApi.rawQuery("PRAGMA table_info($table)")
        cursor.use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
        }
        return columns
    }

    private fun looksLikeConversationListQuery(sql: String): Boolean {
        val lower = sql.lowercase()
        if (!lower.contains("select")) return false
        if (!lower.contains("from ${ConversationTable.NAME}")) return false
        return lower.contains(ConversationTable.PARENT_REF.lowercase()) ||
                lower.contains(ConversationTable.CONVERSATION_TIME.lowercase()) ||
                lower.contains(ConversationTable.UNREAD_COUNT.lowercase())
    }

    private fun appendParentRefFilter(sql: String): String {
        val insertionPoint = listOf(" order by ", " group by ", " limit ")
            .map { sql.indexOf(it, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull() ?: sql.length
        val head = sql.substring(0, insertionPoint)
        val tail = sql.substring(insertionPoint)
        val condition = listOf(FOLDER_PREFIX)
            .joinToString(" AND ") { "ifnull(${ConversationTable.PARENT_REF},'') NOT LIKE '$it%'" }
        val connector = if (head.contains(" where ", ignoreCase = true)) " AND " else " WHERE "
        return "$head$connector$condition$tail"
    }

    private fun rewriteContainerSql(sql: String, folderId: String): String {
        if (!sql.contains(ConversationTable.NAME, ignoreCase = true) ||
            !sql.contains(ConversationTable.PARENT_REF, ignoreCase = true)
        ) {
            return sql
        }
        if (!sql.contains(WeChatFolderPlaceholder.CONVERSATION_BOX) && !sql.contains(WeChatFolderPlaceholder.MESSAGE_FOLD)) {
            return sql
        }
        return sql
            .replace(WeChatFolderPlaceholder.CONVERSATION_BOX, folderId)
            .replace(WeChatFolderPlaceholder.MESSAGE_FOLD, folderId)
    }

    private fun readFolderIdFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        return WeChatIntentExtra.ALL
            .asSequence()
            .mapNotNull { intent.getStringExtra(it) }
            .firstOrNull(::isFolderId)
    }

    private inline fun <T> withQueryRewriteSuppressed(action: () -> T): T {
        val oldValue = suppressQueryRewrite.get()
        suppressQueryRewrite.set(true)
        return try {
            action()
        } finally {
            suppressQueryRewrite.set(oldValue)
        }
    }

    private fun showManagerDialog(context: Context) {
        showComposeDialog(context) {
            var folders by remember { mutableStateOf(loadFolders()) }

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text("聊天归拢") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (folders.isEmpty()) {
                                item {
                                    Text("暂无文件夹, 点击「新建」来创建一个")
                                }
                            }
                            items(folders, key = { it.id }) { folder ->
                                FolderRow(folder) {
                                    showEditFolderDialog(
                                        context = context,
                                        folder = folder,
                                        onFolderUpdated = { folders = loadFolders() },
                                        onFolderDeleted = { folders = loadFolders() }
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("关闭") }
                    TextButton(onClick = {
                        syncFoldersToDatabase()
                        showToast("已重建文件夹索引")
                    }) { Text("重载") }
                    TextButton(onClick = {
                        showCreateFolderDialog(context) {
                            folders = loadFolders()
                        }
                    }) { Text("新建") }
                },
                confirmButton = {
                    Button(onClick = {
                        saveFolders(folders)
                        syncFoldersToDatabase()
                        showToast(context, "已保存, 重启微信生效")
                        onDismiss()
                    }) { Text("保存") }
                }
            )
        }
    }

    private fun showCreateFolderDialog(context: Context, onFolderCreated: () -> Unit) {
        showComposeDialog(context) {
            FolderEditorDialog(
                title = "新建文件夹",
                folder = null,
                onDismiss = onDismiss,
                onSave = { folder ->
                    val currentFolders = loadFolders()
                    saveFolders(currentFolders + folder)
                    onFolderCreated()
                    onDismiss()
                }
            )
        }
    }

    private fun showEditFolderDialog(
        context: Context,
        folder: ChatFolder,
        onFolderUpdated: () -> Unit,
        onFolderDeleted: () -> Unit
    ) {
        showComposeDialog(context) {
            FolderEditorDialog(
                title = "编辑文件夹",
                folder = folder,
                onDismiss = onDismiss,
                onDelete = {
                    val currentFolders = loadFolders()
                    saveFolders(currentFolders.filterNot { it.id == folder.id })
                    onFolderDeleted()
                    onDismiss()
                },
                onSave = { updatedFolder ->
                    val currentFolders = loadFolders()
                    saveFolders(currentFolders.map { if (it.id == updatedFolder.id) updatedFolder else it })
                    onFolderUpdated()
                    onDismiss()
                }
            )
        }
    }

    @Composable
    private fun FolderRow(folder: ChatFolder, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp)
        ) {
            Text(folder.name)
            Text("${folder.members.size} 个对话")
        }
    }

    @Composable
    private fun FolderEditorDialog(
        title: String,
        folder: ChatFolder?,
        onDismiss: () -> Unit,
        onDelete: (() -> Unit)? = null,
        onSave: (ChatFolder) -> Unit
    ) {
        val folderId = remember(folder) { folder?.id ?: newFolderId() }
        var name by remember(folder) { mutableStateOf(folder?.name ?: "") }
        var members by remember(folder) { mutableStateOf(folder?.members?.toSet().orEmpty()) }
        var selectingMembers by remember { mutableStateOf(false) }

        if (selectingMembers) {
            ContactsSelector(
                title = "选择对话",
                contacts = remember { allSelectableConversations() },
                initialSelectedWxIds = members,
                onDismiss = { selectingMembers = false },
                onConfirm = {
                    members = it
                    selectingMembers = false
                }
            )
            return
        }

        AlertDialogContent(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("文件夹名称") },
                        singleLine = true
                    )
                    Text("已选择 ${members.size} 个对话")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { selectingMembers = true }
                        ) {
                            Text("选择对话")
                        }

                        val hasAvatar = CustomLocalFriendAvatars.avatarMap.containsKey(folderId)
                        if (hasAvatar) {
                            Button(onClick = { CustomLocalFriendAvatars.removeAvatar(folderId) }) {
                                Text("清除头像")
                            }
                        }
                        Button(onClick = {
                            CustomLocalFriendAvatars.selectAvatarImage(HostInfo.application, folderId)
                        }) {
                            Text(if (hasAvatar) "更换头像" else "设置头像")
                        }
                    }
                }
            },
            dismissButton = {
                if (onDelete != null) {
                    TextButton(onDelete) { Text("删除") }
                }
                TextButton(onDismiss) { Text("取消") }
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val next = ChatFolder(
                            id = folderId,
                            name = name.trim(),
                            members = members.toList().sorted()
                        )
                        onSave(next)
                    }
                ) { Text("确定") }
            }
        )
    }

    private fun allSelectableConversations(): List<IWeContact> {
        val friends = runCatching { WeDatabaseApi.getFriends() }
            .onFailure { WeLogger.w(TAG, "failed to load friends", it) }
            .getOrDefault(emptyList())
        val groups = runCatching { WeDatabaseApi.getGroups() }
            .onFailure { WeLogger.w(TAG, "failed to load groups", it) }
            .getOrDefault(emptyList())
        return friends + groups
    }

    private fun loadFolders(): List<ChatFolder> {
        val raw = folders
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val membersArray = obj.optJSONArray("members") ?: JSONArray()
                    add(
                        ChatFolder(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            members = buildList {
                                for (j in 0 until membersArray.length()) {
                                    val member = membersArray.optString(j)
                                    if (member.isNotBlank()) add(member)
                                }
                            }
                        )
                    )
                }
            }.filter { isFolderId(it.id) && it.name.isNotBlank() }
        }.onFailure {
            WeLogger.w(TAG, "failed to decode folders config", it)
        }.getOrDefault(emptyList())
    }

    private fun saveFolders(folders: List<ChatFolder>) {
        val array = JSONArray()
        folders.forEach { folder ->
            array.put(
                JSONObject().apply {
                    put("id", folder.id)
                    put("name", folder.name)
                    put("members", JSONArray(folder.members))
                }
            )
        }
        this.folders = array.toString()
    }

    private fun folderById(folderId: String): ChatFolder? {
        return loadFolders().firstOrNull { it.id == folderId }
    }

    private fun newFolderId(): String = "$FOLDER_PREFIX${System.currentTimeMillis()}"

    private fun isFolderId(value: String): Boolean = value.startsWith(FOLDER_PREFIX)


    private data class ChatFolder(
        val id: String,
        val name: String,
        val members: List<String>
    )

    private data class FolderSummary(
        val digest: String = "",
        val digestUser: String = "",
        val isSend: Int = 0,
        val status: Int = 0,
        val conversationTime: Long = System.currentTimeMillis(),
        val flag: Long = 0L,
        val unreadCount: Int = 0,
        val content: String = "",
        val msgType: String = "",
        val chatMode: Int = 0
    )

    private object ConversationTable {
        const val NAME = "rconversation"
        const val USERNAME = "username"
        const val PARENT_REF = "parentRef"
        const val DIGEST = "digest"
        const val DIGEST_USER = "digestUser"
        const val IS_SEND = "isSend"
        const val STATUS = "status"
        const val CONVERSATION_TIME = "conversationTime"
        const val FLAG = "flag"
        const val UNREAD_COUNT = "unReadCount"
        const val CONTENT = "content"
        const val MSG_TYPE = "msgType"
        const val CHAT_MODE = "chatmode"

        val REQUIRED_COLUMNS = setOf(
            USERNAME,
            PARENT_REF,
            DIGEST,
            DIGEST_USER,
            IS_SEND,
            STATUS,
            CONVERSATION_TIME,
            FLAG,
            UNREAD_COUNT,
            CONTENT,
            MSG_TYPE,
            CHAT_MODE
        )
    }

    private object ContactTable {
        const val NAME = "rcontact"
        const val USERNAME = "username"
        const val NICKNAME = "nickname"
        const val TYPE = "type"
        const val VERIFY_FLAG = "verifyFlag"

        val REQUIRED_COLUMNS = setOf(
            USERNAME,
            NICKNAME,
            TYPE,
            VERIFY_FLAG
        )
    }

    private object WeChatIntentExtra {
        const val CONTACT_USER = "Contact_User"
        const val CONTACT_CHAT_ROOM_ID = "Contact_ChatRoomId"
        const val ROOM_NAME = "room_name"
        const val CHAT_USER = "Chat_User"

        val ALL = listOf(
            CONTACT_USER,
            CONTACT_CHAT_ROOM_ID,
            ROOM_NAME,
            CHAT_USER
        )
    }

    private object WeChatFolderPlaceholder {
        const val CONVERSATION_BOX = "conversationboxservice"
        const val MESSAGE_FOLD = "message_fold"
    }


    private fun android.database.Cursor.getStringOrEmpty(column: String): String {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) ?: "" else ""
    }

    private fun android.database.Cursor.getIntOrZero(column: String): Int {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else 0
    }

    private fun android.database.Cursor.getLongOrZero(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }

}
