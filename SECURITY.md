# Security policy

## Verifying a download
Releases of this app are built by GitHub Actions from the public source in this repository and signed with the permanent X Team key.

Signing certificate SHA-256:

`F4:D3:0E:85:23:91:17:AE:BC:35:55:43:6C:65:11:BC:A0:5E:E3:50:81:49:9A:4A:42:AE:B0:98:DC:1E:72:DA`

To check an APK yourself, run `apksigner verify --print-certs app.apk`. The SHA-256 digest it prints must match the value above (letters can be upper or lower case, colons can be ignored).

## Automated checks
This repository runs CodeQL, mobsfscan and OpenSSF Scorecard. Their results are in the Security tab.

## Reporting a vulnerability
Please report it privately by email to Blue.W9@proton.me, or use "Report a vulnerability" in the Security tab of this repository. Please do not open a public issue for security problems.

## Supported versions
Only the latest release receives fixes.
