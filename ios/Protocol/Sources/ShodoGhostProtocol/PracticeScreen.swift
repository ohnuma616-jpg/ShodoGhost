#if canImport(SwiftUI)
import SwiftUI

/// 前面にあるあいだだけ `setForeground(true)` にする。背面では待受を開かない。
/// 助言の配列は親が `lastCritique` を見て渡す。この画面はセッションの通知を購読しない。
public struct PracticeScreen: View {
    let practice: PracticeCoordinator
    let advice: [String]
    let onForeground: (Bool) -> Void

    public init(
        practice: PracticeCoordinator,
        advice: [String] = [],
        onForeground: @escaping (Bool) -> Void
    ) {
        self.practice = practice
        self.advice = advice
        self.onForeground = onForeground
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("半紙をなぞる")
                .font(.title2)
            Text("撮影はグラスが1枚撮って閉じる。添削の待ち時間に Wi-Fi は握らない。")
                .font(.body)
            Button("書き終わり") {
                try? practice.requestCapture()
            }
            Button("画面の四隅を送る") {
                do {
                    let quad = try PaperQuad(
                        topLeft: HudPoint(x: 1000, y: 1000),
                        topRight: HudPoint(x: 9000, y: 1000),
                        bottomRight: HudPoint(x: 9000, y: 9000),
                        bottomLeft: HudPoint(x: 1000, y: 9000)
                    )
                    try practice.commitManualQuad(quad)
                } catch {}
            }
            ForEach(Array(advice.enumerated()), id: \.offset) { _, line in
                Text(line)
            }
        }
        .padding(24)
        .onAppear { onForeground(true) }
        .onDisappear { onForeground(false) }
    }
}
#endif
