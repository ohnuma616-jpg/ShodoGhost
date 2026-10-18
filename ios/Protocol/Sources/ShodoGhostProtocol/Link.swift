public enum LinkSignal: Sendable {
    case start
    case peerFound
    case gattConnected
    case helloOk
    case helloRejected
    case beginWifi
    case socketUp
    case transferFinished
    case wifiTornDown
    case linkLost
    case timeout
    case retry
    case stop
}

public enum LinkEffect: Equatable, Sendable {
    case openDiscovery
    case closeDiscovery
    case requestMtuAndHello
    case acquireRadios
    case openWifiSocket
    case closeWifiSocket
    case releaseRadios
    case scheduleRetry
    case cancelRetry
}

public struct LinkStep: Equatable, Sendable {
    public let phase: LinkPhase
    public let accepted: Bool
    public let effects: [LinkEffect]
}

public enum RetrySchedule {
    private static let delaysMs: [UInt64] = [1_000, 2_000, 4_000, 8_000, 15_000]

    public static func delayMs(attempt: Int) -> UInt64 {
        precondition(attempt >= 0)
        return delaysMs[min(attempt, delaysMs.count - 1)]
    }
}

public enum LinkStateMachine {
    public static func step(phase: LinkPhase, signal: LinkSignal) -> LinkStep {
        guard let next = accept(phase: phase, signal: signal) else {
            return LinkStep(phase: phase, accepted: false, effects: [])
        }
        return LinkStep(phase: next.0, accepted: true, effects: next.1)
    }

    private static func accept(phase: LinkPhase, signal: LinkSignal) -> (LinkPhase, [LinkEffect])? {
        switch phase {
        case .idle:
            switch signal {
            case .start: return (.discovering, [.openDiscovery])
            default: return nil
            }
        case .discovering:
            switch signal {
            case .peerFound: return (.connecting, [.closeDiscovery])
            case .stop: return (.idle, [.closeDiscovery])
            case .linkLost, .timeout: return (.backoff, [.closeDiscovery, .scheduleRetry])
            default: return nil
            }
        case .connecting:
            switch signal {
            case .gattConnected: return (.negotiating, [.requestMtuAndHello])
            case .stop: return (.idle, [.closeDiscovery])
            case .linkLost, .timeout: return (.backoff, [.scheduleRetry])
            default: return nil
            }
        case .negotiating:
            switch signal {
            case .helloOk: return (.bleReady, [])
            case .helloRejected, .timeout, .linkLost: return (.backoff, [.scheduleRetry])
            case .stop: return (.idle, [])
            default: return nil
            }
        case .bleReady:
            switch signal {
            case .beginWifi: return (.wifiWake, [.acquireRadios, .openWifiSocket])
            case .linkLost: return (.backoff, [.scheduleRetry])
            case .stop: return (.idle, [])
            default: return nil
            }
        case .wifiWake:
            switch signal {
            case .socketUp: return (.transfer, [])
            case .timeout: return (.bleReady, [.closeWifiSocket, .releaseRadios])
            case .linkLost: return (.backoff, [.closeWifiSocket, .releaseRadios, .scheduleRetry])
            case .stop: return (.wifiTeardown, [.closeWifiSocket, .releaseRadios])
            default: return nil
            }
        case .transfer:
            switch signal {
            case .transferFinished, .timeout, .stop:
                return (.wifiTeardown, [.closeWifiSocket, .releaseRadios])
            case .linkLost:
                return (.backoff, [.closeWifiSocket, .releaseRadios, .scheduleRetry])
            default:
                return nil
            }
        case .wifiTeardown:
            switch signal {
            case .wifiTornDown, .timeout: return (.bleReady, [])
            case .linkLost: return (.backoff, [.scheduleRetry])
            default: return nil
            }
        case .backoff:
            switch signal {
            case .retry: return (.discovering, [.cancelRetry, .openDiscovery])
            case .stop: return (.idle, [.cancelRetry, .closeDiscovery])
            default: return nil
            }
        }
    }
}

public enum RadioKind: Hashable, Sendable {
    case ble
    case wifi
    case wakeLock
    case camera
}

/// どの相でどのラジオを持ってよいか。実機の mAh は測っていない。プロセスをまたいでは残さない。
public enum PowerPolicy {
    public static let persistsAcrossProcessDeath = false

    public static func allows(phase: LinkPhase, cameraOpen: Bool, displayAsleep: Bool) -> Set<RadioKind> {
        if phase == .idle { return [] }
        var held: Set<RadioKind> = [.ble]
        let transferring = phase == .wifiWake || phase == .transfer
        if transferring && !displayAsleep {
            held.insert(.wifi)
            held.insert(.wakeLock)
        }
        if cameraOpen && phase == .bleReady && !displayAsleep {
            held.insert(.camera)
        }
        return held
    }
}
