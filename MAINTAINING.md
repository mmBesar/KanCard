# Maintaining KanCard

## Releasing

1. Raise `versionCode` (never reuse one) and `versionName` in `app/build.gradle.kts`.
2. Commit, then tag and push:
   ```
   git tag v0.1.0 && git push origin v0.1.0
   ```
3. The **Release** workflow tests, builds a signed APK and publishes it with a checksum.

## Signing (plain words)

Android only installs an update if it is signed with the **same key** as the installed version. Lose the key and nobody can update; they must uninstall first.

- Create a key once: `keytool -genkeypair -v -keystore kancard.jks -alias kancard -keyalg RSA -keysize 4096 -validity 10000`
- **Back it up in two safe places.** Never commit it.
- Add four repository secrets: `ANDROID_KEYSTORE_B64` (`base64 -w0 kancard.jks`), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

## Code map

- `network/` — `BoardApi` (all Kanboard JSON-RPC calls live here), `HttpClients` (the http / self-signed / normal decision), `Tls` (fingerprints and pinning), `ServerAddress`.
- `data/` — Room database, `BoardRepository` (server vs local mode), backups, due dates.
- `security/CredentialStore` — Keystore-encrypted login.
- `ui/` — Compose screens. `widget/` — Glance home-screen widget.

## Rules

- Never log request or response bodies (they hold credentials and private data).
- Tests use local fixtures (MockWebServer) only, never a real Kanboard server.
- A database change needs a version bump, a migration and the updated file in `app/schemas`.
- Keep GPL-3.0.
