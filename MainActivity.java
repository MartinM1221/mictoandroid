package com.example.audio; // Replace with your actual package name

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AudioStream";
    private static final int SERVER_PORT = 8080; // Must match ESP32 port
    private static final int CONNECTION_TIMEOUT_MS = 5000; // 5 seconds

    // --- Audio Configuration --- MUST MATCH ESP32 ---
    private static final int SAMPLE_RATE = 16000; // Hz (Must match I2S_SAMPLE_RATE)
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO; // (Matches I2S_CHANNEL_FMT_ONLY_LEFT)
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_32BIT; // (Matches I2S_BITS_PER_SAMPLE_32BIT)
    // --- End Audio Configuration ---

    private static final int READ_BUFFER_SIZE = 1024; // Network read buffer size

    private EditText editTextIpAddress;
    private Button buttonConnect;
    private TextView textViewStatus;
    private TextView textViewReceivedData;

    private Socket socket;
    private InputStream inputStream;
    private Thread networkThread;
    private volatile boolean isRunning = false;

    private AudioTrack audioTrack;
    private int audioBufferSize = 0;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editTextIpAddress = findViewById(R.id.editTextIpAddress);
        buttonConnect = findViewById(R.id.buttonConnect);
        textViewStatus = findViewById(R.id.textViewStatus);
        textViewReceivedData = findViewById(R.id.textViewReceivedData);

        buttonConnect.setOnClickListener(v -> {
            if (!isRunning) {
                String ipAddress = editTextIpAddress.getText().toString().trim();
                if (ipAddress.isEmpty()) {
                    Toast.makeText(this, "Please enter ESP32 IP Address", Toast.LENGTH_SHORT).show();
                    return;
                }
                startNetworkConnection(ipAddress);
            } else {
                stopNetworkConnection();
            }
        });

        // Calculate minimum buffer size for AudioTrack
        audioBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (audioBufferSize == AudioTrack.ERROR_BAD_VALUE || audioBufferSize == AudioTrack.ERROR) {
            Log.e(TAG, "Invalid AudioTrack parameters. Cannot calculate buffer size.");
            showToast("Error: Invalid Audio Parameters for this device.");
            buttonConnect.setEnabled(false); // Disable connection if audio params invalid
            return;
        }
        // It's often better to use a slightly larger buffer than the minimum
        // audioBufferSize *= 2; // Optional: Double the minimum buffer size
        Log.d(TAG, "Calculated min AudioTrack buffer size: " + audioBufferSize + " bytes");
        if (audioBufferSize <= 0) {
            Log.e(TAG, "AudioTrack buffer size calculation failed. Using default.");
            audioBufferSize = READ_BUFFER_SIZE * 4; // Fallback buffer size
            showToast("Warning: Could not calculate precise audio buffer.");
        }


    }

    private void initializeAudioTrack() {
        if (audioTrack != null) {
            Log.w(TAG, "AudioTrack already initialized. Releasing first.");
            releaseAudioTrack();
        }

        try {
            Log.d(TAG, "Initializing AudioTrack...");
            // Use Builder for newer Android versions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioTrack = new AudioTrack.Builder()
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(CHANNEL_CONFIG)
                                .build())
                        .setBufferSizeInBytes(audioBufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        // Consider setting performance mode if needed (API 26+)
                        // .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();
            } else {
                // Deprecated constructor for older versions
                audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        audioBufferSize,
                        AudioTrack.MODE_STREAM);
            }

            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack initialization failed!");
                showToast("Error: Could not initialize Audio Player");
                audioTrack = null; // Ensure it's null if failed
                // Consider stopping the connection attempt here
                return;
            }

            Log.d(TAG, "AudioTrack Initialized Successfully. State: " + audioTrack.getState());
            audioTrack.play(); // Start playback immediately after initialization
            Log.d(TAG, "AudioTrack playing. State: " + audioTrack.getPlayState());

        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Error initializing AudioTrack: " + e.getMessage());
            showToast("Error: Audio Player Init Failed ("+ e.getMessage() + ")");
            audioTrack = null;
        } catch (UnsupportedOperationException e) {
            Log.e(TAG, "Error initializing AudioTrack (Unsupported): " + e.getMessage());
            showToast("Error: Audio format likely not supported on this device.");
            audioTrack = null;
        }
    }

    private void releaseAudioTrack() {
        if (audioTrack != null) {
            Log.d(TAG, "Releasing AudioTrack...");
            try {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.stop();
                    Log.d(TAG,"AudioTrack stopped.");
                }
                audioTrack.release();
                Log.d(TAG,"AudioTrack released.");
            } catch (IllegalStateException e) {
                Log.e(TAG, "IllegalStateException while releasing AudioTrack: " + e.getMessage());
            } finally {
                audioTrack = null;
            }
        }
    }


    private void startNetworkConnection(String ipAddress) {
        if (networkThread != null && networkThread.isAlive()) {
            Log.w(TAG, "Network thread already running");
            return;
        }
        if(audioBufferSize <= 0){
            showToast("Error: Audio buffer not configured.");
            return;
        }

        isRunning = true;
        updateUiStatus("Connecting...", "Connect");

        networkThread = new Thread(() -> {
            try {
                Log.d(TAG, "Attempting to connect to " + ipAddress + ":" + SERVER_PORT);
                socket = new Socket();
                socket.connect(new InetSocketAddress(ipAddress, SERVER_PORT), CONNECTION_TIMEOUT_MS);
                Log.d(TAG, "Socket Connected");
                inputStream = socket.getInputStream();

                // --- Initialize and start AudioTrack AFTER connection ---
                initializeAudioTrack();
                if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "Failed to initialize AudioTrack. Aborting network thread.");
                    // Don't proceed if audio couldn't be set up
                    throw new IOException("AudioTrack Initialization Failed");
                }
                // --- End AudioTrack Initialization ---

                updateUiStatus("Connected & Playing", "Disconnect");

                byte[] buffer = new byte[READ_BUFFER_SIZE];
                int bytesRead;

                while (isRunning && !Thread.currentThread().isInterrupted() && socket.isConnected() && audioTrack != null) {
                    try {
                        bytesRead = inputStream.read(buffer);
                        if (bytesRead == -1) {
                            Log.d(TAG, "Connection closed by server.");
                            break;
                        }

                        if (bytesRead > 0) {
                            // --- Write received data to AudioTrack ---
                            int bytesWritten = audioTrack.write(buffer, 0, bytesRead);
                            if (bytesWritten < 0) {
                                Log.e(TAG, "AudioTrack write error: " + bytesWritten);
                                // Handle error (e.g., break loop, try to re-init AudioTrack?)
                                break;
                            }
                            if (bytesWritten < bytesRead) {
                                Log.w(TAG, "AudioTrack buffer full? Wrote " + bytesWritten + "/" + bytesRead + " bytes.");
                                // This indicates the buffer might be filling up, playback can't keep up.
                            }
                            // --- End AudioTrack write ---

                            // --- Update UI (Optional: Show first sample value) ---
                            if (bytesRead >= 4) {
                                ByteBuffer bb = ByteBuffer.wrap(buffer, 0, 4);
                                bb.order(ByteOrder.LITTLE_ENDIAN);
                                int sampleValue = bb.getInt();
                                updateUiData(String.valueOf(sampleValue));
                            } else {
                                updateUiData("..."); // Indicate data flowing but not a full sample?
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        Log.v(TAG, "Read timeout");
                        continue;
                    } catch (IOException e) {
                        Log.e(TAG, "Read Error: " + e.getMessage());
                        break;
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "AudioTrack Illegal State during write: " + e.getMessage());
                        break; // Stop if AudioTrack is in a bad state
                    }
                }

            } catch (SocketTimeoutException e) {
                Log.e(TAG, "Connection Timeout: " + e.getMessage());
                updateUiStatus("Error: Connection Timeout", "Connect");
                showToast("Connection Timed Out");
            } catch (IOException e) {
                Log.e(TAG, "Connection/Setup Error: " + e.getMessage());
                updateUiStatus("Error: " + e.getMessage().substring(0, Math.min(e.getMessage().length(), 30)), "Connect");
                showToast("Connection or Audio Setup Failed");
            } finally {
                Log.d(TAG, "Network thread finishing. Cleaning up.");
                closeSocketResources();
                releaseAudioTrack(); // Ensure AudioTrack is released
                if (isRunning) {
                    updateUiStatus("Disconnected", "Connect");
                    updateUiData("---");
                }
                isRunning = false;
            }
        });

        networkThread.start();
    }

    private void stopNetworkConnection() {
        Log.d(TAG, "Stopping network connection...");
        isRunning = false;
        if (networkThread != null) {
            networkThread.interrupt();
        }
        closeSocketResources();
        releaseAudioTrack(); // Release AudioTrack when stopping
        updateUiStatus("Disconnected", "Connect");
        updateUiData("---");
    }

    private void closeSocketResources() {
        // Same as before (closing inputStream and socket)
        try {
            if (inputStream != null) {
                inputStream.close();
                inputStream = null;
                Log.d(TAG,"Input stream closed");
            }
        } catch (IOException e) { /* ... Log error ... */ }
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                socket = null;
                Log.d(TAG,"Socket closed");
            }
        } catch (IOException e) { /* ... Log error ... */ }
    }

    // Helper methods (updateUiData, updateUiStatus, showToast, bytesToHex) remain the same as before...
    private void updateUiData(final String data) {
        uiHandler.post(() -> textViewReceivedData.setText(data));
    }
    private void updateUiStatus(final String status, final String buttonText) {
        uiHandler.post(() -> {
            textViewStatus.setText("Status: " + status);
            buttonConnect.setText(buttonText);
            editTextIpAddress.setEnabled(!isRunning || buttonText.equals("Connect"));
        });
    }
    private void showToast(final String message) {
        uiHandler.post(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
    }
    // bytesToHex remains the same...


    @Override
    protected void onPause() {
        super.onPause();
        // Optional: Decide if you want to stop playback when app is paused
        // if (isRunning) {
        //    stopNetworkConnection();
        // }
        Log.d(TAG,"onPause called");
    }

    @Override
    protected void onStop() {
        super.onStop();
        // It's generally a good idea to stop playback/connection when app is no longer visible
        if (isRunning) {
            Log.d(TAG,"onStop called, stopping connection.");
            stopNetworkConnection();
        } else {
            Log.d(TAG,"onStop called, already stopped.");
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG,"onDestroy called, ensuring cleanup.");
        // Ensure everything is stopped and released when activity is destroyed
        stopNetworkConnection();
    }
}