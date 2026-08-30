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

    mediaRecorder.addEventListener('stop', () => {
        const audioBlob = new Blob(audioChunks, { type: 'audio/webm' });
        console.log('Recording stopped. Blob size:', audioBlob.size, 'bytes');

        statusEl.textContent = 'Ready to record';
        statusEl.classList.remove('recording');
        recordBtn.hidden = false;
        stopBtn.hidden = true;

        // Stage 2 will send audioBlob to the backend here instead of just logging it
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