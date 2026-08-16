package org.kvj.habtproxy

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val TAG = "YunmaiBridge"

data class YunmaiWeightResult(
    val address: String,
    val timestamp: Long,
    val weight: Float,
    val fat: Float? = null,
    val muscle: Float? = null,
    val water: Float? = null,
    val bone: Float? = null,
    val lbm: Float? = null,
    val visceralFat: Float? = null
)

/**
 * GATT bridge to a Yunmai Mini family scale (Mini 2S etc).
 * Auto-connects when the scale is seen during scan, enables notifications
 * on the measurement characteristic, sends user profile/time/start commands,
 * parses the final measurement frame and posts it to the configured webhook.
 */
object YunmaiBridge {

    private const val SVC_MEAS = "0000ffe0-0000-1000-8000-00805f9b34fb"
    private const val CHR_MEAS = "0000ffe4-0000-1000-8000-00805f9b34fb"
    private const val SVC_CMD = "0000ffe5-0000-1000-8000-00805f9b34fb"
    private const val CHR_CMD = "0000ffe9-0000-1000-8000-00805f9b34fb"
    private const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
    private const val CONNECT_TIMEOUT_MS = 15000L

    private val MAGIC_START = byteArrayOf(0x0D, 0x05, 0x13, 0x00, 0x16)

    private var gatt: BluetoothGatt? = null
    private var connecting = false
    private var appContext: Context? = null
    private var pendingWrites = mutableListOf<ByteArray>()
    private var lastMeasurement: YunmaiWeightResult? = null
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun onDeviceSeen(context: Context, device: BluetoothDevice, name: String?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBool(context, R.string.settings_yunmai_enabled, R.string.settings_yunmai_enabled_def)) return
        if (connecting || gatt != null) return
        if (!isYunmaiDevice(device, name, context)) return
        Log.d(TAG, "Connecting to scale: ${device.address} (${name})")
        connecting = true
        appContext = context.applicationContext
        try {
            gatt = device.connectGatt(context, false, callback)
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt failed", e)
            connecting = false
            gatt = null
            return
        }
        handler.postDelayed({
            if (connecting) {
                Log.w(TAG, "Connect timeout, resetting")
                connecting = false
                try {
                    gatt?.disconnect()
                    gatt?.close()
                } catch (e: Exception) {
                }
                gatt = null
            }
        }, CONNECT_TIMEOUT_MS)
    }

    fun tick(context: Context, discovered: Map<String, DiscoveredDevice>) {
        if (connecting || gatt != null) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBool(context, R.string.settings_yunmai_enabled, R.string.settings_yunmai_enabled_def)) return
        discovered.values.forEach { d ->
            if (isYunmaiDevice(d.device, d.name, context)) {
                onDeviceSeen(context, d.device, d.name)
                return
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        connecting = false
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
        }
        gatt = null
    }

    private fun isYunmaiDevice(device: BluetoothDevice, name: String?, context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val addressFilter = prefs.getString(context, R.string.settings_yunmai_address, 0)
        if (!TextUtils.isEmpty(addressFilter)) {
            return device.address.equals(addressFilter, ignoreCase = true)
        }
        val upperName = name?.uppercase() ?: return false
        return upperName.startsWith("YUNMAI-SIGNAL") || upperName.startsWith("YUNMAI-ISM") ||
                upperName.startsWith("YUNMAI-ISC2P") || upperName == "YUNMAI-SCALE-3"
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to scale, discovering services")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Scale disconnected")
                    connecting = false
                    try {
                        g.close()
                    } catch (e: Exception) {
                    }
                    gatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ctx = appContext ?: run {
                connecting = false
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: $status")
                try {
                    g.disconnect()
                } catch (e: Exception) {
                }
                return
            }
            val measChr = g.getService(UUID.fromString(SVC_MEAS))?.getCharacteristic(UUID.fromString(CHR_MEAS))
            val cmdChr = g.getService(UUID.fromString(SVC_CMD))?.getCharacteristic(UUID.fromString(CHR_CMD))
            if (measChr == null || cmdChr == null) {
                Log.w(TAG, "Missing measurement/command characteristics, disconnecting")
                try {
                    g.disconnect()
                } catch (e: Exception) {
                }
                return
            }
            try {
                g.setCharacteristicNotification(measChr, true)
                measChr.getDescriptor(UUID.fromString(CCCD))?.let { cccd ->
                    cccd.value = byteArrayOf(0x01, 0x00)
                    g.writeDescriptor(cccd)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Enable notification failed", e)
            }
            pendingWrites = mutableListOf(
                buildUserPacket(ctx),
                buildSetTimePacket(),
                MAGIC_START
            )
            writeNext(g, cmdChr)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(g: BluetoothGatt, chr: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write failed status=$status")
                connecting = false
                return
            }
            writeNext(g, chr)
        }

        @SuppressLint("MissingPermission")
        private fun writeNext(g: BluetoothGatt, chr: BluetoothGattCharacteristic) {
            if (pendingWrites.isEmpty()) {
                connecting = false
                Log.d(TAG, "Scale bridge setup complete")
                return
            }
            val packet = pendingWrites.removeAt(0)
            Log.d(TAG, "Writing packet: ${packet.joinToString { "%02X".format(it) }}")
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(chr, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                chr.value = packet
                @Suppress("DEPRECATION")
                g.writeCharacteristic(chr)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(g: BluetoothGatt, chr: BluetoothGattCharacteristic, value: ByteArray) {
            handleMeasurement(chr, value)
        }

        @Suppress("DEPRECATION", "MissingPermission")
        override fun onCharacteristicChanged(g: BluetoothGatt, chr: BluetoothGattCharacteristic) {
            chr.value?.let { handleMeasurement(chr, it) }
        }
    }

    private fun handleMeasurement(chr: BluetoothGattCharacteristic, frame: ByteArray) {
        val ctx = appContext ?: return
        if (chr.uuid.toString() != CHR_MEAS || frame.size < 18) return
        // packet_type: 0x01 = measuring (unstable), 0x02 = measured (final)
        if (frame[3].toInt() and 0xFF != 0x02) return
        val weight = fromU16Be(frame, 13) / 100.0f
        if (weight <= 0f || !weight.isFinite()) return
        var ts = fromU32Be(frame, 5) * 1000L
        if (ts < 315532800000L) ts = System.currentTimeMillis()
        val protocolVer = frame[1].toInt() and 0xFF
        val resistance = fromU16Be(frame, 15)

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val height = prefs.getString(ctx, R.string.settings_yunmai_height, R.string.settings_yunmai_height_def)?.toIntOrNull() ?: 175
        val age = prefs.getString(ctx, R.string.settings_yunmai_age, R.string.settings_yunmai_age_def)?.toIntOrNull() ?: 30
        val male = prefs.getString(ctx, R.string.settings_yunmai_sex, R.string.settings_yunmai_sex_def) != "female"

        val fat: Float = if (protocolVer >= 0x1E && frame.size >= 19) {
            fromU16Be(frame, 17) / 100.0f
        } else {
            getFat(age, weight, resistance, height.toFloat(), male)
        }

        val result = YunmaiWeightResult(
            address = gatt?.device?.address ?: "",
            timestamp = ts,
            weight = weight,
            fat = fat.takeIf { it > 0f && it.isFinite() }
        )
        if (result.fat != null) {
            result.copy(
                muscle = getMuscle(result.fat, fitness = false),
                water = getWater(result.fat),
                bone = getBoneMass(getMuscle(result.fat, false), weight, height.toFloat(), male),
                lbm = getLeanBodyMass(weight, result.fat),
                visceralFat = getVisceralFat(result.fat, age, male, fitness = false)
            ).let { publishMeasurement(ctx, it) }
        } else {
            publishMeasurement(ctx, result)
        }
    }

    private fun publishMeasurement(context: Context, m: YunmaiWeightResult) {
        if (isDuplicate(m)) {
            Log.d(TAG, "Duplicate measurement skipped: weight=${m.weight} kg, fat=${m.fat}")
            return
        }
        lastMeasurement = m
        Log.d(TAG, "Measurement: weight=${m.weight} kg, fat=${m.fat}, water=${m.water}, muscle=${m.muscle}, bone=${m.bone}, lbm=${m.lbm}, visceral=${m.visceralFat}")
        postMeasurement(context, m)
    }

    private fun isDuplicate(new: YunmaiWeightResult): Boolean {
        val existing = lastMeasurement ?: return false
        val timeDiff = Math.abs(new.timestamp - existing.timestamp)
        if (timeDiff > 2000) return false
        if (Math.abs(new.weight - existing.weight) > 0.01f) return false
        if (new.fat != null && existing.fat != null && Math.abs(new.fat - existing.fat) > 0.01f) return false
        return true
    }

    private fun postMeasurement(context: Context, m: YunmaiWeightResult) {
        val webhook = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(context, R.string.settings_webhook, 0)
        if (TextUtils.isEmpty(webhook)) {
            Log.w(TAG, "No webhook set, skipping measurement upload")
            return
        }
        Thread {
            try {
                val obj = JSONObject()
                obj.put("type", "yunmai_scale")
                obj.put("address", m.address)
                obj.put("timestamp", m.timestamp)
                obj.put("weight", round2(m.weight))
                m.fat?.let { obj.put("fat", round2(it)) }
                m.muscle?.let { obj.put("muscle", round2(it)) }
                m.water?.let { obj.put("water", round2(it)) }
                m.bone?.let { obj.put("bone", round2(it)) }
                m.lbm?.let { obj.put("lbm", round2(it)) }
                m.visceralFat?.let { obj.put("visceral_fat", round2(it)) }
                val conn = URL(webhook).openConnection() as HttpURLConnection
                try {
                    conn.doOutput = true
                    conn.setChunkedStreamingMode(0)
                    conn.requestMethod = "POST"
                    conn.addRequestProperty("content-type", "application/json")
                    conn.outputStream.bufferedWriter().let {
                        it.write(obj.toString())
                        it.close()
                    }
                    conn.inputStream.bufferedReader().let {
                        it.readText()
                        it.close()
                    }
                    Log.d(TAG, "Measurement posted: $obj")
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error posting measurement", e)
            }
        }.start()
    }

    // --- Protocol helpers ------------------------------------------------------

    private fun buildUserPacket(ctx: Context): ByteArray {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val height = prefs.getString(ctx, R.string.settings_yunmai_height, R.string.settings_yunmai_height_def)?.toIntOrNull() ?: 175
        val age = prefs.getString(ctx, R.string.settings_yunmai_age, R.string.settings_yunmai_age_def)?.toIntOrNull() ?: 30
        val male = prefs.getString(ctx, R.string.settings_yunmai_sex, R.string.settings_yunmai_sex_def) != "female"
        val sex: Byte = if (male) 0x01 else 0x02
        val payload = byteArrayOf(
            0x0D, 0x12, 0x10, 0x01, 0x00, 0x00,
            0x00, 0x01,
            height.toByte(),
            sex,
            age.toByte(),
            0x55, 0x5A, 0x00, 0x00,
            0x01, 0x00,
            0x00
        )
        payload[payload.lastIndex] = xorChecksum(payload, start = 1, endExclusive = payload.lastIndex)
        return payload
    }

    private fun buildSetTimePacket(): ByteArray {
        val unix = System.currentTimeMillis() / 1000L
        val payload = byteArrayOf(
            0x0D, 0x0D, 0x11,
            ((unix shr 24) and 0xFF).toByte(), ((unix shr 16) and 0xFF).toByte(),
            ((unix shr 8) and 0xFF).toByte(), (unix and 0xFF).toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00,
            0x00
        )
        payload[payload.lastIndex] = xorChecksum(payload, start = 1, endExclusive = payload.lastIndex)
        return payload
    }

    private fun xorChecksum(bytes: ByteArray, start: Int, endExclusive: Int): Byte {
        var acc = 0
        for (i in start until endExclusive) acc = acc xor (bytes[i].toInt() and 0xFF)
        return acc.toByte()
    }

    private fun fromU16Be(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)
    }

    private fun fromU32Be(b: ByteArray, offset: Int): Long {
        return ((b[offset].toLong() and 0xFF) shl 24) or
                ((b[offset + 1].toLong() and 0xFF) shl 16) or
                ((b[offset + 2].toLong() and 0xFF) shl 8) or
                (b[offset + 3].toLong() and 0xFF)
    }

    private fun round2(v: Float): Float = (v * 100.0f).roundToInt() / 100.0f

    // --- Body composition (ported from OpenScale YunmaiLib) --------------------

    private fun getFat(age: Int, weightKg: Float, resistance: Int, heightCm: Float, male: Boolean): Float {
        var r = (resistance - 100.0f) / 100.0f
        val h = heightCm / 100.0f
        if (r >= 1) r = sqrt(r)
        var fat = (weightKg * 1.5f / h / h) + (age * 0.08f)
        if (male) fat -= 10.8f
        fat = (fat - 7.4f) + r
        if (fat < 5.0f || fat > 75.0f) fat = 0.0f
        return fat
    }

    private fun getWater(fatPct: Float): Float =
        ((100.0f - fatPct) * 0.726f * 100.0f + 0.5f) / 100.0f

    private fun getMuscle(fatPct: Float, fitness: Boolean): Float =
        (((100.0f - fatPct) * (if (fitness) 0.7f else 0.67f) * 100.0f) + 0.5f) / 100.0f

    private fun getBoneMass(musclePct: Float, weightKg: Float, heightCm: Float, male: Boolean): Float {
        val h = heightCm - 170.0f
        val bone = if (male)
            ((weightKg * (musclePct / 100.0f) * 4.0f) / 7.0f * 0.22f * 0.6f) + (h / 100.0f)
        else
            ((weightKg * (musclePct / 100.0f) * 4.0f) / 7.0f * 0.34f * 0.45f) + (h / 100.0f)
        return ((bone * 10.0f) + 0.5f) / 10.0f
    }

    private fun getLeanBodyMass(weightKg: Float, fatPct: Float): Float =
        weightKg * (100.0f - fatPct) / 100.0f

    private fun getVisceralFat(fatPct: Float, age: Int, male: Boolean, fitness: Boolean): Float {
        if (!fitness) {
            var f = fatPct
            val a = if (age < 18 || age > 120) 18 else age
            f -= when {
                male && a < 40 -> 21.0f
                male && a < 60 -> 22.0f
                male -> 24.0f
                a < 40 -> 34.0f
                a < 60 -> 35.0f
                else -> 36.0f
            }
            val d = if (male) 1.4f else 1.8f
            val vf = (f / d) + 9.5f
            return when {
                vf < 1.0f -> 1.0f
                vf > 30.0f -> 30.0f
                else -> vf
            }
        }
        val vf = if (fatPct > 15.0f) (fatPct - 15.0f) / 1.1f + 12.0f else -1 * (15.0f - fatPct) / 1.4f + 12.0f
        return when {
            vf < 1.0f -> 1.0f
            vf > 9.0f -> 9.0f
            else -> vf
        }
    }
}
