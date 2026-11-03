package com.vox.castsink

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * 车机直连蓝牙 OBD（ELM327 / V-LINK，经典蓝牙 SPP）。
 * 连上后交替轮询 010C(转速) / 010D(车速)，直接喂 VehicleState，跳过 HA 的秒级延迟。
 */
object ObdBt {

    private const val TAG = "ObdBt"
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val NAMES = listOf("V-LINK", "VLINK", "OBD", "ELM", "VGATE")
    private const val HARD_MAC = "<MAC>"

    @Volatile var status = "未连接"
    @Volatile var hz = 0f
    @Volatile var connected = false
    @Volatile var devName = ""
    @Volatile var devAddr = ""

    private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        appCtx = ctx.applicationContext
        Thread {
            while (true) {
                try { session() } catch (t: Throwable) {
                    connected = false
                    status = "ERR " + (t.message ?: t.javaClass.simpleName)
                    Log.w(TAG, status)
                }
                try { Thread.sleep(4000) } catch (_: InterruptedException) { }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun findDevice(ad: BluetoothAdapter): BluetoothDevice? {
        val bonded = try { ad.bondedDevices } catch (_: SecurityException) { return null } ?: return null
        for (d in bonded) {
            val n = try { d.name } catch (_: SecurityException) { null } ?: continue
            val up = n.uppercase()
            if (NAMES.any { up.contains(it) }) return d
        }
        return null
    }

    private fun discoverAndBond(ad: BluetoothAdapter) {
        val ctx = appCtx ?: return
        val filter = android.content.IntentFilter(BluetoothDevice.ACTION_FOUND)
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                if (i.action != BluetoothDevice.ACTION_FOUND) return
                @Suppress("DEPRECATION")
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val n = try { d.name } catch (_: SecurityException) { null } ?: return
                if (NAMES.any { n.uppercase().contains(it) }) {
                    status = "发起配对 " + n
                    try { d.createBond() } catch (_: Throwable) { }
                }
            }
        }
        try { ctx.registerReceiver(rx, filter) } catch (_: Throwable) { }
        try { ad.startDiscovery() } catch (_: Throwable) { }
        try { Thread.sleep(12000) } catch (_: InterruptedException) { }
        try { ad.cancelDiscovery() } catch (_: Throwable) { }
        try { ctx.unregisterReceiver(rx) } catch (_: Throwable) { }
    }

    private var appCtx: Context? = null

    private fun openSocket(dev: BluetoothDevice): BluetoothSocket {
        val bonded = try { dev.bondState == BluetoothDevice.BOND_BONDED } catch (_: Throwable) { false }
        // ELM327 通常不要求鉴权：未绑定时用 insecure RFCOMM，绕开系统配对框
        return try {
            if (bonded) dev.createRfcommSocketToServiceRecord(SPP)
            else dev.createInsecureRfcommSocketToServiceRecord(SPP)
        } catch (t: Throwable) {
            try {
                val m = dev.javaClass.getMethod(
                    if (bonded) "createRfcommSocket" else "createInsecureRfcommSocket",
                    Int::class.javaPrimitiveType
                )
                m.invoke(dev, 1) as BluetoothSocket
            } catch (t2: Throwable) {
                dev.createInsecureRfcommSocketToServiceRecord(SPP)
            }
        }
    }

    private fun session() {
        val ctx = appCtx
        if (ctx != null && android.os.Build.VERSION.SDK_INT >= 31) {
            val connOk = ctx.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!connOk) { status = "缺蓝牙权限"; return }
        }
        val ad = BluetoothAdapter.getDefaultAdapter() ?: run { status = "无蓝牙适配器"; return }
        if (!ad.isEnabled) { status = "蓝牙未开"; return }
        var dev = findDevice(ad)
        if (dev == null) {
            // 没有配对记录：直接按 MAC 绑定（不需要 discovery / 位置权限）
            try {
                val d = ad.getRemoteDevice(HARD_MAC)
                status = "绑定 " + HARD_MAC
                try { d.createBond() } catch (_: Throwable) { }
                // 只等 6 秒；配不上也别卡死，后面走 insecure RFCOMM 直连
                var waited = 0
                while (waited < 6000 && d.bondState != BluetoothDevice.BOND_BONDED) {
                    try { Thread.sleep(500) } catch (_: InterruptedException) { }
                    waited += 500
                }
                dev = d
                status = if (d.bondState == BluetoothDevice.BOND_BONDED) "已绑定" else "未绑定,试直连"
            } catch (t: Throwable) {
                status = "绑定异常 " + (t.message ?: t.javaClass.simpleName)
            }
        }
        if (dev == null) { status = "未配对 OBD"; return }
        devName = try { dev.name ?: "" } catch (_: SecurityException) { "" }
        devAddr = dev.address
        status = "连接 " + (if (devName.isNotEmpty()) devName else devAddr)

        try { ad.cancelDiscovery() } catch (_: Throwable) { }
        val sock = openSocket(dev)
        try {
            sock.connect()
            connected = true
            status = "已连接 " + (if (devName.isNotEmpty()) devName else devAddr)

            val out: OutputStream = sock.outputStream
            val inp: InputStream = sock.inputStream
            init(out, inp)

            var frames = 0
            var t0 = System.currentTimeMillis()
            while (true) {
                val rpm = query(out, inp, "010C")
                if (rpm != null && rpm.size >= 4) {
                    val v = ((rpm[2] * 256 + rpm[3]) / 4f)
                    VehicleState.obdRpm = v
                }
                val sp = query(out, inp, "010D")
                if (sp != null && sp.size >= 3) {
                    VehicleState.obdSpeed = sp[2].toFloat()
                }
                frames++
                val dt = System.currentTimeMillis() - t0
                if (dt >= 1000) {
                    hz = frames * 1000f / dt
                    frames = 0
                    t0 = System.currentTimeMillis()
                }
                if (!sock.isConnected) break
            }
        } finally {
            connected = false
            try { sock.close() } catch (_: Throwable) { }
        }
    }

    private fun init(out: OutputStream, inp: InputStream) {
        for (c in listOf("ATZ", "ATE0", "ATS0", "ATSP7")) {
            out.write((c + "\r").toByteArray()); out.flush()
            readRaw(inp, 400L)
        }
    }

    /** 返回去掉 echo/空白后的十六进制字节，如 41 0C 1A F8 -> [0x41,0x0C,0x1A,0xF8] */
    private fun query(out: OutputStream, inp: InputStream, pid: String): IntArray? {
        out.write((pid + "\r").toByteArray()); out.flush()
        val raw = readRaw(inp, 250L)
        val hex = raw.replace(">", " ").trim()
        val bytes = ArrayList<Int>()
        for (tok in hex.split(Regex("\\s+"))) {
            if (tok.length == 2) tok.toIntOrNull(16)?.let { bytes.add(it) }
        }
        if (bytes.isEmpty()) return null
        // 找到 41(0x41) 起始
        val start = bytes.indexOfFirst { it == 0x41 }
        if (start < 0) return null
        return bytes.subList(start, bytes.size).toIntArray()
    }

    private fun readRaw(inp: InputStream, timeoutMs: Long): String {
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val avail = try { inp.available() } catch (_: Throwable) { 0 }
            if (avail > 0) {
                val b = ByteArray(avail)
                val k = try { inp.read(b) } catch (_: Throwable) { -1 }
                if (k > 0) {
                    sb.append(String(b, 0, k))
                    if (sb.contains(">")) break
                }
            } else {
                try { Thread.sleep(10) } catch (_: InterruptedException) { }
            }
        }
        return sb.toString()
    }
}
