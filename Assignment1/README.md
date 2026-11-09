# COMP3011.assignment1
A website that converts speech to text using an external openai Cloud STT 
(speech-to-text) service.

## Running locally without an OpenAI API key

The real transcription service requires OPENAI_API_KEY to be set as an
environment variable. On TITAN, this is provided automatically. If you're
running the jar manually on a machine without a key configured, you can
use the mock transcription service instead by activating the `local` profile:

    java -jar kali-0.0.1-SNAPSHOT.jar --spring.profiles.active=local

This returns placeholder text instead of a real transcription, but lets
you verify the rest of the application (recording UI, uptime, stats,
graceful shutdown) without needing a valid API key.
## Assignment 3 subtitle transcription cache

The production `POST /api/v1/subtitles` endpoint caches up to 16 recent
successful Whisper transcripts in server memory. The cache key is a SHA-256
hash of the uploaded audio bytes, so identical recordings reuse their text,
word timestamps and segment timestamps even when their filenames differ.
Concurrent uploads of the same audio share one ongoing Whisper request.
Failures are not cached, and cache hits do **not** increase the cumulative
`GET /api/v1/global/usage` audio-seconds total. Only transcription results
are retained, not raw audio. Entries are discarded when the server restarts.

The `local` mock profile bypasses this cache and does not use the OpenAI API.
For a live test, use a single video first; replay the same file without
restarting the server to confirm that the transcript is reused. Different
audio files and a fresh server process still need a real transcription.
