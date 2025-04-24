package com.example.audio; // Use your actual package name

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;

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


public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String CAMERA_STREAM_URL = "http://192.168.1.33:81/stream"; // !!! REPLACE !!!

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

    // State reflecting service status
    private boolean isServiceRunning = false; // User's intent to run/stop
    private boolean isControlActuallyConnected = false; // Updated by broadcasts
    private boolean isReceiveConnected = false;
    private boolean isSendConnected = false;
    private boolean isMelodyPlaying = false; // <<< ADDED: Track melody state

    private float scaleFactor = 1.0f;
    private float posX = 0, posY = 0;
    private GestureDetector gestureDetector;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private String pendingMicSenderIp;
    private String pendingSpeakerReceiverIp;
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.i(TAG, "RECORD_AUDIO permission granted.");
                    if (pendingMicSenderIp != null && pendingSpeakerReceiverIp != null) {
                        startStreamingService(pendingMicSenderIp, pendingSpeakerReceiverIp);
                    } else {
                        Log.w(TAG, "IPs became null after audio permission result.");
                        showToast("Please enter IPs again.");
                        updateUiForStreamingState();
                    }
                } else {
                    Log.w(TAG, "RECORD_AUDIO permission denied.");
                    showToast("Microphone permission is required to send audio.");
                    updateUiForStreamingState();
                }
                pendingMicSenderIp = null;
                pendingSpeakerReceiverIp = null;
            });

    private ActivityResultLauncher<String> requestNotificationPermissionLauncher;
    private boolean notificationPermissionRequested = false;

    private BroadcastReceiver statusUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && StreamingService.ACTION_STATUS_UPDATE.equals(intent.getAction())) { // Corrected Service Name
                String type = intent.getStringExtra(StreamingService.EXTRA_STATUS_TYPE);
                String message = intent.getStringExtra(StreamingService.EXTRA_STATUS_MESSAGE);
                Log.d(TAG, "Receiver got status: Type=" + type + ", Msg=" + message);

                if (type != null && message != null) {
                    updateStatusText(type, message);
                    updateComponentState(type, message);
                }
            }
        }
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestNotificationPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                Log.i(TAG, "POST_NOTIFICATIONS permission granted.");
                checkAudioPermissionAndStartService();
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS permission denied.");
                showToast("Notification permission is needed for background status updates.");
                checkAudioPermissionAndStartService(); // Proceed anyway
            }
            notificationPermissionRequested = false;
        });

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

        buttonStartStopAll.setOnClickListener(v -> {
            if (!isServiceRunning) {
                String micIp = editTextEspMicSenderIpAddress.getText().toString().trim();
                String speakerIp = editTextEspSpeakerReceiverIpAddress.getText().toString().trim();
                if (micIp.isEmpty() || speakerIp.isEmpty()) {
                    showToast("Please enter BOTH ESP32 IP Addresses");
                    return;
                }
                checkPermissionAndStartService(micIp, speakerIp);
            } else {
                stopStreamingService();
            }
        });

        // --- Modified: Melody Button sends Intent ---
        buttonPlayMelody.setEnabled(false); // Start disabled, enabled by broadcast
        buttonPlayMelody.setOnClickListener(v -> {
            if (isServiceRunning && isControlActuallyConnected && !isMelodyPlaying) {
                Log.d(TAG, "Requesting Melody Playback via Service Intent");
                Intent melodyIntent = new Intent(this, StreamingService.class); // Corrected Service Name
                melodyIntent.setAction(StreamingService.ACTION_PLAY_MELODY); // Corrected Service Name
                startService(melodyIntent);
            } else if (isMelodyPlaying) {
                showToast("Melody is already playing.");
            } else {
                showToast("Cannot play melody now (Stream/Control not ready?).");
            }
        });

        setupServoSeekBarListeners();
        setupCameraWebView();
        updateUiForStreamingState();
        resetStatusTextViews();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart - Registering receiver");
        LocalBroadcastManager.getInstance(this).registerReceiver(statusUpdateReceiver,
                new IntentFilter(StreamingService.ACTION_STATUS_UPDATE)); // Corrected Service Name
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop - Unregistering receiver");
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusUpdateReceiver);
    }


    private void checkPermissionAndStartService(String micSenderIp, String speakerReceiverIp) {
        pendingMicSenderIp = micSenderIp;
        pendingSpeakerReceiverIp = speakerReceiverIp;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (!notificationPermissionRequested) {
                    Log.d(TAG, "Requesting POST_NOTIFICATIONS permission...");
                    notificationPermissionRequested = true;
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                    return;
                } else {
                    Log.w(TAG, "Notification permission previously denied, proceeding...");
                }
            }
        }
        checkAudioPermissionAndStartService();
    }

    private void checkAudioPermissionAndStartService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (pendingMicSenderIp != null && pendingSpeakerReceiverIp != null) {
                startStreamingService(pendingMicSenderIp, pendingSpeakerReceiverIp);
                pendingMicSenderIp = null;
                pendingSpeakerReceiverIp = null;
            } else {
                Log.w(TAG, "IPs became null before starting service after permission checks.");
                showToast("Error retrieving IP addresses.");
                isServiceRunning = false;
                updateUiForStreamingState();
            }
        } else {
            Log.d(TAG, "Requesting RECORD_AUDIO permission...");
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startStreamingService(String micIp, String speakerIp) {
        Log.d(TAG, "Requesting Service Start");
        Intent serviceIntent = new Intent(this, StreamingService.class); // Corrected Service Name
        serviceIntent.setAction(StreamingService.ACTION_START); // Corrected Service Name
        serviceIntent.putExtra(StreamingService.EXTRA_MIC_SENDER_IP, micIp); // Corrected Service Name
        serviceIntent.putExtra(StreamingService.EXTRA_SPEAKER_RECEIVER_IP, speakerIp); // Corrected Service Name

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        isServiceRunning = true;
        updateUiForStreamingState();
        resetStatusTextViews();
        textViewStatusReceive.setText("Status: Starting Service...");
    }

    private void stopStreamingService() {
        Log.d(TAG, "Requesting Service Stop");
        Intent serviceIntent = new Intent(this, StreamingService.class); // Corrected Service Name
        serviceIntent.setAction(StreamingService.ACTION_STOP); // Corrected Service Name
        startService(serviceIntent);
        isServiceRunning = false;
        isControlActuallyConnected = false;
        isReceiveConnected = false;
        isSendConnected = false;
        isMelodyPlaying = false; // Reset melody state on stop
        updateUiForStreamingState();
        resetStatusTextViews();
    }

    private void sendCommandToService(String command) {
        if (!isServiceRunning) {
            Log.w(TAG, "Attempted to send command, but service stop initiated.");
            showToast("Start the stream first");
            return;
        }
        Intent serviceIntent = new Intent(this, StreamingService.class); // Corrected Service Name
        serviceIntent.setAction(StreamingService.ACTION_SEND_COMMAND); // Corrected Service Name
        serviceIntent.putExtra(StreamingService.EXTRA_COMMAND, command); // Corrected Service Name
        startService(serviceIntent);
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
            public boolean onDown(MotionEvent e) { return true; }
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
                    posX = 0; posY = 0;
                    cameraWebView.setTranslationX(0); cameraWebView.setTranslationY(0);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        cameraWebView.setOnTouchListener((v, event) -> {
            boolean handled = gestureDetector.onTouchEvent(event);
            return handled || v.onTouchEvent(event);
        });

        scaleFactor = 1.0f; posX = 0; posY = 0;
        cameraWebView.setScaleX(scaleFactor); cameraWebView.setScaleY(scaleFactor);
        cameraWebView.setTranslationX(posX); cameraWebView.setTranslationY(posY);
    }

    private void setupServoSeekBarListeners() {
        if (seekBarServo1 == null || seekBarServo2 == null || textViewServo1Value == null || textViewServo2Value == null) {
            Log.e(TAG, "Servo UI elements null in setupServoSeekBarListeners");
            return;
        }
        seekBarServo1.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && isControlActuallyConnected) {
                    textViewServo1Value.setText(String.valueOf(progress));
                    sendCommandToService("S1=" + progress);
                } else if (fromUser) {
                    showToast("Control not connected");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                if (!isControlActuallyConnected) showToast("Control not connected");
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        seekBarServo2.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && isControlActuallyConnected) {
                    textViewServo2Value.setText(String.valueOf(progress));
                    sendCommandToService("S2=" + progress);
                } else if (fromUser) {
                    showToast("Control not connected");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                if (!isControlActuallyConnected) showToast("Control not connected");
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
    }

    private void updateStatusText(String type, String message) {
        uiHandler.post(() -> {
            switch (type) {
                case StreamingService.TYPE_RECEIVE: // Corrected Service Name
                    if (textViewStatusReceive != null) textViewStatusReceive.setText("Receiving: " + message);
                    break;
                case StreamingService.TYPE_SEND: // Corrected Service Name
                    if (textViewStatusSend != null) textViewStatusSend.setText("Sending: " + message);
                    break;
                case StreamingService.TYPE_CONTROL: // Corrected Service Name
                    if (textViewControlStatus != null) textViewControlStatus.setText("Control: " + message);
                    break;
                case StreamingService.TYPE_MELODY: // Corrected Service Name
                    if (textViewMelodyStatus != null) textViewMelodyStatus.setText("Melody: " + message);
                    break;
                case StreamingService.TYPE_GENERAL: // Corrected Service Name
                    if (message.toLowerCase().contains("error")) {
                        showToast("Service Error: " + message);
                    } else if ("Stopped".equalsIgnoreCase(message) || "Service Stopped".equalsIgnoreCase(message)) {
                        resetStatusTextViews();
                    }
                    break;
            }
        });
    }

    private void updateComponentState(String type, String message) {
        boolean needsUiUpdate = false;
        boolean wasMelodyPlaying = isMelodyPlaying; // Track previous state

        if (StreamingService.TYPE_CONTROL.equals(type)) { // Corrected Service Name
            boolean newControlState = "Connected".equalsIgnoreCase(message);
            if (isControlActuallyConnected != newControlState) {
                isControlActuallyConnected = newControlState;
                needsUiUpdate = true;
            }
        } else if (StreamingService.TYPE_RECEIVE.equals(type)) { // Corrected Service Name
            boolean newReceiveState = "Connected".equalsIgnoreCase(message) || "Receiving/Playing".equalsIgnoreCase(message);
            if (isReceiveConnected != newReceiveState) {
                isReceiveConnected = newReceiveState;
            }
        } else if (StreamingService.TYPE_SEND.equals(type)) { // Corrected Service Name
            boolean newSendState = "Connected".equalsIgnoreCase(message) || "Recording/Sending".equalsIgnoreCase(message);
            if (isSendConnected != newSendState) {
                isSendConnected = newSendState;
            }
        } else if (StreamingService.TYPE_MELODY.equals(type)) { // Corrected Service Name <<< ADDED Melody State Update
            isMelodyPlaying = "Playing...".equalsIgnoreCase(message);
            if (wasMelodyPlaying != isMelodyPlaying) { // Update UI if playing state changed
                needsUiUpdate = true;
            }
        }
        else if (StreamingService.TYPE_GENERAL.equals(type)) { // Corrected Service Name
            if ("Stopped".equalsIgnoreCase(message) || "Service Stopped".equalsIgnoreCase(message)) {
                if (isControlActuallyConnected || isReceiveConnected || isSendConnected || isMelodyPlaying) {
                    isControlActuallyConnected = false;
                    isReceiveConnected = false;
                    isSendConnected = false;
                    isMelodyPlaying = false;
                    needsUiUpdate = true;
                }
            }
        }

        if (needsUiUpdate) {
            updateUiForStreamingState();
        }
    }

    private void updateUiForStreamingState() {
        uiHandler.post(() -> {
            if (buttonStartStopAll == null || editTextEspMicSenderIpAddress == null ||
                    editTextEspSpeakerReceiverIpAddress == null || buttonPlayMelody == null ||
                    seekBarServo1 == null || seekBarServo2 == null) {
                Log.w(TAG, "Cannot update UI state, view is null");
                return;
            }

            buttonStartStopAll.setText(isServiceRunning ? "Stop Streams" : "Start Streams");
            editTextEspMicSenderIpAddress.setEnabled(!isServiceRunning);
            editTextEspSpeakerReceiverIpAddress.setEnabled(!isServiceRunning);

            seekBarServo1.setEnabled(isServiceRunning && isControlActuallyConnected);
            seekBarServo2.setEnabled(isServiceRunning && isControlActuallyConnected);

            // Enable Melody button ONLY if service running, control connected, AND melody NOT playing
            buttonPlayMelody.setEnabled(isServiceRunning && isControlActuallyConnected && !isMelodyPlaying); // <<< Modified
        });
    }

    private void resetStatusTextViews() {
        uiHandler.post(() -> {
            if (textViewStatusReceive != null) textViewStatusReceive.setText("Receiving: Idle");
            if (textViewStatusSend != null) textViewStatusSend.setText("Sending: Idle");
            if (textViewMelodyStatus != null) textViewMelodyStatus.setText("Melody: Idle");
            if (textViewControlStatus != null) textViewControlStatus.setText("Control: Disconnected");
        });
    }

    private void showToast(final String message) {
        if (!isFinishing() && !isDestroyed()) {
            uiHandler.post(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        } else {
            Log.w(TAG, "Activity finishing/destroyed, cannot show toast: " + message);
        }
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
        updateUiForStreamingState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG,"onDestroy called.");

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
        Log.d(TAG, "onDestroy finished.");
    }

}
