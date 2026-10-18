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
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        central.stopScan()
        self.peripheral = peripheral
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([CBUUID(string: GattProfile.service)])
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        self.peripheral = nil
        command = nil
        status = nil
        touch = nil
        payload = nil
        sessionInfo = nil
        sink?.radioDidDrop()
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == CBUUID(string: GattProfile.service) }) else {
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
        let attMtu = peripheral.maximumWriteValueLength(for: .withResponse) + 3
        let callback = prepareCallback
        prepareCallback = nil
        callback?(attMtu)
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
        guard manager?.state == .poweredOn, prepareCallback != nil, peripheral == nil else { return }
        manager?.scanForPeripherals(
            withServices: [CBUUID(string: GattProfile.service)],
            options: nil
        )
    }

    private func write(_ value: [UInt8], _ characteristic: CBCharacteristic?, type: CBCharacteristicWriteType) {
        queue.async {
            guard let peripheral = self.peripheral, let characteristic else { return }
            peripheral.writeValue(Data(value), for: characteristic, type: type)
        }
    }
}
#endif
