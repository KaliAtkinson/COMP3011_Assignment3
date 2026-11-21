# COMP3011 Assignment 3 — reproducible TITAN submission

The tested TITAN archive layout places the **JavaFX Maven project at ZIP root**,
including `pom.xml` and `src/`. TITAN runs `mvn javafx:run` from that root. The
executable Spring Boot **server JAR must also be directly at ZIP root**.
`Assignment1/` contains server source but is not TITAN's JavaFX working directory.

The current repository already has the JavaFX `pom.xml` and `src/` at root.
Do **not** use the older packaging script variant that expects an `Assignment2/`
subdirectory.

## Preserve the exact passing artifact first

The most recent submitted ZIP passed **all 16 TITAN checks**. Keep that exact
archive unchanged (outside Git) and record its SHA-256 before attempting any
fresh packaging. A rebuilt archive should be considered a **new candidate**
until it passes TITAN, even if its source is equivalent.

```powershell
# Replace the path with the archive that actually achieved 16/16.
Copy-Item '..\COMP3011_TITAN.zip' '..\COMP3011_TITAN_16of16_BACKUP.zip'
Get-FileHash '..\COMP3011_TITAN_16of16_BACKUP.zip' -Algorithm SHA256
```

## Build the submission

From the repository root in PowerShell:

```powershell
& .\scripts\build-titan-submission.ps1 -OutputZip '..\COMP3011_TITAN_REBUILT.zip'
```

The script runs `mvn clean verify` for **both** projects and rebuilds the server
JAR. The example above puts the **new** ZIP beside the original without
overwriting the proven passing artifact. If no `-OutputZip` is supplied, the
script's default in this finalisation bundle is `COMP3011_TITAN_REBUILT.zip`
in the repository's parent directory, so it does not overwrite the passing ZIP.
To specify a different output path:

```powershell
& .\scripts\build-titan-submission.ps1 -OutputZip 'C:\path\COMP3011_TITAN.zip'
```

If PowerShell's script execution policy blocks it:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-titan-submission.ps1
```

This changes the execution policy for that one process only.

## Check the ZIP

```powershell
tar -tf ..\COMP3011_TITAN_REBUILT.zip | Select-Object -First 20
```

The archive should have entries like these, **without an enclosing folder**:

```text
pom.xml
src/main/java/comp3011/VideoPlayerApp.java
Assignment1/pom.xml
Assignment1/src/...
scripts/build-titan-submission.ps1
kali-0.0.1-SNAPSHOT.jar
```

The final JAR name may follow the server's `pom.xml` version. The script
includes exactly one rebuilt server JAR at ZIP root and excludes Maven `target/`
directories, `.git/`, `.env`, local video files and other generated artifacts.
It does not require an API key to build, and it does not include your key.

**The packaging script does not run TITAN itself.** A passing Maven build proves
that local tests passed, but the final video-playback and subtitle behaviour must
still be checked against the latest TITAN report.

## Runtime-specific caution

The player suppresses the periodic `Frame headroom` messages during ordinary
playback. Those diagnostic lines must remain opt-in: TITAN's earlier
output-capture arrangement appeared to block JavaFX when the console pipe
filled, stopping at frame 2,807. The silent-by-default version displayed
frame **3,288** and passed T06. The transcript/excerpt cache also passed T13–T15.
Do not mix the old final-frame-hold experiments, probe classes or diagnostic
logging back into the passing source.
