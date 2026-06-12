package dev.ujhhgtg.wekit.hooks.items.chat

import android.annotation.SuppressLint
import android.content.ContentValues
import android.view.View
import com.highcapable.kavaref.condition.type.Modifiers
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeApi
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.hooks.api.core.WeMessageApi
import dev.ujhhgtg.wekit.hooks.api.core.models.MessageType
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.asResolver

@HookItem(name = "退群监控", categories = ["联系人与群组"], description = "监控群成员退出并在退出时插入系统消息作为提示")
object MonitorGroupMemberLeave : SwitchHookItem(), IResolvesDex, WeDatabaseListenerApi.IUpdateListener {

    private val TAG = This.Class.simpleName

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        methodHandleSpanClick.hookBefore {
            val url = args[1].asResolver().firstField {
                type = BString
                modifiers { it.contains(Modifiers.FINAL) }
            }.get()!! as String
            if (!url.startsWith("weixin://weixinhongbao/wekit/chatroom_userinfo/")) return@hookBefore

            val wxId = url.substringAfterLast('/')
            val context = (args[0] as View).context

            WeApi.openContact(context, wxId, WeApi.OpenContactDestination.HOMEPAGE)
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    private val methodHandleSpanClick by dexMethod {
        matcher {
            declaredClass = $$"com.tencent.mm.app.plugin.URISpanHandlerSet$LuckyMoneyUriSpanHandler"
            usingEqStrings("MicroMsg.URISpanHandlerSet", "LuckyMoneyUriSpanHandler handleSpanClick() clickCallback == null")
        }
    }

    @SuppressLint("Range")
    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        if (table != "chatroom") return

        val group = values.getAsString("chatroomname")
        val memberCount = values.getAsInteger("memberCount")
        val rawMembers = values.getAsString("memberlist") ?: return

        val members = rawMembers.split(';')
        val cursor = WeDatabaseApi.rawQuery("SELECT memberlist, memberCount FROM chatroom WHERE chatroomname = ?",
            arrayOf(group))

        runCatching {
            cursor.use { cursor ->
                if (cursor.moveToFirst()) {
                    val origMemberCount = cursor.getInt(cursor.getColumnIndex("memberCount"))
                    if (origMemberCount == 0) return

                    val origRawMembers = cursor.getString(cursor.getColumnIndex("memberlist"))
                    if (origRawMembers.isNullOrEmpty()) return

                    val origMembers = origRawMembers.split(';')
                    if (memberCount >= origMemberCount) return

                    for (member in origMembers - members.toSet()) {
                        val memberDisplayName = WeDatabaseApi.getFriend(member)?.displayName ?: member

                        val href = "weixin://weixinhongbao/wekit/chatroom_userinfo/$member"
                        val content = """<_wc_custom_link_ color="#28C445" href="$href">$memberDisplayName</_wc_custom_link_> 退出了群组"""

                        WeMessageApi.createSimpleMsgInfoAndInsert(
                            type = MessageType.SYSTEM.code,
                            talker = group,
                            content = content,
                            currentTime = System.currentTimeMillis()
                        )
                    }
                }
            }
        }
    }
}
