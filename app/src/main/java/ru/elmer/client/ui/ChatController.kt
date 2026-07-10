package ru.elmer.client.ui

/**
 * Контроллер чата с LLM.
 *
 * Управляет историей диалога и отправкой вопросов на сервер.
 * НЕ зависит от Android UI — только данные и callback для вывода.
 *
 * Использование:
 *   val chat = ChatController { msg -> runOnUiThread { appendStatus(msg) } }
 *   chat.send("Что с оборотами?")  // асинхронно, результат в callback
 *   chat.clear()                   // очистить историю
 */
class ChatController(
    private val onOutput: (String) -> Unit
) {
    /** История диалога: пары (role, content). role = "user" или "assistant". */
    val history: MutableList<Pair<String, String>> = mutableListOf()

    /**
     * Отправить вопрос в LLM.
     * Ответ придёт асинхронно в onOutput.
     *
     * @param question текст вопроса пользователя
     */
    fun send(question: String) {
        if (question.isBlank()) return

        // Добавляем вопрос в историю
        history.add("user" to question)
        onOutput("\n👤 $question")

        // Отправляем на сервер в фоновом потоке
        Thread {
            try {
                val answer = ru.elmer.client.Config.client(null)
                    ?.chat(question, history)
                if (answer != null) {
                    history.add("assistant" to answer)
                    onOutput("\n🤖 $answer")
                } else {
                    onOutput("\n❌ Сервер не ответил")
                }
            } catch (e: Exception) {
                onOutput("\n❌ ${e.message}")
            }
        }.apply {
            name = "Chat"
            isDaemon = true
            start()
        }
    }

    /** Очистить историю диалога. */
    fun clear() {
        history.clear()
    }
}
