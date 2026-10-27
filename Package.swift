// swift-tools-version:5.9
import PackageDescription

// Swift Package Manager entry point for Quartz. The library ships as QuartzKit.xcframework,
// attached to each GitHub release. The release workflow (.github/workflows/release.yml) runs
// tools/release/prepare-release.sh, which rewrites the url and checksum below, so the copy of
// this file at tag v<version> names that release's zip. Don't edit them by hand.
let package = Package(
    name: "Quartz",
    platforms: [
        .iOS(.v15),
        .macOS(.v12),
    ],
    products: [
        .library(name: "QuartzKit", targets: ["QuartzKit"]),
    ],
    targets: [
        .binaryTarget(
            name: "QuartzKit",
            url: "https://github.com/vitorpamplona/quartz/releases/download/v2.4.0/QuartzKit.xcframework.zip",
            checksum: "cfe20c6c5b98d4ee8f9b59b5744e98907e9951d7b7183b7acf5df63cb207a847"
        ),
    ]
)
