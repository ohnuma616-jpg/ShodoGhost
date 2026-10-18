import Foundation
#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif

public protocol WifiBurstLink: AnyObject {
    var boundPort: Int { get }
    func listen(port: Int) throws
    func stop()
    func receive(token: [UInt8], expect: CaptureReady, timeoutSeconds: Double) throws -> [UInt8]
}

/// グラスが繋ぎにくる WebSocket。パスは `/v1/session`、サブプロトコルは `shodo-ghost.v1`。
public final class TcpWifiListener: WifiBurstLink, @unchecked Sendable {
    public private(set) var boundPort: Int = 0
    private var serverFd: Int32 = -1
    private let lock = NSLock()

    public init() {}

    public func listen(port: Int) throws {
        lock.lock()
        defer { lock.unlock() }
        if serverFd >= 0 { return }
        let fd = socket(AF_INET, wifiStreamType, 0)
        guard fd >= 0 else { throw ProtocolError(.wifiTimeout, "socket") }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout.size(ofValue: yes)))
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = UInt16(port).bigEndian
        addr.sin_addr.s_addr = inetAddrLoopback()
        let rc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                wifiBind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard rc == 0, wifiListen(fd, 1) == 0 else {
            _ = wifiClose(fd)
            throw ProtocolError(.wifiTimeout, "listen")
        }
        serverFd = fd
        boundPort = try Self.port(of: fd)
    }

    public func stop() {
        lock.lock()
        let fd = serverFd
        serverFd = -1
        lock.unlock()
        if fd >= 0 {
            _ = wifiShutdown(fd, wifiShutRdwr)
            _ = wifiClose(fd)
        }
    }

    public func receive(token: [UInt8], expect: CaptureReady, timeoutSeconds: Double) throws -> [UInt8] {
        let fd = lock.withLock { serverFd }
        guard fd >= 0 else { throw ProtocolError(.wifiTimeout, "not listening") }
        var wait = timeval(tv_sec: Int(timeoutSeconds), tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &wait, socklen_t(MemoryLayout<timeval>.size))
        let client = wifiAccept(fd, nil, nil)
        guard client >= 0 else { throw ProtocolError(.wifiTimeout, "accept") }
        defer { _ = wifiClose(client) }
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &wait, socklen_t(MemoryLayout<timeval>.size))
        return try WifiExchange.receive(token: token, expect: expect, read: { count in
            try Self.readExact(client, count)
        }, write: { bytes in
            try Self.writeAll(client, bytes)
        })
    }

    private static func port(of fd: Int32) throws -> Int {
        var addr = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let rc = withUnsafeMutablePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                wifiGetsockname(fd, $0, &len)
            }
        }
        guard rc == 0 else { throw ProtocolError(.wifiTimeout, "port") }
        return Int(UInt16(bigEndian: addr.sin_port))
    }

    fileprivate static func readExact(_ fd: Int32, _ count: Int) throws -> [UInt8] {
        if count == 0 { return [] }
        var buf = [UInt8](repeating: 0, count: count)
        var offset = 0
        while offset < count {
            let n = buf.withUnsafeMutableBytes { raw -> Int in
                guard let base = raw.baseAddress else { return -1 }
                return wifiRead(fd, base.advanced(by: offset), count - offset)
            }
            if n <= 0 { throw ProtocolError(.wifiTimeout, "read") }
            offset += n
        }
        return buf
    }

    fileprivate static func writeAll(_ fd: Int32, _ bytes: [UInt8]) throws {
        var sent = 0
        let rc = bytes.withUnsafeBytes { raw -> Int in
            guard let base = raw.baseAddress else { return -1 }
            while sent < bytes.count {
                let n = wifiWrite(fd, base.advanced(by: sent), bytes.count - sent)
                if n <= 0 { return -1 }
                sent += n
            }
            return sent
        }
        if rc < 0 { throw ProtocolError(.wifiTimeout, "write") }
    }
}

public enum WifiUploader {
    /// グラス役。hello、jpeg_begin、16KiB 以下のバイナリ、jpeg_end を送る。
    public static func upload(
        host: String,
        port: Int,
        token: [UInt8],
        jpeg: [UInt8],
        width: Int,
        height: Int
    ) throws {
        let fd = socket(AF_INET, wifiStreamType, 0)
        guard fd >= 0 else { throw ProtocolError(.wifiTimeout, "socket") }
        defer { _ = wifiClose(fd) }
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = UInt16(port).bigEndian
        guard inet_pton(AF_INET, host, &addr.sin_addr) == 1 else {
            throw ProtocolError(.badFrame, "host")
        }
        let rc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                wifiConnect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard rc == 0 else { throw ProtocolError(.wifiTimeout, "connect") }
        let key = Data([UInt8](repeating: 7, count: 16)).base64EncodedString()
        let request = WebSocketHandshake.request(host: host, port: port, key: key)
        try TcpWifiListener.writeAll(fd, Array(request.utf8))
        _ = try readHttpHeaders(fd)
        let hello = try WifiText.encode(op: WifiSession.opHello, fields: ["token": WifiHex.encode(token)])
        try TcpWifiListener.writeAll(fd, WebSocketFrame.encode(opcode: 0x1, payload: Array(hello.utf8), masked: true))
        _ = try readFrame(fd)
        let begin = try WifiText.encode(op: WifiSession.opJpegBegin, fields: [
            "bytes": String(jpeg.count),
            "width": String(width),
            "height": String(height),
            "crc32": String(Crc32.ieee(jpeg)),
        ])
        try TcpWifiListener.writeAll(fd, WebSocketFrame.encode(opcode: 0x1, payload: Array(begin.utf8), masked: true))
        var offset = 0
        while offset < jpeg.count {
            let end = min(offset + Limits.wifiChunkBytes, jpeg.count)
            try TcpWifiListener.writeAll(fd, WebSocketFrame.encode(opcode: 0x2, payload: Array(jpeg[offset..<end]), masked: true))
            offset = end
        }
        let endText = try WifiText.encode(op: WifiSession.opJpegEnd)
        try TcpWifiListener.writeAll(fd, WebSocketFrame.encode(opcode: 0x1, payload: Array(endText.utf8), masked: true))
        _ = try readFrame(fd)
    }

    private static func readHttpHeaders(_ fd: Int32) throws -> String {
        var bytes: [UInt8] = []
        while bytes.count < 4096 {
            let one = try TcpWifiListener.readExact(fd, 1)
            bytes.append(one[0])
            if bytes.count >= 4, Array(bytes.suffix(4)) == [13, 10, 13, 10] { break }
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    private static func readFrame(_ fd: Int32) throws -> [UInt8] {
        let header = try TcpWifiListener.readExact(fd, 2)
        var length = Int(header[1] & 0x7F)
        if length == 126 {
            let ext = try TcpWifiListener.readExact(fd, 2)
            length = (Int(ext[0]) << 8) | Int(ext[1])
        }
        return try TcpWifiListener.readExact(fd, length)
    }
}

enum WifiExchange {
    static func receive(
        token: [UInt8],
        expect: CaptureReady,
        read: (Int) throws -> [UInt8],
        write: ([UInt8]) throws -> Void
    ) throws -> [UInt8] {
        let header = try readHeader(read)
        guard header.contains("GET \(WifiSession.path) "),
              header.lowercased().contains(WifiSession.subprotocol)
        else {
            throw ProtocolError(.badFrame, "upgrade")
        }
        guard let key = Self.headerValue(header, "Sec-WebSocket-Key") else {
            throw ProtocolError(.badFrame, "key")
        }
        try write(Array(WebSocketHandshake.accept(key: key).utf8))
        let helloFrame = try WebSocketFrame.read(read)
        let hello = try WifiText.decode(String(decoding: helloFrame, as: UTF8.self))
        guard hello.op == WifiSession.opHello, WifiHex.decode(hello.fields["token"] ?? "") == token else {
            let error = try WifiText.encode(op: WifiSession.opError, fields: ["code": String(ErrorCode.tokenRejected.rawValue)])
            try write(WebSocketFrame.encode(opcode: 0x1, payload: Array(error.utf8)))
            throw ProtocolError(.tokenRejected, "token")
        }
        let ack = try WifiText.encode(op: WifiSession.opHelloAck, fields: ["ok": "1"])
        try write(WebSocketFrame.encode(opcode: 0x1, payload: Array(ack.utf8)))
        let beginFrame = try WebSocketFrame.read(read)
        let begin = try WifiText.decode(String(decoding: beginFrame, as: UTF8.self))
        guard begin.op == WifiSession.opJpegBegin,
              begin.fields["bytes"] == String(expect.jpegBytes),
              begin.fields["crc32"] == String(expect.crc32)
        else {
            throw ProtocolError(.badFrame, "jpeg_begin")
        }
        var jpeg: [UInt8] = []
        while true {
            let payload = try WebSocketFrame.read(read)
            if let text = String(bytes: payload, encoding: .utf8),
               let frame = try? WifiText.decode(text),
               frame.op == WifiSession.opJpegEnd {
                break
            }
            guard payload.count <= Limits.wifiChunkBytes else { throw ProtocolError(.payloadTooLarge, "chunk") }
            jpeg.append(contentsOf: payload)
            guard jpeg.count <= Limits.maxJpegBytes else { throw ProtocolError(.payloadTooLarge, "jpeg") }
        }
        guard jpeg.count == Int(expect.jpegBytes), Crc32.ieee(jpeg) == expect.crc32 else {
            throw ProtocolError(.crcFail, "jpeg")
        }
        let bye = try WifiText.encode(op: WifiSession.opBye)
        try write(WebSocketFrame.encode(opcode: 0x1, payload: Array(bye.utf8)))
        return jpeg
    }

    private static func readHeader(_ read: (Int) throws -> [UInt8]) throws -> String {
        var bytes: [UInt8] = []
        while bytes.count < 8192 {
            bytes.append(contentsOf: try read(1))
            if bytes.suffix(4).elementsEqual([13, 10, 13, 10]) { break }
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    static func headerValue(_ header: String, _ name: String) -> String? {
        // CRLF is one Character in Swift, so split(separator: "\n") keeps the whole header.
        let lines = header.split(whereSeparator: { $0 == "\n" || $0 == "\r" || $0 == "\r\n" })
        for line in lines {
            let parts = line.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
            if parts.count == 2, parts[0].lowercased() == name.lowercased() {
                return parts[1].trimmingCharacters(in: .whitespaces)
            }
        }
        return nil
    }
}

enum WebSocketHandshake {
    static let guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    static func request(host: String, port: Int, key: String) -> String {
        """
        GET \(WifiSession.path) HTTP/1.1\r
        Host: \(host):\(port)\r
        Upgrade: websocket\r
        Connection: Upgrade\r
        Sec-WebSocket-Key: \(key)\r
        Sec-WebSocket-Protocol: \(WifiSession.subprotocol)\r
        Sec-WebSocket-Version: 13\r
        \r

        """
    }

    static func accept(key: String) -> String {
        let digest = Sha1.hash(Array((key + guid).utf8))
        let accept = Data(digest).base64EncodedString()
        return """
        HTTP/1.1 101 Switching Protocols\r
        Upgrade: websocket\r
        Connection: Upgrade\r
        Sec-WebSocket-Accept: \(accept)\r
        Sec-WebSocket-Protocol: \(WifiSession.subprotocol)\r
        \r

        """
    }
}

enum WebSocketFrame {
    static func encode(opcode: UInt8, payload: [UInt8], masked: Bool = false) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: 0)
        out.append(0x80 | opcode)
        let maskBit: UInt8 = masked ? 0x80 : 0
        if payload.count < 126 {
            out.append(maskBit | UInt8(payload.count))
        } else if payload.count <= 0xFFFF {
            out.append(maskBit | 126)
            out.append(UInt8((payload.count >> 8) & 0xFF))
            out.append(UInt8(payload.count & 0xFF))
        } else {
            out.append(maskBit | 127)
            var len = payload.count
            var wide = [UInt8](repeating: 0, count: 8)
            for index in stride(from: 7, through: 0, by: -1) {
                wide[index] = UInt8(len & 0xFF)
                len >>= 8
            }
            out.append(contentsOf: wide)
        }
        let mask: [UInt8] = [1, 2, 3, 4]
        if masked { out.append(contentsOf: mask) }
        if masked {
            out.append(contentsOf: payload.enumerated().map { $0.element ^ mask[$0.offset % 4] })
        } else {
            out.append(contentsOf: payload)
        }
        return out
    }

    static func read(_ read: (Int) throws -> [UInt8]) throws -> [UInt8] {
        let header = try read(2)
        let opcode = header[0] & 0x0F
        if opcode == 0x8 { throw ProtocolError(.wifiTimeout, "close") }
        var length = Int(header[1] & 0x7F)
        if length == 126 {
            let ext = try read(2)
            length = (Int(ext[0]) << 8) | Int(ext[1])
        } else if length == 127 {
            let ext = try read(8)
            length = 0
            for byte in ext { length = (length << 8) | Int(byte) }
        }
        guard length >= 0, length <= Limits.maxJpegBytes else { throw ProtocolError(.payloadTooLarge, "frame") }
        let masked = header[1] & 0x80 != 0
        let mask = masked ? try read(4) : []
        var payload = try read(length)
        if masked {
            for index in payload.indices {
                payload[index] ^= mask[index % 4]
            }
        }
        return payload
    }
}

enum WifiHex {
    static func encode(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    static func decode(_ text: String) -> [UInt8]? {
        guard text.count.isMultiple(of: 2) else { return nil }
        var out: [UInt8] = []
        var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 2)
            guard let byte = UInt8(text[index..<next], radix: 16) else { return nil }
            out.append(byte)
            index = next
        }
        return out
    }
}

enum Sha1 {
    static func hash(_ message: [UInt8]) -> [UInt8] {
        var h0: UInt32 = 0x67452301
        var h1: UInt32 = 0xEFCDAB89
        var h2: UInt32 = 0x98BADCFE
        var h3: UInt32 = 0x10325476
        var h4: UInt32 = 0xC3D2E1F0
        var bytes = message
        let bitCount = UInt64(message.count) * 8
        bytes.append(0x80)
        while bytes.count % 64 != 56 { bytes.append(0) }
        for shift in stride(from: 56, through: 0, by: -8) {
            bytes.append(UInt8((bitCount >> shift) & 0xFF))
        }
        for offset in stride(from: 0, to: bytes.count, by: 64) {
            var w = [UInt32](repeating: 0, count: 80)
            for i in 0..<16 {
                let j = offset + i * 4
                w[i] = (UInt32(bytes[j]) << 24) | (UInt32(bytes[j + 1]) << 16) | (UInt32(bytes[j + 2]) << 8) | UInt32(bytes[j + 3])
            }
            for i in 16..<80 {
                w[i] = rotl(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1)
            }
            var a = h0, b = h1, c = h2, d = h3, e = h4
            for i in 0..<80 {
                let f: UInt32
                let k: UInt32
                switch i {
                case 0..<20:
                    f = (b & c) | ((~b) & d)
                    k = 0x5A827999
                case 20..<40:
                    f = b ^ c ^ d
                    k = 0x6ED9EBA1
                case 40..<60:
                    f = (b & c) | (b & d) | (c & d)
                    k = 0x8F1BBCDC
                default:
                    f = b ^ c ^ d
                    k = 0xCA62C1D6
                }
                let temp = rotl(a, 5) &+ f &+ e &+ k &+ w[i]
                e = d
                d = c
                c = rotl(b, 30)
                b = a
                a = temp
            }
            h0 = h0 &+ a
            h1 = h1 &+ b
            h2 = h2 &+ c
            h3 = h3 &+ d
            h4 = h4 &+ e
        }
        var out: [UInt8] = []
        for word in [h0, h1, h2, h3, h4] {
            out.append(UInt8((word >> 24) & 0xFF))
            out.append(UInt8((word >> 16) & 0xFF))
            out.append(UInt8((word >> 8) & 0xFF))
            out.append(UInt8(word & 0xFF))
        }
        return out
    }

    private static func rotl(_ value: UInt32, _ count: UInt32) -> UInt32 {
        (value << count) | (value >> (32 - count))
    }
}

private extension NSLock {
    func withLock<T>(_ body: () -> T) -> T {
        lock()
        defer { unlock() }
        return body()
    }
}

#if canImport(Glibc)
private let wifiStreamType = Int32(SOCK_STREAM.rawValue)
private let wifiShutRdwr = Int32(SHUT_RDWR)
private func wifiBind(_ fd: Int32, _ addr: UnsafePointer<sockaddr>, _ len: socklen_t) -> Int32 {
    SwiftGlibc.bind(fd, addr, len)
}
private func wifiListen(_ fd: Int32, _ backlog: Int32) -> Int32 { SwiftGlibc.listen(fd, backlog) }
private func wifiAccept(_ fd: Int32, _ addr: UnsafeMutablePointer<sockaddr>?, _ len: UnsafeMutablePointer<socklen_t>?) -> Int32 {
    SwiftGlibc.accept(fd, addr, len)
}
private func wifiConnect(_ fd: Int32, _ addr: UnsafePointer<sockaddr>, _ len: socklen_t) -> Int32 {
    SwiftGlibc.connect(fd, addr, len)
}
private func wifiClose(_ fd: Int32) -> Int32 { SwiftGlibc.close(fd) }
private func wifiShutdown(_ fd: Int32, _ how: Int32) -> Int32 { SwiftGlibc.shutdown(fd, how) }
private func wifiRead(_ fd: Int32, _ buf: UnsafeMutableRawPointer, _ count: Int) -> Int { SwiftGlibc.read(fd, buf, count) }
private func wifiWrite(_ fd: Int32, _ buf: UnsafeRawPointer, _ count: Int) -> Int { SwiftGlibc.write(fd, buf, count) }
private func wifiGetsockname(_ fd: Int32, _ addr: UnsafeMutablePointer<sockaddr>, _ len: UnsafeMutablePointer<socklen_t>) -> Int32 {
    SwiftGlibc.getsockname(fd, addr, len)
}
private func inetAddrLoopback() -> in_addr_t { inet_addr("127.0.0.1") }
#elseif canImport(Darwin)
private let wifiStreamType = SOCK_STREAM
private let wifiShutRdwr = SHUT_RDWR
private func wifiBind(_ fd: Int32, _ addr: UnsafePointer<sockaddr>, _ len: socklen_t) -> Int32 {
    Darwin.bind(fd, addr, len)
}
private func wifiListen(_ fd: Int32, _ backlog: Int32) -> Int32 { Darwin.listen(fd, backlog) }
private func wifiAccept(_ fd: Int32, _ addr: UnsafeMutablePointer<sockaddr>?, _ len: UnsafeMutablePointer<socklen_t>?) -> Int32 {
    Darwin.accept(fd, addr, len)
}
private func wifiConnect(_ fd: Int32, _ addr: UnsafePointer<sockaddr>, _ len: socklen_t) -> Int32 {
    Darwin.connect(fd, addr, len)
}
private func wifiClose(_ fd: Int32) -> Int32 { Darwin.close(fd) }
private func wifiShutdown(_ fd: Int32, _ how: Int32) -> Int32 { Darwin.shutdown(fd, how) }
private func wifiRead(_ fd: Int32, _ buf: UnsafeMutableRawPointer, _ count: Int) -> Int { Darwin.read(fd, buf, count) }
private func wifiWrite(_ fd: Int32, _ buf: UnsafeRawPointer, _ count: Int) -> Int { Darwin.write(fd, buf, count) }
private func wifiGetsockname(_ fd: Int32, _ addr: UnsafeMutablePointer<sockaddr>, _ len: UnsafeMutablePointer<socklen_t>) -> Int32 {
    Darwin.getsockname(fd, addr, len)
}
private func inetAddrLoopback() -> in_addr_t { inet_addr("127.0.0.1") }
#endif
