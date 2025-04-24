package com.example.audio; // Use your actual package name

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.os.Handler;
import android.os.Looper;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources; // Needed for Melody Task
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

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

// Corrected Class Name
public class StreamingService extends Service {

    private static final String TAG = "StreamingService"; // Corrected TAG
    private static final int ESP32_PORT_RECEIVE_FROM = 8080;
    private static final int ESP32_PORT_SEND_TO = 8081;
    private static final int ESP32_PORT_CONTROL = 8082;
    private static final int CONNECTION_TIMEOUT_MS = 5000;

    private static final int SAMPLE_RATE_RECEIVE = 16000;
    private static final int CHANNEL_CONFIG_RECEIVE = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT_RECEIVE = AudioFormat.ENCODING_PCM_32BIT;

    private static final int SAMPLE_RATE_SEND = 16000;
    private static final int CHANNEL_CONFIG_SEND = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT_SEND = AudioFormat.ENCODING_PCM_16BIT;

    private static final String NOTIFICATION_CHANNEL_ID = "StreamingServiceChannel"; // Use unique ID
    private static final int NOTIFICATION_ID = 1;

    // Actions
    public static final String ACTION_START = "com.example.audio.ACTION_START";
    public static final String ACTION_STOP = "com.example.audio.ACTION_STOP";
    public static final String ACTION_SEND_COMMAND = "com.example.audio.ACTION_SEND_COMMAND";
    public static final String ACTION_PLAY_MELODY = "com.example.audio.ACTION_PLAY_MELODY"; // <<< ADDED Melody Action

    // Extras
    public static final String EXTRA_MIC_SENDER_IP = "com.example.audio.EXTRA_MIC_SENDER_IP";
    public static final String EXTRA_SPEAKER_RECEIVER_IP = "com.example.audio.EXTRA_SPEAKER_RECEIVER_IP";
    public static final String EXTRA_COMMAND = "com.example.audio.EXTRA_COMMAND";

    // Broadcast constants
    public static final String ACTION_STATUS_UPDATE = "com.example.audio.STATUS_UPDATE";
    public static final String EXTRA_STATUS_TYPE = "com.example.audio.EXTRA_STATUS_TYPE";
    public static final String EXTRA_STATUS_MESSAGE = "com.example.audio.EXTRA_STATUS_MESSAGE";
    public static final String TYPE_RECEIVE = "receive";
    public static final String TYPE_SEND = "send";
    public static final String TYPE_CONTROL = "control";
    public static final String TYPE_MELODY = "melody"; // <<< ADDED Melody Type
    public static final String TYPE_GENERAL = "general";


    private ExecutorService executorService;
    private Future<?> receiveTaskFuture;
    private Future<?> sendTaskFuture;
    private Future<?> controlConnectFuture;
    private Future<?> melodyTaskFuture; // <<< ADDED Melody Future

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

    // State variables
    private volatile boolean isStreaming = false;
    private volatile boolean isControlConnected = false;
    private volatile boolean isPlayingMelody = false; // <<< ADDED Melody State

    private LocalBroadcastManager broadcaster;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        broadcaster = LocalBroadcastManager.getInstance(this);
        createNotificationChannel();
        calculateBufferSizes();
    }

    private void sendBroadcastStatus(String type, String message) {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra(EXTRA_STATUS_TYPE, type);
        intent.putExtra(EXTRA_STATUS_MESSAGE, message);
        broadcaster.sendBroadcast(intent);
        Log.d(TAG, "Broadcast sent: Type=" + type + ", Msg=" + message);

        // Simplified notification update
        if (TYPE_GENERAL.equals(type) || message.toLowerCase().contains("error") || message.toLowerCase().contains("fail")) {
            updateNotification(message);
        } else if (message.toLowerCase().contains("disconnect")) {
            updateNotification("Streaming Error/Disconnected");
        } else if ("Streaming Active".equals(message)) { // Only update on general active message
            updateNotification(message);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            Log.d(TAG, "Received action: " + action);

            switch (action) { // Use switch for clarity
                case ACTION_START:
                    String micIp = intent.getStringExtra(EXTRA_MIC_SENDER_IP);
                    String speakerIp = intent.getStringExtra(EXTRA_SPEAKER_RECEIVER_IP);
                    if (micIp != null && !micIp.isEmpty() && speakerIp != null && !speakerIp.isEmpty()) {
                        if (!isStreaming) {
                            sendBroadcastStatus(TYPE_GENERAL, "Starting...");
                            startStreamingInternal(micIp, speakerIp);
                        } else {
                            Log.w(TAG, "Start command received but already streaming.");
                            sendBroadcastStatus(TYPE_GENERAL, "Already Running");
                        }
                    } else {
                        Log.e(TAG, "Start command received with invalid IPs.");
                        sendBroadcastStatus(TYPE_GENERAL, "Error: Invalid IPs");
                        stopSelf();
                    }
                    break;
                case ACTION_STOP:
                    sendBroadcastStatus(TYPE_GENERAL, "Stopping...");
                    stopStreamingInternal();
                    stopSelf();
                    break;
                case ACTION_SEND_COMMAND:
                    String command = intent.getStringExtra(EXTRA_COMMAND);
                    if (command != null && !command.isEmpty()) {
                        sendControlCommand(command);
                    }
                    break;
                case ACTION_PLAY_MELODY: // <<< ADDED: Handle Play Melody Action
                    startMelodyPlaybackInternal();
                    break;
                default:
                    Log.w(TAG, "Unknown action received: " + action);
                    break;
            }
        } else {
            Log.w(TAG, "onStartCommand received null intent or action.");
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");
        if (isStreaming) {
            stopStreamingInternal();
        }
        sendBroadcastStatus(TYPE_GENERAL, "Service Stopped");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void calculateBufferSizes() {
        audioRecordBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_SEND, CHANNEL_CONFIG_SEND, AUDIO_FORMAT_SEND);
        audioTrackBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE_RECEIVE, CHANNEL_CONFIG_RECEIVE, AUDIO_FORMAT_RECEIVE);
        if (audioRecordBufferSize <= 0 || audioTrackBufferSize <= 0) {
            Log.e(TAG, "getMinBufferSize failed. Using default sizes.");
            audioRecordBufferSize = (audioRecordBufferSize <= 0) ? 1024 * 8 : audioRecordBufferSize;
            audioTrackBufferSize = (audioTrackBufferSize <= 0) ? 1024 * 8 : audioTrackBufferSize;
        } else {
            audioRecordBufferSize *= 4;
            audioTrackBufferSize *= 4;
        }
        Log.d(TAG, "AudioRecord BufferSize: " + audioRecordBufferSize);
        Log.d(TAG, "AudioTrack BufferSize: " + audioTrackBufferSize);
    }

    private void startStreamingInternal(String micSenderIp, String speakerReceiverIp) {
        if (isStreaming) {
            Log.w(TAG,"Already streaming, ignoring start request.");
            return;
        }
        if (audioRecordBufferSize <= 0 || audioTrackBufferSize <= 0) {
            Log.e(TAG, "Cannot start streaming due to invalid buffer sizes.");
            sendBroadcastStatus(TYPE_GENERAL, "Error: Invalid Buffer Sizes");
            return;
        }
        Log.d(TAG, "Attempting to start streaming...");
        sendBroadcastStatus(TYPE_RECEIVE, "Initializing...");
        sendBroadcastStatus(TYPE_SEND, "Initializing...");
        sendBroadcastStatus(TYPE_CONTROL, "Initializing...");
        sendBroadcastStatus(TYPE_MELODY, "Idle"); // Initial melody state

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted for service.");
            sendBroadcastStatus(TYPE_GENERAL, "Error: RECORD_AUDIO Permission Missing");
            stopSelf();
            return;
        }

        if (!initializeAudioRecord() || !initializeAudioTrack()) {
            Log.e(TAG, "Failed to initialize AudioRecord or AudioTrack.");
            sendBroadcastStatus(TYPE_GENERAL, "Error: Audio Device Init Failed");
            releaseAudioRecord();
            releaseAudioTrack();
            return;
        }

        isStreaming = true;
        isControlConnected = false;
        isPlayingMelody = false; // Ensure melody state is reset

        startForeground(NOTIFICATION_ID, createNotification("Initializing Connections..."));

        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newFixedThreadPool(4); // Keep 4 threads for tasks
            Log.d(TAG, "Created new ExecutorService (4 threads).");
        }

        receiveTaskFuture = executorService.submit(new ReceiveAndPlayTask(micSenderIp));
        sendTaskFuture = executorService.submit(new RecordAndSendTask(speakerReceiverIp));
        controlConnectFuture = executorService.submit(new ControlConnectTask(speakerReceiverIp));
        sendBroadcastStatus(TYPE_GENERAL, "Connections Initializing");
    }

    private void stopStreamingInternal() {
        if (!isStreaming) {
            Log.w(TAG,"Not streaming, ignoring stop request.");
            return;
        }
        Log.d(TAG, "Attempting to stop streaming...");
        sendBroadcastStatus(TYPE_GENERAL, "Stopping Streams...");
        isStreaming = false;
        isControlConnected = false;
        isPlayingMelody = false; // Stop melody if playing

        if (melodyTaskFuture != null && !melodyTaskFuture.isDone()) melodyTaskFuture.cancel(true); // <<< ADDED: Cancel Melody
        if (receiveTaskFuture != null && !receiveTaskFuture.isDone()) receiveTaskFuture.cancel(true);
        if (sendTaskFuture != null && !sendTaskFuture.isDone()) sendTaskFuture.cancel(true);
        if (controlConnectFuture != null && !controlConnectFuture.isDone()) controlConnectFuture.cancel(true);

        if (executorService != null) {
            Log.d(TAG, "Shutting down ExecutorService...");
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
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

        closeSocket(socketReceive, "Receive Stop"); closeStream(inputStreamReceive, "Receive Input Stop");
        closeSocket(socketSend, "Send Stop"); closeStream(outputStreamSend, "Send Output Stop");
        closeControlConnection();

        socketReceive = null; inputStreamReceive = null; socketSend = null; outputStreamSend = null;

        Log.d(TAG, "Streaming stopped.");
        stopForeground(true);
        sendBroadcastStatus(TYPE_GENERAL, "Stopped");
        sendBroadcastStatus(TYPE_RECEIVE, "Stopped");
        sendBroadcastStatus(TYPE_SEND, "Stopped");
        sendBroadcastStatus(TYPE_CONTROL, "Disconnected");
        sendBroadcastStatus(TYPE_MELODY, "Idle"); // Reset melody status
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Streaming Service Channel", // Corrected Name
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Channel for background audio streaming");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
                Log.d(TAG, "Notification channel created.");
            } else {
                Log.e(TAG, "Failed to get NotificationManager.");
            }
        }
    }

    private Notification createNotification(String statusText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, StreamingService.class); // Use correct Service class
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Audio/Control Stream")
                .setContentText(statusText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        return builder.build();
    }

    private void updateNotification(String statusText) {
        Notification notification = createNotification(statusText);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    // --- Melody Playback Method ---
    private void startMelodyPlaybackInternal() {
        if (!isStreaming) {
            Log.w(TAG, "Cannot play melody, streaming is not active.");
            sendBroadcastStatus(TYPE_MELODY, "Error: Streaming Off");
            return;
        }
        if (isPlayingMelody) {
            Log.w(TAG, "Cannot play melody, already playing.");
            sendBroadcastStatus(TYPE_MELODY, "Already Playing");
            return;
        }
        if (outputStreamSend == null || socketSend == null || socketSend.isClosed()) {
            Log.w(TAG, "Cannot play melody, send stream not ready.");
            sendBroadcastStatus(TYPE_MELODY, "Error: Send Not Ready");
            return;
        }
        if (executorService == null || executorService.isShutdown()){
            Log.e(TAG, "Cannot play melody, executor service is not running.");
            sendBroadcastStatus(TYPE_MELODY, "Error: Service Issue");
            return;
        }


        Log.d(TAG, "Starting melody playback task...");
        isPlayingMelody = true; // Set state
        sendBroadcastStatus(TYPE_MELODY, "Playing..."); // Update status
        melodyTaskFuture = executorService.submit(new SendMelodyTask());
    }


    // --- Tasks (Receive, Send, Control, Melody) ---

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
                sendBroadcastStatus(TYPE_RECEIVE, "Connecting...");
                localSocket = new Socket();
                localSocket.setSoTimeout(5000);
                localSocket.connect(new InetSocketAddress(ip, ESP32_PORT_RECEIVE_FROM), CONNECTION_TIMEOUT_MS);
                localInputStream = localSocket.getInputStream();
                socketReceive = localSocket;
                inputStreamReceive = localInputStream;
                Log.d(TAG, "[Receive] Connected.");
                sendBroadcastStatus(TYPE_RECEIVE, "Connected");
                if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) { Log.e(TAG, "[Receive] AudioTrack not ready."); sendBroadcastStatus(TYPE_RECEIVE,"Error: Speaker Init Failed"); return; }
                if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) { try { audioTrack.play(); Log.d(TAG, "[Receive] AudioTrack playing."); } catch (IllegalStateException e) { Log.e(TAG, "[Receive] Failed start playback: " + e.getMessage()); sendBroadcastStatus(TYPE_RECEIVE,"Error: Playback Start Failed"); return; } }
                sendBroadcastStatus(TYPE_RECEIVE, "Receiving/Playing");
                while (isStreaming && !Thread.currentThread().isInterrupted() && socketReceive != null && !socketReceive.isClosed()) {
                    try {
                        bytesRead = inputStreamReceive.read(buffer);
                        if (bytesRead == -1) { Log.d(TAG, "[Receive] Connection closed by peer (read -1)."); break; }
                        if (bytesRead > 0 && audioTrack != null) {
                            int written = audioTrack.write(buffer, 0, bytesRead);
                            if (written < 0) { Log.e(TAG, "[Receive] AudioTrack write error: " + written); sendBroadcastStatus(TYPE_RECEIVE,"Error: Playback Write Failed"); break; }
                        }
                    } catch (SocketTimeoutException e) { continue; }
                    catch (IOException e) { if (isStreaming && !Thread.currentThread().isInterrupted()) { Log.e(TAG, "[Receive] Read Error: " + e.getMessage()); sendBroadcastStatus(TYPE_RECEIVE,"Error: Network Read Failed"); } else { Log.d(TAG, "[Receive] Read Error after stop: " + e.getMessage()); } break; }
                    catch (IllegalStateException e) { Log.e(TAG, "[Receive] AudioTrack state error: " + e.getMessage()); sendBroadcastStatus(TYPE_RECEIVE,"Error: Playback State Issue"); break; }
                }
            } catch (SocketTimeoutException e) { Log.e(TAG, "[Receive] Connect Timeout: " + e.getMessage()); if(isStreaming) { sendBroadcastStatus(TYPE_RECEIVE,"Error: Conn Timeout"); } }
            catch (IOException e) { if (isStreaming && !Thread.currentThread().isInterrupted()) { Log.e(TAG, "[Receive] Connect Error: " + e.getMessage()); sendBroadcastStatus(TYPE_RECEIVE,"Error: Conn Failed"); } else { Log.d(TAG, "[Receive] Connect Error after stop: " + e.getMessage()); } }
            catch (Exception e) { if(isStreaming) { Log.e(TAG, "[Receive] Unexpected Error: " + e.getMessage(), e); sendBroadcastStatus(TYPE_RECEIVE,"Error: Unexpected"); } }
            finally {
                Log.d(TAG, "[Receive] Task finishing.");
                if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) { try { audioTrack.stop();} catch (Exception e) { Log.w(TAG,"[Receive] Exception stopping AudioTrack: " + e.getMessage()); } }
                closeStream(localInputStream, "Receive Task Finally");
                closeSocket(localSocket, "Receive Task Finally");
                if(isStreaming && !Thread.currentThread().isInterrupted()){
                    sendBroadcastStatus(TYPE_RECEIVE, "Disconnected");
                }
            }
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
            if (ActivityCompat.checkSelfPermission(StreamingService.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "[Send] Permission missing at start!"); sendBroadcastStatus(TYPE_SEND, "Error: Permission Missing"); return; }
            try {
                Thread.currentThread().setName("RecordAndSendTask");
                Log.d(TAG, "[Send] Task starting. Connecting to Speaker Receiver " + ip + ":" + ESP32_PORT_SEND_TO);
                sendBroadcastStatus(TYPE_SEND, "Connecting...");
                localSocket = new Socket();
                localSocket.setSoTimeout(5000);
                localSocket.connect(new InetSocketAddress(ip, ESP32_PORT_SEND_TO), CONNECTION_TIMEOUT_MS);
                localOutputStream = localSocket.getOutputStream();
                socketSend = localSocket;
                outputStreamSend = localOutputStream;
                Log.d(TAG, "[Send] Connected.");
                sendBroadcastStatus(TYPE_SEND, "Connected");
                if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) { Log.e(TAG, "[Send] AudioRecord not ready."); sendBroadcastStatus(TYPE_SEND, "Error: Mic Init Failed"); return; }
                if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { try { audioRecord.startRecording(); Log.d(TAG, "[Send] AudioRecord recording."); } catch (IllegalStateException e) { Log.e(TAG, "[Send] Failed start recording: " + e.getMessage()); sendBroadcastStatus(TYPE_SEND, "Error: Mic Start Failed"); return; } }
                sendBroadcastStatus(TYPE_SEND, "Recording/Sending");
                while (isStreaming && !Thread.currentThread().isInterrupted() && socketSend != null && !socketSend.isClosed()) {
                    // Skip sending mic data if melody is playing
                    if (isPlayingMelody) { try { Thread.sleep(20); continue; } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; } }

                    if (audioRecord == null || audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { Log.w(TAG,"[Send] AudioRecord stopped unexpectedly."); sendBroadcastStatus(TYPE_SEND, "Error: Mic Stopped"); break; }
                    if (ActivityCompat.checkSelfPermission(StreamingService.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "[Send] Permission lost mid-stream."); sendBroadcastStatus(TYPE_SEND, "Error: Permission Lost"); break; }

                    bytesRead = audioRecord.read(buffer, 0, buffer.length);
                    if (bytesRead > 0 && outputStreamSend != null) {
                        try {
                            outputStreamSend.write(buffer, 0, bytesRead);
                        } catch (IOException e) {
                            if (isStreaming && !Thread.currentThread().isInterrupted()) { Log.e(TAG, "[Send] Write Error: " + e.getMessage()); sendBroadcastStatus(TYPE_SEND, "Error: Network Write Failed"); } else { Log.d(TAG, "[Send] Write Error after stop: " + e.getMessage()); }
                            break;
                        }
                    }
                    else if (bytesRead < 0) { Log.e(TAG, "[Send] AudioRecord read error: " + bytesRead); sendBroadcastStatus(TYPE_SEND, "Error: Mic Read Failed"); break; }
                }
            } catch (SocketTimeoutException e) { Log.e(TAG, "[Send] Connect Timeout: " + e.getMessage()); if(isStreaming) { sendBroadcastStatus(TYPE_SEND, "Error: Conn Timeout"); } }
            catch (IOException e) { if(isStreaming && !Thread.currentThread().isInterrupted()) { Log.e(TAG, "[Send] Connect/IO Error: " + e.getMessage()); sendBroadcastStatus(TYPE_SEND, "Error: Conn/IO Failed"); } else { Log.d(TAG, "[Send] Connect/IO Error after stop: " + e.getMessage()); } }
            catch (IllegalStateException e) { if(isStreaming) { Log.e(TAG,"[Send] AudioRecord state error: " + e.getMessage()); sendBroadcastStatus(TYPE_SEND, "Error: Mic State Issue"); } }
            catch (SecurityException e){ Log.e(TAG, "[Send] Security Exception (likely permission): " + e.getMessage()); if(isStreaming) { sendBroadcastStatus(TYPE_SEND, "Error: Permission Issue"); } }
            catch (Exception e) { if(isStreaming) { Log.e(TAG, "[Send] Unexpected Error: " + e.getMessage(), e); sendBroadcastStatus(TYPE_SEND, "Error: Unexpected Send"); } }
            finally {
                Log.d(TAG, "[Send] Task finishing.");
                if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) { try { audioRecord.stop();} catch (Exception e) { Log.w(TAG,"[Send] Exception stopping AudioRecord: " + e.getMessage()); } }
                closeStream(localOutputStream, "Send Task Finally");
                closeSocket(localSocket, "Send Task Finally");
                if(isStreaming && !Thread.currentThread().isInterrupted()){
                    sendBroadcastStatus(TYPE_SEND, "Disconnected");
                }
            }
        }
    }

    // <<< ADDED: SendMelodyTask inner class >>>
    private class SendMelodyTask implements Runnable {
        @Override public void run() {
            Thread.currentThread().setName("SendMelodyTask");
            Log.d(TAG, "[Melody] Task started.");
            InputStream melodyInputStream = null;
            byte[] melodyBuffer = new byte[audioRecordBufferSize > 0 ? audioRecordBufferSize : 4096];
            int bytesRead = 0;
            long totalBytesSent = 0;
            boolean successful = false;
            // Use the service's output stream directly
            OutputStream currentOutputStream = outputStreamSend;
            // Ensure the resource exists in res/raw/
            final int melodyResourceId = R.raw.soothing_melody;

            try {
                if (currentOutputStream == null) {
                    throw new IOException("Output stream is null at melody start.");
                }
                Log.d(TAG, "[Melody] Attempting to open melody resource ID: " + melodyResourceId);
                // Use getResources() from the Service context
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
                            sendBroadcastStatus(TYPE_MELODY, "Error: Network Send");
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
                        sendBroadcastStatus(TYPE_MELODY, "Error: Flush Failed");
                        successful = false;
                    }
                } else {
                    successful = false;
                    Log.w(TAG, "[Melody] Loop terminated prematurely/error. Interrupted=" + Thread.currentThread().isInterrupted() + ", isStreaming=" + isStreaming + ", isPlayingMelody=" + isPlayingMelody + ", bytesRead=" + bytesRead);
                }

                if (successful) {
                    Log.d(TAG, "[Melody] Finished successfully. Total Bytes: " + totalBytesSent);
                    sendBroadcastStatus(TYPE_MELODY, "Finished");
                } else if (!Thread.currentThread().isInterrupted() && isStreaming) {
                    Log.w(TAG, "[Melody] Playback incomplete. Total Bytes: " + totalBytesSent);
                    // Avoid overwriting specific errors with "Incomplete"
                    // sendBroadcastStatus(TYPE_MELODY, "Incomplete");
                } else if (Thread.currentThread().isInterrupted()) {
                    Log.w(TAG, "[Melody] Playback interrupted. Total Bytes: " + totalBytesSent);
                    sendBroadcastStatus(TYPE_MELODY, "Cancelled");
                }

            } catch (Resources.NotFoundException e) {
                String resourceName = "UNKNOWN";
                try { resourceName = getResources().getResourceEntryName(melodyResourceId); } catch (Exception ignore) {}
                Log.e(TAG, "[Melody] Error: Resource not found! Check 'res/raw/" + resourceName + "' (ID: " + melodyResourceId + ")", e);
                sendBroadcastStatus(TYPE_MELODY, "Error: File Missing");
                successful = false;
            } catch (IOException e) {
                if (isStreaming) {
                    Log.e(TAG, "[Melody] IO Error: " + e.getMessage(), e);
                    sendBroadcastStatus(TYPE_MELODY, "Error: IO Failed");
                }
                successful = false;
            } catch (Exception e) {
                if (isStreaming) {
                    Log.e(TAG, "[Melody] Unexpected error during playback: " + e.getMessage(), e);
                    sendBroadcastStatus(TYPE_MELODY, "Error: Unexpected Melody");
                }
                successful = false;
            } finally {
                Log.d(TAG, "[Melody] Task finishing block.");
                isPlayingMelody = false; // <<< Reset melody playing state
                if (melodyInputStream != null) {
                    try { melodyInputStream.close(); } catch (IOException e) { Log.w(TAG, "[Melody] Error closing input stream: " + e.getMessage());}
                }
                // Send final "Idle" status slightly delayed if finished successfully
                // to allow UI to show "Finished" briefly
                if (successful && isStreaming) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if(isStreaming) sendBroadcastStatus(TYPE_MELODY, "Idle");
                    }, 1500);
                } else if (isStreaming) {
                    // If not successful but still streaming, immediately set back to Idle or last error state
                    sendBroadcastStatus(TYPE_MELODY, "Idle"); // Or consider keeping error message?
                }
            }
        }
    }
    // <<< END SendMelodyTask >>>

    private class ControlConnectTask implements Runnable {
        private final String ip;
        ControlConnectTask(String controlIp) { this.ip = controlIp; }
        @Override public void run() {
            Thread.currentThread().setName("ControlConnectTask");
            Log.d(TAG, "[Control] Task starting. Connecting to " + ip + ":" + ESP32_PORT_CONTROL);
            sendBroadcastStatus(TYPE_CONTROL, "Connecting...");
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
                sendBroadcastStatus(TYPE_CONTROL, "Connected");
                updateNotification("Streaming Active"); // Update general status
            } catch (SocketTimeoutException e) { Log.e(TAG, "[Control] Connect Timeout: " + e.getMessage()); sendBroadcastStatus(TYPE_CONTROL, "Error: Timeout"); closeControlConnection(); }
            catch (IOException e) { Log.e(TAG, "[Control] Connect IOException: " + e.getMessage()); if (isStreaming) { sendBroadcastStatus(TYPE_CONTROL, "Error: IO"); } closeControlConnection(); }
            catch (Exception e) { Log.e(TAG, "[Control] Connect Unexpected Error: " + e.getMessage(), e); sendBroadcastStatus(TYPE_CONTROL, "Error: Unexpected"); closeControlConnection(); }
            finally { Log.d(TAG, "[Control] Connect Task finishing."); if (!isControlConnected && isStreaming) { sendBroadcastStatus(TYPE_CONTROL, "Error: Failed"); } }
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
                    sendBroadcastStatus(TYPE_CONTROL, "Error: Send Failed");
                    closeControlConnection();
                }
            } catch (Exception e) {
                Log.e(TAG, "[Control] Unexpected error sending '" + command.trim() + "': " + e.getMessage(), e);
                sendBroadcastStatus(TYPE_CONTROL, "Error: Send Exception");
                closeControlConnection();
            }
        });
    }


    private boolean initializeAudioTrack() {
        releaseAudioTrack();
        try {
            Log.d(TAG, "Initializing AudioTrack...");
            if (audioTrackBufferSize <= 0) { Log.e(TAG,"AudioTrack buffer size invalid."); return false; }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AUDIO_FORMAT_RECEIVE).setSampleRate(SAMPLE_RATE_RECEIVE).setChannelMask(CHANNEL_CONFIG_RECEIVE).build())
                        .setBufferSizeInBytes(audioTrackBufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();
            } else {
                audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE_RECEIVE, CHANNEL_CONFIG_RECEIVE, AUDIO_FORMAT_RECEIVE, audioTrackBufferSize, AudioTrack.MODE_STREAM);
            }
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) { Log.e(TAG, "AudioTrack initialization failed! State: " + audioTrack.getState()); releaseAudioTrack(); return false; }
            Log.d(TAG, "AudioTrack Initialized."); return true;
        } catch (Exception e) { Log.e(TAG, "AudioTrack Init Exception: " + e.getMessage(), e); audioTrack = null; return false; }
    }

    private boolean initializeAudioRecord() {
        releaseAudioRecord();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "RECORD_AUDIO permission not granted."); return false; }
        try {
            Log.d(TAG, "Initializing AudioRecord...");
            if (audioRecordBufferSize <= 0) { Log.e(TAG,"AudioRecord buffer size invalid"); return false; }
            int audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            audioRecord = new AudioRecord(audioSource, SAMPLE_RATE_SEND, CHANNEL_CONFIG_SEND, AUDIO_FORMAT_SEND, audioRecordBufferSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord init failed! State: " + audioRecord.getState() + ". Trying MIC source.");
                if (audioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                    if (audioRecord != null) audioRecord.release();
                    audioSource = MediaRecorder.AudioSource.MIC;
                    audioRecord = new AudioRecord(audioSource, SAMPLE_RATE_SEND, CHANNEL_CONFIG_SEND, AUDIO_FORMAT_SEND, audioRecordBufferSize);
                    if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioRecord init failed with MIC source too.");
                        releaseAudioRecord(); return false;
                    }
                } else {
                    releaseAudioRecord(); return false;
                }
            }
            Log.d(TAG, "AudioRecord Initialized. Source: " + audioSource); return true;
        } catch (Exception e) { Log.e(TAG, "AudioRecord Init Exception: " + e.getMessage(), e); audioRecord = null; return false; }
    }

    private void releaseAudioTrack() {
        if (audioTrack != null) {
            Log.d(TAG, "Releasing AudioTrack...");
            if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                try { if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) { audioTrack.stop(); } audioTrack.release(); }
                catch (Exception e) { Log.e(TAG,"Error releasing AudioTrack: " + e.getMessage());}
            } audioTrack = null;
        }
    }
    private void releaseAudioRecord() {
        if (audioRecord != null) {
            Log.d(TAG, "Releasing AudioRecord...");
            if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                try { if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) { audioRecord.stop(); } audioRecord.release(); }
                catch (Exception e) { Log.e(TAG,"Error releasing AudioRecord: " + e.getMessage());}
            } audioRecord = null;
        }
    }

    private void closeSocket(Socket socket, String context) {
        if (socket != null && !socket.isClosed()) { try { socket.close(); Log.d(TAG, "Socket closed ("+context+")"); } catch (IOException e) { Log.e(TAG, "Error closing "+context+" socket: " + e.getMessage()); } } }
    private void closeStream(InputStream stream, String context) {
        if (stream != null) { try { stream.close(); Log.d(TAG, "InputStream closed ("+context+")"); } catch (IOException e) { Log.e(TAG, "Error closing InputStream ("+context+"): " + e.getMessage()); } } }
    private void closeStream(OutputStream stream, String context) {
        if (stream != null) { try { stream.flush(); stream.close(); Log.d(TAG, "OutputStream closed ("+context+")"); } catch (IOException e) { Log.e(TAG, "Error flushing/closing OutputStream ("+context+"): " + e.getMessage()); } } }

    private void closeControlConnection() {
        boolean wasConnected = isControlConnected;
        isControlConnected = false;
        if (printWriterControl != null) {
            printWriterControl.close(); printWriterControl = null; Log.d(TAG,"[Control] PrintWriter closed.");
        }
        closeSocket(socketControl, "Control Stop"); socketControl = null;
        if(wasConnected && isStreaming) {
            sendBroadcastStatus(TYPE_CONTROL, "Disconnected");
        }
    }

}
