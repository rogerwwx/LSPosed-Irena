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

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Looper
import android.view.View
import androidx.annotation.IdRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import org.lsposed.manager.App
import org.lsposed.manager.R
import org.lsposed.manager.ui.compose.liquid.rememberViewBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.textureBlur

/**
 * Small Java-facing bridge between the existing Fragment NavController and the
 * Compose MIUIX navigation surface.
 *
 * The controller owns navigation UI state and the pager-like transition around
 * the existing NavHostFragment; Fragment content and its back stacks remain
 * managed by Navigation/FragmentManager.
 *
 * When a non-null [backdropView] is passed and the "floating bottom bar"
 * preference is enabled, the bottom bar renders as the KernelSU-style floating
 * liquid-glass pill sampling that view instead of the classic solid bar.
 */
class MiuixNavigationController(
    private val composeView: ComposeView,
    private val navController: NavController,
    private val pagerMediator: MainPagerMediator,
    private val useNavigationRail: Boolean,
    private val backdropView: View? = null,
) {
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

    private val floatingBottomBar =
        backdropView != null && !useNavigationRail && isFloatingBottomBarEnabled(composeView.context)

    /** Liquid glass (blur + refraction) needs AGSL runtime shaders, API 33+. */
    private val floatingBottomBarGlass = floatingBottomBar &&
        App.getPreferences().getBoolean(PREF_FLOATING_BOTTOM_BAR_BLUR, true) &&
        isRuntimeShaderSupported()

    /**
     * KernelSU's "blur" option for the classic bar: page content scrolls
     * behind a translucent bar blurred via texture blur. Also AGSL-gated.
     */
    private val classicBarBlur =
        backdropView != null && !useNavigationRail && !floatingBottomBar &&
            isClassicBarBlurEnabled(composeView.context) && isRuntimeShaderSupported()

    private val isInDarkTheme =
        (composeView.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    init {
        composeView.setContent {
            MiuixTheme {
                val selectedPage by pagerMediator.selectedPage.collectAsState()
                val colors = NavigationColors(
                    surface = Color(surfaceColor),
                    content = Color(contentColor),
                    secondaryContent = Color(secondaryContentColor),
                    primary = Color(primaryColor),
                    inactive = Color(inactiveColor),
                    divider = Color(dividerColor),
                )
                if (floatingBottomBar && backdropView != null) {
                    FloatingNavigation(
                        // Must read through the collectAsState() delegate:
                        // snapshotFlow inside FloatingBottomBar only observes
                        // Compose snapshot state - a lambda capturing the Int
                        // parameter or reading StateFlow.value directly is
                        // invisible to it and the pill would never move.
                        selectedIndex = { selectedPage },
                        backdropView = backdropView,
                        glassEnabled = floatingBottomBarGlass,
                        isInDark = isInDarkTheme,
                        binderAlive = binderAlive.value,
                        magiskInstalled = magiskInstalled.value,
                        moduleCount = moduleCount.intValue,
                        repoUpdateCount = repoUpdateCount.intValue,
                        frameworkUpdateAvailable = frameworkUpdateAvailable.value,
                        onDestinationSelected = ::selectDestination,
                        colors = colors,
                    )
                } else if (classicBarBlur && backdropView != null) {
                    // Classic bar over a blurred window: the pager stretches
                    // full-height behind the (now translucent) bar, mirroring
                    // KernelSU's BlurredBar with textureBlur(25dp) + surface
                    // scrim.
                    val blurBackdrop = rememberViewBackdrop(backdropView)
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .textureBlur(
                                    backdrop = blurBackdrop,
                                    shape = RectangleShape,
                                    blurRadius = 25f,
                                    colors = BlurColors(
                                        blendColors = listOf(
                                            BlendColorEntry(color = Color(surfaceColor).copy(alpha = 0.87f)),
                                        ),
                                    ),
                                ),
                        ) {
                            NavigationSurface(
                                useNavigationRail = false,
                                selectedPage = selectedPage,
                                binderAlive = binderAlive.value,
                                magiskInstalled = magiskInstalled.value,
                                moduleCount = moduleCount.intValue,
                                repoUpdateCount = repoUpdateCount.intValue,
                                frameworkUpdateAvailable = frameworkUpdateAvailable.value,
                                onDestinationSelected = ::selectDestination,
                                colors = colors,
                                transparentBar = true,
                            )
                        }
                    }
                } else {
                    NavigationSurface(
                        useNavigationRail = useNavigationRail,
                        selectedPage = selectedPage,
                        binderAlive = binderAlive.value,
                        magiskInstalled = magiskInstalled.value,
                        moduleCount = moduleCount.intValue,
                        repoUpdateCount = repoUpdateCount.intValue,
                        frameworkUpdateAvailable = frameworkUpdateAvailable.value,
                        onDestinationSelected = ::selectDestination,
                        colors = colors,
                    )
                }
            }
        }
    }

    /** Selects a top-level pager page and closes any second-level overlay first. */
    fun selectDestination(@IdRes id: Int) {
        onMainThread {
            if (!isDestinationAvailable(id)) return@onMainThread

            if (navController.currentDestination?.id != R.id.top_level_stub) {
                navController.popBackStack(R.id.top_level_stub, false)
            }
            val pageId = if (id == R.id.logs_fragment) R.id.main_fragment else id
            pagerMediator.animateToPageId(pageId)
        }
    }

    /**
     * Same as [selectDestination] but switches the pager without the fake-drag
     * animation. Used when a second-level overlay opens right after (e.g. the
     * logs deep link), so the pager work does not overlap and stall the
     * overlay slide.
     */
    fun selectDestinationImmediate(@IdRes id: Int) {
        onMainThread {
            if (!isDestinationAvailable(id)) return@onMainThread

            if (navController.currentDestination?.id != R.id.top_level_stub) {
                navController.popBackStack(R.id.top_level_stub, false)
            }
            val pageId = if (id == R.id.logs_fragment) R.id.main_fragment else id
            pagerMediator.jumpToPageId(pageId)
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
            if (!isDestinationAvailable(R.id.main_fragment)) {
                selectDestination(R.id.main_fragment)
            }
        }
    }

    /** Must be called from Activity.onDestroy(). */
    fun dispose() {
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
        const val PREF_FLOATING_BOTTOM_BAR = "floating_bottom_bar"
        const val PREF_FLOATING_BOTTOM_BAR_BLUR = "floating_bottom_bar_blur"
        const val PREF_ENABLE_BLUR = "enable_blur"

        /**
         * Height the top-level pages must clear so their content can scroll
         * under the floating pill: 64dp pill + 12dp bottom margin.
         */
        private const val FLOATING_BOTTOM_BAR_CONTENT_CLEARANCE_DP = 76

        /**
         * Whether the floating liquid-glass bottom bar should be shown: only on
         * phone layouts (tablets keep the navigation rail) and only when the
         * user enabled it in Settings.
         */
        @JvmStatic
        fun isFloatingBottomBarEnabled(context: Context): Boolean {
            if (context.resources.configuration.smallestScreenWidthDp >= 600) return false
            return App.getPreferences().getBoolean(PREF_FLOATING_BOTTOM_BAR, false)
        }

        /**
         * KernelSU's "blur" option applied to our classic bar. Only meaningful
         * when the floating pill is off (it has its own glass toggle).
         */
        @JvmStatic
        fun isClassicBarBlurEnabled(context: Context): Boolean {
            if (context.resources.configuration.smallestScreenWidthDp >= 600) return false
            if (App.getPreferences().getBoolean(PREF_FLOATING_BOTTOM_BAR, false)) return false
            return App.getPreferences().getBoolean(PREF_ENABLE_BLUR, false)
        }

        /**
         * Whether the pager must stretch behind the bar (floating pill or
         * blurred classic bar), so page content scrolls under it.
         */
        @JvmStatic
        fun isBottomBarOverlayEnabled(context: Context): Boolean =
            isFloatingBottomBarEnabled(context) || isClassicBarBlurEnabled(context)

        /**
         * Pads a top-level page's scroll container so its content scrolls under
         * the overlay bar instead of being hidden by it. No-op when neither
         * overlay bar mode is enabled.
         */
        @JvmStatic
        fun applyFloatingBottomBarContentPadding(view: View) {
            if (!isBottomBarOverlayEnabled(view.context)) return
            val density = view.resources.displayMetrics.density
            val basePadding = (FLOATING_BOTTOM_BAR_CONTENT_CLEARANCE_DP * density + 0.5f).toInt()
            // setClipToPadding lives on ViewGroup, and every scroll container
            // we pad is one.
            (view as? android.view.ViewGroup)?.setClipToPadding(false)
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, basePadding)
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val bottom = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
                ).bottom
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
                insets
            }
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
private fun buildNavigationItems(
    binderAlive: Boolean,
    magiskInstalled: Boolean,
    moduleCount: Int,
    repoUpdateCount: Int,
    frameworkUpdateAvailable: Boolean,
): List<NavigationItem> = buildList {
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

@Composable
private fun NavigationSurface(
    useNavigationRail: Boolean,
    selectedPage: Int,
    binderAlive: Boolean,
    magiskInstalled: Boolean,
    moduleCount: Int,
    repoUpdateCount: Int,
    frameworkUpdateAvailable: Boolean,
    onDestinationSelected: (Int) -> Unit,
    colors: NavigationColors,
    transparentBar: Boolean = false,
) {
    val items = buildNavigationItems(
        binderAlive = binderAlive,
        magiskInstalled = magiskInstalled,
        moduleCount = moduleCount,
        repoUpdateCount = repoUpdateCount,
        frameworkUpdateAvailable = frameworkUpdateAvailable,
    )

    if (useNavigationRail) {
        MiuixNavigationRail(items, selectedPage, onDestinationSelected, colors)
    } else {
        MiuixBottomNavigation(items, selectedPage, onDestinationSelected, colors, drawBackground = !transparentBar)
    }
}

/**
 * Full-screen overlay hosting the floating pill above the (View-side) pager.
 * Empty areas of this surface do not consume touches, so gestures reach the
 * pager underneath; only the pill itself is interactive.
 */
@Composable
private fun FloatingNavigation(
    selectedIndex: () -> Int,
    backdropView: View,
    glassEnabled: Boolean,
    isInDark: Boolean,
    binderAlive: Boolean,
    magiskInstalled: Boolean,
    moduleCount: Int,
    repoUpdateCount: Int,
    frameworkUpdateAvailable: Boolean,
    onDestinationSelected: (Int) -> Unit,
    colors: NavigationColors,
) {
    val items = buildNavigationItems(
        binderAlive = binderAlive,
        magiskInstalled = magiskInstalled,
        moduleCount = moduleCount,
        repoUpdateCount = repoUpdateCount,
        frameworkUpdateAvailable = frameworkUpdateAvailable,
    )
    val backdrop = rememberViewBackdrop(backdropView)

    Box(modifier = Modifier.fillMaxSize()) {
        FloatingBottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            selectedIndex = selectedIndex,
            onSelected = { index -> items.getOrNull(index)?.let { onDestinationSelected(it.id) } },
            backdrop = backdrop,
            tabsCount = items.size,
            isBlurEnabled = glassEnabled,
            isInDark = isInDark,
            accentColor = colors.primary,
            surfaceColor = colors.surface,
        ) {
            items.forEach { item ->
                FloatingNavItemView(
                    item = item,
                    onClick = { onDestinationSelected(item.id) },
                    colors = colors,
                )
            }
        }
    }
}

@Composable
private fun MiuixBottomNavigation(
    items: List<NavigationItem>,
    selectedPage: Int,
    onDestinationSelected: (Int) -> Unit,
    colors: NavigationColors,
    drawBackground: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (drawBackground) Modifier.background(colors.surface) else Modifier)
            .navigationBarsPadding(),
    ) {
        if (drawBackground) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                NavigationItemView(
                    item = item,
                    selected = items.getOrNull(selectedPage) == item,
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
    selectedPage: Int,
    onDestinationSelected: (Int) -> Unit,
    colors: NavigationColors,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .navigationBarsPadding()
            .selectableGroup()
            .padding(horizontal = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        items.forEach { item ->
            NavigationItemView(
                item = item,
                selected = items.getOrNull(selectedPage) == item,
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
            .selectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
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

/**
 * Item view for the floating pill bar, mirroring the KernelSU item:
 * [FloatingBottomBarItem] provides the 76dp minimum width and the press
 * scale, items never change color on selection — the indicator pill and its
 * accent-tinted backdrop copy ([LocalFloatingBottomBarAccentCopy]) convey
 * which page is active.
 */
@Composable
private fun RowScope.FloatingNavItemView(
    item: NavigationItem,
    colors: NavigationColors,
    onClick: () -> Unit,
) {
    val accentCopy = LocalFloatingBottomBarAccentCopy.current
    val foreground = if (accentCopy) colors.primary else colors.content
    FloatingBottomBarItem(
        onClick = onClick,
        modifier = Modifier.defaultMinSize(minWidth = 76.dp),
    ) {
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(item.unselectedIcon),
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
        Text(
            text = item.label,
            color = foreground,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Visible,
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

private fun Context.themeColor(attribute: Int, fallback: Int): Int {
    val typedArray = obtainStyledAttributes(intArrayOf(attribute))
    return try {
        typedArray.getColorStateList(0).defaultColorOr(fallback)
    } finally {
        typedArray.recycle()
    }
}

private fun ColorStateList?.defaultColorOr(fallback: Int): Int = this?.defaultColor ?: fallback
