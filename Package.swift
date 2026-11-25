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
            url: "https://github.com/vitorpamplona/quartz/releases/download/v2.27.0/QuartzKit.xcframework.zip",
            checksum: "83c3818c50123f373774afaeb7bd35cbebabb0b2a59c00b5ca601f6e9494d6b1"
        ),
    ]
)
