# SOR Team Board for Android

A native Android app (Kotlin, Jetpack Compose) for the FRC 6059 System Overload team board. It reads and writes the same
Firestore database as the website (https://team.systemoverload.org) and the iPhone app, under the same `firestore.rules`.
Every write mirrors the website's document shapes, so the website, the Slack bot and both apps stay in step.

It's a port of the iPhone app (private repo SOR-iOS), screen for screen: Home, Tasks (boards, My Work, task details with
checklist, comments, photos, goals, dependencies, Slack reminders), Create, Meetings (calendar, signups, attendance,
hangar agendas), check-in by QR code or digits, "I'm here", Action Items (with agenda documents and team reminders),
Timeline, Board Room, Analytics, Team approvals, the Gemini helper, push notifications, report and block, a Home Screen
widget, and a demo mode with invented people.

**Distribution: APK download only.** No Play Store. People install `sor-team.apk` directly (Android asks them to allow
installs from their browser or Files app once).

## Layout

- `app/src/main/java/org/systemoverload/team/`
  - `model/`: document types (`Models.kt`) and the ICS reader (`TeamCalendar.kt`, a port of `mod-calendar`)
  - `data/`: `AppModel` (sign-in, live listeners, permissions, every write), `AppModelTeam` (shifts, scores, action items,
    Board Room, analytics), `AppModelDevice` (push, links, check-in, photos, reports, roster), `Helper` (Gemini), `DemoData`
  - `ui/`: theme, shared components, navigation (one back stack per tab), and `screens/`
  - `widget/`: the Glance widget (next meetings, competition countdown)
- `app/google-services.json`: public Firebase client config for `org.systemoverload.team` (not a secret)
- `release.sh`: builds the signed APK into `dist/`

## Build

Needs JDK 21 and the Android SDK (`platforms;android-37.2`, `build-tools;37.0.0`). With Homebrew:

```sh
brew install --cask android-commandlinetools
sdkmanager --sdk_root=$HOME/Library/Android/sdk "platform-tools" "platforms;android-37.2" "build-tools;37.0.0"
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./release.sh                        # dist/sor-team.apk, signed with the release key
```

Plain builds run against the **live** database. "Look around the demo" on the sign-in screen uses invented data and
never touches it.

## Signing

The release key is **not** in this repo: `~/.sor-android/sor-release.jks` and `~/.sor-android/keystore.properties`
(made 2026-11-25). Back both up somewhere safe. If the key is lost, new APKs can't install over old ones (people would
have to uninstall first), and Google sign-in breaks until the new fingerprint is added in Firebase.

Its fingerprints are registered on the Firebase Android app (Project settings › Your apps › SOR Team (Android)), which is
what lets Google sign-in work:

- SHA-1 `00:11:8A:AA:86:AF:85:66:8E:B9:C3:6A:D5:F3:C4:C8:C4:6C:35:A9`
- SHA-256 `FA:F1:06:99:CB:F8:78:B0:09:14:EC:EA:4A:43:91:27:26:9A:4B:12:D0:FA:EE:0B:4E:CB:65:7E:4B:00:0A:A0`

Debug builds use the same key when it's present, so sign-in works in them too.

## How it ties into the rest

- **Sign-in:** Google through Credential Manager (the web client id from `google-services.json`), or "Other sign-in"
  (email and password accounts made in Firebase). New people answer the same questions and wait for a Board member.
- **Push:** the app saves its FCM token to `pushTokens/{token}` with `platform: "android"`. The Slack bot's
  `lib/push.mjs` sends to every token the same way for both platforms. Turned-off kinds live in `users/{uid}.push`.
- **Links:** `team.systemoverload.org/task/…`, `/action/…`, `/signups/…`, `/checkin/…` open in the app once the site
  server serves `/.well-known/assetlinks.json` for `org.systemoverload.team` with the SHA-256 above.
- **Website pages inside the app** (Take Attendance, Team settings) get `SORTeamApp` in the user agent (so the site adds
  `html.in-app`) and `window.sorAppToken` with a fresh Google ID token.
- **Calendar ids** must match the website, the bot and the iPhone app. `CalendarParityTest` checks the Kotlin parser
  against the bot's `lib/calendar.mjs` output for `app/src/test/resources/fixture.ics` (regenerate `fixture.expected`
  with the bot's `parseICS` + `expand` when the parser changes there).

## Rules carried over from the iPhone app

- Native screens don't change when the website does. A website feature change needs the matching change here.
- Nothing animates or blurs behind the page.
- The grant tracker is gone (Business is a normal board).
- Report and block stay: comments, photos, Board channel messages and tasks can be reported; admins handle them in
  You › Reports.
- This repo is public: no emails, names of real people, keys or team data in it. Demo data uses invented people.
