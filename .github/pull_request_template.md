## Summary

<!-- What changed and why. Link issues with "Closes #123". -->

## Checklist

- [ ] Each changed extension's `extVersionCode` (or theme `baseVersionCode`) is bumped **once** in this PR
- [ ] `libVersion = 16` and only the extensions-lib 16 API is used
- [ ] `./gradlew -Pextensions=<lang>:<name> :src:<lang>:<name>:spotlessCheck :src:<lang>:<name>:testDebugUnitTest :src:<lang>:<name>:assembleDebug` passes
- [ ] `python3 tools/validate_extensions.py` passes
- [ ] Source `README.md` documents access method, limitations and rights
- [ ] No credentials, cookies or personal data in code, fixtures or logs
- [ ] Renamed source? The old `id` is preserved (see docs/DISTRIBUTION.md)
