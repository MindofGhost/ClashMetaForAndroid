## Clash Meta for Android

A Graphical user interface of [Clash.Meta](https://github.com/MetaCubeX/Clash.Meta) for Android

### Feature

Feature of [Clash.Meta](https://github.com/MetaCubeX/Clash.Meta)

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/com.github.metacubex.clash.meta/)

### Requirement

- Android 5.0+ (minimum)
- Android 7.0+ (recommend)
- `armeabi-v7a` , `arm64-v8a`, `x86` or `x86_64` Architecture

### Build

1. Update submodules

   ```bash
   git submodule update --init --recursive
   ```

2. Install **OpenJDK 11**, **Android SDK**, **CMake** and **Golang**

3. Create `local.properties` in project root with

   ```properties
   sdk.dir=/path/to/android-sdk
   ```

4. (Optional) Custom app package name. Add the following configuration to `local.properties`.

   ```properties
   # config your ownn applicationId, or it will be 'com.github.metacubex.clash'
   custom.application.id=com.my.compile.clash
   # remove application id suffix, or the applicaion id will be 'com.github.metacubex.clash.alpha'
   remove.suffix=true

5. Create `signing.properties` in project root with

   ```properties
   keystore.path=/path/to/keystore/file
   keystore.password=<key store password>
   key.alias=<key alias>
   key.password=<key password>
   ```

6. Build

   ```bash
   ./gradlew app:assembleAlphaRelease
   ```

### Bypass Helpers

With the TURN fallback option enabled, the subscription can link each helper to a Clash proxy:

```yaml
bypass:
  - type: turn
    endpoint: hysteria_WL
    check: "https://vk.ru = true; https://google.com = false"
    config: "-listen 127.0.0.1:9000 -peer 203.0.113.10:56000 -vk-link https://vk.ru/call/join/EXAMPLE"
  - type: turn
    endpoint: hysteria_WL2
    config: "-listen 127.0.0.1:9001 -peer 203.0.113.20:56000 -vk-link https://vk.ru/call/join/EXAMPLE2"
```

`endpoint` is required and must exactly match an existing proxy name. Endpoint names must be unique;
each TURN instance needs its own local listen address matching its proxy's server and port.
All names in `bypass` are excluded when evaluating ordinary proxy availability. Unsupported helper
types are not started. TURN candidates start after the first failed ordinary check and are confirmed
after the second. Before confirmation, ordinary recovery cancels startup immediately; after confirmation,
stopping requires two successful ordinary checks and 60 seconds from the first success.

Optional `check` contains semicolon-separated `URL = true/false` conditions. All conditions must
match before that helper starts. Checks send GET requests with the default OkHttp User-Agent, follow HTTP
and HTTPS redirects, and succeed only for a final 2xx response. Other statuses, DNS/TLS errors,
and timeouts count as failure. Each request has a 10-second overall timeout. Sockets and system DNS
are bound to the selected physical network, bypassing the VPN and system HTTP proxies; checks
are rejected if that network changes. Without `check`, no additional startup probe is performed.
This replaces the previous VK-link DNS precheck; it does not replace ordinary Clash health checks.
A failed startup check blocks retries for that helper until the next completed ordinary health-check
round. The watchdog and individual helper results cannot retry it in between. Another failed ordinary
round permits a new attempt; ordinary recovery cancels startup as usual.

A candidate wins only after its endpoint passes a core health check and is selected by a top-level
group (following nested selections). Other candidates are cancelled and stopped. Ties retain an
eligible current winner, otherwise use subscription order. A failed check of an active connected
helper requests its reconnect only after the first-stream warm-up barrier completes (first
successful connection or the existing 20-second timeout). Every UDP reconnect re-arms this
barrier. Candidates waiting for manual captcha are left to finish.
Credentials and personas are cached separately per endpoint under the app cache directory.

### Automation

APP package name is `com.github.metacubex.clash.meta`

- Toggle Clash.Meta service status
  - Send intent to activity `com.github.kr328.clash.ExternalControlActivity` with action `com.github.metacubex.clash.meta.action.TOGGLE_CLASH`
- Start Clash.Meta service
  - Send intent to activity `com.github.kr328.clash.ExternalControlActivity` with action `com.github.metacubex.clash.meta.action.START_CLASH`
- Stop Clash.Meta service
  - Send intent to activity `com.github.kr328.clash.ExternalControlActivity` with action `com.github.metacubex.clash.meta.action.STOP_CLASH`
- Import a profile
  - URL Scheme `clash://install-config?url=<encoded URI>` or `clashmeta://install-config?url=<encoded URI>`

### Contribution and Project Maintenance

#### Meta Kernel

- CMFA uses the kernel from `android-real` branch under `MetaCubeX/Clash.Meta`, which is a merge of the main `Alpha` branch and `android-open`.
  - If you want to contribute to the kernel, make PRs to `Alpha` branch of the Meta kernel repository.
  - If you want to contribute Android-specific patches to the kernel, make PRs to  `android-open` branch of the Meta kernel repository.

#### Maintenance

- When `MetaCubeX/Clash.Meta` kernel is updated to a new version, the `Update Dependencies` actions in this repo will be triggered automatically.
  - It will pull the new version of the meta kernel, update all the golang dependencies, and create a PR without manual intervention.
  - If there is any compile error in PR, you need to fix it before merging. Alternatively, you may merge the PR directly.
- Manually triggering `Build Pre-Release` actions will compile and publish a `PreRelease` version.
- Manually triggering `Build Release` actions will compile, tag and publish a `Release` version.
  - You must fill the blank `Release Tag` with the tag you want to release in the format of `v1.2.3`.
  - `versionName` and `versionCode` in `build.gradle.kts` will be automatically bumped to the tag you filled above.
