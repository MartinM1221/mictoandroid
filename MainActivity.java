package com.example.audio; // Replace with your package name

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
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
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BiDiAudioTwoESP"; // Updated Tag
    private static final int ESP32_PORT_RECEIVE_FROM = 8080; // Port for receiving INMP441 data FROM ESP32#1
    private static final int ESP32_PORT_SEND_TO = 8081; // Port for sending phone mic data TO ESP32#2
    private static final int CONNECTION_TIMEOUT_MS = 5000;

    // --- Audio Config: RECEIVING from ESP32#1 (INMP441) ---
    private static final int SAMPLE_RATE_RECEIVE = 16000;
    private static final int CHANNEL_CONFIG_RECEIVE = AudioFormat.CHANNEL_OUT_MONO;
    // *** IMPORTANT: Matches the RAW 32-bit data ESP32#1 sends ***
    private static final int AUDIO_FORMAT_RECEIVE = AudioFormat.ENCODING_PCM_32BIT;

    // --- Audio Config: SENDING from Phone Mic TO ESP32#2 ---
    private static final int SAMPLE_RATE_SEND = 16000;
    private static final int CHANNEL_CONFIG_SEND = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT_SEND = AudioFormat.ENCODING_PCM_16BIT;

    // --- UI Elements ---
    private EditText editTextEspMicSenderIpAddress;     // <<< RENAMED
    private EditText editTextEspSpeakerReceiverIpAddress; // <<< ADDED
    private Button buttonStartStopAll;
    private TextView textViewStatusReceive;
    private TextView textViewStatusSend;

    // --- Networking ---
    private Socket socketReceive = null; // Connection TO ESP32#1
    private InputStream inputStreamReceive = null;
    private Socket socketSend = null;    // Connection TO ESP32#2
    private OutputStream outputStreamSend = null;

    // --- Audio ---
    private AudioTrack audioTrack = null;
    private AudioRecord audioRecord = null;
    private int audioRecordBufferSize = 0;
    private int audioTrackBufferSize = 0;

    // --- Threading & State ---
    private ExecutorService executorService;
    private Future<?> receiveTaskFuture;
    private Future<?> sendTaskFuture;
    private volatile boolean isStreaming = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    // Store both IPs when starting
    private String targetMicSenderIpAddress;
    private String targetSpeakerReceiverIpAddress;


    // --- Permission Handling ---
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.i(TAG, "RECORD_AUDIO permission granted.");
                    // Retry starting with the stored IPs
                    startStreaming(targetMicSenderIpAddress, targetSpeakerReceiverIpAddress);
                } else {
                    Log.w(TAG, "RECORD_AUDIO permission denied.");
                    showToast("Microphone permission is required to send audio.");
                    updateUiState(false, "Error: Permission Denied", "Error: Permission Denied");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Use your layout file name

        // Find UI elements by their NEW IDs
        editTextEspMicSenderIpAddress = findViewById(R.id.editTextEspMicSenderIpAddress);         // <<< RENAMED ID
        editTextEspSpeakerReceiverIpAddress = findViewById(R.id.editTextEspSpeakerReceiverIpAddress); // <<< ADDED ID
        buttonStartStopAll = findViewById(R.id.buttonStartStopAll);
        textViewStatusReceive = findViewById(R.id.textViewStatusReceive);
        textViewStatusSend = findViewById(R.id.textViewStatusSend);

        // Calculate buffer sizes
        audioRecordBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_SEND, CHANNEL_CONFIG_SEND, AUDIO_FORMAT_SEND);
        audioTrackBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE_RECEIVE, CHANNEL_CONFIG_RECEIVE, AUDIO_FORMAT_RECEIVE);

        if (audioRecordBufferSize <= 0 || audioTrackBufferSize <= 0) {
            Log.e(TAG, "Failed to calculate one or both audio buffer sizes.");
            showToast("Error: Audio buffer calculation failed for this device.");
            buttonStartStopAll.setEnabled(false);
        } else {
            Log.d(TAG, "AudioRecord BufferSize: " + audioRecordBufferSize);
            Log.d(TAG, "AudioTrack BufferSize: " + audioTrackBufferSize);
        }

        buttonStartStopAll.setOnClickListener(v -> {
            if (!isStreaming) {
                // Get BOTH IP Addresses
                targetMicSenderIpAddress = editTextEspMicSenderIpAddress.getText().toString().trim();         // <<< Get Mic Sender IP
                targetSpeakerReceiverIpAddress = editTextEspSpeakerReceiverIpAddress.getText().toString().trim(); // <<< Get Speaker Receiver IP

                // Validate BOTH IPs
                if (targetMicSenderIpAddress.isEmpty() || targetSpeakerReceiverIpAddress.isEmpty()) { // <<< Check both
                    showToast("Please enter BOTH ESP32 IP Addresses");
                    return;
                }
                // Check permission first, pass both IPs
                checkPermissionAndStartStream(targetMicSenderIpAddress, targetSpeakerReceiverIpAddress); // <<< Pass both IPs
            } else {
                stopStreaming();
            }
        });
    }

    // Modified to accept both IPs
    private void checkPermissionAndStartStream(String micSenderIp, String speakerReceiverIp) {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startStreaming(micSenderIp, speakerReceiverIp); // <<< Pass both IPs
        } else {
            Log.d(TAG, "Requesting RECORD_AUDIO permission...");
            // Store IPs for retry after permission grant
            targetMicSenderIpAddress = micSenderIp;
            targetSpeakerReceiverIpAddress = speakerReceiverIp;
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    // Modified to accept both IPs
    private void startStreaming(String micSenderIp, String speakerReceiverIp) {
        if (isStreaming) return;
        if (audioRecordBufferSize <= 0 || audioTrackBufferSize <= 0) {
            showToast("Cannot start: Audio buffers not initialized.");
            return;
        }

        Log.d(TAG, "Attempting to start streaming to MicSender=" + micSenderIp + " and SpeakerReceiver=" + speakerReceiverIp);
        isStreaming = true; // Set flag early
        updateUiState(true, "Connecting...", "Connecting...");

        // Initialize Audio Devices
        if (!initializeAudioRecord() || !initializeAudioTrack()) {
            Log.e(TAG, "Failed to initialize AudioRecord or AudioTrack.");
            releaseAudioRecord(); releaseAudioTrack(); // Cleanup
            updateUiState(false, "Error: Mic/Speaker Init Failed", "Error: Mic/Speaker Init Failed");
            isStreaming = false; return;
        }

        // Start background tasks, passing the CORRECT IP to each task
        executorService = Executors.newFixedThreadPool(2);
        receiveTaskFuture = executorService.submit(new ReceiveAndPlayTask(micSenderIp));         // <<< Use Mic Sender IP
        sendTaskFuture = executorService.submit(new RecordAndSendTask(speakerReceiverIp));     // <<< Use Speaker Receiver IP
    }

    // Stop streaming remains largely the same
    private void stopStreaming() {
        if (!isStreaming) return;
        Log.d(TAG, "Attempting to stop streaming...");
        isStreaming = false;

        updateUiState(false, "Stopping...", "Stopping...");

        if (executorService != null) {
            executorService.shutdownNow();
            try { if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) { Log.w(TAG,"Executor tasks did not terminate gracefully."); } }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); Log.w(TAG,"Interrupted while waiting for executor shutdown."); }
            executorService = null;
        }

        releaseAudioTrack();
        releaseAudioRecord();
        // Close sockets and streams individually
        closeSocket(socketReceive, "Receive"); closeStream(inputStreamReceive, "Receive Input");
        closeSocket(socketSend, "Send"); closeStream(outputStreamSend, "Send Output");


        Log.d(TAG, "Streaming stopped.");
        updateUiState(false, "Idle", "Idle");
    }

    // --- Task for Receiving Audio from ESP32#1 (Mic Sender) and Playing ---
    private class ReceiveAndPlayTask implements Runnable {
        private final String ip; // IP Address of the ESP32 Mic Sender

        ReceiveAndPlayTask(String ipAddress) { this.ip = ipAddress; }

        @Override
        public void run() {
            byte[] buffer = new byte[audioTrackBufferSize];
            int bytesRead;

            try {
                Log.d(TAG, "[Receive] Task started. Connecting to Mic Sender " + ip + ":" + ESP32_PORT_RECEIVE_FROM);
                socketReceive = new Socket();
                socketReceive.connect(new InetSocketAddress(ip, ESP32_PORT_RECEIVE_FROM), CONNECTION_TIMEOUT_MS);
                inputStreamReceive = socketReceive.getInputStream();
                Log.d(TAG, "[Receive] Connected to Mic Sender.");

                if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) { throw new IOException("AudioTrack not ready."); }
                if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) { audioTrack.play(); Log.d(TAG, "[Receive] AudioTrack playing."); }

                updateReceiveStatus("Receiving/Playing");

                while (isStreaming && !Thread.currentThread().isInterrupted() && socketReceive.isConnected()) {
                    try {
                        bytesRead = inputStreamReceive.read(buffer);
                        if (bytesRead == -1) { Log.d(TAG, "[Receive] Connection closed by Mic Sender."); break; }

                        if (bytesRead > 0 && audioTrack != null) {
                            // *** IMPORTANT: Writing 32-bit PCM data ***
                            int written = audioTrack.write(buffer, 0, bytesRead);
                            if (written < 0) { Log.e(TAG, "[Receive] AudioTrack write error: " + written); updateReceiveStatus("Error: Playback Failed"); break; }
                        }
                    } catch (SocketTimeoutException e) { continue; /* Normal if no data */ }
                    catch (IOException e) { if (isStreaming) { Log.e(TAG, "[Receive] Read Error: " + e.getMessage()); updateReceiveStatus("Error: Network Read Failed"); } break; }
                    catch (IllegalStateException e) { Log.e(TAG, "[Receive] AudioTrack state error: " + e.getMessage()); updateReceiveStatus("Error: Playback State Issue"); break; }
                }
            } catch (SocketTimeoutException e) { Log.e(TAG, "[Receive] Connection Timeout to Mic Sender: " + e.getMessage()); updateReceiveStatus("Error: Conn Timeout (Mic)"); }
            catch (IOException e) { if (isStreaming) { Log.e(TAG, "[Receive] Connection Error to Mic Sender: " + e.getMessage()); updateReceiveStatus("Error: Conn Failed (Mic)"); } }
            catch (Exception e) { if(isStreaming) { Log.e(TAG, "[Receive] Unexpected Error: " + e.getMessage(), e); updateReceiveStatus("Error: Unexpected"); } }
            finally {
                Log.d(TAG, "[Receive] Task finishing.");
                if(isStreaming){ updateReceiveStatus("Disconnected/Stopped"); }
                closeSocket(socketReceive, "Receive Task Finally"); closeStream(inputStreamReceive, "Receive Task Finally");
            }
        }
    }

    // --- Task for Recording Phone Mic and Sending to ESP32#2 (Speaker Receiver) ---
    private class RecordAndSendTask implements Runnable {
        private final String ip; // IP Address of the ESP32 Speaker Receiver

        RecordAndSendTask(String ipAddress) { this.ip = ipAddress; }

        @Override
        public void run() {
            byte[] buffer = new byte[audioRecordBufferSize];
            int bytesRead;

            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "[Send] Permission missing!"); updateSendStatus("Error: Permission Missing"); return; }

            try {
                Log.d(TAG, "[Send] Task started. Connecting to Speaker Receiver " + ip + ":" + ESP32_PORT_SEND_TO);
                socketSend = new Socket();
                socketSend.connect(new InetSocketAddress(ip, ESP32_PORT_SEND_TO), CONNECTION_TIMEOUT_MS);
                outputStreamSend = socketSend.getOutputStream();
                Log.d(TAG, "[Send] Connected to Speaker Receiver.");

                if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) { throw new IOException("AudioRecord not ready."); }
                if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { audioRecord.startRecording(); Log.d(TAG, "[Send] AudioRecord recording."); }

                updateSendStatus("Recording/Sending");

                while (isStreaming && !Thread.currentThread().isInterrupted() && socketSend.isConnected()) {
                    if (audioRecord == null || audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { Log.w(TAG,"[Send] AudioRecord stopped."); updateSendStatus("Error: Mic Stopped"); break; }

                    bytesRead = audioRecord.read(buffer, 0, buffer.length);

                    if (bytesRead > 0 && outputStreamSend != null) {
                        try {
                            // *** IMPORTANT: Sending 16-bit PCM data ***
                            outputStreamSend.write(buffer, 0, bytesRead);
                        } catch (IOException e) { if (isStreaming) { Log.e(TAG, "[Send] Write Error: " + e.getMessage()); updateSendStatus("Error: Network Write Failed"); } break; }
                    } else if (bytesRead < 0) { Log.e(TAG, "[Send] AudioRecord read error: " + bytesRead); updateSendStatus("Error: Mic Read Failed"); break; }
                }
            } catch (SocketTimeoutException e) { Log.e(TAG, "[Send] Connection Timeout to Speaker Receiver: " + e.getMessage()); updateSendStatus("Error: Conn Timeout (Spkr)"); }
            catch (IOException e) { if(isStreaming) { Log.e(TAG, "[Send] Connection Error to Speaker Receiver: " + e.getMessage()); updateSendStatus("Error: Conn Failed (Spkr)"); } }
            catch (IllegalStateException e) { if(isStreaming) { Log.e(TAG,"[Send] AudioRecord state error: " + e.getMessage()); updateSendStatus("Error: Mic State Issue"); } }
            catch (SecurityException e){ Log.e(TAG, "[Send] Security Exception (Permission): " + e.getMessage()); updateSendStatus("Error: Permission Issue"); }
            catch (Exception e) { if(isStreaming) { Log.e(TAG, "[Send] Unexpected Error: " + e.getMessage(), e); updateSendStatus("Error: Unexpected"); } }
            finally {
                Log.d(TAG, "[Send] Task finishing.");
                if(isStreaming){ updateSendStatus("Disconnected/Stopped"); }
                closeSocket(socketSend, "Send Task Finally"); closeStream(outputStreamSend, "Send Task Finally");
            }
        }
    }


    // --- Helper Methods (Initialization, Release, Close, UI Update) ---
    // (These remain mostly the same as your provided code, minor logging changes maybe)

    private boolean initializeAudioTrack() {
        releaseAudioTrack();
        try {
            Log.d(TAG, "Initializing AudioTrack (32-bit)..."); // Note format
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioTrack = new AudioTrack.Builder()
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AUDIO_FORMAT_RECEIVE).setSampleRate(SAMPLE_RATE_RECEIVE).setChannelMask(CHANNEL_CONFIG_RECEIVE).build())
                        .setBufferSizeInBytes(audioTrackBufferSize).setTransferMode(AudioTrack.MODE_STREAM).build();
            } else { audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE_RECEIVE, CHANNEL_CONFIG_RECEIVE, AUDIO_FORMAT_RECEIVE, audioTrackBufferSize, AudioTrack.MODE_STREAM); }
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) { Log.e(TAG, "AudioTrack initialization failed!"); audioTrack = null; return false; }
            Log.d(TAG, "AudioTrack Initialized."); return true;
        } catch (Exception e) { Log.e(TAG, "AudioTrack Init Exception: " + e.getMessage()); audioTrack = null; return false; }
    }

    private boolean initializeAudioRecord() {
        releaseAudioRecord();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "RECORD_AUDIO permission not granted for init"); return false; } // Check permission before init
        try {
            Log.d(TAG, "Initializing AudioRecord (16-bit)..."); // Note format
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE_SEND, CHANNEL_CONFIG_SEND, AUDIO_FORMAT_SEND, audioRecordBufferSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) { Log.e(TAG, "AudioRecord initialization failed!"); audioRecord = null; return false; }
            Log.d(TAG, "AudioRecord Initialized."); return true;
        } catch (Exception e) { Log.e(TAG, "AudioRecord Init Exception: " + e.getMessage()); audioRecord = null; return false; }
    }


    private void releaseAudioTrack() {
        if (audioTrack != null) {
            Log.d(TAG, "Releasing AudioTrack...");
            try { if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) audioTrack.stop(); audioTrack.release(); }
            catch (Exception e) { Log.e(TAG,"Error releasing AudioTrack: " + e.getMessage());} audioTrack = null;
        }
    }

    private void releaseAudioRecord() {
        if (audioRecord != null) {
            Log.d(TAG, "Releasing AudioRecord...");
            try { if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop(); audioRecord.release(); }
            catch (Exception e) { Log.e(TAG,"Error releasing AudioRecord: " + e.getMessage());} audioRecord = null;
        }
    }

    // Socket/Stream closing methods remain the same
    private void closeSocket(Socket socket, String context) { if (socket != null && !socket.isClosed()) { try { socket.close(); Log.d(TAG, "Socket closed ("+context+")");} catch (IOException e) { Log.e(TAG, "Error closing "+context+" socket: " + e.getMessage()); } } }
    private void closeStream(InputStream stream, String context) { if (stream != null) { try { stream.close(); Log.d(TAG, "InputStream closed ("+context+")");} catch (IOException e) {Log.e(TAG, "Error closing "+context+": " + e.getMessage());} } }
    private void closeStream(OutputStream stream, String context) { if (stream != null) { try { stream.close(); Log.d(TAG, "OutputStream closed ("+context+")");} catch (IOException e) {Log.e(TAG, "Error closing "+context+": " + e.getMessage());} } }


    // UI Update Helpers - Modified to handle two EditText fields
    private void updateUiState(final boolean isStreamingNow, final String receiveStatus, final String sendStatus) {
        uiHandler.post(() -> {
            buttonStartStopAll.setText(isStreamingNow ? "Stop Both Streams" : "Start Both Streams");
            editTextEspMicSenderIpAddress.setEnabled(!isStreamingNow);     // <<< Disable/Enable Mic Sender IP field
            editTextEspSpeakerReceiverIpAddress.setEnabled(!isStreamingNow); // <<< Disable/Enable Speaker Receiver IP field
            textViewStatusReceive.setText("Receiving (ESP32 Mic): " + receiveStatus);
            textViewStatusSend.setText("Sending (Phone Mic): " + sendStatus);
        });
    }
    // updateReceiveStatus, updateSendStatus, showToast remain the same
    private void updateReceiveStatus(final String status) { uiHandler.post(() -> textViewStatusReceive.setText("Receiving (ESP32 Mic): " + status)); }
    private void updateSendStatus(final String status) { uiHandler.post(() -> textViewStatusSend.setText("Sending (Phone Mic): " + status)); }
    private void showToast(final String message) { uiHandler.post(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show()); }


    // Lifecycle Methods (onStop, onDestroy) remain the same
    @Override
    protected void onStop() { super.onStop(); Log.d(TAG,"onStop called."); if (isStreaming) { stopStreaming(); } }
    @Override
    protected void onDestroy() { super.onDestroy(); Log.d(TAG,"onDestroy called."); if(isStreaming) { stopStreaming(); } releaseAudioTrack(); releaseAudioRecord(); closeSocket(socketReceive, "Destroy Receive"); closeStream(inputStreamReceive, "Destroy Receive Input"); closeSocket(socketSend, "Destroy Send"); closeStream(outputStreamSend, "Destroy Send Output"); if (executorService != null && !executorService.isShutdown()) { executorService.shutdownNow(); } }
}
