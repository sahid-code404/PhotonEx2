# Development signing

`photonex2-dev.jks.b64` is a **development-only**, deliberately repository-visible signing key.
It exists so every green CI build can update the same installed development APK without uninstalling.

- alias: `photonex2-dev`
- store/key password: `photonex2dev`
- expected certificate SHA-256: see `EXPECTED_CERT_SHA256`

This is not a production trust boundary: anyone who can read this public repository can reconstruct the
key and sign a compatible development APK. A future stable/release channel must use a separately
protected key and must never inherit this signer.
