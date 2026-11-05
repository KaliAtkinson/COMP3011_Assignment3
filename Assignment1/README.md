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