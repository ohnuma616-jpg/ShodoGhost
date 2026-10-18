// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ShodoGhostProtocol",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "ShodoGhostProtocol", targets: ["ShodoGhostProtocol"]),
    ],
    targets: [
        .target(name: "ShodoGhostProtocol"),
        .testTarget(
            name: "ShodoGhostProtocolTests",
            dependencies: ["ShodoGhostProtocol"]
        ),
    ]
)
