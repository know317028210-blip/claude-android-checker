<div align="center">

# Claude Environment Check

**Before you open Claude, look at what your device is telling them.**

21 signals · weighted scoring · on-device · open source

[![License](https://img.shields.io/badge/license-MIT-d97757.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-3a9e6b.svg)](#install)
[![Size](https://img.shields.io/badge/APK-65%20KB-78716a.svg)](#install)
[![No Tracking](https://img.shields.io/badge/telemetry-none-3a9e6b.svg)](#privacy)

[简体中文](README.md) · [繁體中文](README.zh-TW.md) · **English** · [日本語](README.ja.md)

</div>

---

## What this is

A diagnostic app for your phone. It reads the signals your device already emits and tells you whether this environment looks like **an ordinary user overseas**, or like **a set of fingerprints that have been flagged before**.

Unlike a web-based check, this is a native Android app, so it reaches things a page never can: which fonts are installed in the system, which country the SIM belongs to, whether it is an eSIM, whether app cloning is in use, whether the ROM is official, whether the VPN is system-wide.

You get a score out of 100 and one sentence of conclusion. The score itself is not the point. What matters is **which item is dragging you down, why, and how to fix it**.

<br>

<div align="center">
<table>
<tr>
<td><img src="docs/screenshots/01-home-zh.png" width="200"></td>
<td><img src="docs/screenshots/02-scan-zh.png" width="200"></td>
<td><img src="docs/screenshots/03-result-zh.png" width="200"></td>
<td><img src="docs/screenshots/04-detail-zh.png" width="200"></td>
</tr>
<tr>
<td align="center">Home</td>
<td align="center">Checking</td>
<td align="center">Score</td>
<td align="center">All checks</td>
</tr>
<tr>
<td><img src="docs/screenshots/05-sheet-zh.png" width="200"></td>
<td><img src="docs/screenshots/06-settings-zh.png" width="200"></td>
<td><img src="docs/screenshots/07-home-en.png" width="200"></td>
<td><img src="docs/screenshots/09-home-ja.png" width="200"></td>
</tr>
<tr>
<td align="center">Item detail</td>
<td align="center">API & language</td>
<td align="center">English</td>
<td align="center">日本語</td>
</tr>
</table>
</div>

---

## What it checks

21 items, scored by how much each one actually weighs in risk decisions. **The same mistake costs wildly different amounts depending on the item.**

| Weight | Item | Why this weight |
|:---:|---|---|
| **20** | IP location | A hard gate written into the policy; instant veto |
| **13** | IP type and cleanliness | Datacenter and residential are treated nothing alike, and many "residential" proxies are datacenter IPs renamed |
| 7 | System timezone | The most direct evidence of physical location |
| 6 | IPv6 leak | A real exit that bypasses the tunnel |
| 5 | WebRTC leak / timezone vs IP / emulator / system language / SIM | Not fatal alone, heavy when stacked |
| 4 | System locale / CJK fonts / payment environment | Mid-layer profiling signals |
| 3 | Root / vendor fonts / cloned apps | Environmental trust |
| 2 | Chinese variant / timezone offset | Small contradictions |
| 1 | Device model / ROM state / proxy tunnel / reachability | Informative, but should not sink the total |

Full rules, hidden heuristics and known misjudgements live in **[docs/DETECTION.md](docs/DETECTION.md)** — including what counts as a failure for every item.

### Five instant vetoes

Hit any one and the verdict is "do not use", regardless of score:

- System timezone is a mainland China timezone
- Exit IP is in an unsupported region
- IPv6 exit differs from the IPv4 region
- WebRTC exposed a public address from another region
- Running on an emulator or cloud phone

---

## A small example

Same "deduction", twenty times the cost:

```
Wrong device model (a Xiaomi phone)   → lose 1 point
IP in an unsupported region           → lose 20 points, and get vetoed
```

That is why items are not all worth 10. An early build scored every item out of 10, which meant "your phone is a Xiaomi" and "you are in mainland China" cost exactly the same. That number was meaningless.

---

## Install

**Option 1: APK**

Grab `ClaudeDoctor.apk` from [Releases](../../releases) and allow installs from unknown sources.

**Option 2: Web version (same UI, minus native checks)**

Open `app/index.html` in a browser. It cannot read the font table, SIM country, root state or cloned apps; those items are marked "unreadable in a browser" instead of silently passing.

**Option 3: Desktop**

```bash
# Windows
desktop/doctor-lite/ClaudeDoctorLite.exe

# macOS / Linux
bash desktop/claude-env-check.sh
```

---

## Build it yourself

No Gradle needed — just the Android SDK (build-tools + platform 34) and a JDK.

```bash
git clone https://github.com/<your-name>/claude-doctor.git
cd claude-doctor/android
bash build.sh
```

The script runs `aapt2 compile → link → javac → d8 → zipalign → apksigner`, produces a 65 KB APK and installs it to any connected device.

> The first build creates `android/keystore.jks`. That is your signing key, already excluded in `.gitignore`. **Do not commit it.**

---

## Privacy

- Every decision happens on device; results never leave it
- Only the IP checks contact external services, and they send your **exit IP** because the query cannot work without it
- No device identifiers, no accounts, no location, no contacts
- No analytics, no telemetry, no sign-up

External endpoints and their free quotas are listed in [docs/DETECTION.md](docs/DETECTION.md#六外部接口与配额).

---

## Scope

This is a **diagnostic tool**. It does not provide any means of bypassing regional restrictions and it does not modify system settings — it reads, then reports.

Results are indicative, not an official Anthropic assessment. Real risk decisions are dynamic and multi-dimensional; this tool produces a risk profile from public policy and community observation.

If you are in a supported region on a normal device and home broadband, you should score high. If you score low, the environment really does contain contradictions worth fixing before signing in.

---

## Contributing

Issues and PRs welcome. Especially:

- **New checks** — particularly native-only ones (sensor consistency, GPU model, DNS egress)
- **False positive reports** — include your measured values and reasoning; these are the most valuable
- **Translations** — currently zh-CN, zh-TW, en, ja
- **Intelligence sources** — keyless, quota-friendly IP APIs

No style guide. Working code that explains itself is enough. Detection logic lives in the `CHECKS` array inside `app/index.html`; adding a check means adding an object.

---

## License

[MIT](LICENSE)

<div align="center">
<sub>Runs locally. Uploads nothing. Go fix your timezone.</sub>
</div>
