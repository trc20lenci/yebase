package com.base.editor

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.base.editor.ui.home.HomeScreen
import com.base.editor.ui.profile.ProfileScreen
import com.base.editor.ui.projects.ProjectsScreen
import com.base.editor.ui.theme.BaseColors

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Дом", Icons.Rounded.Home), Projects("Проекты", Icons.Rounded.Folder), Me("Я", Icons.Rounded.Person)
}

@Composable
fun MainScreen(onNewVideo: () -> Unit, onEditPhoto: () -> Unit, onOpenProject: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val vm: AppViewModel = viewModel()
    val projects by vm.projects.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Color.White,
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label, fontSize = 12.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BaseColors.Ink, selectedTextColor = BaseColors.Ink,
                            unselectedIconColor = BaseColors.Muted, unselectedTextColor = BaseColors.Muted,
                            indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
            Crossfade(tab, animationSpec = androidx.compose.animation.core.tween(com.base.editor.ui.theme.BaseMotion.STATE_MS + 40, easing = com.base.editor.ui.theme.BaseMotion.EaseOut), label = "tabs") { t ->
                when (Tab.entries[t]) {
                    Tab.Home -> HomeScreen(projects, onNewVideo, onEditPhoto, onOpenProject, onSeeAll = { tab = 1 })
                    Tab.Projects -> ProjectsScreen(projects, onCreate = onNewVideo, onOpen = onOpenProject, onDelete = vm::delete)
                    Tab.Me -> ProfileScreen(projects)
                }
            }
        }
    }
}
