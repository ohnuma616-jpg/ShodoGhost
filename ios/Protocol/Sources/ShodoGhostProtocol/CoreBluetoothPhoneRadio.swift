#if canImport(CoreBluetooth)
import CoreBluetooth
import Foundation

/// iPhone のセントラル。セッション本体は [PhoneSession] で、ここは電波だけを運ぶ。
public final class CoreBluetoothPhoneRadio: NSObject, PhoneRadio, CBCentralManagerDelegate, CBPeripheralDelegate {
    public static let restoreIdentifier = "dev.shodoghost.central"

    public weak var sink: PhoneRadioSink?
    private var manager: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var command: CBCharacteristic?
    private var status: CBCharacteristic?
    private var touch: CBCharacteristic?
    private var payload: CBCharacteristic?
    private var sessionInfo: CBCharacteristic?
    private var prepareCallback: ((Int) -> Void)?
    private var readCallback: (([UInt8]) -> Void)?
    private var connectTimer: DispatchSourceTimer?
    private let queue = DispatchQueue(label: "dev.shodoghost.bluetooth")

    public override init() {
        super.init()
    }

    public func start() {
        manager = CBCentralManager(
            delegate: self,
            queue: queue,
            options: [CBCentralManagerOptionRestoreIdentifierKey: Self.restoreIdentifier]
        )
    }

    public func prepare(mtu: Int, ready: @escaping (Int) -> Void) {
        queue.async {
            self.prepareCallback = ready
            if self.manager == nil {
                self.manager = CBCentralManager(
                    delegate: self,
                    queue: self.queue,
                    options: [CBCentralManagerOptionRestoreIdentifierKey: Self.restoreIdentifier]
                )
            }
            self.scanIfPoweredOn()
        }
    }

    public func readSessionInfo(ready: @escaping ([UInt8]) -> Void) {
        queue.async {
            guard let peripheral = self.peripheral, let sessionInfo = self.sessionInfo else { return }
            self.readCallback = ready
            peripheral.readValue(for: sessionInfo)
        }
    }

    public func writeCommand(_ value: [UInt8]) { write(value, command, type: .withResponse) }
    public func writeTouch(_ value: [UInt8]) { write(value, touch, type: .withoutResponse) }
    public func writePayload(_ value: [UInt8]) { write(value, payload, type: .withResponse) }

    public func close() {
        queue.async {
            if let peripheral = self.peripheral {
                self.manager?.cancelPeripheralConnection(peripheral)
            }
            self.manager?.stopScan()
        }
    }

    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            scanIfPoweredOn()
        }
    }

    public func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral]
        if let peripheral = restored?.first {
            self.peripheral = peripheral
            peripheral.delegate = self
        }
        followGate()
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        central.stopScan()
        cancelConnectTimeout()
        self.peripheral = peripheral
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
        armConnectTimeout()
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        cancelConnectTimeout()
        peripheral.discoverServices([CBUUID(string: GattProfile.service)])
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        release(peripheral, dropSession: false)
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        release(peripheral, dropSession: true)
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil,
            let service = peripheral.services?.first(where: { $0.uuid == CBUUID(string: GattProfile.service) })
        else {
            manager?.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.discoverCharacteristics(
            [
                CBUUID(string: GattProfile.command),
                CBUUID(string: GattProfile.status),
                CBUUID(string: GattProfile.touch),
                CBUUID(string: GattProfile.payload),
                CBUUID(string: GattProfile.sessionInfo),
            ],
            for: service
        )
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        for characteristic in service.characteristics ?? [] {
            switch characteristic.uuid.uuidString.lowercased() {
            case GattProfile.command: command = characteristic
            case GattProfile.status:
                status = characteristic
                peripheral.setNotifyValue(true, for: characteristic)
            case GattProfile.touch: touch = characteristic
            case GattProfile.payload:
                payload = characteristic
                peripheral.setNotifyValue(true, for: characteristic)
            case GattProfile.sessionInfo: sessionInfo = characteristic
            default: break
            }
        }
        if status == nil {
            finishPrepare(peripheral)
        }
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard characteristic.uuid == status?.uuid else { return }
        if error != nil || !characteristic.isNotifying {
            sink?.radioDidDrop()
            return
        }
        finishPrepare(peripheral)
    }

    private func finishPrepare(_ peripheral: CBPeripheral) {
        let callback = prepareCallback
        prepareCallback = nil
        guard let callback else { return }
        let attMtu = peripheral.maximumWriteValueLength(for: .withResponse) + 3
        callback(attMtu)
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        let bytes = [UInt8](characteristic.value ?? Data())
        if characteristic.uuid == sessionInfo?.uuid {
            let callback = readCallback
            readCallback = nil
            callback?(bytes)
            return
        }
        if characteristic.uuid == status?.uuid {
            sink?.radioDidNotifyStatus(bytes)
        } else if characteristic.uuid == payload?.uuid {
            sink?.radioDidNotifyPayload(bytes)
        }
    }

    private func scanIfPoweredOn() {
        followGate()
    }

    private func followGate() {
        guard let manager else { return }
        let known: KnownPeripheral
        if let peripheral {
            switch peripheral.state {
            case .connected:
                known = .connected
            case .connecting:
                known = .connecting
            default:
                known = .disconnected
            }
        } else {
            known = .absent
        }
        switch RadioGate.step(
            poweredOn: manager.state == .poweredOn,
            awaitingPrepare: prepareCallback != nil,
            known: known
        ) {
        case .hold:
            return
        case .scan:
            cancelConnectTimeout()
            manager.scanForPeripherals(
                withServices: [CBUUID(string: GattProfile.service)],
                options: nil
            )
        case .connect:
            if let peripheral, peripheral.state != .connecting {
                manager.connect(peripheral, options: nil)
            }
            armConnectTimeout()
        case .discoverServices:
            cancelConnectTimeout()
            manager.stopScan()
            peripheral?.discoverServices([CBUUID(string: GattProfile.service)])
        }
    }

    private func armConnectTimeout() {
        connectTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 4)
        timer.setEventHandler { [weak self] in
            self?.giveUpConnect()
        }
        connectTimer = timer
        timer.resume()
    }

    private func cancelConnectTimeout() {
        connectTimer?.cancel()
        connectTimer = nil
    }

    /// 覚えていた端末へ届かないときは、その接続を捨てて広告を探し直す。
    private func giveUpConnect() {
        cancelConnectTimeout()
        guard let peripheral, peripheral.state != .connected else { return }
        manager?.cancelPeripheralConnection(peripheral)
        if self.peripheral?.identifier == peripheral.identifier {
            self.peripheral = nil
        }
        clearCharacteristics()
        followGate()
    }

    private func release(_ peripheral: CBPeripheral, dropSession: Bool) {
        if self.peripheral?.identifier == peripheral.identifier {
            self.peripheral = nil
        }
        clearCharacteristics()
        if prepareCallback != nil {
            followGate()
            return
        }
        if dropSession {
            sink?.radioDidDrop()
        }
    }

    private func clearCharacteristics() {
        command = nil
        status = nil
        touch = nil
        payload = nil
        sessionInfo = nil
    }

    private func write(_ value: [UInt8], _ characteristic: CBCharacteristic?, type: CBCharacteristicWriteType) {
        queue.async {
            guard let peripheral = self.peripheral, let characteristic else { return }
            peripheral.writeValue(Data(value), for: characteristic, type: type)
        }
    }
}
#endif
