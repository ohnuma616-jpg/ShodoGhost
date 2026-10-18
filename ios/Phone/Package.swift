// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ShodoGhostPhone",
    platforms: [
        .iOS(.v17),
    ],
    products: [
        .library(name: "ShodoGhost", targets: ["ShodoGhostPhone"]),
    ],
    dependencies: [
        .package(path: "../Protocol"),
    ],
    targets: [
        .target(
            name: "ShodoGhostPhone",
            dependencies: [
                .product(name: "ShodoGhostProtocol", package: "Protocol"),
            ]
        ),
    ]
)
