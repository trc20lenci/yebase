package com.base.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import com.base.editor.ui.theme.BaseMotion
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.base.editor.core.PickedMedia
import com.base.editor.data.PickedMediaInbox
import com.base.editor.data.ProjectRepository
import com.base.editor.ui.editor.EditorScreen
import com.base.editor.ui.picker.MediaPickerScreen
import com.base.editor.ui.theme.BaseTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Routes {
    const val MAIN = "main"
    const val PICKER_NEW = "picker/new/{tab}"
    const val PICKER_ADD = "picker/add/{projectId}"
    const val EDITOR = "editor/{projectId}"
    fun pickerNew(photo: Boolean) = "picker/new/${if (photo) "photo" else "video"}"
    fun editor(id: String) = "editor/$id"
    fun pickerAdd(projectId: String) = "picker/add/$projectId"
}

@Composable
fun BaseApp() {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var splash by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(900); splash = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // «fade through»: уходящий экран быстро гаснет, новый появляется с лёгким увеличением (ease-out, ≤ 250 мс)
        val ease = BaseMotion.EaseOut
        NavHost(
            nav, startDestination = Routes.MAIN,
            enterTransition = { fadeIn(tween(BaseMotion.ENTER_MS, delayMillis = 70, easing = ease)) + scaleIn(tween(BaseMotion.ENTER_MS + 40, delayMillis = 70, easing = ease), initialScale = 0.97f) },
            exitTransition = { fadeOut(tween(90, easing = ease)) },
            popEnterTransition = { fadeIn(tween(BaseMotion.ENTER_MS, delayMillis = 70, easing = ease)) },
            popExitTransition = { fadeOut(tween(BaseMotion.EXIT_MS, easing = ease)) + scaleOut(tween(BaseMotion.EXIT_MS, easing = ease), targetScale = 0.97f) },
        ) {
            composable(Routes.MAIN) {
                BaseTheme(dark = false) {
                    MainScreen(
                        onNewVideo = { nav.navigate(Routes.pickerNew(photo = false)) },
                        onEditPhoto = { nav.navigate(Routes.pickerNew(photo = true)) },
                        onOpenProject = { nav.navigate(Routes.editor(it)) },
                    )
                }
            }
            composable(Routes.PICKER_NEW, arguments = listOf(navArgument("tab") { type = NavType.StringType })) { e ->
                BaseTheme(dark = true) {
                    MediaPickerScreen(
                        startOnPhotos = e.arguments?.getString("tab") == "photo",
                        onClose = { nav.popBackStack() },
                        onConfirm = { items: List<PickedMedia> ->
                            scope.launch {
                                val id = ProjectRepository.get(ctx).create(items)
                                nav.navigate(Routes.editor(id)) { popUpTo(Routes.MAIN) }
                            }
                        },
                    )
                }
            }
            // Добавление медиа в открытый проект: результат уходит в SavedStateHandle редактора
            composable(Routes.PICKER_ADD, arguments = listOf(navArgument("projectId") { type = NavType.StringType })) { e ->
                val projectId = e.arguments?.getString("projectId").orEmpty()
                BaseTheme(dark = true) {
                    MediaPickerScreen(
                        startOnPhotos = false,
                        onClose = { nav.popBackStack() },
                        onConfirm = { items ->
                            PickedMediaInbox.post(projectId, items)   // редактор заберёт и добавит клипы
                            nav.popBackStack()
                        },
                    )
                }
            }
            composable(Routes.EDITOR, arguments = listOf(navArgument("projectId") { type = NavType.StringType })) { e ->
                val projectId = e.arguments?.getString("projectId").orEmpty()
                BaseTheme(dark = true) {
                    EditorScreen(onClose = { nav.popBackStack() }, onAddMedia = { nav.navigate(Routes.pickerAdd(projectId)) })
                }
            }
        }
        AnimatedVisibility(visible = splash, exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.logo_base_white), "BASE", Modifier.width(180.dp))
            }
        }
    }
}
