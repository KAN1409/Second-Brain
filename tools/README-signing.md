# Permanent APK signing bootstrap

Distributed Second Brain APKs must never rely on GitHub-hosted runner debug keystores. Those keystores are ephemeral and make Android updates fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

The Android CI workflow accepts these repository secrets:

- `SECOND_BRAIN_KEYSTORE_B64`
- `SECOND_BRAIN_KEYSTORE_PASSWORD`
- `SECOND_BRAIN_KEY_ALIAS`
- `SECOND_BRAIN_KEY_PASSWORD`

The keystore itself is never committed. CI decodes it into the runner temp directory and Gradle signs the debug milestone APK with the permanent key. Local developer builds without these environment variables continue to use the normal local debug key and must not be distributed as update APKs.

Required environment variables consumed by Gradle when permanent signing is active:

- `SB_SIGNING_STORE_FILE`
- `SB_SIGNING_STORE_PASSWORD`
- `SB_SIGNING_KEY_ALIAS`
- `SB_SIGNING_KEY_PASSWORD`

CI also verifies the resulting APK with `apksigner verify --verbose --print-certs` before artifact upload.
