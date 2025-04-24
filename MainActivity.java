package com.example.audio;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BiDiAudioServoCam";
    private static final int ESP32_PORT_RECEIVE_FROM = 8080;
    private static final int ESP32_PORT_SEND_TO = 8081;
    private static final int ESP32_PORT_CONTROL = 8082;
    private static final int CONNECTION_TIMEOUT_MS = 5000;

    private static final String CAMERA_STREAM_URL = "http://192.168.1.33:81/stream"; // !!! IMPORTANT: Replace this !!!

    private static final int SAMPLE_RATE_RECEIVE = 16000;
    private static final int CHANNEL_CONFIG_RECEIVE = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT_RECEIVE = AudioFormat.ENCODING_PCM_32BIT;

    private static final int SAMPLE_RATE_SEND = 16000;
    private static final int CHANNEL_CONFIG_SEND = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT_SEND = AudioFormat.ENCODING_PCM_16BIT;

    private EditText editTextEspMicSenderIpAddress;
    private EditText editTextEspSpeakerReceiverIpAddress;
    private Button buttonStartStopAll;
    private Button buttonPlayMelody;
    private TextView textViewStatusReceive;
    private TextView textViewStatusSend;
    private TextView textViewMelodyStatus;
    private SeekBar seekBarServo1;
    private SeekBar seekBarServo2;
    private TextView textViewServo1Value;
    private TextView textViewServo2Value;
    private TextView textViewControlStatus;
    private WebView cameraWebView;
    private SeekBar zoomSeekBar;

    private Socket socketReceive = null;
    private InputStream inputStreamReceive = null;
    private Socket socketSend = null;
    private OutputStream outputStreamSend = null;
    private Socket socketControl = null;
    private PrintWriter printWriterControl = null;

    private AudioTrack audioTrack = null;
    private AudioRecord audioRecord = null;
    private int audioRecordBufferSize = 0;
    private int audioTrackBufferSize = 0;

    private ExecutorService executorService;
    private Future<?> receiveTaskFuture;
    private Future<?> sendTaskFuture;
    private Future<?> melodyTaskFuture;
    private Future<?> controlConnectFuture;
    private volatile boolean isStreaming = false;
    private volatile boolean isPlayingMelody = false;
    private volatile boolean isControlConnected = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private String targetMicSenderIpAddress;
    private String targetSpeakerReceiverIpAddress;

    private float scaleFactor = 1.0f;
    private float posX = 0, posY = 0;
    private GestureDetector gestureDetector;


    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.i(TAG, "RECORD_AUDIO permission granted.");
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

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editTextEspMicSenderIpAddress = findViewById(R.id.editTextEspMicSenderIpAddress);
        editTextEspSpeakerReceiverIpAddress = findViewById(R.id.editTextEspSpeakerReceiverIpAddress);
        buttonStartStopAll = findViewById(R.id.buttonStartStopAll);
        buttonPlayMelody = findViewById(R.id.buttonPlayMelody);
        textViewStatusReceive = findViewById(R.id.textViewStatusReceive);
        textViewStatusSend = findViewById(R.id.textViewStatusSend);
        textViewMelodyStatus = findViewById(R.id.textViewMelodyStatus);
        seekBarServo1 = findViewById(R.id.seekBarServo1);
        seekBarServo2 = findViewById(R.id.seekBarServo2);
        textViewServo1Value = findViewById(R.id.textViewServo1Value);
        textViewServo2Value = findViewById(R.id.textViewServo2Value);
        textViewControlStatus = findViewById(R.id.textViewControlStatus);

        cameraWebView = findViewById(R.id.cameraWebView);
        zoomSeekBar = findViewById(R.id.zoomSeekBar);

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

        buttonStartStopAll.setOnClickListener(v -> {
            if (!isStreaming) {
                targetMicSenderIpAddress = editTextEspMicSenderIpAddress.getText().toString().trim();
                targetSpeakerReceiverIpAddress = editTextEspSpeakerReceiverIpAddress.getText().toString().trim();
                if (targetMicSenderIpAddress.isEmpty() || targetSpeakerReceiverIpAddress.isEmpty()) {
                    showToast("Please enter BOTH ESP32 IP Addresses");
                    return;
                }
                checkPermissionAndStartStream(targetMicSenderIpAddress, targetSpeakerReceiverIpAddress);
            } else {
                stopStreaming();
            }
        });


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

        setupServoSeekBarListeners();
        setupCameraWebView();

    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupCameraWebView() {
        if (cameraWebView == null || zoomSeekBar == null) {
            Log.e(TAG, "Camera WebView or Zoom SeekBar is null in setupCameraWebView");
            showToast("Error initializing camera view");
            return;
        }

        WebSettings webSettings = cameraWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setSupportZoom(false);

        cameraWebView.setWebViewClient(new WebViewClient());
        cameraWebView.loadUrl(CAMERA_STREAM_URL);

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (scaleFactor > 1.0f) {
                    float maxPosX = (cameraWebView.getWidth() * (scaleFactor - 1)) / 2;
                    float maxPosY = (cameraWebView.getHeight() * (scaleFactor - 1)) / 2;

                    posX -= distanceX;
                    posY -= distanceY;

                    posX = Math.max(-maxPosX, Math.min(maxPosX, posX));
                    posY = Math.max(-maxPosY, Math.min(maxPosY, posY));

                    cameraWebView.setTranslationX(posX);
                    cameraWebView.setTranslationY(posY);
                }
                return true;
            }
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });

        zoomSeekBar.setMax(200);
        zoomSeekBar.setProgress(0);
        zoomSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                scaleFactor = 1.0f + (progress / 100.0f);
                cameraWebView.setScaleX(scaleFactor);
                cameraWebView.setScaleY(scaleFactor);

                if (scaleFactor <= 1.01f) {
                    posX = 0;
                    posY = 0;
                    cameraWebView.setTranslationX(0);
                    cameraWebView.setTranslationY(0);
                } else {

                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        cameraWebView.setOnTouchListener((v, event) -> {
            boolean handled = gestureDetector.onTouchEvent(event);
            return handled || v.onTouchEvent(event);
        });

        scaleFactor = 1.0f;
        posX = 0;
        posY = 0;
        cameraWebView.setScaleX(scaleFactor);
        cameraWebView.setScaleY(scaleFactor);
        cameraWebView.setTranslationX(posX);
        cameraWebView.setTranslationY(posY);
    }

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

    private void startStreaming(String micSenderIp, String speakerReceiverIp) {
        if (isStreaming) return;
        if (audioRecordBufferSize <= 0 || audioTrackBufferSize <= 0) {
            showToast("Cannot start: Invalid audio buffer sizes.");
            Log.e(TAG, "Cannot start streaming due to invalid buffer sizes: Record=" + audioRecordBufferSize + ", Track=" + audioTrackBufferSize);
            return;
        }

        Log.d(TAG, "Attempting to start streaming (Audio/Control)...");
        isStreaming = true;
        isPlayingMelody = false;
        isControlConnected = false;
        updateUiState(true, "Connecting...", "Connecting...");
        updateControlStatus("Connecting...");
        updateMelodyStatus("Idle");

        if (!initializeAudioRecord() || !initializeAudioTrack()) {
            Log.e(TAG, "Failed to initialize AudioRecord or AudioTrack.");
            releaseAudioRecord(); releaseAudioTrack();
            updateUiState(false, "Error: Mic/Speaker Init Failed", "Error: Mic/Speaker Init Failed");
            updateControlStatus("Idle");
            isStreaming = false; return;
        }

        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newFixedThreadPool(4);
            Log.d(TAG, "Created new ExecutorService (4 threads).");
        } else { Log.d(TAG, "Reusing existing ExecutorService."); }

        receiveTaskFuture = executorService.submit(new ReceiveAndPlayTask(micSenderIp));
        sendTaskFuture = executorService.submit(new RecordAndSendTask(speakerReceiverIp));
        controlConnectFuture = executorService.submit(new ControlConnectTask(speakerReceiverIp));
    }


    private void stopStreaming() {
        if (!isStreaming) return;
        Log.d(TAG, "Attempting to stop streaming (Audio/Control)...");
        isStreaming = false;
        isPlayingMelody = false;
        isControlConnected = false;

        if (melodyTaskFuture != null && !melodyTaskFuture.isDone()) melodyTaskFuture.cancel(true);
        if (receiveTaskFuture != null && !receiveTaskFuture.isDone()) receiveTaskFuture.cancel(true);
        if (sendTaskFuture != null && !sendTaskFuture.isDone()) sendTaskFuture.cancel(true);
        if (controlConnectFuture != null && !controlConnectFuture.isDone()) controlConnectFuture.cancel(true);

        updateUiState(false, "Stopping...", "Stopping...");
        updateMelodyStatus("Idle");
        updateControlStatus("Disconnecting...");

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

        releaseAudioTrack();
        releaseAudioRecord();

        closeSocket(socketReceive, "Receive Stop Fallback"); closeStream(inputStreamReceive, "Receive Input Stop Fallback");
        closeSocket(socketSend, "Send Stop Fallback"); closeStream(outputStreamSend, "Send Output Stop Fallback");
        closeControlConnection();
        socketReceive = null; inputStreamReceive = null; socketSend = null; outputStreamSend = null;


        Log.d(TAG, "Streaming stopped.");
        updateUiState(false, "Idle", "Idle");
        updateControlStatus("Disconnected");
    }

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

    private class ReceiveAndPlayTask implements Runnable {
        private final String ip;
        ReceiveAndPlayTask(String ipAddress) { this.ip = ipAddress; }
        @Override public void run() {
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
                        if (bytesRead == -1) { Log.d(TAG, "[Receive] Connection closed by peer (read -1)."); break; }
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
            finally { Log.d(TAG, "[Receive] Task finishing."); if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) { try { audioTrack.stop();} catch (Exception e) { Log.w(TAG,"[Receive] Exception stopping AudioTrack: " + e.getMessage()); } } closeStream(localInputStream, "Receive Task Finally"); closeSocket(localSocket, "Receive Task Finally"); if(!isStreaming){ updateReceiveStatus("Stopped"); } else if (!Thread.currentThread().isInterrupted()) { updateReceiveStatus("Disconnected"); } else { updateReceiveStatus("Stopped (Interrupted)"); } }
        }
    }

    private class RecordAndSendTask implements Runnable {
        private final String ip;
        RecordAndSendTask(String ipAddress) { this.ip = ipAddress; }
        @Override public void run() {
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
                    if (isPlayingMelody) { try { Thread.sleep(20); continue; }
                    catch (InterruptedException e) { Log.w(TAG, "[Send] Interrupted waiting for melody."); Thread.currentThread().interrupt(); break; } }
                    if (audioRecord == null || audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { Log.w(TAG,"[Send] AudioRecord stopped unexpectedly."); updateSendStatus("Error: Mic Stopped"); break; }
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "[Send] Permission lost mid-stream."); updateSendStatus("Error: Permission Lost"); break; }

                    bytesRead = audioRecord.read(buffer, 0, buffer.length);
                    if (bytesRead > 0 && outputStreamSend != null) {
                        try {
                            outputStreamSend.write(buffer, 0, bytesRead);
                        } catch (IOException e) {
                            if (isStreaming && !Thread.currentThread().isInterrupted()) { Log.e(TAG, "[Send] Write Error: " + e.getMessage()); updateSendStatus("Error: Network Write Failed"); }
                            else { Log.d(TAG, "[Send] Write Error after stop: " + e.getMessage()); }
                            break;
                        }
                    }
                    else if (bytesRead < 0) {
                        Log.e(TAG, "[Send] AudioRecord read error: " + bytesRead);
                        updateSendStatus("Error: Mic Read Failed (" + bytesRead + ")");
                        break;
                    }
                }
            } catch (SocketTimeoutException e) { Log.e(TAG, "[Send] Connect Timeout: " + e.getMessage()); if(isStreaming) updateSendStatus("Error: Conn Timeout (Spkr)"); }
            catch (IOException e) { if(isStreaming && !Thread.currentThread().isInterrupted()) { Log.e(TAG, "[Send] Connect/IO Error: " + e.getMessage()); updateSendStatus("Error: Conn/IO Failed (Spkr)"); } else { Log.d(TAG, "[Send] Connect/IO Error after stop: " + e.getMessage()); } }
            catch (IllegalStateException e) { if(isStreaming) { Log.e(TAG,"[Send] AudioRecord state error: " + e.getMessage()); updateSendStatus("Error: Mic State Issue"); } }
            catch (SecurityException e){ Log.e(TAG, "[Send] Security Exception (likely permission): " + e.getMessage()); if(isStreaming) updateSendStatus("Error: Permission Issue"); }
            catch (Exception e) { if(isStreaming) { Log.e(TAG, "[Send] Unexpected Error: " + e.getMessage(), e); updateSendStatus("Error: Unexpected Send"); } }
            finally { Log.d(TAG, "[Send] Task finishing."); if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) { try { audioRecord.stop();} catch (Exception e) { Log.w(TAG,"[Send] Exception stopping AudioRecord: " + e.getMessage()); } } closeStream(localOutputStream, "Send Task Finally"); closeSocket(localSocket, "Send Task Finally"); if(!isStreaming){ updateSendStatus("Stopped"); } else if (!Thread.currentThread().isInterrupted()) { updateSendStatus("Disconnected"); } else { updateSendStatus("Stopped (Interrupted)"); } }
        }
    }

    private class SendMelodyTask implements Runnable {
        @Override public void run() {
            Thread.currentThread().setName("SendMelodyTask");
            Log.d(TAG, "[Melody] Task started.");
            InputStream melodyInputStream = null;
            byte[] melodyBuffer = new byte[audioRecordBufferSize > 0 ? audioRecordBufferSize : 4096];
            int bytesRead = 0;
            long totalBytesSent = 0;
            boolean successful = false;
            OutputStream currentOutputStream = outputStreamSend;
            final int melodyResourceId = R.raw.soothing_melody;

            try {
                if (currentOutputStream == null) {
                    throw new IOException("Output stream is null at melody start.");
                }
                Log.d(TAG, "[Melody] Attempting to open melody resource ID: " + melodyResourceId);
                melodyInputStream = getResources().openRawResource(melodyResourceId);
                Log.d(TAG, "[Melody] Opened melody resource successfully.");

                while (isStreaming && isPlayingMelody && !Thread.currentThread().isInterrupted()) {
                    bytesRead = melodyInputStream.read(melodyBuffer);
                    if (bytesRead == -1) {
                        Log.d(TAG, "[Melody] Reached EOF.");
                        break;
                    }
                    try {
                        currentOutputStream.write(melodyBuffer, 0, bytesRead);
                        totalBytesSent += bytesRead;
                    } catch (IOException e) {
                        if (isStreaming) {
                            Log.e(TAG, "[Melody] Write error during playback: " + e.getMessage());
                            updateMelodyStatus("Error: Network Send");
                        }
                        successful = false;
                        break;
                    }
                }

                if (!Thread.currentThread().isInterrupted() && bytesRead == -1) {
                    try {
                        currentOutputStream.flush();
                        Log.d(TAG, "[Melody] Flushed stream after EOF.");
                        successful = true;
                        Log.d(TAG, "[Melody] Marked successful.");
                    } catch (IOException e) {
                        Log.w(TAG, "[Melody] Flush error after EOF: " + e.getMessage());
                        updateMelodyStatus("Error: Flush Failed");
                        successful = false;
                    }
                } else {
                    successful = false;
                    Log.w(TAG, "[Melody] Loop terminated prematurely/error. Interrupted=" + Thread.currentThread().isInterrupted() + ", isStreaming=" + isStreaming + ", isPlayingMelody=" + isPlayingMelody + ", bytesRead=" + bytesRead);
                }

                if (successful) {
                    Log.d(TAG, "[Melody] Finished successfully. Total Bytes: " + totalBytesSent);
                    updateMelodyStatus("Finished");
                } else if (!Thread.currentThread().isInterrupted() && isStreaming) {
                    Log.w(TAG, "[Melody] Playback incomplete. Total Bytes: " + totalBytesSent);
                    updateMelodyStatusIfNotError("Incomplete");
                } else if (Thread.currentThread().isInterrupted()) {
                    Log.w(TAG, "[Melody] Playback interrupted. Total Bytes: " + totalBytesSent);
                    updateMelodyStatus("Cancelled");
                }

            } catch (Resources.NotFoundException e) {
                String resourceName = "UNKNOWN";
                try { resourceName = getResources().getResourceEntryName(melodyResourceId); } catch (Exception ignore) {}
                Log.e(TAG, "[Melody] Error: Resource not found! Check 'res/raw/" + resourceName + ".wav' (ID: " + melodyResourceId + ")", e);
                showToast("Error: Melody file not found.");
                updateMelodyStatus("Error: File Missing");
                successful = false;
            } catch (IOException e) {
                if (isStreaming) {
                    Log.e(TAG, "[Melody] IO Error: " + e.getMessage(), e);
                    updateMelodyStatus("Error: IO Failed");
                }
                successful = false;
            } catch (Exception e) {
                if (isStreaming) {
                    Log.e(TAG, "[Melody] Unexpected error during playback: " + e.getMessage(), e);
                    updateMelodyStatus("Error: Unexpected Melody");
                }
                successful = false;
            } finally {
                Log.d(TAG, "[Melody] Task finishing block.");
                if (melodyInputStream != null) {
                    try { melodyInputStream.close(); } catch (IOException e) { Log.w(TAG, "[Melody] Error closing input stream: " + e.getMessage());}
                }
                final boolean wasSuccessful = successful;
                uiHandler.post(() -> {
                    isPlayingMelody = false;
                    if (buttonPlayMelody != null) {
                        buttonPlayMelody.setEnabled(isStreaming);
                    } else {
                        Log.w(TAG,"buttonPlayMelody is null in melody finally block");
                    }
                    if (wasSuccessful && isStreaming) {
                        uiHandler.postDelayed(() -> {
                            if (isStreaming && !isPlayingMelody && textViewMelodyStatus != null && textViewMelodyStatus.getText().toString().contains("Finished")) {
                                updateMelodyStatus("Idle");
                            }
                        }, 2000);
                    } else if (!isStreaming && textViewMelodyStatus != null) {
                        updateMelodyStatus("Idle");
                    }
                });
                Log.d(TAG, "[Melody] isPlayingMelody set false on UI thread post.");
            }
        }

        private void updateMelodyStatusIfNotError(final String newStatus) {
            uiHandler.post(() -> {
                boolean isCurrentlyError = false;
                if (textViewMelodyStatus != null) {
                    String currentStatus = textViewMelodyStatus.getText().toString();
                    isCurrentlyError = currentStatus.toLowerCase().contains("error") || currentStatus.toLowerCase().contains("fail");
                }
                if (!isCurrentlyError && textViewMelodyStatus != null) {
                    textViewMelodyStatus.setText("Melody: " + newStatus);
                } else if (textViewMelodyStatus != null){
                    Log.d(TAG, "[Melody] Skipped status update to '" + newStatus + "' because current status indicates error.");
                }
            });
        }
    }


    private class ControlConnectTask implements Runnable {
        private final String ip;
        ControlConnectTask(String controlIp) { this.ip = controlIp; }
        @Override public void run() {
            Thread.currentThread().setName("ControlConnectTask");
            Log.d(TAG, "[Control] Task starting. Connecting to " + ip + ":" + ESP32_PORT_CONTROL);
            Socket tempSocket = null;
            PrintWriter tempWriter = null;
            try {
                tempSocket = new Socket();
                tempSocket.connect(new InetSocketAddress(ip, ESP32_PORT_CONTROL), CONNECTION_TIMEOUT_MS);
                tempSocket.setKeepAlive(true);

                tempWriter = new PrintWriter(tempSocket.getOutputStream(), true);

                socketControl = tempSocket;
                printWriterControl = tempWriter;
                isControlConnected = true;

                Log.d(TAG, "[Control] Connected successfully.");
                updateControlStatus("Connected");

            } catch (SocketTimeoutException e) {
                Log.e(TAG, "[Control] Connect Timeout: " + e.getMessage());
                updateControlStatus("Error: Timeout");
                closeControlConnection();
            } catch (IOException e) {
                Log.e(TAG, "[Control] Connect IOException: " + e.getMessage());
                if (isStreaming) {
                    updateControlStatus("Error: IO");
                } else {
                    updateControlStatus("Disconnected");
                }
                closeControlConnection();
            } catch (Exception e) {
                Log.e(TAG, "[Control] Connect Unexpected Error: " + e.getMessage(), e);
                updateControlStatus("Error: Unexpected");
                closeControlConnection();
            } finally {
                Log.d(TAG, "[Control] Connect Task finishing.");
                if (!isControlConnected && isStreaming) {
                    updateControlStatus("Error: Failed");
                }
            }
        }
    }

    private void sendControlCommand(final String command) {
        if (!isControlConnected || printWriterControl == null || executorService == null || executorService.isShutdown()) {
            Log.w(TAG, "[Control] Cannot send command - Not connected or writer/executor invalid. Command: " + command.trim());
            return;
        }

        executorService.submit(() -> {
            if (!isControlConnected || printWriterControl == null) {
                Log.w(TAG, "[Control] Send task executed, but no longer connected or writer is null. Command: " + command.trim());
                return;
            }
            try {
                printWriterControl.println(command.trim());

                if (printWriterControl.checkError()) {
                    Log.e(TAG, "[Control] PrintWriter error detected after sending: " + command.trim());
                    updateControlStatus("Error: Send Failed");
                    closeControlConnection();
                } else {
                }
            } catch (Exception e) {
                Log.e(TAG, "[Control] Unexpected error sending '" + command.trim() + "': " + e.getMessage(), e);
                updateControlStatus("Error: Send Exception");
                closeControlConnection();
            }
        });
    }

    private void closeControlConnection() {
        isControlConnected = false;

        if (printWriterControl != null) {
            try {
                printWriterControl.close();
                Log.d(TAG,"[Control] PrintWriter closed.");
            } catch (Exception e) {
                Log.w(TAG,"[Control] Exception closing PrintWriter: " + e.getMessage());
            } finally {
                printWriterControl = null;
            }
        }

        closeSocket(socketControl, "Control Stop");
        socketControl = null;

        uiHandler.post(() -> {
            if (seekBarServo1 != null) seekBarServo1.setEnabled(false);
            if (seekBarServo2 != null) seekBarServo2.setEnabled(false);
        });
    }

    private void setupServoSeekBarListeners() {
        if (seekBarServo1 == null || seekBarServo2 == null || textViewServo1Value == null || textViewServo2Value == null) {
            Log.e(TAG, "Servo UI elements null in setupServoSeekBarListeners");
            return;
        }
        seekBarServo1.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    textViewServo1Value.setText(String.valueOf(progress));
                    sendControlCommand("S1=" + progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        seekBarServo2.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    textViewServo2Value.setText(String.valueOf(progress));
                    sendControlCommand("S2=" + progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
    }

    private boolean initializeAudioTrack() {
        releaseAudioTrack();
        try {
            Log.d(TAG, "Initializing AudioTrack (Rate: " + SAMPLE_RATE_RECEIVE + ", Format: " + AUDIO_FORMAT_RECEIVE + ", Ch: " + CHANNEL_CONFIG_RECEIVE + ", Buf: " + audioTrackBufferSize + ")");
            if (audioTrackBufferSize <= 0) {
                Log.e(TAG,"AudioTrack buffer size invalid: "+audioTrackBufferSize);
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT_RECEIVE)
                                .setSampleRate(SAMPLE_RATE_RECEIVE)
                                .setChannelMask(CHANNEL_CONFIG_RECEIVE)
                                .build())
                        .setBufferSizeInBytes(audioTrackBufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();
            } else {
                audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL,
                        SAMPLE_RATE_RECEIVE,
                        CHANNEL_CONFIG_RECEIVE,
                        AUDIO_FORMAT_RECEIVE,
                        audioTrackBufferSize,
                        AudioTrack.MODE_STREAM);
            }
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack initialization failed! State: " + audioTrack.getState());
                releaseAudioTrack();
                return false;
            }
            Log.d(TAG, "AudioTrack Initialized successfully. Session ID: " + audioTrack.getAudioSessionId());
            return true;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "AudioTrack Init IllegalArgumentException: " + e.getMessage(), e);
            audioTrack = null;
            return false;
        } catch (UnsupportedOperationException e) {
            Log.e(TAG, "AudioTrack Init UnsupportedOperationException: " + e.getMessage(), e);
            audioTrack = null;
            return false;
        } catch (Exception e) {
            Log.e(TAG, "AudioTrack Init Unexpected Exception: " + e.getMessage(), e);
            audioTrack = null;
            return false;
        }
    }

    private boolean initializeAudioRecord() {
        releaseAudioRecord();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted before initializing AudioRecord.");
            return false;
        }
        try {
            Log.d(TAG, "Initializing AudioRecord (Rate: " + SAMPLE_RATE_SEND + ", Format: " + AUDIO_FORMAT_SEND + ", Ch: " + CHANNEL_CONFIG_SEND + ", Buf: " + audioRecordBufferSize + ")");
            if (audioRecordBufferSize <= 0) {
                Log.e(TAG,"AudioRecord buffer size invalid: " + audioRecordBufferSize);
                return false;
            }
            int audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            audioRecord = new AudioRecord(audioSource,
                    SAMPLE_RATE_SEND,
                    CHANNEL_CONFIG_SEND,
                    AUDIO_FORMAT_SEND,
                    audioRecordBufferSize);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed! State: " + audioRecord.getState());
                if (audioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                    Log.w(TAG, "VOICE_COMMUNICATION failed, trying MIC...");
                    audioSource = MediaRecorder.AudioSource.MIC;
                    if (audioRecord != null) audioRecord.release();
                    audioRecord = new AudioRecord(audioSource,
                            SAMPLE_RATE_SEND,
                            CHANNEL_CONFIG_SEND,
                            AUDIO_FORMAT_SEND,
                            audioRecordBufferSize);

                    if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioRecord init failed with MIC as well! State: " + audioRecord.getState());
                        releaseAudioRecord();
                        return false;
                    } else {
                        Log.d(TAG, "AudioRecord Initialized with MIC source.");
                    }
                } else {
                    releaseAudioRecord();
                    return false;
                }
            }
            Log.d(TAG, "AudioRecord Initialized successfully. Source: " + (audioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION ? "VOICE_COMM" : "MIC") + ", Session ID: " + audioRecord.getAudioSessionId());
            return true;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "AudioRecord Init IllegalArgumentException: " + e.getMessage(), e);
            audioRecord = null;
            return false;
        } catch (SecurityException e) {
            Log.e(TAG, "AudioRecord Init SecurityException (Permission likely missing or revoked): " + e.getMessage(), e);
            audioRecord = null;
            return false;
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord Init Unexpected Exception: " + e.getMessage(), e);
            audioRecord = null;
            return false;
        }
    }

    private void releaseAudioTrack() {
        if (audioTrack != null) {
            Log.d(TAG, "Releasing AudioTrack...");
            if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                try {
                    if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.stop();
                        Log.d(TAG, "AudioTrack stopped.");
                    }
                    audioTrack.release();
                    Log.d(TAG, "AudioTrack released.");
                } catch (IllegalStateException e) {
                    Log.e(TAG,"IllegalStateException releasing AudioTrack: " + e.getMessage());
                } catch (Exception e) {
                    Log.e(TAG,"Exception releasing AudioTrack: " + e.getMessage());
                }
            } else {
                Log.d(TAG, "AudioTrack was not initialized, skipping stop/release methods.");
            }
            audioTrack = null;
        }
    }

    private void releaseAudioRecord() {
        if (audioRecord != null) {
            Log.d(TAG, "Releasing AudioRecord...");
            if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                try {
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();
                        Log.d(TAG, "AudioRecord stopped.");
                    }
                    audioRecord.release();
                    Log.d(TAG, "AudioRecord released.");
                } catch (IllegalStateException e) {
                    Log.e(TAG,"IllegalStateException releasing AudioRecord: " + e.getMessage());
                } catch (Exception e) {
                    Log.e(TAG,"Exception releasing AudioRecord: " + e.getMessage());
                }
            } else {
                Log.d(TAG, "AudioRecord was not initialized, skipping stop/release methods.");
            }
            audioRecord = null;
        }
    }

    private void closeSocket(Socket socket, String context) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
                Log.d(TAG, "Socket closed successfully ("+context+")");
            } catch (IOException e) {
                Log.e(TAG, "IOException closing "+context+" socket: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Unexpected exception closing "+context+" socket: " + e.getMessage());
            }
        } else if (socket != null) {
            Log.d(TAG, "Socket already closed ("+context+")");
        } else {
        }
    }

    private void closeStream(InputStream stream, String context) {
        if (stream != null) {
            try {
                stream.close();
                Log.d(TAG, "InputStream closed ("+context+")");
            } catch (IOException e) {
                Log.e(TAG, "Error closing InputStream ("+context+"): " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Unexpected exception closing InputStream ("+context+"): " + e.getMessage());
            }
        }
    }

    private void closeStream(OutputStream stream, String context) {
        if (stream != null) {
            try {
                stream.flush();
                stream.close();
                Log.d(TAG, "OutputStream flushed and closed ("+context+")");
            } catch (IOException e) {
                Log.e(TAG, "IOException flushing/closing OutputStream ("+context+"): " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Unexpected exception flushing/closing OutputStream ("+context+"): " + e.getMessage());
            }
        }
    }

    private void updateUiState(final boolean isStreamingNow, final String receiveStatus, final String sendStatus) {
        uiHandler.post(() -> {
            if (buttonStartStopAll == null || editTextEspMicSenderIpAddress == null ||
                    editTextEspSpeakerReceiverIpAddress == null || buttonPlayMelody == null ||
                    textViewStatusReceive == null || textViewStatusSend == null ||
                    textViewMelodyStatus == null || seekBarServo1 == null || seekBarServo2 == null ||
                    textViewControlStatus == null) {
                Log.e(TAG, "UI element is null in updateUiState, cannot update UI.");
                return;
            }

            buttonStartStopAll.setText(isStreamingNow ? "Stop Both Streams" : "Start Audio/Control Streams");
            editTextEspMicSenderIpAddress.setEnabled(!isStreamingNow);
            editTextEspSpeakerReceiverIpAddress.setEnabled(!isStreamingNow);

            buttonPlayMelody.setEnabled(isStreamingNow && isControlConnected && !isPlayingMelody);

            textViewStatusReceive.setText("Receiving (ESP32 Mic): " + receiveStatus);
            textViewStatusSend.setText("Sending (Phone Mic): " + sendStatus);

            if (!isStreamingNow) {
                textViewMelodyStatus.setText("Melody: Idle");
            }

            seekBarServo1.setEnabled(isControlConnected);
            seekBarServo2.setEnabled(isControlConnected);

            if (zoomSeekBar != null) {
            }
        });
    }

    private void updateMelodyStatus(final String status) {
        uiHandler.post(() -> {
            if (textViewMelodyStatus != null) {
                textViewMelodyStatus.setText("Melody: " + status);
            } else {
                Log.w(TAG,"textViewMelodyStatus is null in updateMelodyStatus");
            }
        });
    }

    private void updateReceiveStatus(final String status) {
        uiHandler.post(() -> {
            if (textViewStatusReceive != null) {
                textViewStatusReceive.setText("Receiving (ESP32 Mic): " + status);
            } else {
                Log.w(TAG,"textViewStatusReceive is null in updateReceiveStatus");
            }
        });
    }

    private void updateSendStatus(final String status) {
        uiHandler.post(() -> {
            if (textViewStatusSend != null) {
                textViewStatusSend.setText("Sending (Phone Mic): " + status);
            } else {
                Log.w(TAG,"textViewStatusSend is null in updateSendStatus");
            }
        });
    }

    private void showToast(final String message) {
        if (!isFinishing() && !isDestroyed()) {
            uiHandler.post(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        } else {
            Log.w(TAG, "Activity finishing/destroyed, cannot show toast: " + message);
        }
    }

    private void updateControlStatus(final String status) {
        uiHandler.post(() -> {
            boolean connected = status.equalsIgnoreCase("Connected");

            if (textViewControlStatus != null) {
                textViewControlStatus.setText("Control: " + status);
            } else {
                Log.w(TAG,"textViewControlStatus is null");
            }

            isControlConnected = connected;

            if (seekBarServo1 != null) seekBarServo1.setEnabled(connected);
            else Log.w(TAG,"seekBarServo1 is null");
            if (seekBarServo2 != null) seekBarServo2.setEnabled(connected);
            else Log.w(TAG,"seekBarServo2 is null");

            if (buttonPlayMelody != null) {
                buttonPlayMelody.setEnabled(isStreaming && connected && !isPlayingMelody);
            } else {
                Log.w(TAG,"buttonPlayMelody is null");
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG,"onPause called.");
        if (cameraWebView != null) {
            cameraWebView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG,"onResume called.");
        if (cameraWebView != null) {
            cameraWebView.onResume();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG,"onStop called. Stopping streams if active.");
        if (isStreaming) {
            stopStreaming();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG,"onDestroy called. Final cleanup.");
        if (isStreaming) {
            stopStreaming();
        }
        if (cameraWebView != null) {
            Log.d(TAG,"Destroying Camera WebView.");
            cameraWebView.stopLoading();
            cameraWebView.onPause();
            cameraWebView.clearHistory();
            cameraWebView.clearCache(true);
            cameraWebView.clearFormData();
            cameraWebView.destroy();
            cameraWebView = null;
        }

        releaseAudioTrack();
        releaseAudioRecord();

        closeSocket(socketReceive, "Destroy Receive Final");
        closeStream(inputStreamReceive, "Destroy Receive Input Final");
        closeSocket(socketSend, "Destroy Send Final");
        closeStream(outputStreamSend, "Destroy Send Output Final");
        closeControlConnection();

        if (executorService != null && !executorService.isShutdown()) {
            Log.w(TAG, "ExecutorService still running in onDestroy, forcing shutdownNow.");
            executorService.shutdownNow();
        }
        executorService = null;

        Log.d(TAG, "onDestroy finished.");
    }

}
