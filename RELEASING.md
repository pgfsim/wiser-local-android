# Making a release

Releases are built and signed by GitHub Actions
([release.yml](.github/workflows/release.yml)) when a version tag is pushed.
Raise `versionCode` and `versionName` in `app/build.gradle.kts`, commit, then:

```bash
git tag v0.2.0
git push origin v0.2.0
```

The tag has to match `versionName`. The release gets the signed APK and its
SHA-256 checksum, and the workflow log prints the signing certificate so it can
be compared with the fingerprint in the README.

## The signing key

Every release must be signed with the same key, because Android only installs
an update signed with the key of the installed version. Keep the keystore file
and its password backed up somewhere safe; losing either means existing users
can't update.

The key is kept out of the repo. The workflow reads it from four repository
secrets (**Settings → Secrets and variables → Actions**):

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | The keystore file, base64-encoded |
| `SIGNING_STORE_PASSWORD` | The keystore password |
| `SIGNING_KEY_ALIAS` | The key's alias (`wiser-local`) |
| `SIGNING_KEY_PASSWORD` | The key's password (the same as the store password for a PKCS12 keystore) |

To copy the keystore as base64 on Windows (PowerShell):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\wiser-local-release.jks")) | Set-Clipboard
```

## Signed builds on your own machine

Create a `keystore.properties` file next to `settings.gradle.kts` (it is
git-ignored):

```properties
storeFile=C:/path/to/wiser-local-release.jks
storePassword=...
keyAlias=wiser-local
keyPassword=...
```

Without it, `./gradlew assembleRelease` builds an unsigned APK.

## Testing the in-app updater

Build with `-PupdateApi=http://host:port` to point the update check at a test
server; it requests `/repos/pgfsim/wiser-local-android/releases/latest` there.
