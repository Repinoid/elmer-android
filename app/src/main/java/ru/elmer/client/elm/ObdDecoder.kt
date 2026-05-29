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
        if (raw in setOf("OK", "NO DATA", "?") ||
            raw.startsWith("SEARCHING") ||
            raw.startsWith("STOPPED")
        ) return raw

        val clean = raw.replace(":", "").replace(" ", "").uppercase()

        // VIN
        if (cmd == "0902" && "490201" in clean) {
            return decodeVin(clean) ?: raw
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
        val pid = clean.substring(2, 4)
        val hex = clean.substring(4)
        try {
            val b0 = Integer.parseInt(hex.substring(0, 2), 16)
            val b1 = if (hex.length >= 4) Integer.parseInt(hex.substring(2, 4), 16) else 0

            val (name, value) = pidValue(pid, b0, b1) ?: return "PID $pid: raw"
            return "$name: $value"
        } catch (_: Exception) {
            return clean
        }
    }

    private fun pidValue(pid: String, b0: Int, b1: Int): Pair<String, String>? = when (pid) {
        "05" -> "ОЖ" to "${b0 - 40} °C"
        "0C" -> "RPM" to "${(b0 * 256 + b1) / 4.0} RPM"
        "0D" -> "Скорость" to "$b0 км/ч"
        "11" -> "Дроссель" to "${"%.1f".format(b0 * 100.0 / 255.0)} %"
        "0B" -> "MAP" to "$b0 кПа"
        "0F" -> "IAT" to "${b0 - 40} °C"
        "1F" -> "Время" to "${b0 * 256 + b1} с"
        "04" -> "Нагрузка" to "${"%.1f".format(b0 * 100.0 / 255.0)} %"
        "06" -> "STFT" to "${"%.1f".format((b0 - 128) * 100.0 / 128.0)} %"
        "07" -> "LTFT" to "${"%.1f".format((b0 - 128) * 100.0 / 128.0)} %"
        else -> null
    }
}
