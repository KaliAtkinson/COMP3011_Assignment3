# Assignment 3 — Inspecting subtitle recognition and timing

The player normally does **not** save or print dialogue text. To compare the
original Whisper result with the exact captions used by the overlay, explicitly
opt in to a local SRT diagnostic export.

From the Assignment 3 repository root, in the PowerShell terminal used for the
video player:

```powershell
$env:COMP3011_EXPORT_SUBTITLES = "1"
mvn -f Assignment2/pom.xml javafx:run "-Djavafx.args=movie.mp4 -aSfyv"
```

Keep the Assignment 1 subtitle server running separately (local mock for a free
smoke test; `production` for real Whisper recognition). The export runs **only
when a transcript has completed successfully**. No transcript is saved when
export is disabled. A successful request prints two temporary file paths:

- `whisper-segments.srt` — Whisper's segment text (with normalised whitespace) and its
  start/end timestamps. Segments excluded by the assignment's silence rule are
  omitted. This reveals what the speech service actually recognised.
- `displayed-captions.srt` — the words, line breaks and display times selected
  by our JavaFX player *after* splitting and one-to-seven-second holding rules.

Use a text editor to compare the files, or load each SRT as an external subtitle
track in a media player that supports external SRT files. If a word is wrong in
both files, the problem is upstream speech recognition; reformatting cannot
recover it. If raw words are correct but displayed captions lose them, investigate
word-to-segment alignment and splitting. If all cues are consistently early or
late, measure the offset against actual dialogue before changing the clock.

**Privacy:** Both SRT files contain spoken dialogue. They are created in the OS
temporary directory, not the repository, and should not be committed or shared
without reviewing their contents. Delete them after testing. The exporter does
not send an extra API request and does not interfere with caption display.

To stop exporting in the current PowerShell session:

```powershell
Remove-Item Env:COMP3011_EXPORT_SUBTITLES
```

The first real Whisper transcription may still take time; this diagnostic commit
does not make recognition faster or improve recogniser accuracy by itself.
