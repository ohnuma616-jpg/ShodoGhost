#if canImport(Network)
import Foundation
import Network

/// iPhone の待受。グラスがホットスポット越しに繋ぐ。127.0.0.1 には縛らない。
public final class NwWifiListener: WifiBurstLink, @unchecked Sendable {
    public private(set) var boundPort: Int = 0
    private let queue = DispatchQueue(label: "dev.shodoghost.wifi")
    private let lock = NSLock()
    private var listener: NWListener?
    private var pending: NWConnection?
    private var acceptWait: DispatchSemaphore?

    public init() {}

    public func listen(port: Int) throws {
        let nwPort: NWEndpoint.Port
        if port == 0 {
            nwPort = .any
        } else if let exact = NWEndpoint.Port(rawValue: UInt16(port)) {
            nwPort = exact
        } else {
            throw ProtocolError(.wifiTimeout, "port")
        }
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters, on: nwPort)
        let ready = NetSignal()
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                ready.signal()
            case .failed(let error):
                ready.error = error
                ready.fail()
                ready.signal()
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.take(connection)
        }
        listener.start(queue: queue)
        if !ready.wait(5) || ready.failed {
            listener.cancel()
            throw ProtocolError(.wifiTimeout, "listen")
        }
        let actual = Int(listener.port?.rawValue ?? 0)
        guard actual > 0 else {
            listener.cancel()
            throw ProtocolError(.wifiTimeout, "port")
        }
        self.listener = listener
        boundPort = actual
    }

    public func stop() {
        lock.lock()
        let current = listener
        let connection = pending
        listener = nil
        pending = nil
        lock.unlock()
        current?.cancel()
        connection?.cancel()
    }

    public func receive(token: [UInt8], expect: CaptureReady, timeoutSeconds: Double) throws -> [UInt8] {
        let connection = try nextConnection(timeoutSeconds)
        let ready = NetSignal()
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                ready.signal()
            case .failed:
                ready.fail()
                ready.signal()
            default:
                break
            }
        }
        connection.start(queue: queue)
        if !ready.wait(timeoutSeconds) || ready.failed {
            connection.cancel()
            throw ProtocolError(.wifiTimeout, "accept")
        }
        defer { connection.cancel() }
        return try WifiExchange.receive(token: token, expect: expect, read: { count in
            try self.readExact(connection, count, timeoutSeconds)
        }, write: { bytes in
            try self.writeAll(connection, bytes, timeoutSeconds)
        })
    }

    private func take(_ connection: NWConnection) {
        lock.lock()
        if pending == nil {
            pending = connection
            acceptWait?.signal()
        } else {
            connection.cancel()
        }
        lock.unlock()
    }

    private func nextConnection(_ timeoutSeconds: Double) throws -> NWConnection {
        lock.lock()
        if let connection = pending {
            pending = nil
            lock.unlock()
            return connection
        }
        let wait = DispatchSemaphore(value: 0)
        acceptWait = wait
        lock.unlock()
        if wait.wait(timeout: .now() + timeoutSeconds) == .timedOut {
            throw ProtocolError(.wifiTimeout, "accept")
        }
        lock.lock()
        let connection = pending
        pending = nil
        acceptWait = nil
        lock.unlock()
        guard let connection else { throw ProtocolError(.wifiTimeout, "accept") }
        return connection
    }

    private func readExact(_ connection: NWConnection, _ count: Int, _ timeoutSeconds: Double) throws -> [UInt8] {
        if count == 0 { return [] }
        var out: [UInt8] = []
        while out.count < count {
            let chunk = NetSignal()
            connection.receive(minimumIncompleteLength: 1, maximumLength: count - out.count) { data, _, isComplete, error in
                if let data { chunk.bytes = [UInt8](data) }
                if error != nil || (isComplete && chunk.bytes.isEmpty) { chunk.fail() }
                chunk.signal()
            }
            if !chunk.wait(timeoutSeconds) || chunk.failed || chunk.bytes.isEmpty {
                throw ProtocolError(.wifiTimeout, "read")
            }
            out.append(contentsOf: chunk.bytes)
        }
        return out
    }

    private func writeAll(_ connection: NWConnection, _ bytes: [UInt8], _ timeoutSeconds: Double) throws {
        let done = NetSignal()
        connection.send(content: Data(bytes), completion: .contentProcessed { error in
            if error != nil { done.fail() }
            done.signal()
        })
        if !done.wait(timeoutSeconds) || done.failed {
            throw ProtocolError(.wifiTimeout, "write")
        }
    }
}

private final class NetSignal: @unchecked Sendable {
    private let semaphore = DispatchSemaphore(value: 0)
    var failed = false
    var error: NWError?
    var bytes: [UInt8] = []

    func fail() { failed = true }

    func signal() { semaphore.signal() }

    func wait(_ seconds: Double) -> Bool {
        semaphore.wait(timeout: .now() + seconds) == .success
    }
}
#endif
