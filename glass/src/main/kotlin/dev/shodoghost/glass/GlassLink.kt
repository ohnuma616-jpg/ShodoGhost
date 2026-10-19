package dev.shodoghost.glass

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import dev.shodoghost.protocol.BeginWifiSession
import dev.shodoghost.protocol.CalibrateCommit
import dev.shodoghost.protocol.CaptureNow
import dev.shodoghost.protocol.GattProfile
import dev.shodoghost.protocol.StrokePack
import dev.shodoghost.protocol.Limits
import dev.shodoghost.protocol.LinkPhase
import dev.shodoghost.protocol.link.GlassListener
import dev.shodoghost.protocol.link.GlassRadio
import dev.shodoghost.protocol.link.GlassSession
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * GATT ペリフェラル。広告名は ShodoGhost。
 * Command の書き込みは [GlassSession] へ渡し、撮影はロックの外で [onCapture] が行う。
 */
class GlassLink(
    private val context: Context,
    private val onCapture: (CaptureNow) -> Unit,
    private val onPhase: (LinkPhase) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onWifi: (BeginWifiSession) -> Unit,
    private val adviceSink: (List<String>) -> Unit,
    private val correctionSink: (StrokePack) -> Unit,
    private val exemplarSink: (StrokePack) -> Unit,
    private val cornerSink: (CalibrateCommit) -> Unit,
) {
    val session: GlassSession
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private var payloadChar: BluetoothGattCharacteristic? = null
    private var connected: BluetoothDevice? = null
    private val prepared = linkedMapOf<UUID, ByteArrayOutputStream>()
    private val notifyLock = Any()
    private val pending = ArrayDeque<PendingNotify>()
    private var notifyInFlight = false
    private var bondReceiver: BroadcastReceiver? = null
    private var serviceReady = false
    private var advertising = false

    private val radio = object : GlassRadio {
        override fun notifyStatus(frame: ByteArray) {
            enqueue(statusChar, frame)
            publishPhase()
        }

        override fun notifyPayload(frame: ByteArray) {
            enqueue(payloadChar, frame)
        }
    }

    init {
        session = GlassSession(
            radio,
            object : GlassListener() {
                override fun onCaptureRequested(request: CaptureNow) {
                    onCapture(request)
                }

                override fun onWifiBegin(session: BeginWifiSession) {
                    onWifi(session)
                }

                override fun onAdvice(lines: List<String>) {
                    adviceSink(lines)
                }

                override fun onStrokePack(pack: StrokePack) {
                    if (pack.correction) correctionSink(pack) else exemplarSink(pack)
                }

                override fun onCalibrateCommit(commit: CalibrateCommit) {
                    cornerSink(commit)
                }
            },
        )
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("BLEオフ")
            return
        }
        if (adapter.name != GattProfile.LOCAL_NAME) {
            Log.i(TAG, "name ${adapter.setName(GattProfile.LOCAL_NAME)}")
        }
        listenForBond()
        if (server == null) {
            server = manager.openGattServer(context, callback)
            if (server == null) {
                onStatus("サーバ不可")
                return
            }
            server?.addService(buildService())
        } else if (serviceReady) {
            advertise()
        }
        val began = session.phase == LinkPhase.IDLE || session.phase == LinkPhase.BACKOFF
        if (began) session.startAdvertising()
        if (began || !advertising) publishPhase()
    }

    fun stopAdvertising() {
        advertising = false
        advertiser?.stopAdvertising(advertiseCallback)
    }

    fun close() {
        stopAdvertising()
        connected?.let { server?.cancelConnection(it) }
        server?.close()
        server = null
        serviceReady = false
        connected = null
        bondReceiver?.let { context.unregisterReceiver(it) }
        bondReceiver = null
        if (session.phase != LinkPhase.IDLE) session.onLinkDown()
        publishPhase()
    }

    private fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(uuid(GattProfile.SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(writable(GattProfile.COMMAND, writeNoResponse = false))
        statusChar = notifiable(GattProfile.STATUS)
        service.addCharacteristic(statusChar)
        service.addCharacteristic(writable(GattProfile.TOUCH, writeNoResponse = true))
        payloadChar = notifiable(GattProfile.PAYLOAD, alsoWrite = true)
        service.addCharacteristic(payloadChar)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                uuid(GattProfile.SESSION_INFO),
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            ),
        )
        return service
    }

    private fun writable(name: String, writeNoResponse: Boolean): BluetoothGattCharacteristic {
        val property = if (writeNoResponse) {
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.PROPERTY_WRITE
        }
        return BluetoothGattCharacteristic(uuid(name), property, BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED)
    }

    private fun notifiable(name: String, alsoWrite: Boolean = false): BluetoothGattCharacteristic {
        var properties = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        var permissions = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        if (alsoWrite) {
            properties = properties or BluetoothGattCharacteristic.PROPERTY_WRITE
            permissions = permissions or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        }
        val characteristic = BluetoothGattCharacteristic(uuid(name), properties, permissions)
        characteristic.addDescriptor(
            BluetoothGattDescriptor(
                uuid(GattProfile.CCCD),
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        return characteristic
    }

    @SuppressLint("MissingPermission")
    private fun advertise() {
        val adapter = manager.adapter ?: return
        val next = adapter.bluetoothLeAdvertiser
        if (next == null) {
            onStatus("広告不可")
            return
        }
        if (advertising) return
        advertiser = next
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(uuid(GattProfile.SERVICE)))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        next.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            onStatus("広告中")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            onStatus("失敗$errorCode")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.i(TAG, "service $status")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            serviceReady = true
            advertise()
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "link $newState status $status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val current = connected
                if (current != null && current.address != device.address) {
                    server?.cancelConnection(device)
                    return
                }
                connected = device
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    noteBond(device)
                } else {
                    onStatus("ペア待ち")
                    if (device.bondState == BluetoothDevice.BOND_NONE) device.createBond()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && connected?.address == device.address) {
                connected = null
                synchronized(notifyLock) {
                    notifyInFlight = false
                    pending.clear()
                }
                session.onLinkDown()
                publishPhase()
                advertiser?.stopAdvertising(advertiseCallback)
                advertising = false
                advertise()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "mtu $mtu")
            if (mtu < Limits.MIN_ATT_MTU) return
            session.onLinkUp(mtu)
            publishPhase()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            noteBond(device)
            val value = session.sessionInfoFrame()
            val slice = if (offset >= value.size) ByteArray(0) else value.copyOfRange(offset, value.size)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (preparedWrite) {
                prepared.getOrPut(characteristic.uuid) { ByteArrayOutputStream() }.write(value)
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (!preparedWrite) dispatch(device, characteristic.uuid, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            noteBond(device)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            if (execute) {
                val queued = prepared.toMap()
                prepared.clear()
                queued.forEach { (id, buf) -> dispatch(device, id, buf.toByteArray()) }
            } else {
                prepared.clear()
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val follow = synchronized(notifyLock) {
                notifyInFlight = false
                claim()
            }
            follow?.let { send(it) }
        }
    }

    private fun dispatch(device: BluetoothDevice, id: UUID, value: ByteArray) {
        noteBond(device)
        val uuid = id.toString()
        if (uuid == GattProfile.COMMAND || uuid == GattProfile.TOUCH || uuid == GattProfile.PAYLOAD) {
            session.onBonded()
            wakeForWrite()
        }
        when (uuid) {
            GattProfile.COMMAND -> session.onCommand(value)
            GattProfile.TOUCH -> session.onTouch(value)
            GattProfile.PAYLOAD -> session.onPayload(value)
            else -> Unit
        }
        publishPhase()
    }

    /** MTU 通知が再接続で来ないことがある。書けた時点でリンクは生きている。 */
    private fun wakeForWrite() {
        val phase = session.phase
        if (phase == LinkPhase.IDLE ||
            phase == LinkPhase.DISCOVERING ||
            phase == LinkPhase.CONNECTING ||
            phase == LinkPhase.BACKOFF
        ) {
            session.onLinkUp(Limits.MIN_ATT_MTU)
        }
    }

    private fun noteBond(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) session.onBonded()
    }

    private fun listenForBond() {
        if (bondReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                if (device != null && device.address == connected?.address && state == BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "bonded")
                    session.onBonded()
                    publishPhase()
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        bondReceiver = receiver
    }

    private fun enqueue(characteristic: BluetoothGattCharacteristic?, frame: ByteArray) {
        val target = characteristic ?: return
        val next = synchronized(notifyLock) {
            pending.add(PendingNotify(target, frame.copyOf()))
            claim()
        }
        next?.let { send(it) }
    }

    private fun claim(): PendingNotify? {
        if (notifyInFlight) return null
        val next = pending.removeFirstOrNull() ?: return null
        notifyInFlight = true
        return next
    }

    @SuppressLint("MissingPermission")
    private fun send(next: PendingNotify) {
        val device = connected
        val gatt = server
        if (device == null || gatt == null) {
            synchronized(notifyLock) { notifyInFlight = false }
            return
        }
        val sent = if (Build.VERSION.SDK_INT >= 33) {
            gatt.notifyCharacteristicChanged(device, next.characteristic, false, next.value) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            next.characteristic.value = next.value
            @Suppress("DEPRECATION")
            gatt.notifyCharacteristicChanged(device, next.characteristic, false)
        }
        if (!sent) {
            Log.i(TAG, "notify failed")
            val follow = synchronized(notifyLock) {
                notifyInFlight = false
                claim()
            }
            follow?.let { send(it) }
        }
    }

    private fun publishPhase() {
        onPhase(session.phase)
    }

    private fun uuid(text: String): UUID = UUID.fromString(text)

    private data class PendingNotify(
        val characteristic: BluetoothGattCharacteristic,
        val value: ByteArray,
    )

    private companion object {
        const val TAG = "ShodoGlass"
    }
}
