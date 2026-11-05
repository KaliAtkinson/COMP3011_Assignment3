const recordBtn = document.getElementById('recordBtn');
const stopBtn = document.getElementById('stopBtn');
const statusEl = document.getElementById('status');

let mediaRecorder;
let audioChunks = [];

recordBtn.addEventListener('click', startRecording);
stopBtn.addEventListener('click', stopRecording);

async function startRecording() {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });

    mediaRecorder = new MediaRecorder(stream);
    audioChunks = [];

    mediaRecorder.addEventListener('dataavailable', (event) => {
        audioChunks.push(event.data);
    });

    mediaRecorder.addEventListener('stop', async () => {
        const audioBlob = new Blob(audioChunks, { type: 'audio/webm' });

        statusEl.textContent = 'Transcribing…';
        statusEl.classList.remove('recording');
        recordBtn.hidden = false;
        stopBtn.hidden = true;

        const formData = new FormData();
        formData.append('audio', audioBlob, 'recording.webm');

        try {
            const response = await fetch('/api/v1/transcribe', {
                method: 'POST',
                body: formData
            });

            if (!response.ok) {
                throw new Error('Transcription failed: ' + response.status);
            }

            const data = await response.json();
            document.getElementById('transcript').textContent = data.text;
            statusEl.textContent = 'Ready to record';

        } catch (err) {
            console.error(err);
            statusEl.textContent = 'Error — try again';
        }
    });

    mediaRecorder.start();

    statusEl.textContent = 'Recording…';
    statusEl.classList.add('recording');
    recordBtn.hidden = true;
    stopBtn.hidden = false;
}

function stopRecording() {
    mediaRecorder.stop();
}