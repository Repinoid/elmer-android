package ru.elmer.client.elm

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth-подключение к ELM327.
 *
 * Отвечает ТОЛЬКО за создание RFCOMM-сокета и создание ElmProtocol.
 * Никакой логики ELM — только транспорт.
 *
 * Использование:
 *   val connector = BtConnector()
 *   val elm = connector.connect(device, adapter)  // возвращает ElmProtocol или бросает IOException
 *   // ... работа с elm ...
 *   connector.disconnect()
 */
class BtConnector {

    companion object {
        private const val TAG = "BtConnector"
        /** UUID для SPP (Serial Port Profile) — стандартный для ELM327 */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    /** Текущий сокет. null если не подключены. */
    private var socket: BluetoothSocket? = null

    /** Подключены ли к ELM. */
    val isConnected: Boolean get() = socket?.isConnected == true

    /**
     * Создать BT-сокет, подключиться к ELM327, создать ElmProtocol.
     *
     * Идемпотентен: если сокет уже открыт — возвращает null (не переподключается).
     *
     * @param device  Bluetooth-устройство ELM327 (должно быть в bonded)
     * @param adapter Bluetooth-адаптер телефона
     * @return ElmProtocol, готовый к init()
     * @throws IOException если не удалось подключиться
     */
    @Throws(IOException::class)
    fun connect(device: BluetoothDevice, adapter: BluetoothAdapter): ElmProtocol {
        // Уже подключены — не дёргаем повторно (идемпотентность)
        if (socket?.isConnected == true) {
            Log.d(TAG, "Already connected, skipping")
            throw IOException("Already connected")  // не должно случаться при правильном использовании
        }

        // На всякий случай закроем старый сокет, если он есть, но не connected
        try { socket?.close() } catch (_: Exception) {}

        Log.i(TAG, "Connecting to ${device.name} (${device.address})...")

        try {
            // Первая попытка: стандартный createRfcommSocketToServiceRecord
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            adapter.cancelDiscovery()
            s.connect()
            socket = s
            Thread.sleep(500)  // даём ELM проснуться после коннекта
            Log.i(TAG, "Connected OK (standard method)")
            return ElmProtocol(s.inputStream, s.outputStream)
        } catch (e: IOException) {
            Log.w(TAG, "Standard connect failed: ${e.message}, trying fallback...")
            // Вторая попытка: createRfcommSocket(1) через рефлексию (для старых/китайских ELM)
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                val s = m.invoke(device, 1) as BluetoothSocket
                s.connect()
                socket = s
                Thread.sleep(500)
                Log.i(TAG, "Connected OK (fallback method)")
                return ElmProtocol(s.inputStream, s.outputStream)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback connect also failed: ${e2.message}")
                throw IOException("Не удалось подключиться к ELM327", e2)
            }
        }
    }

    /**
     * Закрыть сокет, освободить ресурсы.
     * Безопасно вызывать повторно.
     */
    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        Log.i(TAG, "Disconnected")
    }
}
