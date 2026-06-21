package dev.ujhhgtg.wekit.hooks.items.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.children
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_box
import com.composables.icons.materialsymbols.outlined.Arrow_downward
import com.composables.icons.materialsymbols.outlined.Arrow_upward
import com.composables.icons.materialsymbols.outlined.Attach_file
import com.composables.icons.materialsymbols.outlined.Attach_money
import com.composables.icons.materialsymbols.outlined.Camera
import com.composables.icons.materialsymbols.outlined.Favorite
import com.composables.icons.materialsymbols.outlined.Format_list_numbered
import com.composables.icons.materialsymbols.outlined.Location_on
import com.composables.icons.materialsymbols.outlined.Mail
import com.composables.icons.materialsymbols.outlined.Mic
import com.composables.icons.materialsymbols.outlined.Music_note
import com.composables.icons.materialsymbols.outlined.Photo_library
import com.composables.icons.materialsymbols.outlined.Redeem
import com.composables.icons.materialsymbols.outlined.Video_chat
import com.composables.icons.materialsymbols.outlined.Voice_chat
import dev.ujhhgtg.reflekt.utils.createInstance
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.AppTheme
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.findViewByChildIndexes
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.iterable
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.now
import dev.ujhhgtg.reflekt.reflekt
import kotlinx.coroutines.flow.MutableStateFlow
import org.luckypray.dexkit.DexKitBridge
import kotlin.time.Duration.Companion.seconds

@SuppressLint("StaticFieldLeak")
@HookItem(name = "聊天工具栏", categories = ["聊天"], description = "在输入框上方添加工具栏 (点击配置)")
object ChatToolbar : ClickableHookItem(), IResolveDex {

    private val TAG = This.Class.simpleName

    private val NAME_TO_ICON_MAP = mapOf(
        "相册" to MaterialSymbols.Outlined.Photo_library,
        "拍摄" to MaterialSymbols.Outlined.Camera,
        "系统拍摄" to MaterialSymbols.Outlined.Camera,
        "视频通话" to MaterialSymbols.Outlined.Video_chat,
        "语音通话" to MaterialSymbols.Outlined.Voice_chat,
        "位置" to MaterialSymbols.Outlined.Location_on,
        "红包" to MaterialSymbols.Outlined.Mail,
        "礼物" to MaterialSymbols.Outlined.Redeem,
        "转账" to MaterialSymbols.Outlined.Attach_money,
        "语音输入" to MaterialSymbols.Outlined.Mic,
        "收藏" to MaterialSymbols.Outlined.Favorite,
        "接龙" to MaterialSymbols.Outlined.Format_list_numbered,
        "文件" to MaterialSymbols.Outlined.Attach_file,
        "名片" to MaterialSymbols.Outlined.Account_box,
        "音乐" to MaterialSymbols.Outlined.Music_note
    )

    private val methodAppPanelInitAppGrid by dexMethod()
    private val methodAppPanelOnMeasure by dexMethod()

    private var lastToolListUpdateTime = now()

    private lateinit var appPanel: LinearLayout

    data class MenuItem(
        val name: String,
        val onClickListener: AdapterView.OnItemClickListener,
        val onLongClickListener: AdapterView.OnItemLongClickListener,
        val gridView: GridView,
        val itemView: View,
        val indexInGrid: Int
    )

    private val toolsState = MutableStateFlow<List<Pair<String, MenuItem>>>(emptyList())

    private var itemsOrder by WePrefs.prefOption("chat_toolbar_order", NAME_TO_ICON_MAP.keys.joinToString(","))
    private var enabledItems by WePrefs.prefOption("chat_toolbar_enabled_items", NAME_TO_ICON_MAP.keys)

    override fun onEnable() {
        methodAppPanelInitAppGrid.apply {
            hookBefore {
                appPanel = args[0] as LinearLayout

                val measurer = methodAppPanelOnMeasure.method.declaringClass
                    .createInstance(appPanel)
                // onMeasure() would be called again with actual width & height anyways,
                // so as long as those numbers aren't too small, it's probably fine
                // currently no unwanted side effects are observed, except that the pager's
                // indicator disappears
                methodAppPanelOnMeasure.method.invoke(measurer, 1440, 1200)
            }

            hookAfter {
                val now = now()
                if (now - lastToolListUpdateTime < 2.seconds) return@hookAfter

                val tools = mutableListOf<Pair<String, MenuItem>>()

                val appPanel = args[0] as LinearLayout
                val grids = appPanel.findViewByChildIndexes<ViewGroup>(0, 0, 0)!!
                    .children.map { view -> view as GridView }
                grids.forEach { grid ->
                    val onClickListener = grid.reflekt()
                        .firstField {
                            type = AdapterView.OnItemClickListener::class
                        }.get()!! as AdapterView.OnItemClickListener
                    val onLongClickListener = grid.reflekt()
                        .firstField {
                            type = AdapterView.OnItemLongClickListener::class
                        }.get()!! as AdapterView.OnItemLongClickListener
                    val listAdapter = grid.adapter
                    listAdapter.iterable(grid).forEachIndexed { index, itemView ->
                        val name = (itemView.tag.reflekt()
                            .firstField { type = TextView::class }
                            .get()!! as TextView).text.toString()
                        tools.add(
                            name to MenuItem(
                                name,
                                onClickListener,
                                onLongClickListener,
                                grid,
                                itemView,
                                index
                            )
                        )
                    }
                }

                WeLogger.d(TAG, "populated tool list with ${tools.size} items")
                toolsState.value = tools

                // rate limit this since this method is called REALLY frequently
                lastToolListUpdateTime = now()
            }
        }

        ChatFooter::class.reflekt()
            .firstConstructor {
                parameters(Context::class, AttributeSet::class, Int::class)
            }
            .hookAfter {
                val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner

                val chatFooter = thisObject as FrameLayout
                chatFooter.setLifecycleOwner(lifecycleOwner)
                val linearLayout = chatFooter.findViewByChildIndexes<LinearLayout>(0, 1)!!
                linearLayout.setLifecycleOwner(lifecycleOwner)
                if (linearLayout.findViewWhich<View> { it is ComposeView } != null) return@hookAfter

                val activity = chatFooter.context as Activity
                activity.window.decorView.setLifecycleOwner(lifecycleOwner)

                linearLayout.addView(ComposeView(activity).apply {
                    setLifecycleOwner(lifecycleOwner)

                    setContent {
                        AppTheme {
                            val tools by toolsState.collectAsStateWithLifecycle()

                            val allItems = remember(tools) {
                                val list = mutableListOf<Pair<String, () -> Unit>>()
                                if (tools.isNotEmpty()) {
                                    val firstTool = tools[0].second
                                    list.add("相册" to {
                                        firstTool.onClickListener.onItemClick(
                                            firstTool.gridView,
                                            firstTool.itemView,
                                            0,
                                            0
                                        )
                                    })
                                    list.add("系统拍摄" to {
                                        firstTool.onLongClickListener.onItemLongClick(
                                            null,
                                            null,
                                            0,
                                            0
                                        )
                                    })

                                    tools.forEach { (name, menuItem) ->
                                        if (name in NAME_TO_ICON_MAP && name != "相册" && name != "系统拍摄") {
                                            list.add(name to {
                                                menuItem.onClickListener.onItemClick(
                                                    menuItem.gridView,
                                                    menuItem.itemView,
                                                    menuItem.indexInGrid + 1,
                                                    0
                                                )
                                            })
                                        }
                                    }
                                }
                                list
                            }

                            val order = itemsOrder.split(",").filter { it.isNotEmpty() }
                            val enabled = enabledItems

                            val sortedVisibleItems = remember(allItems, itemsOrder, enabledItems) {
                                allItems.filter { it.first in enabled }
                                    .sortedBy { item ->
                                        val idx = order.indexOf(item.first)
                                        if (idx == -1) Int.MAX_VALUE else idx
                                    }
                            }

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                items(sortedVisibleItems, key = { it.first }) { (name, onClick) ->
                                    val icon = NAME_TO_ICON_MAP[name]!!
                                    FeatureChip(name, icon, onClick)
                                }
                            }
                        }
                    }
                }, 0)
            }
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            val currentOrder = remember {
                val order = itemsOrder.split(",").filter { it.isNotEmpty() }.toMutableStateList()
                NAME_TO_ICON_MAP.keys.forEach { if (it !in order) order.add(it) }
                order
            }
            val currentEnabled = remember { enabledItems.toMutableStateList() }

            AlertDialogContent(
                title = { Text("聊天工具栏配置") },
                text = {
                    LazyColumn(modifier = Modifier.size(height = 400.dp, width = 300.dp)) {
                        itemsIndexed(currentOrder) { index, name ->
                            ListItem(
                                headlineContent = { Text(name) },
                                leadingContent = {
                                    NAME_TO_ICON_MAP[name]?.let { icon ->
                                        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                                    }
                                },
                                trailingContent = {
                                    Row {
                                        IconButton(onClick = {
                                            if (index > 0) {
                                                val temp = currentOrder[index]
                                                currentOrder[index] = currentOrder[index - 1]
                                                currentOrder[index - 1] = temp
                                            }
                                        }, enabled = index > 0) {
                                            Icon(MaterialSymbols.Outlined.Arrow_upward, contentDescription = "Up")
                                        }
                                        IconButton(onClick = {
                                            if (index < currentOrder.size - 1) {
                                                val temp = currentOrder[index]
                                                currentOrder[index] = currentOrder[index + 1]
                                                currentOrder[index + 1] = temp
                                            }
                                        }, enabled = index < currentOrder.size - 1) {
                                            Icon(MaterialSymbols.Outlined.Arrow_downward, contentDescription = "Down")
                                        }
                                        Switch(
                                            checked = name in currentEnabled,
                                            onCheckedChange = { checked ->
                                                if (checked) currentEnabled.add(name) else currentEnabled.remove(name)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        itemsOrder = currentOrder.joinToString(",")
                        enabledItems = currentEnabled.toSet()
                        onDismiss()
                    }) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            )
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodAppPanelInitAppGrid.find(dexKit) {
            matcher {
                declaredClass = "com.tencent.mm.pluginsdk.ui.chat.AppPanel"
                usingEqStrings("MicroMsg.AppPanel", "initAppGrid()")
            }
        }

        methodAppPanelOnMeasure.find(dexKit) {
            searchPackages("com.tencent.mm.pluginsdk.ui.chat")
            matcher {
                usingEqStrings(
                    "MicroMsg.AppPanel",
                    "onMeasure width: %d, heigth:%d, isMeasured:%b, gridWidth:%d, gridHeight:%d"
                )
            }
        }
    }
}

@Composable
private fun FeatureChip(text: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text) },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                Modifier.size(AssistChipDefaults.IconSize)
            )
        }
    )
}
