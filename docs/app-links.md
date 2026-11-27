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
  1. looks the handle up through the signed-in site's social web API (`ActivityPubClient.profile`), whose answer carries the actor's ID,
  2. builds the note ID on the actor's Ghost site (`ghostNoteId`),
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

The file has one statement, for the release package `co.magicpages.socialweb`, listing every key a release is signed with:

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "co.magicpages.socialweb",
      "sha256_cert_fingerprints": [
        "C1:E9:A7:26:48:A9:4C:88:3C:0C:DF:DA:C5:F9:DE:AD:D2:95:36:DE:3B:83:9C:1D:8F:A2:B0:1F:84:9C:04:9E",
        "C7:AC:81:A3:2F:0A:7D:10:1C:C6:BB:C4:BF:26:3C:98:85:F6:87:8F:04:2D:22:C9:CF:34:CE:32:B7:40:7D:3F",
        "B6:0C:6F:4E:E0:24:49:73:8C:0F:17:FE:7B:70:1B:C3:8F:AE:8B:D9:03:22:26:51:97:F6:FD:B6:76:10:38:77",
        "8A:15:95:5F:34:44:2C:AC:5F:5B:16:32:98:A3:E7:6E:98:ED:3D:70:76:80:F9:B9:51:FF:D6:EB:BC:E0:20:E9",
        "E0:3C:B9:24:16:0D:96:43:27:31:AE:44:79:8E:5D:5E:A5:D9:E9:FB:46:36:ED:8C:69:F0:4F:6F:77:88:E2:A0"
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

| Key | SHA-256 | Which builds verify against it |
|---|---|---|
| Play app signing key, original | `C1:E9:A7:26:48:A9:4C:88:3C:0C:DF:DA:C5:F9:DE:AD:D2:95:36:DE:3B:83:9C:1D:8F:A2:B0:1F:84:9C:04:9E` | Builds installed from Google Play, internal testing included |
| Play app signing key, upgraded (classical) | `C7:AC:81:A3:2F:0A:7D:10:1C:C6:BB:C4:BF:26:3C:98:85:F6:87:8F:04:2D:22:C9:CF:34:CE:32:B7:40:7D:3F` | Builds from Google Play once Play signs with the upgraded key |
| Play app signing key, upgraded (post-quantum) | `B6:0C:6F:4E:E0:24:49:73:8C:0F:17:FE:7B:70:1B:C3:8F:AE:8B:D9:03:22:26:51:97:F6:FD:B6:76:10:38:77` | The same |
| Upload key | `8A:15:95:5F:34:44:2C:AC:5F:5B:16:32:98:A3:E7:6E:98:ED:3D:70:76:80:F9:B9:51:FF:D6:EB:BC:E0:20:E9` | A release build signed locally and installed outside Play (see [RELEASING.md](../RELEASING.md)) |
| GitHub release key | `E0:3C:B9:24:16:0D:96:43:27:31:AE:44:79:8E:5D:5E:A5:D9:E9:FB:46:36:ED:8C:69:F0:4F:6F:77:88:E2:A0` | The APK on the [GitHub releases](https://github.com/magicpages/social-web-for-ghost-android/releases) |
| F-Droid's key | Not known until F-Droid publishes the app. Then read it from F-Droid's APK with `apksigner verify --print-certs` and add it. | Builds installed from F-Droid |

Play Console lists the Play app signing keys and the upload key under Protected with Play → App signing. The Digital Asset Links snippet on that page lists only the original key, so copy the fingerprints from the table instead.

When a signing key is added or changed, update this file and ask fedi.pub to serve the new one.

Debug and dev builds (`co.magicpages.socialweb.debug`) have no statement. Every machine has its own debug key, and the debug keystore's password is public, so anyone holding a copy of a listed one could build a `.debug` app that opens fedi.pub links. Approve the domain by hand instead (see below).

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

**Which key verifies.** A release build (`co.magicpages.socialweb`) verifies against the keys above. A debug or dev build (`co.magicpages.socialweb.debug`) never verifies on its own: approve the domain by hand.

Install a release build, wait about 20 seconds for verification, then check:

```sh
adb shell pm verify-app-links --re-verify co.magicpages.socialweb
adb shell pm get-app-links co.magicpages.socialweb
```

The domain shows as `verified` once fedi.pub serves a matching fingerprint. A number of 1024 or more means verification failed.

**For a debug or dev build, or before fedi.pub serves the file**, approve the domain by hand for the package you installed: `co.magicpages.socialweb.debug` for a debug or dev build, `co.magicpages.socialweb` for a release build. The syntax is in `adb shell pm help`:

```sh
PKG=co.magicpages.socialweb.debug   # or co.magicpages.socialweb for a release build
adb shell pm set-app-links-user-selection --user 0 --package $PKG true fedi.pub
adb shell pm get-app-links --user 0 $PKG
```

To start over, reset the state with `adb shell pm set-app-links --package $PKG 0 all`.

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
