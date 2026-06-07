package ru.elmer.client.elm

/**
 * Декодирует сырые HEX-ответы ELM327 в читаемые значения.
 *
 * Поддерживает:
 *   - VIN (mode 09 PID 02)
 *   - DTC stored/pending (mode 03/07)
 *   - PID значения (mode 01) — RPM, скорость, температура, etc.
 *   - Сигналы протокола: SEARCHING, NO DATA, OK, STOPPED
 */
object ObdDecoder {

    /** Декодирует сырой ответ ELM327 для заданной команды. */
    fun decode(cmd: String, raw: String): String {
        // Сигналы протокола — возвращаем как есть
        if (raw in setOf("OK", "?", "NO DATA", "NODATA") ||
            raw.startsWith("SEARCHING")
        ) return raw

        // Ошибки шины — human-friendly
        if (raw.startsWith("STOPPED")) return "нет связи"
        if (raw.startsWith("UNABLE")) return "нет ответа ЭБУ"
        if (raw.startsWith("BUS BUSY")) return "шина занята"
        if (raw.startsWith("BUS ERROR")) return "ошибка шины"
        if (raw.startsWith("BUS INIT")) return "инициализация шины"
        if (raw.startsWith("CAN ERROR")) return "ошибка CAN"
        if (raw.startsWith("DATA ERROR")) return "ошибка данных"
        if (raw.startsWith("BUFFER FULL")) return "буфер полон"
        if (raw.startsWith("RX ERROR")) return "ошибка приёма"
        if (raw == "(no elm)" || raw == "(err)") return raw

        val clean = raw.replace("\r", "").replace("\n", "").replace(":", "").replace(" ", "").uppercase()

        // VIN
        if (cmd == "0902" && "490201" in clean) {
            return decodeVin(clean) ?: raw
        }

        // Калибровка (mode 09 PID 04) — многострочный ответ, не пытаемся парсить
        if (cmd == "0904" && clean.startsWith("4904")) {
            val lines = raw.trim().split(Regex("\\s+"))
            return if (lines.size >= 2) "калибровка (${lines.size} строк)" else "калибровка: ок"
        }

        // Имя ЭБУ (mode 09 PID 0A)
        if (cmd == "090A" && clean.startsWith("490A")) {
            val hex = clean.substringAfter("490A").take(40)
            val name = buildString {
                for (i in hex.indices step 2) {
                    if (i + 1 >= hex.length) break
                    try { append(Integer.parseInt(hex.substring(i, i + 2), 16).toChar()) }
                    catch (_: Exception) { break }
                }
            }
            return if (name.isNotEmpty()) "ЭБУ: $name" else "ЭБУ: (нет имени)"
        }

        // DTC
        if (clean.startsWith("43") || clean.startsWith("47")) {
            return decodeDtc(clean)
        }

        // PID
        if (clean.startsWith("41") && clean.length >= 6) {
            return decodePid(clean)
        }

        return raw.trim()
    }

    // ── VIN ────────────────────────────────────────────

    private fun decodeVin(clean: String): String? {
        val hex = clean.substringAfter("490201").take(34)
        val vin = buildString {
            for (i in hex.indices step 2) {
                if (i + 1 >= hex.length) break
                try {
                    append(Integer.parseInt(hex.substring(i, i + 2), 16).toChar())
                } catch (_: Exception) {
                    break
                }
            }
        }
        return when {
            vin.length == 17 -> "VIN: $vin"
            vin.isNotEmpty() -> "VIN(${vin.length}): $vin"
            else -> null
        }
    }

    // ── DTC ────────────────────────────────────────────

    private fun decodeDtc(clean: String): String {
        val mode = if (clean.startsWith("43")) "stored" else "pending"
        val hex = clean.substring(2)
        val codes = mutableListOf<String>()
        var i = 2 // skip byte count
        while (i + 3 < hex.length) {
            try {
                val a = Integer.parseInt(hex.substring(i, i + 2), 16)
                val b = Integer.parseInt(hex.substring(i + 2, i + 4), 16)
                val p = when (a shr 6) {
                    0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U"
                }
                val code = "$p${(a shr 4) and 3}${a and 15}${
                    b.toString(16).uppercase().padStart(2, '0')}"
                if (code != "P0000") codes.add(code)
            } catch (_: Exception) {
                break
            }
            i += 4
        }
        return if (codes.isEmpty()) "DTC $mode: none"
        else "DTC $mode: ${codes.joinToString(" ")}"
    }

    // ── PID ────────────────────────────────────────────

    private fun decodePid(clean: String): String {
        val prefix = clean.substring(0, 2)
        val pid = clean.substring(2, 4)
        val hex = clean.substring(4)

        // 7F — ЭБУ не поддерживает этот PID
        if (prefix == "7F") {
            return "не поддерживается"
        }

        try {
            val b0 = Integer.parseInt(hex.substring(0, 2), 16)
            val b1 = if (hex.length >= 4) Integer.parseInt(hex.substring(2, 4), 16) else 0

            val (name, value) = pidValue(pid, b0, b1) ?: return "PID $pid: ${clean.take(10)}"
            return "$name: $value"
        } catch (_: Exception) {
            return "данные: ${clean.take(10)}"
        }
    }

    private fun pidValue(pid: String, b0: Int, b1: Int): Pair<String, String>? = when (pid) {
        "05" -> "ОЖ" to "${b0 - 40} °C"
        "0C" -> "Обороты" to "${"%.0f".format((b0 * 256 + b1) / 4.0)} об/мин"
        "0D" -> "Скорость" to "$b0 км/ч"
        "11" -> "Дроссель" to "${"%.1f".format(b0 * 100.0 / 255.0)} %"
        "0B" -> "Давление (MAP)" to "$b0 кПа"
        "0F" -> "Темп. воздуха" to "${b0 - 40} °C"
        "1F" -> "Время работы" to "${b0 * 256 + b1} с"
        "04" -> "Нагрузка" to "${"%.1f".format(b0 * 100.0 / 255.0)} %"
        "06" -> "Коррекция (STFT)" to "${"%.1f".format((b0 - 128) * 100.0 / 128.0)} %"
        "07" -> "Коррекция (LTFT)" to "${"%.1f".format((b0 - 128) * 100.0 / 128.0)} %"
        "10" -> "MAF" to "${"%.1f".format((b0 * 256.0 + b1) / 100.0)} г/с"
        "1C" -> "Стандарт OBD" to when (b0) {
            1 -> "OBD-II (CARB)"
            2 -> "OBD (EPA)"
            3 -> "OBD + OBD-II"
            4 -> "OBD-I"
            5 -> "не OBD"
            6 -> "EOBD"
            else -> "тип $b0"
        }
        else -> null
    }
}
