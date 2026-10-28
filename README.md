# KanCard

An independent, open-source Android app for your self-hosted [Kanboard](https://kanboard.org/) server.

Made for home-lab servers: plain `http://` and self-signed HTTPS certificates are supported. It can also run fully on your phone with no server at all.

> KanCard is not an official Kanboard app and is not affiliated with or endorsed by the Kanboard project.

## Features

- Sign in with your Kanboard username and password
- Works with `https://` (normal or **self-signed certificate**, pinned by fingerprint) and plain `http://` (after a warning)
- Browse projects, swipe between columns, create / edit / move / close / reopen / delete tasks
- Due dates, new projects, archive projects
- Works fully offline: every change (tasks, subtasks, comments, new and archived projects) is saved on the phone and sent automatically when you are back online
- Today screen across all projects; each project opens as a Board or a List (your choice, per project)
- Subtasks and comments, task details as a sheet, quick-add per column, swipe to finish, drag tasks between columns in both views
- Drag and drop tasks, task colours and priority, search
- Appearance: all Catppuccin themes, system colours, AMOLED, custom accent
- Home-screen widget per project
- Local-only mode (everything stays on the phone)
- JSON backup and restore
- Catppuccin colours (Latte in light mode, Macchiato in dark mode, Mauve accent)
- Android 6.0 and newer

## How the connection safety works

| Server address | What KanCard does |
|---|---|
| `https://` with a trusted certificate | Normal, strict checking. |
| `https://` with a self-signed certificate | Shows you the certificate's SHA-256 fingerprint and asks. If you trust it, KanCard saves that fingerprint and **only accepts that exact certificate** from then on. A different certificate (even a valid one) is refused. If you renew the certificate, you approve the new one. |
| `http://` | Shows a warning first. Nothing is encrypted, so use it only on a network you trust. |

Compare the fingerprint with your server's before trusting it:

```
openssl x509 -noout -fingerprint -sha256 -in your-certificate.pem
```

Your password is stored encrypted with a key in the Android Keystore. No ads, no analytics, no tracking. Permissions: Internet, and network state (to notice when the connection returns so saved changes can be sent).

## Install

Download the APK from the [Releases](../../releases) page and install it. Check it against the `.sha256` file if you like.

## Build from source

Needs JDK 17 and the Android SDK (platform 36).

```
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the same steps on every push.

## Modes

- **Kanboard** — the server is the master; the phone keeps a full offline copy and a queue of changes that are sent when a connection is available.
- **Local only** — projects and tasks live in the phone's database. New projects get the columns Backlog, Ready, Work in progress, Done.

The two modes never sync. Switching modes erases the on-phone workspace after a confirmation. Backups never contain your server address, username or token.

## License

GPL-3.0. See [LICENSE](LICENSE).

## Acknowledgements

KanCard was inspired by [Kandroid](https://github.com/P-o-n-R/Kandroid) by P-o-n-R (GPL-3.0), which showed what a good Kanboard client for Android can be. KanCard is a separate project with its own code; it adds plain `http://` and self-signed HTTPS support, an offline change queue, and more.
