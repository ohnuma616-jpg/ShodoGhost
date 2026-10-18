import Foundation
#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif

enum AttOp {
    static let command = 1
    static let status = 2
    static let touch = 3
    static let payloadWrite = 4
    static let payloadNotify = 5
    static let readReq = 6
    static let readRsp = 7
    static let linkUp = 8
    static let linkDown = 9
    static let bond = 10
}

public final class TcpPhoneRadio: PhoneRadio, @unchecked Sendable {
    public weak var sink: PhoneRadioSink?
    private let fd: Int32
    private let writeLock = NSLock()
    private let callbackLock = NSLock()
    private var readCallback: (([UInt8]) -> Void)?
    private var closed = false

    public static func connect(host: String, port: Int) throws -> TcpPhoneRadio {
        let fd = socket(AF_INET, streamType, 0)
        guard fd >= 0 else { throw ProtocolError(.badFrame, "socket") }
        var yes: Int32 = 1
        setsockopt(fd, tcpProtocol, tcpNoDelay, &yes, socklen_t(MemoryLayout.size(ofValue: yes)))
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = UInt16(port).bigEndian
        guard inet_pton(AF_INET, host, &addr.sin_addr) == 1 else {
            _ = sysClose(fd)
            throw ProtocolError(.badFrame, "host")
        }
        let rc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                sysConnect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard rc == 0 else {
            _ = sysClose(fd)
            throw ProtocolError(.wifiTimeout, "connect")
        }
        return TcpPhoneRadio(fd: fd)
    }

    private init(fd: Int32) {
        self.fd = fd
        Thread.detachNewThread { [weak self] in
            self?.readLoop()
        }
    }

    public func prepare(mtu: Int, ready: @escaping (Int) -> Void) {
        send(AttOp.bond, [])
        var payload = [UInt8](repeating: 0, count: 2)
        payload[0] = UInt8(mtu & 0xFF)
        payload[1] = UInt8((mtu >> 8) & 0xFF)
        send(AttOp.linkUp, payload)
        ready(mtu)
    }

    public func readSessionInfo(ready: @escaping ([UInt8]) -> Void) {
        callbackLock.lock()
        readCallback = ready
        callbackLock.unlock()
        send(AttOp.readReq, [])
    }

    public func writeCommand(_ value: [UInt8]) { send(AttOp.command, value) }
    public func writeTouch(_ value: [UInt8]) { send(AttOp.touch, value) }
    public func writePayload(_ value: [UInt8]) { send(AttOp.payloadWrite, value) }

    public func close() {
        writeLock.lock()
        if !closed {
            closed = true
            _ = sysShutdown(fd, shutRdwr)
            _ = sysClose(fd)
        }
        writeLock.unlock()
    }

    private func send(_ op: Int, _ payload: [UInt8]) {
        var packet = [UInt8](repeating: 0, count: 5 + payload.count)
        packet[0] = UInt8(op)
        let len = payload.count
        packet[1] = UInt8(len & 0xFF)
        packet[2] = UInt8((len >> 8) & 0xFF)
        packet[3] = UInt8((len >> 16) & 0xFF)
        packet[4] = UInt8((len >> 24) & 0xFF)
        payload.enumerated().forEach { packet[5 + $0.offset] = $0.element }
        writeLock.lock()
        if !closed {
            _ = packet.withUnsafeBytes { raw in
                writeAll(fd, raw.baseAddress!, raw.count)
            }
        }
        writeLock.unlock()
    }

    private func readLoop() {
        while !closed {
            guard let header = readExact(5) else { break }
            let op = Int(header[0])
            let len = Int(header[1]) | (Int(header[2]) << 8) | (Int(header[3]) << 16) | (Int(header[4]) << 24)
            guard len >= 0, len <= 1_048_576, let payload = readExact(len) else { break }
            switch op {
            case AttOp.status:
                sink?.radioDidNotifyStatus(payload)
            case AttOp.payloadNotify:
                sink?.radioDidNotifyPayload(payload)
            case AttOp.readRsp:
                callbackLock.lock()
                let callback = readCallback
                readCallback = nil
                callbackLock.unlock()
                callback?(payload)
            default:
                break
            }
        }
        if !closed {
            sink?.radioDidDrop()
        }
    }

    private func readExact(_ count: Int) -> [UInt8]? {
        if count == 0 { return [] }
        var buf = [UInt8](repeating: 0, count: count)
        var offset = 0
        while offset < count {
            let n = buf.withUnsafeMutableBytes { raw -> Int in
                guard let base = raw.baseAddress else { return -1 }
                return sysRead(fd, base.advanced(by: offset), count - offset)
            }
            if n <= 0 { return nil }
            offset += n
        }
        return buf
    }
}

#if canImport(Glibc)
private let streamType = Int32(SOCK_STREAM.rawValue)
private let tcpProtocol = Int32(IPPROTO_TCP)
private let tcpNoDelay = Int32(TCP_NODELAY)
private let shutRdwr = Int32(SHUT_RDWR)

private func sysConnect(_ fd: Int32, _ addr: UnsafePointer<sockaddr>, _ len: socklen_t) -> Int32 {
    SwiftGlibc.connect(fd, addr, len)
}

private func sysClose(_ fd: Int32) -> Int32 { SwiftGlibc.close(fd) }

private func sysShutdown(_ fd: Int32, _ how: Int32) -> Int32 { SwiftGlibc.shutdown(fd, how) }

private func sysRead(_ fd: Int32, _ buf: UnsafeMutableRawPointer, _ count: Int) -> Int {
    SwiftGlibc.read(fd, buf, count)
}

private func sysWrite(_ fd: Int32, _ buf: UnsafeRawPointer, _ count: Int) -> Int {
    SwiftGlibc.write(fd, buf, count)
}
#elseif canImport(Darwin)
private let streamType = SOCK_STREAM
private let tcpProtocol = IPPROTO_TCP
private let tcpNoDelay = TCP_NODELAY
private let shutRdwr = SHUT_RDWR

private func sysConnect(_ fd: Int32, _ addr: UnsafePointer<sockaddr>, _ len: socklen_t) -> Int32 {
    Darwin.connect(fd, addr, len)
}

private func sysClose(_ fd: Int32) -> Int32 { Darwin.close(fd) }

private func sysShutdown(_ fd: Int32, _ how: Int32) -> Int32 { Darwin.shutdown(fd, how) }

private func sysRead(_ fd: Int32, _ buf: UnsafeMutableRawPointer, _ count: Int) -> Int {
    Darwin.read(fd, buf, count)
}

private func sysWrite(_ fd: Int32, _ buf: UnsafeRawPointer, _ count: Int) -> Int {
    Darwin.write(fd, buf, count)
}
#endif

private func writeAll(_ fd: Int32, _ pointer: UnsafeRawPointer, _ count: Int) -> Int {
    var sent = 0
    while sent < count {
        let n = sysWrite(fd, pointer.advanced(by: sent), count - sent)
        if n <= 0 { return -1 }
        sent += n
    }
    return sent
}
