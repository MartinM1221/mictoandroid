package com.example.audio; // Replace com.example.audio with your actual package name

// --- Keep ALL existing imports ---
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar; // <<< ADDED: Import SeekBar
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter; // <<< ADDED: Import PrintWriter for easy string sending
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

// Complete code as of 2025-04-13, including servo control.
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BiDiAudioServo"; // Updated Tag
    private static final int ESP32_PORT_RECEIVE_FROM = 8080; // Port for receiving INMP441 data FROM ESP32#1
    private static final int ESP32_PORT_SEND_TO = 8081;      // Port for sending phone mic/melody data TO ESP32#2
    private static final int ESP32_PORT_CONTROL = 8082;      // <<< ADDED: Port for sending control commands TO ESP32#2
    private static final int CONNECTION_TIMEOUT_MS = 5000;

    // --- Audio Config: RECEIVING from ESP32#1 (INMP441) ---
    private static final int SAMPLE_RATE_RECEIVE = 16000;
    private static final int CHANNEL_CONFIG_RECEIVE = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT_RECEIVE = AudioFormat.ENCODING_PCM_32BIT; // Matches ESP32#1 output

    // --- Audio Config: SENDING from Phone Mic/Melody TO ESP32#2 ---
    private static final int SAMPLE_RATE_SEND = 16000; // Melody file MUST match this
    private static final int CHANNEL_CONFIG_SEND = AudioFormat.CHANNEL_IN_MONO; // Melody file MUST match this
    private static final int AUDIO_FORMAT_SEND = AudioFormat.ENCODING_PCM_16BIT; // Melody file MUST match this

    // --- UI Elements ---
    private EditText editTextEspMicSenderIpAddress;
    private EditText editTextEspSpeakerReceiverIpAddress;
    private Button buttonStartStopAll;
    private Button buttonPlayMelody;
    private TextView textViewStatusReceive;
    private TextView textViewStatusSend;
    private TextView textViewMelodyStatus;
    private SeekBar seekBarServo1;             // <<< ADDED
    private SeekBar seekBarServo2;             // <<< ADDED
    private TextView textViewServo1Value;      // <<< ADDED
    private TextView textViewServo2Value;      // <<< ADDED
    private TextView textViewControlStatus;    // <<< ADDED

    // --- Networking ---
    // Audio Sockets/Streams
    private Socket socketReceive = null;
    private InputStream inputStreamReceive = null;
    private Socket socketSend = null;
    private OutputStream outputStreamSend = null;
    // Control Socket/Stream
    private Socket socketControl = null;       // <<< ADDED
    private PrintWriter printWriterControl = null; // <<< ADDED (PrintWriter is easier for sending text commands)

    // --- Audio ---
    // Keep existing audio variables
    private AudioTrack audioTrack = null;
    private AudioRecord audioRecord = null;
    private int audioRecordBufferSize = 0;
    private int audioTrackBufferSize = 0;

    // --- Threading & State ---
    private ExecutorService executorService;
    private Future<?> receiveTaskFuture;
    private Future<?> sendTaskFuture;
    private Future<?> melodyTaskFuture;
    private Future<?> controlConnectFuture; // <<< ADDED
    private volatile boolean isStreaming = false;
    private volatile boolean isPlayingMelody = false;
    private volatile boolean isControlConnected = false; // <<< ADDED
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    // Store IPs
    private String targetMicSenderIpAddress;
    private String targetSpeakerReceiverIpAddress; // Used for audio send AND control


    // --- Permission Handling ---
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.i(TAG, "RECORD_AUDIO permission granted.");
                    // Retry starting with the stored IPs
                    if (targetMicSenderIpAddress != null && targetSpeakerReceiverIpAddress != null) {
                        startStreaming(targetMicSenderIpAddress, targetSpeakerReceiverIpAddress);
                    } else {
                        Log.w(TAG, "IP addresses were null on permission result.");
                        showToast("Please enter IPs again.");
                    }
                } else {
                    Log.w(TAG, "RECORD_AUDIO permission denied.");
                    showToast("Microphone permission is required to send audio.");
                    updateUiState(false, "Idle", "Error: Permission Denied");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Find UI elements
        editTextEspMicSenderIpAddress = findViewById(R.id.editTextEspMicSenderIpAddress);
        editTextEspSpeakerReceiverIpAddress = findViewById(R.id.editTextEspSpeakerReceiverIpAddress);
        buttonStartStopAll = findViewById(R.id.buttonStartStopAll);
        buttonPlayMelody = findViewById(R.id.buttonPlayMelody);
        textViewStatusReceive = findViewById(R.id.textViewStatusReceive);
        textViewStatusSend = findViewById(R.id.textViewStatusSend);
        textViewMelodyStatus = findViewById(R.id.textViewMelodyStatus);
        seekBarServo1 = findViewById(R.id.seekBarServo1);           // <<< ADDED
        seekBarServo2 = findViewById(R.id.seekBarServo2);           // <<< ADDED
        textViewServo1Value = findViewById(R.id.textViewServo1Value);  // <<< ADDED
        textViewServo2Value = findViewById(R.id.textViewServo2Value);  // <<< ADDED
        textViewControlStatus = findViewById(R.id.textViewControlStatus);// <<< ADDED

        // --- Buffer size calculation (using 4x multiplier) ---
        audioRecordBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_SEND, CHANNEL_CONFIG_SEND, AUDIO_FORMAT_SEND);
        audioTrackBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE_RECEIVE, CHANNEL_CONFIG_RECEIVE, AUDIO_FORMAT_RECEIVE);
        if (audioRecordBufferSize > 0) audioRecordBufferSize *= 4; else audioRecordBufferSize = 1024 * 8;
        if (audioTrackBufferSize > 0) audioTrackBufferSize *= 4; else audioTrackBufferSize = 1024 * 8;
        if (AudioRecord.getMinBufferSize(SAMPLE_RATE_SEND, CHANNEL_CONFIG_SEND, AUDIO_FORMAT_SEND) <= 0 ||
                AudioTrack.getMinBufferSize(SAMPLE_RATE_RECEIVE, CHANNEL_CONFIG_RECEIVE, AUDIO_FORMAT_RECEIVE) <=0 ) {
            Log.e(TAG, "getMinBufferSize failed. Check device support/permissions.");
            showToast("Error: Audio config not supported.");
            buttonStartStopAll.setEnabled(false);
            buttonPlayMelody.setEnabled(false);
        } else {
            Log.d(TAG, "AudioRecord BufferSize: " + audioRecordBufferSize);
            Log.d(TAG, "AudioTrack BufferSize: " + audioTrackBufferSize);
        }


        // --- buttonStartStopAll listener ---
        buttonStartStopAll.setOnClickListener(v -> {
            if (!isStreaming) {
                targetMicSenderIpAddress = editTextEspMicSenderIpAddress.getText().toString().trim();
                targetSpeakerReceiverIpAddress = editTextEspSpeakerReceiverIpAddress.getText().toString().trim(); // Used for speaker and control
                if (targetMicSenderIpAddress.isEmpty() || targetSpeakerReceiverIpAddress.isEmpty()) {
                    showToast("Please enter BOTH ESP32 IP Addresses");
                    return;
                }
                checkPermissionAndStartStream(targetMicSenderIpAddress, targetSpeakerReceiverIpAddress);
            } else {
                stopStreaming();
            }
        });


        // --- buttonPlayMelody listener ---
        buttonPlayMelody.setOnClickListener(v -> {
            if (isStreaming && !isPlayingMelody && socketSend != null && outputStreamSend != null && !socketSend.isClosed()) {
                startMelodyPlayback();
            } else if (!isStreaming) { showToast("Start streaming first."); }
            else if (isPlayingMelody) { showToast("Melody is already playing."); }
            else {
                showToast("Send connection not ready or closed.");
                Log.w(TAG, "Melody play attempt failed: isStreaming=" + isStreaming + ", isPlayingMelody=" + isPlayingMelody +
                        ", socketSend=" + (socketSend == null ? "null" : "exists") +
                        ", outputStreamSend=" + (outputStreamSend == null ? "null" : "exists") +
                        ", socketSendClosed=" + (socketSend == null ? "N/A" : socketSend.isClosed()));
            }
        });

        // --- Setup SeekBar Listeners ---
        setupSeekBarListeners();

    } // --- End onCreate ---

    // --- checkPermissionAndStartStream ---
    private void checkPermissionAndStartStream(String micSenderIp, String speakerReceiverIp) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startStreaming(micSenderIp, speakerReceiverIp);
        } else {
            Log.d(TAG, "Requesting RECORD_AUDIO permission...");
            targetMicSenderIpAddress = micSenderIp;
            targetSpeakerReceiverIpAddress = speakerReceiverIp;
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    // --- startStreaming (Modified for Control Connection) ---
    private void startStreaming(String micSenderIp, String speakerReceiverIp) {
        if (isStreaming) return;
        if (audioRecordBufferSize <= 0 || audioTrackBufferSize <= 0) {
            showToast("Cannot start: Invalid audio buffer sizes.");
            Log.e(TAG, "Cannot start streaming due to invalid buffer sizes: Record=" + audioRecordBufferSize + ", Track=" + audioTrackBufferSize);
            return;
        }

        Log.d(TAG, "Attempting to start streaming...");
        isStreaming = true;
        isPlayingMelody = false;
        isControlConnected = false; // <<< ADDED: Reset control connection state
        updateUiState(true, "Connecting...", "Connecting...");
        updateControlStatus("Connecting..."); // <<< ADDED
        updateMelodyStatus("Idle");

        if (!initializeAudioRecord() || !initializeAudioTrack()) {
            Log.e(TAG, "Failed to initialize AudioRecord or AudioTrack.");
            releaseAudioRecord(); releaseAudioTrack();
            updateUiState(false, "Error: Mic/Speaker Init Failed", "Error: Mic/Speaker Init Failed");
            updateControlStatus("Idle");
            isStreaming = false; return;
        }

        // Increase pool size for the control connection task
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newFixedThreadPool(4); // Increased to 4
            Log.d(TAG, "Created new ExecutorService (4 threads).");
        } else { Log.d(TAG, "Reusing existing ExecutorService."); }

        // Submit tasks
        receiveTaskFuture = executorService.submit(new ReceiveAndPlayTask(micSenderIp));
        sendTaskFuture = executorService.submit(new RecordAndSendTask(speakerReceiverIp));
        controlConnectFuture = executorService.submit(new ControlConnectTask(speakerReceiverIp)); // <<< ADDED: Start control connection task
    }

    // --- stopStreaming (Modified for Control Connection) ---
    private void stopStreaming() {
        if (!isStreaming) return;
        Log.d(TAG, "Attempting to stop streaming...");
        isStreaming = false;
        isPlayingMelody = false;
        isControlConnected = false; // <<< ADDED

        // Cancel Futures
        if (melodyTaskFuture != null && !melodyTaskFuture.isDone()) melodyTaskFuture.cancel(true);
        if (receiveTaskFuture != null && !receiveTaskFuture.isDone()) receiveTaskFuture.cancel(true);
        if (sendTaskFuture != null && !sendTaskFuture.isDone()) sendTaskFuture.cancel(true);
        if (controlConnectFuture != null && !controlConnectFuture.isDone()) controlConnectFuture.cancel(true); // <<< ADDED

        updateUiState(false, "Stopping...", "Stopping...");
        updateMelodyStatus("Idle");
        updateControlStatus("Disconnecting..."); // <<< ADDED

        // Shutdown executor
        if (executorService != null) {
            Log.d(TAG, "Shutting down ExecutorService...");
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                    if (!executorService.awaitTermination(500, TimeUnit.MILLISECONDS))
                        Log.e(TAG, "Executor did not terminate.");
                }
            } catch (InterruptedException ie) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executorService = null;
            Log.d(TAG,"ExecutorService shutdown complete.");
        }

        // Release audio resources
        releaseAudioTrack();
        releaseAudioRecord();

        // Close Sockets and Streams
        closeSocket(socketReceive, "Receive Stop Fallback"); closeStream(inputStreamReceive, "Receive Input Stop Fallback");
        closeSocket(socketSend, "Send Stop Fallback"); closeStream(outputStreamSend, "Send Output Stop Fallback");
        closeControlConnection(); // <<< ADDED: Use helper for control connection
        // Nullify refs
        socketReceive = null; inputStreamReceive = null; socketSend = null; outputStreamSend = null;
        // socketControl & printWriterControl are nullified in closeControlConnection()

        Log.d(TAG, "Streaming stopped.");
        updateUiState(false, "Idle", "Idle");
        updateControlStatus("Disconnected"); // <<< ADDED
    }

    // --- startMelodyPlayback ---
    private void startMelodyPlayback() {
        if (isPlayingMelody || !isStreaming || executorService == null || executorService.isShutdown() || outputStreamSend == null || socketSend == null || socketSend.isClosed()) {
            Log.w(TAG, "Cannot start melody: State invalid.");
            if (outputStreamSend == null || socketSend == null || socketSend.isClosed()) {
                showToast("Audio send connection not ready/closed."); return;
            }
            return;
        }
        Log.d(TAG, "Starting melody playback...");
        isPlayingMelody = true;
        updateMelodyStatus("Playing...");
        uiHandler.post(() -> buttonPlayMelody.setEnabled(false));
        melodyTaskFuture = executorService.submit(new SendMelodyTask());
    }

    // --- ReceiveAndPlayTask (ESP32 Mic -> Phone Speaker) ---
    private class ReceiveAndPlayTask implements Runnable {
        private final String ip;
        ReceiveAndPlayTask(String ipAddress) { this.ip = ipAddress; }
        @Override public void run() { /* ... Same as previous version ... */
            byte[] buffer = new byte[audioTrackBufferSize];
            int bytesRead;
            Socket localSocket = null;
            InputStream localInputStream = null;
            try {
                Thread.currentThread().setName("ReceiveAndPlayTask");
                Log.d(TAG, "[Receive] Task started. Connecting to Mic Sender " + ip + ":" + ESP32_PORT_RECEIVE_FROM);
                localSocket = new Socket();
                localSocket.setSoTimeout(5000);
                localSocket.connect(new InetSocketAddress(ip, ESP32_PORT_RECEIVE_FROM), CONNECTION_TIMEOUT_MS);
                localInputStream = localSocket.getInputStream();
                socketReceive = localSocket;
                inputStreamReceive = localInputStream;
                Log.d(TAG, "[Receive] Connected.");
                if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) { Log.e(TAG, "[Receive] AudioTrack not ready."); updateReceiveStatus("Error: Speaker Init Failed"); return; }
                if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) { try { audioTrack.play(); Log.d(TAG, "[Receive] AudioTrack playing."); } catch (IllegalStateException e) { Log.e(TAG, "[Receive] Failed start playback: " + e.getMessage()); updateReceiveStatus("Error: Playback Start Failed"); return; } }
                updateReceiveStatus("Receiving/Playing");
                while (isStreaming && !Thread.currentThread().isInterrupted() && socketReceive != null && !socketReceive.isClosed()) {
                    try {
                        bytesRead = inputStreamReceive.read(buffer);
                        if (bytesRead == -1) { Log.d(TAG, "[Receive] Connection closed (read -1)."); break; }
                        if (bytesRead > 0 && audioTrack != null) {
                            int written = audioTrack.write(buffer, 0, bytesRead);
                            if (written < 0) { Log.e(TAG, "[Receive] AudioTrack write error: " + written); updateReceiveStatus("Error: Playback Write Failed"); break; }
                            else if (written != bytesRead) { Log.w(TAG, "[Receive] Partial AudioTrack write: " + written + "/" + bytesRead); }
                        }
                    } catch (SocketTimeoutException e) { continue; }
                    catch (IOException e) { if (isStreaming && !Thread.currentThread().isInterrupted()) { Log.e(TAG, "[Receive] Read Error: " + e.getMessage()); updateReceiveStatus("Error: Network Read Failed"); } else { Log.d(TAG, "[Receive] Read Error after stop: " + e.getMessage()); } break; }
                    catch (IllegalStateException e) { Log.e(TAG, "[Receive] AudioTrack state error: " + e.getMessage()); updateReceiveStatus("Error: Playback State Issue"); break; }
                }
            } catch (SocketTimeoutException e) { Log.e(TAG, "[Receive] Connect Timeout: " + e.getMessage()); if(isStreaming) updateReceiveStatus("Error: Conn Timeout (Mic)"); }
            catch (IOException e) { if (isStreaming && !Thread.currentThread().isInterrupted()) { Log.e(TAG, "[Receive] Connect Error: " + e.getMessage()); updateReceiveStatus("Error: Conn Failed (Mic)"); } else { Log.d(TAG, "[Receive] Connect Error after stop: " + e.getMessage()); } }
            catch (Exception e) { if(isStreaming) { Log.e(TAG, "[Receive] Unexpected Error: " + e.getMessage(), e); updateReceiveStatus("Error: Unexpected"); } }
            finally { Log.d(TAG, "[Receive] Task finishing."); if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) { try { audioTrack.stop();} catch (Exception e) {} } closeStream(localInputStream, "Receive Task Finally"); closeSocket(localSocket, "Receive Task Finally"); if(!isStreaming){ updateReceiveStatus("Stopped"); } else if (!Thread.currentThread().isInterrupted()) { updateReceiveStatus("Disconnected"); } else { updateReceiveStatus("Stopped (Interrupted)"); } }
        }
    }

    // --- RecordAndSendTask (Phone Mic -> ESP32 Speaker) ---
    private class RecordAndSendTask implements Runnable {
        private final String ip;
        RecordAndSendTask(String ipAddress) { this.ip = ipAddress; }
        @Override public void run() { /* ... Same as previous version (with sleep logic) ... */
            byte[] buffer = new byte[audioRecordBufferSize];
            int bytesRead;
            Socket localSocket = null;
            OutputStream localOutputStream = null;
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "[Send] Permission missing at start!"); updateSendStatus("Error: Permission Missing"); if (isStreaming) { uiHandler.post(MainActivity.this::stopStreaming); } return; }
            try {
                Thread.currentThread().setName("RecordAndSendTask");
                Log.d(TAG, "[Send] Task starting. Connecting to Speaker Receiver " + ip + ":" + ESP32_PORT_SEND_TO);
                localSocket = new Socket();
                localSocket.setSoTimeout(5000);
                localSocket.connect(new InetSocketAddress(ip, ESP32_PORT_SEND_TO), CONNECTION_TIMEOUT_MS);
                localOutputStream = localSocket.getOutputStream();
                socketSend = localSocket;
                outputStreamSend = localOutputStream;
                Log.d(TAG, "[Send] Connected.");
                if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) { Log.e(TAG, "[Send] AudioRecord not ready."); updateSendStatus("Error: Mic Init Failed"); return; }
                if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { try { audioRecord.startRecording(); Log.d(TAG, "[Send] AudioRecord recording."); } catch (IllegalStateException e) { Log.e(TAG, "[Send] Failed start recording: " + e.getMessage()); updateSendStatus("Error: Mic Start Failed"); return; } }
                updateSendStatus("Recording/Sending");
                while (isStreaming && !Thread.currentThread().isInterrupted() && socketSend != null && !socketSend.isClosed()) {
                    if (isPlayingMelody) { try { Thread.sleep(10); continue; } catch (InterruptedException e) { Log.w(TAG, "[Send] Interrupted waiting for melody."); Thread.currentThread().interrupt(); break; } }
                    if (audioRecord == null || audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { Log.w(TAG,"[Send] AudioRecord stopped unexpectedly."); updateSendStatus("Error: Mic Stopped"); break; }
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "[Send] Permission lost."); updateSendStatus("Error: Permission Lost"); break; }
                    bytesRead = audioRecord.read(buffer, 0, buffer.length);
                    if (bytesRead > 0 && outputStreamSend != null) { try { outputStreamSend.write(buffer, 0, bytesRead); } catch (IOException e) { if (isStreaming && !Thread.currentThread().isInterrupted()) { Log.e(TAG, "[Send] Write Error: " + e.getMessage()); updateSendStatus("Error: Network Write Failed"); } else { Log.d(TAG, "[Send] Write Error after stop: " + e.getMessage()); } break; } }
                    else if (bytesRead < 0) { Log.e(TAG, "[Send] AudioRecord read error: " + bytesRead); updateSendStatus("Error: Mic Read Failed (" + bytesRead + ")"); break; }
                }
            } catch (SocketTimeoutException e) { Log.e(TAG, "[Send] Connect Timeout: " + e.getMessage()); if(isStreaming) updateSendStatus("Error: Conn Timeout (Spkr)"); }
            catch (IOException e) { if(isStreaming && !Thread.currentThread().isInterrupted()) { Log.e(TAG, "[Send] Connect/IO Error: " + e.getMessage()); updateSendStatus("Error: Conn/IO Failed (Spkr)"); } else { Log.d(TAG, "[Send] Connect/IO Error after stop: " + e.getMessage()); } }
            catch (IllegalStateException e) { if(isStreaming) { Log.e(TAG,"[Send] AudioRecord state error: " + e.getMessage()); updateSendStatus("Error: Mic State Issue"); } }
            catch (SecurityException e){ Log.e(TAG, "[Send] Security Exception: " + e.getMessage()); if(isStreaming) updateSendStatus("Error: Permission Issue"); }
            catch (Exception e) { if(isStreaming) { Log.e(TAG, "[Send] Unexpected Error: " + e.getMessage(), e); updateSendStatus("Error: Unexpected Send"); } }
            finally { Log.d(TAG, "[Send] Task finishing."); if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) { try { audioRecord.stop();} catch (Exception e) {} } closeStream(localOutputStream, "Send Task Finally"); closeSocket(localSocket, "Send Task Finally"); if(!isStreaming){ updateSendStatus("Stopped"); } else if (!Thread.currentThread().isInterrupted()) { updateSendStatus("Disconnected"); } else { updateSendStatus("Stopped (Interrupted)"); } }
        }
    }

    // --- SendMelodyTask (Phone -> ESP32 Speaker) ---
    private class SendMelodyTask implements Runnable {
        @Override public void run() { /* ... Same as previous version (with scope fix) ... */
            Thread.currentThread().setName("SendMelodyTask");
            Log.d(TAG, "[Melody] Task started.");
            InputStream melodyInputStream = null;
            byte[] melodyBuffer = new byte[1024 * 4];
            int bytesRead = 0;
            long totalBytesSent = 0;
            boolean successful = false;
            OutputStream currentOutputStream = outputStreamSend;
            final int melodyResourceId = R.raw.soothing_melody; // Declared outside try
            try {
                if (currentOutputStream == null) { throw new IOException("Output stream is null."); }
                Log.d(TAG, "[Melody] Attempting to open melody resource ID: " + melodyResourceId);
                melodyInputStream = getResources().openRawResource(melodyResourceId);
                Log.d(TAG, "[Melody] Opened melody resource successfully.");
                while (isStreaming && isPlayingMelody && !Thread.currentThread().isInterrupted() && (bytesRead = melodyInputStream.read(melodyBuffer)) != -1) {
                    try { currentOutputStream.write(melodyBuffer, 0, bytesRead); totalBytesSent += bytesRead; }
                    catch (IOException e) { if(isStreaming) { Log.e(TAG, "[Melody] Write error: " + e.getMessage()); updateMelodyStatus("Error: Network"); } successful = false; break; }
                }
                if (!Thread.currentThread().isInterrupted() && bytesRead == -1) { Log.d(TAG, "[Melody] Reached EOF."); }
                else { successful = false; Log.w(TAG, "[Melody] Loop terminated prematurely/error. Interrupted=" + Thread.currentThread().isInterrupted() + ", isStreaming=" + isStreaming + ", isPlayingMelody=" + isPlayingMelody + ", bytesRead=" + bytesRead); }
                if (currentOutputStream != null && !Thread.currentThread().isInterrupted()) {
                    try { currentOutputStream.flush(); Log.d(TAG, "[Melody] Flushed stream."); if (bytesRead == -1) { successful = true; Log.d(TAG, "[Melody] Marked successful."); } else { successful = false; } }
                    catch (IOException e) { Log.w(TAG, "[Melody] Flush error: " + e.getMessage()); updateMelodyStatus("Error: Flush Failed"); successful = false; }
                } else { successful = false; }
                if (successful) { Log.d(TAG, "[Melody] Finished successfully. Bytes: " + totalBytesSent); updateMelodyStatus("Finished"); }
                else if (!Thread.currentThread().isInterrupted() && isStreaming) { Log.w(TAG, "[Melody] Not successful. Bytes: " + totalBytesSent); updateMelodyStatusIfNotError("Incomplete"); }
                else if (Thread.currentThread().isInterrupted()) { Log.w(TAG, "[Melody] Interrupted. Bytes: "+totalBytesSent); updateMelodyStatus("Cancelled"); }
            } catch (Resources.NotFoundException e) { String resourceName = "UNKNOWN"; try { resourceName = getResources().getResourceEntryName(melodyResourceId); } catch (Exception ignore) {} Log.e(TAG, "[Melody] Error: File not found! Check 'res/raw/" + resourceName + "' (ID: " + melodyResourceId + ")", e); showToast("Error: Melody file not found."); updateMelodyStatus("Error: File Missing"); successful = false; }
            catch (IOException e) { if (isStreaming) { Log.e(TAG, "[Melody] IO Error: " + e.getMessage(), e); updateMelodyStatus("Error: IO Failed"); } successful = false; }
            catch (Exception e) { if (isStreaming) { Log.e(TAG, "[Melody] Unexpected error: " + e.getMessage(), e); updateMelodyStatus("Error: Unexpected Melody"); } successful = false; }
            finally { Log.d(TAG, "[Melody] Task finishing block."); if (melodyInputStream != null) { try { melodyInputStream.close(); } catch (IOException e) {} } isPlayingMelody = false; final boolean finalSuccess = successful; uiHandler.post(() -> { if (isStreaming) { buttonPlayMelody.setEnabled(true); if (finalSuccess) { uiHandler.postDelayed(() -> { if(isStreaming && !isPlayingMelody) updateMelodyStatus("Idle"); }, 2000); } } else { updateMelodyStatus("Idle"); buttonPlayMelody.setEnabled(false); } }); Log.d(TAG, "[Melody] isPlayingMelody set false."); }
        }
        private void updateMelodyStatusIfNotError(final String newStatus) { uiHandler.post(() -> { boolean isCurrentlyError = false; if (textViewMelodyStatus != null) { String currentStatus = textViewMelodyStatus.getText().toString(); isCurrentlyError = currentStatus.toLowerCase().contains("error") || currentStatus.toLowerCase().contains("fail"); } if (!isCurrentlyError && textViewMelodyStatus != null) { textViewMelodyStatus.setText("Melody: " + newStatus); } else if (textViewMelodyStatus != null){ Log.d(TAG, "[Melody] Skipped status update to '" + newStatus + "'"); } }); }
    }

    // --- ControlConnectTask (Phone -> ESP32 Speaker Control Port) ---
    private class ControlConnectTask implements Runnable {
        private final String ip;
        ControlConnectTask(String controlIp) { this.ip = controlIp; }
        @Override public void run() { /* ... Same as previous version ... */
            Thread.currentThread().setName("ControlConnectTask");
            Log.d(TAG, "[Control] Task starting. Connecting to " + ip + ":" + ESP32_PORT_CONTROL);
            Socket tempSocket = null;
            try {
                tempSocket = new Socket();
                tempSocket.connect(new InetSocketAddress(ip, ESP32_PORT_CONTROL), CONNECTION_TIMEOUT_MS);
                tempSocket.setKeepAlive(true);
                socketControl = tempSocket;
                printWriterControl = new PrintWriter(socketControl.getOutputStream(), true); // autoFlush=true
                isControlConnected = true;
                Log.d(TAG, "[Control] Connected successfully.");
                updateControlStatus("Connected");
                // Keep alive handled by TCP, task can exit after connecting
            } catch (SocketTimeoutException e) { Log.e(TAG, "[Control] Connect Timeout: " + e.getMessage()); updateControlStatus("Error: Timeout"); closeControlConnection(); }
            catch (IOException e) { Log.e(TAG, "[Control] Connect IOException: " + e.getMessage()); if (!isStreaming) { updateControlStatus("Disconnected"); } else { updateControlStatus("Error: IO"); } closeControlConnection(); }
            catch (Exception e) { Log.e(TAG, "[Control] Connect Unexpected Error: " + e.getMessage(), e); updateControlStatus("Error: Unexpected"); closeControlConnection(); }
            finally { Log.d(TAG, "[Control] Connect Task finishing."); if (!isControlConnected && isStreaming) { updateControlStatus("Error: Failed"); } }
        }
    }

    // --- sendControlCommand ---
    private void sendControlCommand(final String command) {
        if (!isControlConnected || printWriterControl == null || executorService == null || executorService.isShutdown()) {
            Log.w(TAG, "[Control] Cannot send command - Not connected or writer/executor invalid. Command: " + command.trim());
            // Maybe add a visual cue or attempt reconnect here?
            return;
        }
        executorService.submit(() -> { // Send on background thread
            try {
                if(printWriterControl != null && isControlConnected && !printWriterControl.checkError()) { // Re-check state
                    printWriterControl.println(command.trim());
                    if (printWriterControl.checkError()) { // Check error *after* sending
                        Log.e(TAG, "[Control] PrintWriter error after sending: " + command.trim());
                        updateControlStatus("Error: Send");
                        closeControlConnection(); // Close broken connection
                    } else {
                        // Log.d(TAG, "[Control] Sent: " + command.trim()); // Optional: Log successful send
                    }
                } else { Log.w(TAG, "[Control] Send task executed but writer invalid or not connected."); }
            } catch (Exception e) { Log.e(TAG, "[Control] Unexpected error sending '" + command.trim() + "': " + e.getMessage(), e); updateControlStatus("Error: Send Exception"); closeControlConnection(); }
        });
    }

    // --- closeControlConnection ---
    private void closeControlConnection() {
        isControlConnected = false; // Update state first
        if (printWriterControl != null) {
            printWriterControl.close();
            printWriterControl = null;
            Log.d(TAG,"[Control] PrintWriter closed.");
        }
        closeSocket(socketControl, "Control Stop");
        socketControl = null;
        // Update UI right away
        uiHandler.post(() -> {
            if(seekBarServo1 != null) seekBarServo1.setEnabled(false);
            if(seekBarServo2 != null) seekBarServo2.setEnabled(false);
            // Don't necessarily change status text here, let stopStreaming handle final status
        });
    }

    // --- setupSeekBarListeners ---
    private void setupSeekBarListeners() {
        seekBarServo1.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (fromUser) { textViewServo1Value.setText(String.valueOf(progress)); sendControlCommand("S1=" + progress); } }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { /* Optional: Maybe send initial position? */ }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { /* Command already sent on change */ }
        });
        seekBarServo2.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (fromUser) { textViewServo2Value.setText(String.valueOf(progress)); sendControlCommand("S2=" + progress); } }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { /* Optional */ }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { /* Optional */ }
        });
    }

    // --- Audio Init/Release Helpers ---
    private boolean initializeAudioTrack() { /* ... Same as previous version ... */
        releaseAudioTrack(); try { Log.d(TAG, "Initializing AudioTrack..."); if (audioTrackBufferSize <= 0) { Log.e(TAG,"AudioTrack buffer size invalid"); return false; } if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { audioTrack = new AudioTrack.Builder().setAudioFormat(new AudioFormat.Builder().setEncoding(AUDIO_FORMAT_RECEIVE).setSampleRate(SAMPLE_RATE_RECEIVE).setChannelMask(CHANNEL_CONFIG_RECEIVE).build()).setBufferSizeInBytes(audioTrackBufferSize).setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build(); } else { audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE_RECEIVE, CHANNEL_CONFIG_RECEIVE, AUDIO_FORMAT_RECEIVE, audioTrackBufferSize, AudioTrack.MODE_STREAM); } if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) { Log.e(TAG, "AudioTrack init failed! State: " + audioTrack.getState()); releaseAudioTrack(); return false; } Log.d(TAG, "AudioTrack Initialized."); return true; } catch (Exception e) { Log.e(TAG, "AudioTrack Init Exception: " + e.getMessage(), e); audioTrack = null; return false; } }
    private boolean initializeAudioRecord() { /* ... Same as previous version ... */
        releaseAudioRecord(); if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "RECORD_AUDIO permission not granted"); return false; } try { Log.d(TAG, "Initializing AudioRecord..."); if (audioRecordBufferSize <= 0) { Log.e(TAG,"AudioRecord buffer size invalid"); return false; } audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE_SEND, CHANNEL_CONFIG_SEND, AUDIO_FORMAT_SEND, audioRecordBufferSize); if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) { Log.e(TAG, "AudioRecord init failed! State: " + audioRecord.getState()); releaseAudioRecord(); return false; } Log.d(TAG, "AudioRecord Initialized."); return true; } catch (Exception e) { Log.e(TAG, "AudioRecord Init Exception: " + e.getMessage(), e); audioRecord = null; return false; } }
    private void releaseAudioTrack() { /* ... Same as previous version ... */
        if (audioTrack != null) { Log.d(TAG, "Releasing AudioTrack..."); if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) { try { if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) { audioTrack.stop(); } audioTrack.release(); } catch (Exception e) { Log.e(TAG,"Error releasing AudioTrack: " + e.getMessage());} } audioTrack = null; } }
    private void releaseAudioRecord() { /* ... Same as previous version ... */
        if (audioRecord != null) { Log.d(TAG, "Releasing AudioRecord..."); if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) { try { if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) { audioRecord.stop(); } audioRecord.release(); } catch (Exception e) { Log.e(TAG,"Error releasing AudioRecord: " + e.getMessage());} } audioRecord = null; } }

    // --- Socket/Stream Close Helpers ---
    private void closeSocket(Socket socket, String context) { /* ... Same as previous version ... */
        if (socket != null && !socket.isClosed()) { try { socket.close(); Log.d(TAG, "Socket closed ("+context+")"); } catch (IOException e) { Log.e(TAG, "Error closing "+context+" socket: " + e.getMessage()); } } }
    private void closeStream(InputStream stream, String context) { /* ... Same as previous version ... */
        if (stream != null) { try { stream.close(); Log.d(TAG, "InputStream closed ("+context+")"); } catch (IOException e) { Log.e(TAG, "Error closing InputStream ("+context+"): " + e.getMessage()); } } }
    private void closeStream(OutputStream stream, String context) { /* ... Same as previous version ... */
        if (stream != null) { try { stream.flush(); stream.close(); Log.d(TAG, "OutputStream closed ("+context+")"); } catch (IOException e) { Log.e(TAG, "Error flushing/closing OutputStream ("+context+"): " + e.getMessage()); } } }


    // --- UI Update Helpers ---
    private void updateUiState(final boolean isStreamingNow, final String receiveStatus, final String sendStatus) { /* ... Same as previous version (includes seekbar enable/disable) ... */
        uiHandler.post(() -> { if (buttonStartStopAll == null || editTextEspMicSenderIpAddress == null || editTextEspSpeakerReceiverIpAddress == null || buttonPlayMelody == null || textViewStatusReceive == null || textViewStatusSend == null || textViewMelodyStatus == null || seekBarServo1 == null || seekBarServo2 == null || textViewControlStatus == null) { Log.e(TAG, "UI element null in updateUiState"); return; } buttonStartStopAll.setText(isStreamingNow ? "Stop Both Streams" : "Start Both Streams"); editTextEspMicSenderIpAddress.setEnabled(!isStreamingNow); editTextEspSpeakerReceiverIpAddress.setEnabled(!isStreamingNow); buttonPlayMelody.setEnabled(isStreamingNow && !isPlayingMelody); textViewStatusReceive.setText("Receiving (ESP32 Mic): " + receiveStatus); textViewStatusSend.setText("Sending (Phone Mic): " + sendStatus); if (!isStreamingNow) { textViewMelodyStatus.setText("Melody: Idle"); } seekBarServo1.setEnabled(isControlConnected); seekBarServo2.setEnabled(isControlConnected); }); }
    private void updateMelodyStatus(final String status) { /* ... Same as previous version ... */
        uiHandler.post(() -> { if (textViewMelodyStatus != null) { textViewMelodyStatus.setText("Melody: " + status); } else { Log.w(TAG,"textViewMelodyStatus is null"); } }); }
    private void updateReceiveStatus(final String status) { /* ... Same as previous version ... */
        uiHandler.post(() -> { if (textViewStatusReceive != null) { textViewStatusReceive.setText("Receiving (ESP32 Mic): " + status); } else { Log.w(TAG,"textViewStatusReceive is null"); } }); }
    private void updateSendStatus(final String status) { /* ... Same as previous version ... */
        uiHandler.post(() -> { if (textViewStatusSend != null) { textViewStatusSend.setText("Sending (Phone Mic): " + status); } else { Log.w(TAG,"textViewStatusSend is null"); } }); }
    private void showToast(final String message) { /* ... Same as previous version ... */
        if (!isFinishing() && !isDestroyed()) { uiHandler.post(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show()); } else { Log.w(TAG, "Activity finishing/destroyed, cannot show toast: " + message); } }
    private void updateControlStatus(final String status) { /* ... Same as previous version ... */
        uiHandler.post(() -> { if (textViewControlStatus != null) { textViewControlStatus.setText("Control: " + status); boolean connected = status.equalsIgnoreCase("Connected"); if(seekBarServo1 != null) seekBarServo1.setEnabled(connected); if(seekBarServo2 != null) seekBarServo2.setEnabled(connected); isControlConnected = connected; } else { Log.w(TAG,"textViewControlStatus is null"); } }); }

    // --- Lifecycle Methods ---
    @Override protected void onStop() { super.onStop(); Log.d(TAG,"onStop called."); if (isStreaming) { stopStreaming(); } }
    @Override protected void onDestroy() { super.onDestroy(); Log.d(TAG,"onDestroy called."); if (isStreaming) { stopStreaming(); } releaseAudioTrack(); releaseAudioRecord(); closeSocket(socketReceive, "Destroy Receive Final"); closeStream(inputStreamReceive, "Destroy Receive Input Final"); closeSocket(socketSend, "Destroy Send Final"); closeStream(outputStreamSend, "Destroy Send Output Final"); closeControlConnection(); if (executorService != null && !executorService.isShutdown()) { executorService.shutdownNow(); } Log.d(TAG, "onDestroy finished."); }

} // --- End MainActivity ---
