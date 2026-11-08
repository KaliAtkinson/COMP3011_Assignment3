# Assignment 3 — Real Whisper Subtitle Test

The local (`local`) Spring profile returns **mock** subtitles and makes no paid
OpenAI calls. **Real** transcription uses the production profile with the
`whisper-1` model required by the assignment. The video player always calls
`http://localhost:8080/api/v1/subtitles`.

## 1. Verify without spending API credits

From the `COMP3011_Assignment3` repository root:

```powershell
mvn -f Assignment1/pom.xml clean verify
mvn -f Assignment2/pom.xml clean verify
```

These tests use mocked responses. No real OpenAI API key is needed.

## 2. Start the real subtitle server (PowerShell terminal 1)

Keep your API key out of source code, the repository and shell command history.
The following prompts for it without displaying the characters:

```powershell
$secret = Read-Host "OpenAI API key" -AsSecureString
$env:OPENAI_API_KEY = [System.Net.NetworkCredential]::new("", $secret).Password
Remove-Variable secret
mvn -f Assignment1/pom.xml spring-boot:run "-Dspring-boot.run.profiles=production"
```

Do **not** include `-Dspring-boot.run.profiles=local` for this test.
The server should listen on port **8080**. Stop the mock server first to avoid
port conflicts. The key is kept in this terminal's process environment, not in
the repository; never paste the key into a bug report.

## 3. Check usage before playing (PowerShell terminal 2)

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/global/usage
```

Usage starts at 0 seconds after a fresh server restart.

## 4. Play a short video with audio and subtitles

Keep terminal 1 running, then from the repository root in terminal 2:

```powershell
mvn -f Assignment2/pom.xml javafx:run "-Djavafx.args=movie.mp4 -aS"
```

If `movie.mp4` is absent, put your own test media in `Assignment2` or adjust
the filename. The player extracts the **original audio** and sends a WAV to the
server; the server uploads that audio to OpenAI. **A real request is billable.**
Subtitles may not appear until extraction, upload and transcription finish.
Check that the player logs `Response received` with a positive number of timed
phrases and that captions match the spoken dialogue rather than the repeated
"Local subtitle preview" mock text.

Check cumulative usage again:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/global/usage
```

A successful request should increase the total (audio seconds). Usage is kept
in memory and resets when the server restarts. Avoid replaying/restarting the
player unnecessarily while testing real transcription: server-side caching is
not yet implemented, and each fresh request can incur another charge.

## 5. Troubleshooting

- **No subtitles or `HTTP 502`**: check terminal 1. It now logs a **safe**
  upstream status and category, without printing the API key or provider body.
- **HTTP 401/403 upstream**: check API key and project access.
- **HTTP 429 upstream**: rate limit or quota problem.
- **Connection refused**: wrong profile/port, or server not running.
- **Server shows repeated mock text**: the `local` profile is still active.
- **Audio too large**: the transcription API accepts uploads up to 25 MB;
  use shorter test media. This implementation has no long-audio chunking yet.

When done, press Ctrl+C in the server terminal and clear its environment key:

```powershell
Remove-Item Env:OPENAI_API_KEY
```

**Privacy:** uploaded audio is transmitted to OpenAI when using the production
profile. Test only with media you are authorised to process.
