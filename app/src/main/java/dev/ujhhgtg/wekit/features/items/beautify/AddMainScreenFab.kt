package dev.ujhhgtg.wekit.features.items.beautify

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlinedfilled.Add
import com.composables.icons.materialsymbols.outlinedfilled.Bookmark
import com.composables.icons.materialsymbols.outlinedfilled.Camera
import com.composables.icons.materialsymbols.outlinedfilled.Cancel
import com.composables.icons.materialsymbols.outlinedfilled.Check_circle
import com.composables.icons.materialsymbols.outlinedfilled.Extension
import com.composables.icons.materialsymbols.outlinedfilled.Favorite
import com.composables.icons.materialsymbols.outlinedfilled.Movie
import com.composables.icons.materialsymbols.outlinedfilled.Qr_code_scanner
import com.composables.icons.materialsymbols.outlinedfilled.Settings
import com.composables.icons.materialsymbols.outlinedfilled.Update
import com.composables.icons.materialsymbols.outlinedfilled.Wallet
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.MainSettingsScreen
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.AppTheme
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.rootView
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.system.exitProcess

@Feature(name = "主屏幕添加 FAB", categories = ["界面美化"], description = "向微信主屏幕添加浮动操作按钮")
object AddMainScreenFab : ClickableFeature() {

    private val TAG = This.Class.simpleName
    private const val KEY_FAB_CONFIG = "fab_button_configs_json"

    @Serializable
    enum class FabType {
        START_ACTIVITY,
        MARK_ALL_READ,
        MODULE_SETTINGS,
        FORCE_STOP
    }

    @Serializable
    data class FabItemConfig(
        val id: String,
        val type: FabType,
        val name: String,
        val iconName: String,
        val targetActivity: String? = null
    )

    // 可选图标池映射
    private val iconPool by lazy {
        mapOf(
            "Qr_code_scanner" to MaterialSymbols.OutlinedFilled.Qr_code_scanner,
            "Camera" to MaterialSymbols.OutlinedFilled.Camera,
            "Wallet" to MaterialSymbols.OutlinedFilled.Wallet,
            "Movie" to MaterialSymbols.OutlinedFilled.Movie,
            "Settings" to MaterialSymbols.OutlinedFilled.Settings,
            "Extension" to MaterialSymbols.OutlinedFilled.Extension,
            "Cancel" to MaterialSymbols.OutlinedFilled.Cancel,
            "Update" to MaterialSymbols.OutlinedFilled.Update,
            "Bookmark" to MaterialSymbols.OutlinedFilled.Bookmark,
            "Favorite" to MaterialSymbols.OutlinedFilled.Favorite,
            "Check_circle" to MaterialSymbols.OutlinedFilled.Check_circle
        )
    }

    // 预设 Activity 映射
    private val presets = mapOf(
        "扫一扫" to "com.tencent.mm.plugin.scanner.ui.BaseScanUI",
        "朋友圈" to "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI",
        "钱包" to "com.tencent.mm.plugin.mall.ui.MallIndexUIv2",
        "视频号" to "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI",
        "设置" to "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI",
        "收藏夹" to "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI"
    )

    // 默认配置列表
    private val defaultList = listOf(
        FabItemConfig("1", FabType.START_ACTIVITY, "扫一扫", "Qr_code_scanner", "com.tencent.mm.plugin.scanner.ui.BaseScanUI"),
        FabItemConfig("2", FabType.START_ACTIVITY, "朋友圈", "Camera", "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI"),
        FabItemConfig("3", FabType.START_ACTIVITY, "钱包", "Wallet", "com.tencent.mm.plugin.mall.ui.MallIndexUIv2"),
        FabItemConfig("4", FabType.START_ACTIVITY, "视频号", "Movie", "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI"),
        FabItemConfig("5", FabType.START_ACTIVITY, "设置", "Settings", "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"),
        FabItemConfig("6", FabType.MODULE_SETTINGS, "模块设置", "Extension"),
        FabItemConfig("7", FabType.FORCE_STOP, "强行停止", "Cancel"),
        FabItemConfig("8", FabType.MARK_ALL_READ, "清空未读", "Check_circle")
    )

    private fun loadConfig(): List<FabItemConfig> {
        val jsonStr = WePrefs.getString(KEY_FAB_CONFIG) ?: return defaultList
        return try {
            Json.decodeFromString<List<FabItemConfig>>(jsonStr)
        } catch (e: Exception) {
            WeLogger.e(TAG, "解析依赖失败，还原默认配置", e)
            defaultList
        }
    }

    private fun saveConfig(list: List<FabItemConfig>) {
        try {
            val jsonStr = Json.encodeToString(list)
            WePrefs.putString(KEY_FAB_CONFIG, jsonStr)
        } catch (e: Exception) {
            WeLogger.e(TAG, "保存配置失败", e)
        }
    }

    private fun startActivityByName(context: Context, className: String) {
        val intent = Intent().apply {
            setClassName(context.packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun onEnable() {
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject.reflekt()
                .firstField {
                    type = "com.tencent.mm.ui.MMFragmentActivity"
                }
                .get()!! as Activity

            // 动态解析已经保存的配置生成菜单项目
            val configList = loadConfig()
            val menuItems = mutableMapOf<String, Pair<ImageVector, () -> Unit>>()

            configList.forEach { item ->
                val icon = iconPool[item.iconName] ?: MaterialSymbols.OutlinedFilled.Add
                val action: () -> Unit = when (item.type) {
                    FabType.START_ACTIVITY -> {
                        { item.targetActivity?.let { startActivityByName(activity, it) } }
                    }
                    FabType.MARK_ALL_READ -> {
                        {
                            WeConversationApi.markAllAsRead()
                            showToast("已将全部未读消息标为已读")
                        }
                    }
                    FabType.MODULE_SETTINGS -> {
                        { MainSettingsScreen().show(activity) }
                    }
                    FabType.FORCE_STOP -> {
                        { exitProcess(0) }
                    }
                }
                menuItems[item.name] = icon to action
            }

            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner
            val root = activity.rootView

            root.addView(
                ComposeView(activity).apply {
                    setLifecycleOwner(lifecycleOwner)

                    setContent {
                        AppTheme {
                            val backgroundColor = if (isSystemInDarkTheme()) Color(0xFF191919) else Color(0xFFF7F7F7)
                            val activeColor = MaterialTheme.colorScheme.primary

                            var expanded by remember { mutableStateOf(false) }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = 60.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        horizontalAlignment = Alignment.End
                                    ) {
                                        menuItems.entries.forEachIndexed { index, (name, pair) ->
                                            val itemDelay = index * 35
                                            val reverseDelay = (menuItems.size - 1 - index) * 35

                                            AnimatedVisibility(
                                                visible = expanded,
                                                enter = fadeIn(
                                                    animationSpec = tween(durationMillis = 160, delayMillis = reverseDelay, easing = EaseOut)
                                                ) + slideInVertically(
                                                    animationSpec = tween(durationMillis = 180, delayMillis = reverseDelay, easing = EaseOutCubic),
                                                    initialOffsetY = { it / 2 }
                                                ),
                                                exit = fadeOut(
                                                    animationSpec = tween(durationMillis = 100, delayMillis = itemDelay, easing = EaseIn)
                                                ) + slideOutVertically(
                                                    animationSpec = tween(durationMillis = 100, delayMillis = itemDelay, easing = EaseInCubic),
                                                    targetOffsetY = { it / 2 }
                                                )
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    Surface(
                                                        shape = MaterialTheme.shapes.large,
                                                        color = backgroundColor,
                                                        tonalElevation = 2.dp,
                                                        shadowElevation = 2.dp
                                                    ) {
                                                        Text(
                                                            text = name,
                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                            color = activeColor,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                    }

                                                    SmallFloatingActionButton(
                                                        onClick = {
                                                            pair.second()
                                                            expanded = false
                                                        },
                                                        containerColor = backgroundColor,
                                                        shape = CircleShape,
                                                        elevation = FloatingActionButtonDefaults.elevation(2.dp)
                                                    ) {
                                                        Icon(pair.first, contentDescription = null, tint = activeColor)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    FloatingActionButton(
                                        onClick = { expanded = !expanded },
                                        containerColor = backgroundColor,
                                        shape = CircleShape
                                    ) {
                                        val rotation by animateFloatAsState(if (expanded) 45f else 0f)
                                        Icon(
                                            MaterialSymbols.OutlinedFilled.Add,
                                            contentDescription = null,
                                            tint = activeColor,
                                            modifier = Modifier.rotate(rotation)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    // 实现配置弹窗后台逻辑
    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var currentItems by remember { mutableStateOf(loadConfig()) }
            var showAddSection by remember { mutableStateOf(false) }

            // 添加新组件的状态收集
            var newType by remember { mutableStateOf(FabType.START_ACTIVITY) }
            var newName by remember { mutableStateOf("") }
            var newActivity by remember { mutableStateOf("") }
            var newIconName by remember { mutableStateOf("Qr_code_scanner") }

            AlertDialogContent(
                title = { Text("FAB 悬浮按钮配置") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        if (showAddSection) {
                            // 新建或添加单项控制逻辑
                            Text("添加新功能按钮", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

                            Text("按钮响应类型:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = newType == FabType.START_ACTIVITY, onClick = { newType = FabType.START_ACTIVITY })
                                Text("自定义启动页 (Activity)", modifier = Modifier.clickable { newType = FabType.START_ACTIVITY })
                            }

                            // 控制代码功能唯一性出现
                            val hasMarkRead = currentItems.any { it.type == FabType.MARK_ALL_READ }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = newType == FabType.MARK_ALL_READ,
                                    onClick = { if (!hasMarkRead) newType = FabType.MARK_ALL_READ },
                                    enabled = !hasMarkRead
                                )
                                Text(
                                    "清空未读",
                                    color = if (hasMarkRead) Color.Gray else Color.Unspecified,
                                    modifier = Modifier.clickable(enabled = !hasMarkRead) { newType = FabType.MARK_ALL_READ })
                            }

                            val hasModule = currentItems.any { it.type == FabType.MODULE_SETTINGS }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = newType == FabType.MODULE_SETTINGS,
                                    onClick = { if (!hasModule) newType = FabType.MODULE_SETTINGS },
                                    enabled = !hasModule
                                )
                                Text(
                                    "模块设置",
                                    color = if (hasModule) Color.Gray else Color.Unspecified,
                                    modifier = Modifier.clickable(enabled = !hasModule) { newType = FabType.MODULE_SETTINGS })
                            }

                            val hasForce = currentItems.any { it.type == FabType.FORCE_STOP }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = newType == FabType.FORCE_STOP,
                                    onClick = { if (!hasForce) newType = FabType.FORCE_STOP },
                                    enabled = !hasForce
                                )
                                Text(
                                    "强行停止",
                                    color = if (hasForce) Color.Gray else Color.Unspecified,
                                    modifier = Modifier.clickable(enabled = !hasForce) { newType = FabType.FORCE_STOP })
                            }

                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                label = { Text("显示按钮文本名称") },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )

                            if (newType == FabType.START_ACTIVITY) {
                                OutlinedTextField(
                                    value = newActivity,
                                    onValueChange = { newActivity = it },
                                    label = { Text("Activity 完整类名") },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )

                                Text(
                                    "系统预设快捷填入 (点击填入):",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                Column {
                                    presets.forEach { (pName, pClass) ->
                                        Text(
                                            text = "• $pName ($pClass)",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    newActivity = pClass
                                                    if (newName.isEmpty()) newName = pName
                                                }
                                                .padding(vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            Text("选择展示图标:", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                                iconPool.keys.forEach { iconName ->
                                    val isSelected = newIconName == iconName
                                    Box(
                                        modifier = Modifier
                                            .padding(4.dp)
                                            .clickable { newIconName = iconName }
                                            .padding(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = iconPool[iconName]!!,
                                            contentDescription = iconName,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                                        )
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                                TextButton({ showAddSection = false }) { Text("返回列表") }
                                TextButton({
                                    if (newName.isNotBlank() && (newType != FabType.START_ACTIVITY || newActivity.isNotBlank())) {
                                        val newItem = FabItemConfig(
                                            id = UUID.randomUUID().toString(),
                                            type = newType,
                                            name = newName,
                                            iconName = newIconName,
                                            targetActivity = if (newType == FabType.START_ACTIVITY) newActivity else null
                                        )
                                        val updated = currentItems + newItem
                                        currentItems = updated
                                        saveConfig(updated)
                                        showAddSection = false
                                    }
                                }) { Text("添加保存") }
                            }
                        } else {
                            // 列表展示页：增删改查及排序
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("定制按钮顺序及内容", fontWeight = FontWeight.SemiBold)
                                TextButton({
                                    showAddSection = true
                                    newName = ""
                                    newActivity = ""
                                    newType = FabType.START_ACTIVITY
                                    newIconName = "Qr_code_scanner"
                                }) { Text("+ 添加按钮") }
                            }

                            // FIX: removed inner verticalScroll + heightIn — the outer DefaultColumn
                            // already scrolls. Nesting two same-direction scroll containers causes
                            // Compose to give the inner one zero/capped height, cutting off the list.
                            Column {
                                if (currentItems.isEmpty()) {
                                    Text("当前没有任何悬浮节点, 请先添加", color = Color.Gray, modifier = Modifier.padding(16.dp))
                                }
                                currentItems.forEachIndexed { index, item ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = iconPool[item.iconName] ?: MaterialSymbols.OutlinedFilled.Add,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 8.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                            Text(
                                                text = when (item.type) {
                                                    FabType.START_ACTIVITY -> item.targetActivity ?: ""
                                                    FabType.MARK_ALL_READ -> "核心功能: 标记全部消息为已读"
                                                    FabType.MODULE_SETTINGS -> "核心功能: 打开模块设置"
                                                    FabType.FORCE_STOP -> "核心功能: 终止微信进程"
                                                },
                                                fontSize = 11.sp,
                                                color = Color.Gray
                                            )
                                        }
                                        // 排序：上移
                                        Text(
                                            text = "↑",
                                            modifier = Modifier
                                                .clickable(enabled = index > 0) {
                                                    val list = currentItems.toMutableList()
                                                    val temp = list[index]
                                                    list[index] = list[index - 1]
                                                    list[index - 1] = temp
                                                    currentItems = list
                                                    saveConfig(list)
                                                }
                                                .padding(8.dp),
                                            color = if (index > 0) MaterialTheme.colorScheme.primary else Color.Gray,
                                            fontWeight = FontWeight.Bold
                                        )
                                        // 排序：下移
                                        Text(
                                            text = "↓",
                                            modifier = Modifier
                                                .clickable(enabled = index < currentItems.size - 1) {
                                                    val list = currentItems.toMutableList()
                                                    val temp = list[index]
                                                    list[index] = list[index + 1]
                                                    list[index + 1] = temp
                                                    currentItems = list
                                                    saveConfig(list)
                                                }
                                                .padding(8.dp),
                                            color = if (index < currentItems.size - 1) MaterialTheme.colorScheme.primary else Color.Gray,
                                            fontWeight = FontWeight.Bold
                                        )
                                        // 删除功能
                                        Text(
                                            text = "删除",
                                            modifier = Modifier
                                                .clickable {
                                                    val list = currentItems.toMutableList()
                                                    list.removeAt(index)
                                                    currentItems = list
                                                    saveConfig(list)
                                                }
                                                .padding(8.dp),
                                            color = Color.Red,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onDismiss) { Text("完成并关闭") }
                }
            )
        }
    }
}
