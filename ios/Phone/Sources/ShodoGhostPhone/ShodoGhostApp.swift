import ShodoGhostProtocol
import SwiftUI

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
            Button("撮影") {
                model.shoot()
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.phase != .bleReady)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(24)
    }
}

final class CaptureModel: ObservableObject, PhoneSessionDelegate {
    @Published var phase: LinkPhase = .idle
    @Published var detail = "グラスを探しています"

    private let session: PhoneSession

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

    func phoneSession(_ session: PhoneSession, phase: LinkPhase) {
        DispatchQueue.main.async {
            self.phase = phase
            if phase != .bleReady {
                self.detail = Self.word(phase)
            } else if self.detail == "グラスを探しています" || self.detail == "接続しています" || self.detail == "再接続しています" {
                self.detail = Self.word(phase)
            }
        }
    }

    func phoneSession(_ session: PhoneSession, event: PhoneEvent) {
        DispatchQueue.main.async {
            switch event {
            case .captureReady(let ready):
                self.detail = "\(ready.jpegBytes) バイト"
            case .error(let error):
                self.detail = "失敗 \(error.detail)"
            case .helloAck(let ack) where !ack.accept:
                self.detail = "接続を拒否されました"
            default:
                break
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
