# Assignment 3 — reproducible TITAN submission

The TITAN smoke checks previously passed with **one executable Spring Boot server JAR at the ZIP root** and **Assignment 2's Maven JavaFX project at the ZIP root**. In particular, TITAN invokes `mvn javafx:run -Djavafx.args=--help` in its working directory.

Use `scripts/build-titan-submission.ps1` from PowerShell on Windows after committing your working changes. It runs `mvn clean verify` for both projects, copies only required project sources and the newly built Spring Boot JAR, and creates `COMP3011_TITAN.zip` **in the parent directory of the repository**. No `-Pfat-jar` is used.

From the combined repository directory:

```powershell
& .\scripts\build-titan-submission.ps1
```

If PowerShell blocks execution due to script policy, use:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-titan-submission.ps1
```

To choose an output filename, pass `-OutputZip 'C:\path\COMP3011_TITAN.zip'`.

Check the archive before submitting:

```powershell
tar -tf ..\COMP3011_TITAN.zip | Select-Object -First 15
```

The archive must have `pom.xml`, `src/` and the server JAR **directly at the top level**. There is no video file, `.git` directory, `target` directory, API key, `.env` file, or video-player fat JAR in the submission. The build includes server source under `Assignment1/` solely for inspection and grading.

The script validates the expected ZIP structure, but does not run the TITAN service itself. Passing TITAN's four initial checks verifies launch/usage/help only; test caption quality, seek and toggle behaviour, and failure handling separately.
