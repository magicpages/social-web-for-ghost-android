# Releasing Social Web to Google Play

Package: `co.magicpages.socialweb`. Releases are Android App Bundles (`.aab`) signed with an **upload key**; Google re-signs them with the app signing key it holds (Play App Signing).

## Where things live

| What | Where |
|---|---|
| Listing text, per language | `fastlane/metadata/android/<locale>/` (`title.txt` ≤30, `short_description.txt` ≤80, `full_description.txt` ≤4000, `changelogs/<versionCode>.txt` ≤500) |
| Icon (512×512) and feature graphic (1024×500) | `fastlane/metadata/android/en-US/images/` |
| Version | `versionCode`, `versionName` in `app/build.gradle.kts` |
| Signing config | `app/build.gradle.kts`, reading `keystore.properties` or `SOCIALWEB_*` environment variables |

The Play locale folders map to the app's languages: `in`→`id`, `nb`→`no-NO`, `pt`→`pt-PT`, `b+zh+Hans`→`zh-CN`, `b+zh+Hant`→`zh-TW`, the rest `xx-XX` (`de-DE`, `ja-JP`, …) or bare (`ar`, `uk`, `vi`).

The Play title is "Social Web for Ghost" (Turkish: "Ghost için Social Web", as on the App Store); the launcher label is "Social Web" (`app_name`).

English screenshots live in `fastlane/metadata/android/en-US/images/`: six phone shots (1080×1920, 9:16, inside Play's 2:1 limit) in `phoneScreenshots/` and two tablet shots (2560×1600) in `tenInchScreenshots/`. They were taken on an emulator signed in to a fake Ghost site, so no real account appears. Play falls back to them for other locales.

To retake them offline, use the debug build's **demo mode**: the app signs in as "Seaside Notes", the made-up sample site of Magic Pages' feature pages, and every request is answered from made-up content (Notes, Reader, Explore, Notifications, threads, profiles, articles, link cards, image posts, and replying with a picture). Start it on a fresh emulator or device with:

```sh
adb shell am force-stop co.magicpages.socialweb.debug
adb shell am start -n co.magicpages.socialweb.debug/co.magicpages.socialweb.MainActivity --ez demo true
```

`--ez demo false` switches a running app back to the real accounts. The flag lasts as long as the process, and demo mode never touches real accounts. Pictures are placeholders in `app/src/debug/assets/demo/`, the content is in `app/src/debug/kotlin/co/magicpages/socialweb/demo/`. The store `release` build doesn't contain any of it; only the `nonMinifiedRelease` and `benchmarkRelease` variants of the [baseline profile](#baseline-profile) add it.

## Versioning

- `versionCode`: integer, must rise with every upload to Play. Bump it by one.
- `versionName`: shown to users (`0.1.0`, `1.0.0`, …).
- Add `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` for each new `versionCode` (all 24 languages + en-US; 500 characters max).

## One-time setup

### 1. Create the upload keystore

Keep it outside the repo or in the repo root (`*.jks` and `keystore.properties` are git-ignored). Back it up somewhere safe; losing it means asking Google to reset the upload key.

```bash
keytool -genkeypair -v -storetype PKCS12 -keystore upload.jks \
  -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Then create `keystore.properties` in the repo root:

```properties
storeFile=upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

`storeFile` is relative to the repo root (an absolute path works too). `keyPassword` defaults to `storePassword`. Without this file the release build is simply unsigned.

For CI, set these environment variables instead (the file wins when both exist): `SOCIALWEB_KEYSTORE_FILE`, `SOCIALWEB_KEYSTORE_PASSWORD`, `SOCIALWEB_KEY_ALIAS`, `SOCIALWEB_KEY_PASSWORD`.

### 2. Create the app in Play Console

1. [Play Console](https://play.google.com/console) → **Create app**: name "Social Web for Ghost", default language English (United States), App, Free.
2. The first upload enrolls the app in **Play App Signing**: Google keeps the app signing key, you keep only the upload key. Accept the enrollment when prompted.
3. Fill in the listing (see [Store listing](#store-listing)) and the forms under **Policy and programs → App content** (see below).

Personal developer accounts created after November 2023 must run a closed test with at least 12 testers for 14 days before they can apply for production access. Organization accounts don't.

### 3. API access for fastlane (optional)

Play Console → **Setup → API access** → link a Google Cloud project, create a service account, grant it **Release apps to testing tracks** (and **Manage store presence** for listing text), and download its JSON key. Keep the key outside the repo.

## Baseline profile

A baseline profile tells ART which classes and methods to compile ahead of time, so the app starts and scrolls faster right after installation. The `:baselineprofile` module records it (`BaselineProfileGenerator`) by driving the app's demo mode: cold start, scroll Notes, open a thread and a profile, Reader with an article, Explore, Notifications. Because it runs on made-up content it needs no account and no network. The result is committed and `:app:bundleRelease` packs it into the bundle.

Regenerate it when the start-up path or the main screens change a lot, and before a release if it hasn't been done for a while:

1. Start one emulator or device, set to English (the journey finds tabs by their labels). Android 13+ on a non-rooted device works; an emulator image with Google APIs, API 33 or later, is the simplest.
2. Run:

   ```bash
   ./gradlew :app:generateBaselineProfile
   ```

   It builds `nonMinifiedRelease`, runs the generator on the connected device (several cold starts until the profile stops changing) and writes the profile into the app.
3. Commit `app/src/release/generated/baselineProfiles/baseline-prof.txt` and `startup-prof.txt`. `startup-prof.txt` is the part for the first launch only; R8 uses it to put the start-up classes together in the primary dex.

Compare cold start with and without the profile (the profile must be generated first):

```bash
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
```

`StartupBenchmarks` runs 10 cold starts each with `CompilationMode.None()` and with the profile compiled in, on the `benchmarkRelease` variant (R8, profileable, signed with the debug key). Read `timeToInitialDisplayMs` of the two tests in the console output or in the JSON files under `baselineprofile/build/outputs/connected_android_test_additional_output/`. Emulator numbers are only good for comparing the two runs with each other; the build suppresses Macrobenchmark's emulator error for that reason.

Why the profiled variants contain demo code: `nonMinifiedRelease` and `benchmarkRelease` are created by the Baseline Profile plugin as copies of `release`, so without it they would start at the login screen. `app/build.gradle.kts` therefore adds `src/debug`'s Kotlin, assets and manifest to those two variants. The real `release`, and so the bundle, is untouched. Rules for `co.magicpages.socialweb.demo` classes do end up in `baseline-prof.txt` and `startup-prof.txt` (the plugin's `filter { exclude(...) }` didn't remove them with 1.5.0); ART and R8 ignore rules for classes the build doesn't have. To check a release build is clean, build it and search for the package: `grep -c 'socialweb.demo' app/build/outputs/mapping/release/mapping.txt` must print `0`.

## Each release

1. Bump `versionCode` (and `versionName`) in `app/build.gradle.kts`; add `changelogs/<versionCode>.txt` in every locale. If the screens or the start-up path changed a lot, [regenerate the baseline profile](#baseline-profile).
2. Build the signed bundle:

   ```bash
   ./gradlew :app:bundleRelease
   ```

   Output: `app/build/outputs/bundle/release/app-release.aab`. If it says it is unsigned, `keystore.properties` or the environment variables weren't found.
3. Upload it, either way:
   - **Console:** Testing → Internal testing → Create new release → upload the `.aab` → Save → Review → Start rollout.
   - **fastlane supply** (install with `gem install fastlane`):

     ```bash
     fastlane supply --json_key play-key.json --package_name co.magicpages.socialweb \
       --aab app/build/outputs/bundle/release/app-release.aab --track internal \
       --metadata_path fastlane/metadata/android
     ```

     Add `--release_status draft` while the app has no finished listing (Play refuses to publish a release for an incomplete app).
4. Add testers: Testing → Internal testing → Testers → create an email list, then share the opt-in link. Internal testing has no review and is live within minutes (up to 100 testers).
5. Promote the same release to Closed testing, then Production, from the track's **Promote release** menu.

### Store listing

Push the text and graphics without a build:

```bash
fastlane supply --json_key play-key.json --package_name co.magicpages.socialweb \
  --skip_upload_aab --skip_upload_apk --skip_upload_changelogs \
  --metadata_path fastlane/metadata/android
```

Without fastlane, paste each locale's files into Grow → **Store presence → Main store listing** (add languages under Manage translations), and upload the icon, feature graphic and screenshots there.

Also set in the Console: the privacy policy URL (iOS uses `https://www.synapsmedia.com/privacy-social-web-ios/`; use an Android-appropriate page that says the same), support email, category (Social), and the contact website `https://www.synapsmedia.com/about-us/`. The app's About screen opens the same policy from the constant `PrivacyPolicyUrl` in `app/src/main/kotlin/co/magicpages/socialweb/ui/about/AboutScreen.kt`: it is a placeholder (`https://www.magicpages.co/legal/privacy/`) until the app's own policy is published, so change it there to the final URL before release and keep it identical to the Console's. The licenses list on the About screen is generated at build time by the AboutLibraries Gradle plugin (`res/raw/aboutlibraries.json` in the build output), so it needs no upkeep.

## Reviewer access

Play reviewers need to get past sign-in. In **App content → App access** choose "All or some functionality is restricted" and give a throwaway Ghost 6 test site with the social web on: its address, an Administrator email and password (or a Staff Access Token, entered under "Staff token"). Don't use a personal site.

## Forms under App content

### Data safety

What the app really does (checked in code):

- **Network:** only HTTPS (`cleartextTrafficPermitted="false"`). It talks to the Ghost site the user signs in to (Admin API, ActivityPub endpoints, members Portal in a web view) and to fediverse servers the user's site federates with (profiles, posts, images, videos, server rules). There is no developer backend.
- **Credentials:** email and password go only to the user's own Ghost site, to open a session; the password is not stored. The session cookie or Staff Access Token is stored encrypted (Tink AES-GCM, key in the Android Keystore) in app-private storage. Backup and device transfer are excluded (`allowBackup=false`, `data_extraction_rules.xml`).
- **No analytics, crash reporting, ads or advertising ID.** No third-party SDK receives app data. Translation and language detection use Google ML Kit through Google Play services: text stays on the device, only the language files are downloaded.
- **Permissions:** `INTERNET`, `POST_NOTIFICATIONS` (background checks for new notifications, user-controlled). Photos are picked with the system photo picker, so there is no media permission.
- **User reports** are written as an email to `reports@magicpages.co` (and, if the user opts in, the server's moderators) that the user sends from their own mail app; nothing is sent in the background.

Answers:

| Question | Answer |
|---|---|
| Does your app collect or share any required user data types? | Yes. Transmitting data to any server counts as collecting, even the user's own site. Declare the types below. |
| Is all of the user data collected by your app encrypted in transit? | Yes |
| Do you provide a way for users to request that their data is deleted? | No: the app has no developer-held accounts. Signing out removes the stored session from the device; data on the Ghost site and on other servers is managed there. |
| **Personal info → Email address** | Collected, not shared. Purpose: App functionality, Account management. Optional: no (sign-in needs it, unless the user signs in with a Staff Access Token). |
| **Personal info → User IDs** (fediverse handle) | Collected, not shared. Purpose: App functionality. |
| **Photos and videos → Photos** | Collected, not shared. Purpose: App functionality. Optional: yes (images attached to notes). |
| **Messages → Other in-app messages** (notes, replies) | Collected, not shared. Purpose: App functionality. |
| **App activity → Other user-generated content** (likes, reposts, follows, blocks, reports) | Collected, not shared. Purpose: App functionality. |
| Location, contacts, financial info, health, web history, device or other IDs, diagnostics | Not collected |

"Not shared": data goes to servers the user chose, for actions the user took, and never to third parties on the developer's behalf. Re-check the answers if an SDK that talks to the network is added.

### Other forms

- **Privacy policy:** the URL set in the listing.
- **Ads:** No ads.
- **App access:** see Reviewer access.
- **Account creation:** the app doesn't create accounts (it signs in to an existing Ghost site), so no account-deletion URL is needed.
- **Target audience:** 18 and over is the simplest, since users can encounter any fediverse content. Not designed for children.
- **Content rating (IARC questionnaire):** category *Social / communication*. Answer **Yes** to user-generated content and to users interacting with each other; **No** to sharing the user's location, digital purchases, and gambling. Violence, sexual content and similar questions: describe the app, not the content ("only user-generated, from other servers"). Expect a Teen/Mature-style rating because of unmoderated UGC.
- **User-generated content policy:** the app meets it with in-app **Report** (posts and accounts), **Block** (accounts and whole servers) and a blocked list in Settings. Mention these if Play asks.
- **Government apps, financial features, health, news, COVID, advertising ID, exact alarms, foreground services:** none apply.
- **Data safety, privacy and content rating** must all be complete before a release can be sent for review.
