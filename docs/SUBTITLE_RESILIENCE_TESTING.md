# Subtitle resilience: verification and viva notes

The player runs audio extraction on a separate worker and makes an asynchronous
HTTP request to `localhost:8080`. Video decoding, effects, audio output, and the
JavaFX animation loop do **not** wait for Whisper. A failed or late transcription
therefore affects the optional captions, not the video playback pipeline.

## Automated checks (Commit 21)

`src/test/java/comp3011/subtitle/SubtitleResilienceRegressionTest.java`
uses injected, deterministic fake extraction and transcription stages. It does
not make paid API requests or depend on FFmpeg natives. It covers:

- An uncancellable old HTTP response arriving after a different video was opened
- A late failure from the previous video after the new one succeeded
- Forward and backward seeking without making new transcription requests
- Rapid caption toggles during an in-progress transcription
- A failed server request followed by successful playback of a different file
- Closing the player while a noncancellable response remains in flight
- Media disappearing from disk before audio extraction reads it
- Cancelling queued work before an extraction/upload can begin

The previous suites also cover binary WAV uploads, server HTTP errors, upload
cleanup, audio extraction quality, subtitle timing and the in-memory cache.

From the project root:

```powershell
mvn clean verify
mvn -f Assignment1/pom.xml clean verify
```

## Manual checks (use the `local` mock profile; no Whisper charges)

Start the mock server in one terminal:

```powershell
mvn -f Assignment1/pom.xml spring-boot:run "-Dspring-boot.run.profiles=local"
```

Start the player in a second terminal (with a valid local `movie.mp4`):

```powershell
mvn javafx:run "-Djavafx.args=movie.mp4 -aSfyv"
```

1. **Basic playback and effects:** audio and flicker/yellow/vignette remain active
   with captions enabled.
2. **Toggle:** press `s` or click CC while playing, then while paused. Captions
   hide immediately; video and audio continue without restarting.
3. **Seek:** seek forward and backward into different caption intervals. Captions
   match the newly displayed position, including silence/gaps.
4. **Server failure:** stop the mock server *before* starting a new player process.
   Video should still run. Caption status should report failure rather than crash.
   Restart the mock server and toggle captions off and on to request a retry.
5. **Slow result:** observe the player with an in-flight transcript; continue
   playback/seek/pause. When subtitles arrive they should correspond to the
   currently displayed timestamp. (The unit tests simulate arbitrarily late
   responses deterministically.)
6. **Cache:** when using the production profile, replay the same video *without
   restarting the server*. The second upload should use the existing transcript,
   and `/api/v1/global/usage` should not increase. Restarting the server clears
   the cache; a different audio file produces a miss.
7. **Media missing:** try to open a nonexistent video. The player should report
   the media/open failure, and subtitle extraction must not send a request.

### Limits to explain in the viva

- The cache is bounded and in memory (up to 16 completed transcripts), not a
  persistent on-disk subtitle store.
- The initial transcription still takes time. Captions can appear late; video
  playback must not wait for them.
- Seeking is a timeline lookup, not a new paid Whisper request.
- In-flight HTTP uploads may finish after cancellation so their temporary WAV
  can be deleted safely; stale results are ignored by a media-generation check.
- Live subtitles are composited using a transparent overlay at the displayed
  frame timestamp; the frame-processor interface remains part of the pipeline.

Record any failures against the exact local reproducer before changing code.
