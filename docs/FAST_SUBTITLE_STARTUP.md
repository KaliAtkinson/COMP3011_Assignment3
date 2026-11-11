# Optional fast-start subtitles

Normally the player extracts and uploads the whole movie audio to the Assignment 1
server and waits for Whisper's complete timestamped response. On longer videos,
that means early dialogue can pass before the first captions are available.

## Enable opening-caption preview

From the **Assignment 3 repository root**, start the local mock server for a free
functional test:

```powershell
mvn -f Assignment1/pom.xml spring-boot:run "-Dspring-boot.run.profiles=local"
```

In a separate PowerShell terminal, run:

```powershell
$env:COMP3011_FAST_SUBTITLES = "1"
mvn javafx:run "-Djavafx.args=movie.mp4 -aSfyv"
```

With the feature on, the player extracts the opening **20 seconds** as its own
16 kHz mono WAV. The preview is sent to `/api/v1/subtitles`, while the full
movie's original audio is prepared on a background thread and submitted in a
second request. After the preview arrives it can display the first captions
immediately; the full response replaces the preview without restarting playback
or making an additional request when seeking. The log shows:

```
[subtitles] Opening preview received; ... phrases available; full transcription still loading
[subtitles] Response received; ... timed phrases available
```

The second line may appear first if the full request finishes earlier. If the
preview fails, the full request still proceeds. If the full request fails after
the preview succeeded, the opening captions remain available.

Disable the feature by running `Remove-Item Env:COMP3011_FAST_SUBTITLES`
(or launching the player without the variable in a new terminal). The old
single-request path remains the default.

## Live timing test and cost

To measure the improvement, use the production server **with your own API key
set only in its process environment**. The local mock responds so quickly that
it is useful for correctness but not for meaningful latency benchmarks.

The first play of a new video in fast-start mode sends **two paid Whisper
requests**, one for 20 seconds and another for the full audio. The overlapping
20 seconds are billed twice; the server's usage endpoint correctly records both
successful requests. The in-memory server cache can reuse both results on
later playback of the same files, but it resets on restart. For a video shorter
than 20 seconds, fast-start provides no latency benefit and may duplicate the
entire transcription, so leave it disabled.

Neither mode changes live video/audio scheduling, existing frame effects or the
required Whisper model (`whisper-1` with word and segment timestamps). It is an
optional client-side optimisation, not true streaming transcription. Word
boundaries near the 20-second cut may be incomplete in the preview; the full
response corrects them once available. Actual speedup depends on extraction,
network and transcription latency and has not been benchmarked on TITAN.

## Tests

```powershell
mvn clean verify
mvn -f Assignment1/pom.xml clean verify
```

The new tests verify that a clipped WAV contains the correct duration and
samples, that preview results arrive while full upload is pending, both WAVs
are cleaned up, old-video previews cannot leak into new media, and failures
fall back without interrupting playback.

For TITAN, disable `COMP3011_FAST_SUBTITLES` unless you explicitly intend to
trade increased Whisper usage for earlier opening captions.
