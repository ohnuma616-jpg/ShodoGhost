import Darwin
import ShodoGhostProtocol
import SwiftUI
import UIKit

@main
struct ShodoGhostApp: App {
    @StateObject private var model = CaptureModel()

    var body: some Scene {
        WindowGroup {
            CaptureView(model: model)
        }
    }
}

struct CaptureView: View {
    @ObservedObject var model: CaptureModel

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("半紙をなぞる")
                .font(.title2)
            Text(model.detail)
                .font(.body)
            if let jpeg = model.jpeg, let image = UIImage(data: jpeg) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: 420)
            }
            Button("撮影") {
                model.shoot()
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.phase != .bleReady)
            Button("四隅を送る") {
                model.sendCorners()
            }
            .disabled(model.phase != .bleReady)
            Button("手本を送る") {
                model.sendLesson()
            }
            .disabled(model.phase != .bleReady)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(24)
    }
}

final class CaptureModel: ObservableObject, PhoneSessionDelegate {
    @Published var phase: LinkPhase = .idle
    @Published var detail = "グラスを探しています"
    @Published var jpeg: Data?

    private let session: PhoneSession
    private let photos = DispatchQueue(label: "dev.shodoghost.photo")
    private var receiving = false
    private var lesson: [Stroke] = []

    init() {
        let radio = CoreBluetoothPhoneRadio()
        session = PhoneSession(radio: radio)
        session.delegate = self
        session.start()
    }

    func shoot() {
        do {
            try session.captureNow(.phone, quality: 90)
            detail = "撮影を送りました"
        } catch {
            detail = "送れません"
        }
    }

    func sendCorners() {
        do {
            let quad = try PaperQuad(
                topLeft: HudPoint(x: 1000, y: 1000),
                topRight: HudPoint(x: 9000, y: 1000),
                bottomRight: HudPoint(x: 9000, y: 9000),
                bottomLeft: HudPoint(x: 1000, y: 9000)
            )
            try session.calibrateCommit(
                CalibrateCommit(method: .manual, quad: quad, widthMm: PaperProfile.hanshi.widthMm, heightMm: PaperProfile.hanshi.heightMm)
            )
            detail = "四隅を送りました"
        } catch {
            detail = "送れません"
        }
    }

    func sendLesson() {
        do {
            let strokes = try EiExemplar.strokes()
            try session.setMode(.trace, paper: .hanshi)
            let points = strokes.reduce(0) { $0 + $1.points.count }
            let meta = ExemplarMeta(
                exemplarId: EiExemplar.id,
                strokeCount: strokes.count,
                pointCount: points,
                layerMask: InkLayer.contour.rawValue
            )
            let pack = try StrokePack(
                exemplarOrCritiqueId: EiExemplar.id,
                packIndex: 0,
                packCount: 1,
                strokes: strokes,
                correction: false
            )
            try session.sendExemplar(meta, packs: [pack])
            lesson = strokes
            detail = "手本を送りました"
        } catch {
            detail = "送れません"
        }
    }

    func phoneSession(_ session: PhoneSession, phase: LinkPhase) {
        DispatchQueue.main.async {
            self.phase = phase
            switch phase {
            case .idle, .discovering, .connecting, .negotiating, .backoff:
                self.detail = Self.word(phase)
            case .wifiWake, .transfer, .wifiTeardown:
                if self.receiving { self.detail = Self.word(phase) }
            case .bleReady:
                if self.detail == "グラスを探しています" || self.detail == "接続しています" || self.detail == "再接続しています" {
                    self.detail = Self.word(phase)
                }
            }
        }
    }

    func phoneSession(_ session: PhoneSession, event: PhoneEvent) {
        DispatchQueue.main.async {
            switch event {
            case .captureReady(let ready):
                self.detail = "\(ready.jpegBytes) バイト"
                self.receive(ready)
            case .error(let error):
                self.detail = "失敗 \(error.detail)"
            case .helloAck(let ack) where !ack.accept:
                self.detail = "接続を拒否されました"
            default:
                break
            }
        }
    }

    private func receive(_ ready: CaptureReady) {
        guard !receiving else { return }
        receiving = true
        let session = self.session
        photos.async {
            let link = NwWifiListener()
            do {
                DispatchQueue.main.async { self.detail = "写真を受けています" }
                try link.listen(port: 0)
                let address = LanAddress.ipv4()
                let token = try session.beginWifi(ipv4: address, port: link.boundPort, purpose: .uploadJpeg)
                let jpeg = try link.receive(
                    token: token,
                    expect: ready,
                    timeoutSeconds: Double(Limits.wifiHardTimeoutMs) / 1000
                )
                link.stop()
                try session.endWifi(reason: 0)
                let data = Data(jpeg)
                let foundQuad = JpegGray.quad(in: data)
                if let foundQuad {
                    try session.calibrateCommit(
                        CalibrateCommit(
                            method: .oneShot,
                            quad: foundQuad,
                            widthMm: PaperProfile.hanshi.widthMm,
                            heightMm: PaperProfile.hanshi.heightMm
                        )
                    )
                }
                let found = foundQuad != nil
                DispatchQueue.main.async {
                    self.jpeg = data
                    self.detail = found ? "四隅を合わせました" : "四隅が見つかりません"
                }
                try session.showAdvice(["添削中"])
                let result = try ManualCritique().critique(
                    CritiqueRequest(
                        jpeg: jpeg,
                        exemplarId: EiExemplar.id,
                        strokes: self.lesson,
                        paper: .hanshi,
                        widthMm: PaperProfile.hanshi.widthMm,
                        heightMm: PaperProfile.hanshi.heightMm
                    )
                )
                try session.showAdvice(result.advice)
                try session.sendCorrections(critiqueId: result.critiqueId, strokes: result.strokes)
                DispatchQueue.main.async {
                    self.detail = found ? "四隅を合わせ、添削を送りました" : "四隅は見つからず、添削を送りました"
                    self.receiving = false
                }
            } catch {
                link.stop()
                switch session.phase {
                case .wifiWake, .transfer, .wifiTeardown:
                    try? session.endWifi(reason: 0)
                default:
                    break
                }
                let text = (error as? ProtocolError)?.detail ?? "受信"
                DispatchQueue.main.async {
                    self.detail = "失敗 \(text)"
                    self.receiving = false
                }
            }
        }
    }

    private static func word(_ phase: LinkPhase) -> String {
        switch phase {
        case .idle, .discovering:
            return "グラスを探しています"
        case .connecting, .negotiating:
            return "接続しています"
        case .bleReady:
            return "準備完了"
        case .backoff:
            return "再接続しています"
        case .wifiWake, .transfer, .wifiTeardown:
            return "写真を送っています"
        }
    }
}

enum JpegGray {
    static func quad(in data: Data) -> PaperQuad? {
        guard let image = UIImage(data: data)?.cgImage else { return nil }
        let width = image.width
        let height = image.height
        guard width >= 8, height >= 8 else { return nil }
        let targetW = min(160, width)
        let targetH = max(1, height * targetW / width)
        let count = targetW * targetH
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: count)
        defer { buffer.deallocate() }
        guard let context = CGContext(
            data: buffer,
            width: targetW,
            height: targetH,
            bitsPerComponent: 8,
            bytesPerRow: targetW,
            space: CGColorSpaceCreateDeviceGray(),
            bitmapInfo: CGImageAlphaInfo.none.rawValue
        ) else { return nil }
        context.interpolationQuality = .none
        context.draw(image, in: CGRect(x: 0, y: 0, width: targetW, height: targetH))
        let gray = Array(UnsafeBufferPointer(start: buffer, count: count))
        return PaperFinder.quad(width: targetW, height: targetH, gray: gray)
    }
}

enum LanAddress {
    static func ipv4() -> [UInt8] {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return [172, 20, 10, 1] }
        defer { freeifaddrs(first) }
        var fallback: [UInt8] = []
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let iface = cursor {
            defer { cursor = iface.pointee.ifa_next }
            guard let addr = iface.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            var host = [CChar](repeating: 0, count: 1025)
            let rc = getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            if rc != 0 { continue }
            let text = String(cString: host)
            let parts = text.split(separator: ".").compactMap { UInt8($0) }
            guard parts.count == 4, parts != [127, 0, 0, 1] else { continue }
            if text == "172.20.10.1" { return parts }
            if fallback.isEmpty, !text.hasPrefix("169.254.") { fallback = parts }
        }
        return fallback.isEmpty ? [172, 20, 10, 1] : fallback
    }
}
