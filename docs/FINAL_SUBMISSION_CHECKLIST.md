# COMP3011 Assignment 3 — final submission and Git checklist

**Baseline:** the last TITAN report supplied in this conversation showed **T01–T16 all YES (16/16)**. This is a report of that run, not proof that any subsequently rebuilt ZIP is identical.

## 1. Preserve the successful ZIP

From the repository root, if the confirmed passing ZIP is in the parent folder:

```powershell
Copy-Item '..\COMP3011_TITAN.zip' '..\COMP3011_TITAN_16of16_BACKUP.zip' -Force
Get-FileHash '..\COMP3011_TITAN.zip' -Algorithm SHA256
Get-FileHash '..\COMP3011_TITAN_16of16_BACKUP.zip' -Algorithm SHA256
```

The hashes should match. Adjust the paths if the ZIP is elsewhere. Keep this backup untouched; don't commit the archive to Git or overwrite it with a fresh build.

## 2. Commit **only** the verified T06 source fix

T15 was already committed separately. Run:

```powershell
git status --short
git diff -- src/main/java/comp3011/VideoPlayerModel.java
```

If `VideoPlayerModel.java` is the **only** changed source file, and the diff only makes per-second frame-headroom printing conditional on `playbackDiagnostics != null`, then:

```powershell
git add -- src/main/java/comp3011/VideoPlayerModel.java
git diff --cached
git commit -m 'fix: prevent playback stalls from console output backpressure'
```

If there are other changes, don't stage them with `git add .`; inspect first. In particular, don't commit `T06PlaybackProbe.java`, `FinalFrameHold.java`, `VideoFrameCountProbeTest.java`, probe notes or `titan-repro.log`.

## 3. Leave the old experiments stashed

```powershell
git status
git stash list
```

There is no need to pop/apply the previous `wip: T06 playback experiments and diagnostics` stash. If it contains other uncommitted work worth preserving, leave it alone.

## 4. Commit documentation separately

After extracting the documentation package, **review the diff** for any local edits you want to keep:

```powershell
git diff -- README.md Assignment1/README.md docs/TITAN_SUBMISSION.md
git status --short
git add README.md Assignment1/README.md docs/TITAN_SUBMISSION.md docs/FINAL_SUBMISSION_CHECKLIST.md docs/ASSIGNMENT3_VIVA_GUIDE.md
git commit -m 'docs: finalise Assignment 3 submission and viva notes'
```

The handover bundle does not change Java, Maven dependencies, tests or server JARs. It also includes an optional, safely named packaging script. Stage it in a separate `build:` commit only if you apply that script. If you've already updated your own docs, merge the text instead of blindly overwriting it.

## 5. Final clean-state checks

```powershell
git status
git log -3 --oneline
```

Keep the original **16/16 TITAN report** and ZIP checksum as evidence. Do not rebuild merely for cleanup; if you intentionally rebuild, use a different filename and treat it as an unverified candidate until TITAN accepts it. No credentials, temporary WAVs, `.env`, `target/`, or media files should be committed.
