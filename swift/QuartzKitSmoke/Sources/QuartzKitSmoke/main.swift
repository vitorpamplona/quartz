import QuartzKit

// Runs Kotlin code through the framework: a random key pair exercises the bundled secp256k1, and
// the hex encoding is a top-level Kotlin function, which Swift sees on its file's `Kt` class.
let keyPair = KeyPair(privKey: nil, pubKey: nil, forceReplacePubkey: true)
let pubKeyHex = HexKeyKt.toHexKey(keyPair.pubKey)

precondition(pubKeyHex.count == 64, "expected a 32-byte public key in hex, got '\(pubKeyHex)'")
print("QuartzKit works from Swift: \(pubKeyHex)")
