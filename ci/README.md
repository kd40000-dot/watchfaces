# Development signing (personal testing only)

The Base64-encoded keystore in `arcs-dev-signing.jks.b64` is a **public development key** used to make builds update-compatible across GitHub Actions runners. It is **not private**, and therefore **not appropriate for distributing a trusted application**. Anyone who can access this public repository can sign another APK with the same developer signature. Do not use this signing certificate for any sensitive software.

Package: `dev.kd40000.arcscompasslive` (also used for the WFF face's separate package).

Keystore alias: `arcsdev`. Expiration: 2036-11-04.

Previous GitHub builds were signed with per-run transient Android debug keys. The older signing private key has not been retained and cannot be reconstructed from an APK, so upgrading from those builds requires a **one-time uninstall**. After installing the new persistent-key build, future builds should update in place (as long as package IDs remain the same and the signing key is not replaced).

For a proper release, generate a *new private signing key*, store it as a protected GitHub Actions secret (not in the repository), and adopt it for all release builds. That would again require a one-time uninstall when migrating from this public development key.
