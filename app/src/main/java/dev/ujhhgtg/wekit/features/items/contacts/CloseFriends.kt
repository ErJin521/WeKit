package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import java.lang.ref.WeakReference

/**
 * 密友设置 —— 「隐藏联系人」的伴生功能, **独立文件、独立开关**, 不改 HideContacts.kt 一行。
 *
 * 上游 [HideContacts] 已经内建完整的"临时显示"状态机: 它的一个 private 标志 `temporarilyShown`
 * 被所有隐藏判定点(会话/通讯录/群聊/搜索/锁屏/摇一摇/voip/朋友圈)读取。本功能只做两件事:
 *   1. **换触发方式**: 长按底部「通讯录」Tab 2 秒 → 反射把 `temporarilyShown` 翻成 true(上游据此放出全部隐藏项);
 *      离开主界面 → 翻回 false 恢复隐藏。(上游原生触发是聊天框打 `#show`/`#hide` 文字命令, 两者共用同一标志、互不冲突。)
 *   2. **主动刷新通讯录列表**, 让长按后立刻可见(上游 `#show` 只翻标志、要等自然重查才刷)。含冷态守卫: 通讯录从未
 *      自然加载过时绝不调 z0()(否则惰性初始化 AddressLiveList 会和首次自然加载相撞、卡"正在加载", 实测根因)。
 *
 * 为什么不直接改 HideContacts.kt: 上游频繁重构该文件, 内联改动每次 sync rebase 必冲突。拆成独立文件后,
 * HideContacts.kt 跟上游走、永不冲突; 本文件上游没有、也永不冲突。反射写 `temporarilyShown` 是唯一耦合点,
 * 万一上游改了字段名, 这里会定位失败并降级为 no-op(记 warn), 不会崩。
 */
@Feature(
    // 名字以「隐藏联系人」开头, 让 FeaturesScanner(按名字 Unicode 码点排序)把本项紧排在「隐藏联系人」正下方。
    // 纯「密友设置」的 密(U+5BC6) < 隐(U+9690) 会被排到中间, 故用前缀钉位。
    name = "隐藏联系人·密友设置",
    categories = ["联系人与群组"],
    description = """长按底部「通讯录」Tab 2 秒, 临时显示被「隐藏联系人」隐藏的好友/群聊/会话; 离开主界面后自动恢复隐藏 (重启微信生效)。
需先在「隐藏联系人」里设置隐藏名单并保持其开启。"""
)
object CloseFriends : ClickableFeature() {

    private val TAG = This.Class.simpleName

    private const val ADDR_FRAGMENT = "com.tencent.mm.ui.contact.address.MvvmAddressUIFragment"

    // 长按"通讯录"触发临时显示所需的按住时长(毫秒)
    private const val LONG_PRESS_REVEAL_MS = 2000L

    // 本功能自己的"当前是否临时显示中"状态(供冷/热态刷新决策)。与 HideContacts.temporarilyShown 同步。
    @Volatile
    private var revealing = false

    // --- 与 HideContacts 的唯一耦合: 反射读写其 private temporarilyShown ---

    // 缓存 HideContacts.temporarilyShown 字段(绑定到 HideContacts 单例)。定位失败 → null → 降级 no-op。
    private val hostRevealField by lazy {
        runCatching { HideContacts.reflekt().firstFieldOrNull { name = "temporarilyShown" } }
            .onFailure { WeLogger.w(TAG, "定位 HideContacts.temporarilyShown 失败", it) }
            .getOrNull()
            .also { if (it == null) WeLogger.w(TAG, "HideContacts.temporarilyShown 缺失, 临时显示将失效(上游可能改了字段名)") }
    }

    private fun setHostReveal(v: Boolean) {
        hostRevealField?.set(v)
    }

    // ============================ 生命周期 ============================

    override fun onEnable() {
        // 长按底部「通讯录」Tab 临时显示; 返回主界面时复位
        LauncherUI::class.reflekt().firstMethod { name = "onResume" }.hookAfter {
            val activity = thisObject as? Activity ?: return@hookAfter
            val decor = activity.window?.decorView ?: return@hookAfter
            decor.post {
                bindContactsTabLongPress(activity, decor)
                // 从聊天页/后台返回主界面: 复位临时显示并重新隐藏(会话即时, 通讯录靠下方 fragment hook)
                if (revealing) reHideAll()
            }
        }

        // 通讯录页(MVVM)显示时再应用显隐: 离屏调 submitRefreshAll 会卡"正在加载", 必须等其 resumed
        runCatching {
            val cl = LauncherUI::class.java.classLoader
            Class.forName(ADDR_FRAGMENT, false, cl).reflekt()
                .firstMethod { name = "onResume"; superclass() }
                .hookAfter {
                    val frag = thisObject
                    // 冷加载(本 fragment 首次显示): 自然查询会按当前 revealing 显隐, 绝不调 z0()(原因见 refreshContactListFor 冷态守卫)
                    val cold = lastAddrFragment?.get() !== frag
                    lastAddrFragment = WeakReference(frag)
                    if (cold) {
                        lastAppliedContactReveal = revealing
                        addrFragmentReady = true // 已自然加载, 之后 warm 调 z0() 才安全
                        return@hookAfter
                    }
                    // 热加载(同一 fragment 再次显示): 状态变了才重查刷出/隐藏(此时 z0() 安全)
                    if (revealing == lastAppliedContactReveal) return@hookAfter
                    applyContactRevealDeferred(frag, cl)
                }
        }.onFailure { WeLogger.w(TAG, "hook MvvmAddressUIFragment.onResume fail", it) }
    }

    override fun onDisable() {
        revealing = false
        setHostReveal(false)
        lastAppliedContactReveal = false
        addrFragmentReady = false
        lastAddrFragment = null
    }

    // ============================ 显隐动作 ============================

    private fun reveal(activity: Activity) {
        revealing = true
        setHostReveal(true)
        val hidden = HideContacts.hiddenContacts.toTypedArray()
        // 首页会话: 取消隐藏(持久可见性开关, 即时生效)
        runCatching { WeConversationApi.setConversationsVisibility(true, hidden) }
            .onFailure { WeLogger.w(TAG, "reveal conversations fail", it) }
        // 通讯录好友(MVVM): 仅当已自然加载过(warm)才重查; 冷态绝不碰 z0(), 靠切过去的自然加载显隐
        applyContactRevealIfActive()
        // 群聊列表在长按当下不可见, 靠自然重查(temporarilyShown=true 时上游 getCount/getView 不再抹位置), 无需主动刷。
    }

    private fun reHideAll() {
        revealing = false
        setHostReveal(false)
        val hidden = HideContacts.hiddenContacts.toTypedArray()
        runCatching { WeConversationApi.setConversationsVisibility(false, hidden) }
            .onFailure { WeLogger.w(TAG, "re-hide conversations fail", it) }
        applyContactRevealIfActive()
    }

    // ============================ 长按手势 ============================

    /**
     * 长按底部"通讯录"Tab 满 LONG_PRESS_REVEAL_MS 毫秒 → 临时显示隐藏的好友/群聊/会话;
     * 离开主界面(返回/切后台再回来)自动恢复隐藏。系统默认长按 ~0.5s 不够, 自定义触摸计时实现。
     */
    private fun bindContactsTabLongPress(activity: Activity, decor: View) {
        val tabText = findViews(decor) {
            it is TextView && it.visibility == View.VISIBLE && it.text?.toString() == "通讯录"
        }.firstOrNull() as? TextView ?: return
        val tabCell = findClickableParent(tabText) ?: tabText
        tabCell.setOnTouchListener(object : View.OnTouchListener {
            private var triggered = false
            private val revealRunnable = Runnable {
                triggered = true
                reveal(activity)
                showToast("已临时显示隐藏好友/群聊/会话")
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        triggered = false
                        v.removeCallbacks(revealRunnable)
                        v.postDelayed(revealRunnable, LONG_PRESS_REVEAL_MS)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (event.x < 0 || event.y < 0 || event.x > v.width || event.y > v.height) {
                            v.removeCallbacks(revealRunnable)
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(revealRunnable)
                        if (triggered) {
                            triggered = false
                            return true // 已触发临时显示: 吞掉点击, 避免又切到通讯录页
                        }
                    }
                }
                return false
            }
        })
    }

    // ============================ 通讯录(MVVM)刷新 + 冷态守卫 ============================

    // 通讯录(MVVM)已应用的显示状态: 与 revealing 不一致时才重查, 避免普通导航也重查
    @Volatile
    private var lastAppliedContactReveal = false

    // 上次见到的通讯录 fragment 实例(其 onResume 触发时记录)
    private var lastAddrFragment: WeakReference<Any>? = null

    // 通讯录是否已"自然加载过一次": 只有 true 时才允许调 z0()/submitRefreshAll。
    // 冷态(从未自然加载)调 z0() 会触发 AddressLiveList 惰性初始化, 与切过去的首次自然加载相撞 → 卡死。
    @Volatile
    private var addrFragmentReady = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 把当前 revealing 应用到通讯录列表。**仅在通讯录已自然加载过(addrFragmentReady)时才执行**——
     * 此时 z0()/submitRefreshAll 安全。冷态绝不调(z0() 惰性初始化会和首次自然加载撞车卡死),
     * 冷态由 MvvmAddressUIFragment.onResume 的自然加载按 revealing 显隐。
     */
    private fun applyContactRevealIfActive() {
        if (!addrFragmentReady) return
        if (revealing == lastAppliedContactReveal) return
        val frag = lastAddrFragment?.get() ?: return
        applyContactRevealDeferred(frag, LauncherUI::class.java.classLoader)
    }

    /** 延帧把当前 revealing 应用到通讯录列表(submitRefreshAll 重查, 上游 preprocessList 据 temporarilyShown 显隐) */
    private fun applyContactRevealDeferred(fragment: Any, cl: ClassLoader?) {
        cl ?: return
        val target = revealing
        mainHandler.postDelayed({
            if (target != revealing) return@postDelayed // 状态又变了, 放弃(末次胜)
            WeLogger.d(TAG, "apply contact reveal=$target: " + refreshContactListFor(fragment, cl))
            lastAppliedContactReveal = target
        }, 300)
    }

    /**
     * 通讯录(MVVM)列表是否已完成首次自然加载。冷态(进程内从没切到过通讯录)时 RecyclerView 适配器还没挂上
     * (adapter=null / itemCount=0); 暖态 itemCount>0。用标准 Android RecyclerView API 判定, 不碰 z0()、
     * 不依赖混淆字段。异常/取不到一律按"未加载"(false), 偏保守地跳过 submitRefreshAll——最多这次没刷出隐藏,
     * 绝不会把列表卡死。
     */
    private fun isContactListLoaded(fragment: Any): Boolean = runCatching {
        val view = fragment.reflekt().firstMethod { name = "getView"; superclass() }.invoke() as? View
            ?: return@runCatching false
        val rv = findViews(view) { it.javaClass.name.contains("Recycler", ignoreCase = true) }.firstOrNull()
            ?: return@runCatching false
        val adapter = rv.reflekt().firstMethod { name = "getAdapter"; superclass() }.invoke()
            ?: return@runCatching false
        val cnt = adapter.reflekt().firstMethod { name = "getItemCount"; superclass() }.invoke() as? Int
            ?: return@runCatching false
        cnt > 0
    }.getOrDefault(false)

    /**
     * 通讯录好友列表(MVVM)全量重查。8.0.74: 通讯录 tab = MvvmAddressUIFragment,
     * 其无参方法(z0)返回 AddressLiveList(继承 MvvmList)。调 MvvmList 静态
     * submitRefreshAll = r(liveList, config=null, flag=1, obj=null) 重跑数据源查询并刷新。
     */
    private fun refreshContactListFor(fragment: Any, cl: ClassLoader): String {
        return try {
            // ⚠ 冷态守卫(必须在 z0() 之前!): z0() 会惰性初始化 AddressLiveList, 冷态下提前调它会与
            // 切到通讯录时的自然首次加载相撞、卡死"正在加载"(实测根因)。冷态直接跳过——切过去的自然
            // 加载在 temporarilyShown=true(上游 preprocessList 不抹)下本就带出隐藏好友。isContactListLoaded
            // 只读 RecyclerView 适配器 itemCount, 不碰 z0()。
            if (!isContactListLoaded(fragment)) return "冷态未加载,跳过(自然加载即含隐藏)"
            val addressLiveListClass =
                Class.forName("com.tencent.mm.ui.contact.address.AddressLiveList", false, cl)
            // liveList: 8.0.74 = fragment.z0(); 回退到按返回类型扫描
            val liveList = runCatching {
                fragment.reflekt().firstMethod { name = "z0"; superclass() }.invoke()
            }.getOrNull() ?: fragment.javaClass.methods.firstOrNull { m ->
                m.parameterCount == 0 && addressLiveListClass.isAssignableFrom(m.returnType)
            }?.also { it.isAccessible = true }?.invoke(fragment) ?: return "LiveList为空"
            val mvvmListClass =
                Class.forName("com.tencent.mm.plugin.mvvmlist.MvvmList", false, cl)
            // submitRefreshAll: 8.0.74 = 静态 r(MvvmList, config, int, Object)
            val candidates = mvvmListClass.declaredMethods.filter { m ->
                java.lang.reflect.Modifier.isStatic(m.modifiers) && m.parameterCount == 4 &&
                        m.parameterTypes[0] == mvvmListClass &&
                        m.parameterTypes[2] == Integer.TYPE &&
                        m.parameterTypes[3] == Any::class.java
            }
            val refreshAll = (candidates.firstOrNull { it.name == "r" } ?: candidates.firstOrNull())
                ?.also { it.isAccessible = true } ?: return "无submitRefreshAll"
            refreshAll.invoke(null, liveList, null, 1, null)
            "已重查(${refreshAll.name})"
        } catch (t: Throwable) {
            WeLogger.w(TAG, "refresh contact list fail", t)
            "异常:${t.message}"
        }
    }

    private fun findViews(root: View, pred: (View) -> Boolean): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (pred(v)) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun findClickableParent(view: View): View? {
        var v: View? = view
        var depth = 0
        while (v != null && depth < 6) {
            if (v.isClickable) return v
            v = v.parent as? View
            depth++
        }
        return null
    }

    // ============================ 配置弹窗(预留后续扩充) ============================

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("隐藏联系人·密友设置") },
                text = {
                    DefaultColumn {
                        ListItem(
                            headlineContent = { Text("长按通讯录临时显示") },
                            supportingContent = { Text("长按底部「通讯录」Tab 2 秒, 临时显示被「隐藏联系人」隐藏的好友/群聊/会话; 离开主界面后自动恢复隐藏。需先在「隐藏联系人」里设置隐藏名单并保持其开启。") }
                        )
                        ListItem(
                            headlineContent = { Text("当前隐藏名单") },
                            supportingContent = { Text("共 ${HideContacts.hiddenContacts.size} 项, 长按时全部临时显示") }
                        )
                    }
                }
            )
        }
    }
}
