# Fixture "adb" for guards-selftest.ps1 phase 2 (live-loop smoke test). NOT a real adb.
#
# It mimics a host with no device attached: every call prints an adb error line and exits 1.
# guards.ps1 merges stdout+stderr (2>&1) and only needs non-numeric text to report a probe
# failure, so printing on stdout keeps this fixture deterministic under PowerShell 5.1.
Write-Output 'error: no devices/emulators found'
exit 1
