# App Links: fedi.pub

fedi.pub makes shareable pages for Ghost social web accounts. Two kinds of fedi.pub link open in the app instead of the browser:

| Link | Opens |
|---|---|
| `https://fedi.pub/@user@domain` | The profile of `@user@domain` |
| `https://fedi.pub/@user@domain/<note id>/` (trailing slash optional) | The note's thread |

Everything else on fedi.pub, such as the home page and the link generator, stays in the browser.

## How the app handles them

- **Manifest.** `MainActivity` has an `autoVerify` intent filter for `https://fedi.pub` paths that start with `/@`. Every Android version uses this rule. On Android 15 and later, fedi.pub's Dynamic App Links rules narrow it further (see below).
- **Parsing.** `parseFediPubLink` in `data/ghost/GhostSites.kt` reads the link. It's the inverse of `fediPubUrl` and `fediPubNoteUrl`, which make the links the app shares.
  - It ignores host case, a trailing slash, a query string and a fragment.
  - It reads the path decoded, as Android does, so `%40` counts as `@`. An encoded slash makes the link not ours.
  - Any other path is not ours, including one with extra segments.
- **Routing.** The link goes the same way as a link shared to "Open in Social Web": `OpenLinkRequests`, then the navigation in `AppRoot`. It's handled at launch and in `onNewIntent`, since `MainActivity` is `singleTop`.
  - A profile opens `ProfileKey`.
  - A note opens `PostKey` with `resolve = true`.
- **Finding a note.** A note link only carries the author's handle and the last part of the note's ActivityPub ID (`https://<site>/.ghost/activitypub/note/<id>`). The handle's domain needn't be where the site's ActivityPub lives: `@jannis@jannis.io` is `https://www.jannis.io/.ghost/activitypub/users/index`. So the thread screen:
  1. looks the handle up with WebFinger,
  2. builds the note ID on the actor's site (`ghostNoteId`),
  3. loads it the way it loads any post by its ActivityPub ID.
- **Signed out.** The link waits for the sign-in, as a shared link does. A dialog offers to sign in or to open the link in the browser.
- **Failures.** If the lookup fails (offline, unknown handle, no such note), the thread or profile screen shows its usual error with **Open in browser**.
- **Not ours.** A fedi.pub page the app doesn't show, which Android before 15 sends to the app too, goes straight back to the browser.
- **Reaching the browser.** A plain `ACTION_VIEW` intent for these links would come back to the app, so the Custom Tab handler names a browser package for any link the app is the App Link for.

## What fedi.pub serves

fedi.pub serves `https://fedi.pub/.well-known/assetlinks.json`:
- over HTTPS,
- as `application/json`,
- with no redirects.

The file has one statement per package:

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "co.magicpages.socialweb",
      "sha256_cert_fingerprints": [
        "<SHA-256 of the Play app signing key>",
        "<SHA-256 of the upload key>"
      ]
    },
    "relation_extensions": {
      "delegate_permission/common.handle_all_urls": {
        "dynamic_app_link_components": [
          {"/": "/@?*/?*/?*", "exclude": true},
          {"/": "/@?*@?*"}
        ]
      }
    }
  },
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "co.magicpages.socialweb.debug",
      "sha256_cert_fingerprints": [
        "<SHA-256 of each maintainer's debug key>"
      ]
    },
    "relation_extensions": {
      "delegate_permission/common.handle_all_urls": {
        "dynamic_app_link_components": [
          {"/": "/@?*/?*/?*", "exclude": true},
          {"/": "/@?*@?*"}
        ]
      }
    }
  }
]
```

### Fingerprints

| Key | Where to find its SHA-256 | Which builds verify against it |
|---|---|---|
| Play app signing key | Play Console → App integrity → Play app signing (Google's docs call it Release → Setup → App signing). The page also offers a ready Digital Asset Links snippet. | Every build installed from Google Play, internal testing included |
| Upload key | The same Play Console page, or `keytool -list -v -keystore upload.jks -alias upload` | A release build signed locally and installed outside Play (see [RELEASING.md](../RELEASING.md)) |
| Debug key | `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`, or `./gradlew :app:signingReport` | Debug and dev builds (`co.magicpages.socialweb.debug`) |

Each machine has its own debug key, so the `.debug` statement lists the maintainers' debug keys. Anyone else can approve the domain by hand (see below).

### Dynamic App Links

Google documents `relation_extensions` and `dynamic_app_link_components` in [Configure website associations and dynamic rules](https://developer.android.com/training/app-links/configure-assetlinks). Some key facts from that page and [About App Links](https://developer.android.com/training/app-links/about):

- **Who uses them.** Android 15 (API 35) and later, on devices with Google services. Android 14 and lower ignore `relation_extensions` and use the manifest's `/@` rule alone.
- **Narrowing only.** The rules can narrow the manifest's intent filter but not widen it.
- **Rule fields.** A rule can match the path (`"/"`), the query (`"?"`, a dictionary of key/value patterns) and the fragment (`"#"`), and can be an `"exclude"` rule.
- **Wildcards.** `*` matches zero or more characters up to the next character in the pattern, `?` matches one character, and `?*` matches one or more.
- **Order.** Rules are tried in order and the first match wins. A link no rule matches doesn't open the app.
- **Bad rules.** If a field is malformed or empty, Android drops the dynamic rules and falls back to the manifest.

The rules above:
1. exclude paths with a third segment (`/@user@domain/<id>/more`),
2. allow everything else that looks like `/@user@domain…`.

A two-segment path that isn't a note, such as `/@user@domain/feed.xml`, still matches. The app sends it back to the browser, so it's harmless.

## Testing before a store release

**Which key verifies.** A sideloaded debug or dev build (`co.magicpages.socialweb.debug`) verifies against the debug key's fingerprint. Builds from Play internal testing (`co.magicpages.socialweb`) verify against the Play app signing key.

Install the build, wait about 20 seconds for verification, then check:

```sh
adb shell pm verify-app-links --re-verify co.magicpages.socialweb.debug
adb shell pm get-app-links co.magicpages.socialweb.debug
```

The domain shows as `verified` once fedi.pub serves a matching fingerprint. A number of 1024 or more means verification failed.

**Before fedi.pub serves the file** (or for a debug key that isn't listed), approve the domain by hand. The syntax is in `adb shell pm help`:

```sh
adb shell pm set-app-links-user-selection --user 0 --package co.magicpages.socialweb.debug true fedi.pub
adb shell pm get-app-links --user 0 co.magicpages.socialweb.debug
```

To start over, reset the state with `adb shell pm set-app-links --package co.magicpages.socialweb.debug 0 all`.

**Open links the way a browser would:**

```sh
# A profile: opens in the app.
adb shell am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "https://fedi.pub/@jannis@jannis.io"
# A note (its actor lives on www.jannis.io): opens its thread in the app.
adb shell am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "https://fedi.pub/@jannis@jannis.io/e14637d6-a13a-47d8-927b-d7f31e523377/"
# The home page: opens in the browser.
adb shell am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "https://fedi.pub/"
```

Signed out, the profile and note links show the sign-in dialog.

**When changes reach devices.**
- On Android 15 and later, the system re-fetches the file and re-verifies in the background; Google says changes can take up to seven days to reach every device.
- Android 14 and lower verify only when the app is installed or updated.
- Reinstalling forces a new verification, though Google's server-side cache may hold an old answer for a few hours.

[Verify App Links](https://developer.android.com/training/app-links/verify-android-applinks) has more.
