// swift-tools-version:5.9
import PackageDescription

// A Swift app that imports QuartzKit, built in CI's test-apple job against a debug
// QuartzKit.xcframework copied into this folder. It fails the build when the framework stops
// being usable from Swift, before a release ships it.
let package = Package(
    name: "QuartzKitSmoke",
    platforms: [
        .macOS(.v12),
    ],
    targets: [
        .binaryTarget(name: "QuartzKit", path: "QuartzKit.xcframework"),
        .executableTarget(name: "QuartzKitSmoke", dependencies: ["QuartzKit"]),
    ]
)
