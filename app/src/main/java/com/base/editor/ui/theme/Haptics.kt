package com.base.editor.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Виды тактильного отклика: от еле заметного «тика» при перемотке до чёткого «щелчка» при привязке к центру. */
enum class Haptic { TICK, LIGHT, SELECT, LONG, SNAP, SUCCESS, ERROR }

/** Подбирает самый подходящий системный отклик для версии Android; уважает системную настройку «вибрация при касании». */
fun View.haptic(h: Haptic) {
    val sdk = Build.VERSION.SDK_INT
    val c = when (h) {
        Haptic.TICK -> HapticFeedbackConstants.CLOCK_TICK
        Haptic.LIGHT -> HapticFeedbackConstants.KEYBOARD_TAP
        Haptic.SELECT -> HapticFeedbackConstants.CONTEXT_CLICK
        Haptic.LONG -> HapticFeedbackConstants.LONG_PRESS
        Haptic.SNAP -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        Haptic.SUCCESS -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
        Haptic.ERROR -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(c)
}

/** Шина для вибрации из ViewModel: экран подписывается и исполняет отклик на своём View. */
object HapticBus {
    val events = MutableSharedFlow<Haptic>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    fun emit(h: Haptic) { events.tryEmit(h) }
}
