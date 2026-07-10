package ru.elmer.client.ui

import android.graphics.Color
import android.widget.TextView

/**
 * Панель индикаторов-светофоров (Сервер, ELM, ЭБУ, LLM).
 *
 * Каждый индикатор — TextView с иконкой и цветным текстом.
 * Состояния: 🟢 зелёный (OK), 🟡 жёлтый (проверка...), 🔴 красный (ошибка).
 *
 * Использование:
 *   val bar = IndicatorBar(indServer, indElm, indEcu, indLlm)
 *   bar.set(IndicatorBar.Id.SERVER, IndicatorBar.State.OK)
 *   val isServerOk = bar.isOk(IndicatorBar.Id.SERVER)
 *   bar.onChange = { updateUiState() }
 */
class IndicatorBar(
    private val tvServer: TextView,
    private val tvElm: TextView,
    private val tvEcu: TextView,
    private val tvLlm: TextView
) {
    /** Идентификаторы индикаторов */
    enum class Id { SERVER, ELM, ECU, LLM }

    /** Состояние индикатора */
    enum class State(val emoji: String, val color: Int) {
        OK("🟢", 0xFF4CAF50.toInt()),
        CHECKING("🟡", 0xFFFFC107.toInt()),
        ERROR("🔴", 0xFFF44336.toInt())
    }

    /** Текущие состояния */
    private val states = mutableMapOf(
        Id.SERVER to State.CHECKING,
        Id.ELM to State.CHECKING,
        Id.ECU to State.CHECKING,
        Id.LLM to State.CHECKING
    )

    /** Callback при изменении любого индикатора */
    var onChange: (() -> Unit)? = null

    private val labels = mapOf(
        Id.SERVER to "📡",
        Id.ELM to "ELM",
        Id.ECU to "ECU",
        Id.LLM to "LLM"
    )

    /**
     * Установить состояние индикатора.
     * Выполняется в UI-потоке (безопасно вызывать из любого потока).
     */
    fun set(id: Id, state: State) {
        val tv = when (id) {
            Id.SERVER -> tvServer
            Id.ELM -> tvElm
            Id.ECU -> tvEcu
            Id.LLM -> tvLlm
        }
        states[id] = state
        tv.post {
            tv.text = "${labels[id]} ${state.emoji}"
            tv.setTextColor(state.color)
            onChange?.invoke()
        }
    }

    /** Проверить, OK ли индикатор. */
    fun isOk(id: Id): Boolean = states[id] == State.OK

    /** Сбросить все индикаторы в CHECKING. */
    fun resetAll() {
        for (id in Id.entries) set(id, State.CHECKING)
    }
}
