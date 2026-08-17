/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed. If not, see <https://www.gnu.org/licenses/>.
 */

package org.lsposed.manager.ui.compose

import android.content.res.ColorStateList
import android.graphics.Color as AndroidColor
import android.os.Looper
import androidx.annotation.IdRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavOptions
import org.lsposed.manager.R
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text

/**
 * Small Java-facing bridge between the existing Fragment NavController and the
 * Compose MIUIX navigation surface.
 *
 * The controller deliberately owns only navigation UI state. Fragment content
 * continues to be hosted by the existing NavHostFragment.
 */
class MiuixNavigationController(
    private val composeView: ComposeView,
    private val navController: NavController,
    private val useNavigationRail: Boolean,
) {
    private val selectedDestination: MutableIntState = mutableIntStateOf(
        topLevelDestination(navController.currentDestination) ?: R.id.main_fragment,
    )
    private val moduleCount: MutableIntState = mutableIntStateOf(0)
    private val repoUpdateCount: MutableIntState = mutableIntStateOf(0)
    private val frameworkUpdateAvailable: MutableState<Boolean> = mutableStateOf(false)
    private val binderAlive: MutableState<Boolean> = mutableStateOf(true)
    private val magiskInstalled: MutableState<Boolean> = mutableStateOf(false)

    private val surfaceColor = composeView.context.themeColor(
        com.google.android.material.R.attr.colorSurface,
        AndroidColor.WHITE,
    )
    private val contentColor = composeView.context.themeColor(
        com.google.android.material.R.attr.colorOnSurface,
        AndroidColor.BLACK,
    )
    private val secondaryContentColor = composeView.context.themeColor(
        com.google.android.material.R.attr.colorOnSurfaceVariant,
        contentColor,
    )
    private val primaryColor = composeView.context.themeColor(
        com.google.android.material.R.attr.colorPrimary,
        0xff3482ff.toInt(),
    )
    private val inactiveColor = ContextCompat.getColor(
        composeView.context,
        R.color.lsposed_miuix_navigation_inactive,
    )
    private val dividerColor = ContextCompat.getColor(
        composeView.context,
        R.color.lsposed_miuix_divider,
    )

    private val destinationListener =
        NavController.OnDestinationChangedListener { _, destination, _ ->
            topLevelDestination(destination)?.let { selectedDestination.intValue = it }
        }

    init {
        navController.addOnDestinationChangedListener(destinationListener)
        composeView.setContent {
            MiuixTheme {
                NavigationSurface(
                    useNavigationRail = useNavigationRail,
                    selectedId = selectedDestination.intValue,
                    binderAlive = binderAlive.value,
                    magiskInstalled = magiskInstalled.value,
                    moduleCount = moduleCount.intValue,
                    repoUpdateCount = repoUpdateCount.intValue,
                    frameworkUpdateAvailable = frameworkUpdateAvailable.value,
                    onDestinationSelected = ::selectDestination,
                    colors = NavigationColors(
                        surface = Color(surfaceColor),
                        content = Color(contentColor),
                        secondaryContent = Color(secondaryContentColor),
                        primary = Color(primaryColor),
                        inactive = Color(inactiveColor),
                        divider = Color(dividerColor),
                    ),
                )
            }
        }
    }

    /** Selects a top-level destination and mirrors NavigationUI's state-saving behaviour. */
    fun selectDestination(@IdRes id: Int) {
        onMainThread {
            if (!isDestinationAvailable(id)) return@onMainThread

            val options = NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setRestoreState(true)
                .setPopUpTo(findStartDestinationId(navController.graph), false, true)
                .build()

            // All IDs come from the root graph/menu. Let Navigation report programming
            // errors instead of silently desynchronising the visible selection.
            navController.navigate(id, null, options)
        }
    }

    fun setModuleCount(count: Int) = update(moduleCount, count.coerceAtLeast(0))

    fun setRepoUpdateCount(count: Int) = update(repoUpdateCount, count.coerceAtLeast(0))

    fun setFrameworkUpdateAvailable(available: Boolean) = update(frameworkUpdateAvailable, available)

    /**
     * Matches the legacy manager behaviour: without a live binder Modules and Logs
     * disappear, while Repo remains available only when Magisk is installed.
     */
    fun setAvailability(binderAlive: Boolean, magiskInstalled: Boolean) {
        onMainThread {
            this.binderAlive.value = binderAlive
            this.magiskInstalled.value = magiskInstalled

            if (!isDestinationAvailable(selectedDestination.intValue)) {
                selectDestination(R.id.main_fragment)
            }
        }
    }

    /** Must be called from Activity.onDestroy(). */
    fun dispose() {
        navController.removeOnDestinationChangedListener(destinationListener)
        composeView.disposeComposition()
    }

    private fun isDestinationAvailable(@IdRes id: Int): Boolean = when (id) {
        R.id.modules_nav, R.id.logs_fragment -> binderAlive.value
        R.id.repo_nav -> binderAlive.value || magiskInstalled.value
        R.id.main_fragment, R.id.settings_fragment -> true
        else -> false
    }

    private fun onMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else composeView.post { action() }
    }

    private fun update(state: MutableIntState, value: Int) = onMainThread { state.intValue = value }

    private fun <T> update(state: MutableState<T>, value: T) = onMainThread { state.value = value }

    companion object {
        private val topLevelIds = intArrayOf(
            R.id.repo_nav,
            R.id.modules_nav,
            R.id.main_fragment,
            R.id.settings_fragment,
        )

        private fun topLevelDestination(destination: NavDestination?): Int? {
            var current = destination
            while (current != null) {
                if (topLevelIds.contains(current.id)) return current.id
                current = current.parent
            }
            return null
        }

        private fun findStartDestinationId(graph: NavGraph): Int {
            var destination: NavDestination = graph
            while (destination is NavGraph) {
                val currentGraph = destination
                destination = currentGraph.findNode(currentGraph.startDestinationId)
                    ?: return currentGraph.id
            }
            return destination.id
        }
    }
}

private data class NavigationColors(
    val surface: Color,
    val content: Color,
    val secondaryContent: Color,
    val primary: Color,
    val inactive: Color,
    val divider: Color,
)

private data class NavigationItem(
    @param:IdRes val id: Int,
    val label: String,
    val selectedIcon: Int,
    val unselectedIcon: Int,
    val badgeText: String? = null,
    val showDot: Boolean = false,
)

@Composable
private fun NavigationSurface(
    useNavigationRail: Boolean,
    selectedId: Int,
    binderAlive: Boolean,
    magiskInstalled: Boolean,
    moduleCount: Int,
    repoUpdateCount: Int,
    frameworkUpdateAvailable: Boolean,
    onDestinationSelected: (Int) -> Unit,
    colors: NavigationColors,
) {
    val items = buildList {
        add(
            NavigationItem(
                id = R.id.main_fragment,
                label = stringResource(R.string.overview),
                selectedIcon = R.drawable.ic_baseline_home_24,
                unselectedIcon = R.drawable.ic_outline_home_24,
                showDot = frameworkUpdateAvailable,
            ),
        )
        if (binderAlive) {
            add(
                NavigationItem(
                    id = R.id.modules_nav,
                    label = stringResource(R.string.Modules),
                    selectedIcon = R.drawable.ic_baseline_extension_24,
                    unselectedIcon = R.drawable.ic_outline_extension_24,
                    badgeText = moduleCount.badgeText(),
                ),
            )
        }
        if (binderAlive || magiskInstalled) {
            add(
                NavigationItem(
                    id = R.id.repo_nav,
                    label = stringResource(R.string.module_repo),
                    selectedIcon = R.drawable.ic_baseline_get_app_24,
                    unselectedIcon = R.drawable.ic_outline_get_app_24,
                    badgeText = repoUpdateCount.badgeText(),
                ),
            )
        }
        add(
            NavigationItem(
                id = R.id.settings_fragment,
                label = stringResource(R.string.Settings),
                selectedIcon = R.drawable.ic_baseline_settings_24,
                unselectedIcon = R.drawable.ic_outline_settings_24,
            ),
        )
    }

    if (useNavigationRail) {
        MiuixNavigationRail(items, selectedId, onDestinationSelected, colors)
    } else {
        MiuixBottomNavigation(items, selectedId, onDestinationSelected, colors)
    }
}

@Composable
private fun MiuixBottomNavigation(
    items: List<NavigationItem>,
    selectedId: Int,
    onDestinationSelected: (Int) -> Unit,
    colors: NavigationColors,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                NavigationItemView(
                    item = item,
                    selected = item.id == selectedId,
                    colors = colors,
                    onClick = { onDestinationSelected(item.id) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MiuixNavigationRail(
    items: List<NavigationItem>,
    selectedId: Int,
    onDestinationSelected: (Int) -> Unit,
    colors: NavigationColors,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .navigationBarsPadding()
            .padding(horizontal = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        items.forEach { item ->
            NavigationItemView(
                item = item,
                selected = item.id == selectedId,
                colors = colors,
                onClick = { onDestinationSelected(item.id) },
                modifier = Modifier
                    .width(76.dp)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun NavigationItemView(
    item: NavigationItem,
    selected: Boolean,
    colors: NavigationColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val foreground = if (selected) colors.content else colors.inactive
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(if (selected) item.selectedIcon else item.unselectedIcon),
                contentDescription = item.label,
                modifier = Modifier.size(26.dp),
                colorFilter = ColorFilter.tint(foreground),
            )
            NavigationBadge(
                text = item.badgeText,
                showDot = item.showDot,
                colors = colors,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 1.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.label,
            modifier = Modifier.fillMaxWidth(),
            color = if (selected) colors.content else colors.inactive,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NavigationBadge(
    text: String?,
    showDot: Boolean,
    colors: NavigationColors,
    modifier: Modifier = Modifier,
) {
    when {
        text != null -> Box(
            modifier = modifier
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.primary)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }

        showDot -> Box(
            modifier = modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(colors.primary),
        )
    }
}

private fun Int.badgeText(): String? = when {
    this <= 0 -> null
    this > 99 -> "99+"
    else -> toString()
}

private fun android.content.Context.themeColor(attribute: Int, fallback: Int): Int {
    val typedArray = obtainStyledAttributes(intArrayOf(attribute))
    return try {
        typedArray.getColorStateList(0).defaultColorOr(fallback)
    } finally {
        typedArray.recycle()
    }
}

private fun ColorStateList?.defaultColorOr(fallback: Int): Int = this?.defaultColor ?: fallback
