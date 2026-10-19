import Foundation

/// 覚えていた端末が残っていても、未接続なら探し直す。
public enum KnownPeripheral: Sendable {
    case absent
    case disconnected
    case connecting
    case connected
}

public enum RadioStep: Equatable, Sendable {
    case hold
    case scan
    case connect
    case discoverServices
}

public enum RadioGate {
    public static func step(poweredOn: Bool, awaitingPrepare: Bool, known: KnownPeripheral) -> RadioStep {
        guard poweredOn, awaitingPrepare else { return .hold }
        switch known {
        case .absent:
            return .scan
        case .disconnected, .connecting:
            return .connect
        case .connected:
            return .discoverServices
        }
    }
}
