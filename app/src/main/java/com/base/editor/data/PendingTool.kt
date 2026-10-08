package com.base.editor.data

/**
 * Инструмент, который нужно открыть сразу после создания проекта: плитки на главном экране запускают новый проект
 * и передают сюда id инструмента, редактор забирает его один раз после загрузки клипов.
 */
object PendingTool {
    @Volatile private var id: String? = null
    fun set(tool: String?) { id = tool }
    fun take(): String? = id.also { id = null }
}
