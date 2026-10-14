package com.example.tugas7;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class StepActivity extends AppCompatActivity implements SensorEventListener, LocationListener {
    private static final int ACTIVITY_RECOGNITION_REQUEST = 100;
    private static final int LOCATION_REQUEST = 101;
    private static final int DAILY_GOAL = 10_000;
    private static final long MIN_DETECTOR_INTERVAL_NS = 300_000_000L;
    private static final String PREFS = "step_preferences";
    private static final String KEY_BASELINE = "baseline";
    private static final String KEY_DATE = "baseline_date";

    private SensorManager sensorManager;
    private Sensor stepCounter;
    private Sensor stepDetector;
    private Sensor accelerometer;
    private Sensor pressureSensor;
    private LocationManager locationManager;
    private SharedPreferences preferences;
    private TextView stepsText;
    private TextView goalText;
    private TextView statusText;
    private TextView movementText;
    private TextView pressureText;
    private TextView locationText;
    private ProgressBar goalProgress;
    private long sessionSteps;
    private float latestCounterValue = -1f;
    private long lastDetectorTimestamp;
    private boolean usingCounter;
    private boolean isListening;
    private float pressureBaseline = Float.NaN;
    private float lastAcceleration;
    private Location lastLocation;
    private float distanceMeters;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_step);

        stepsText = findViewById(R.id.steps_text);
        goalText = findViewById(R.id.goal_text);
        statusText = findViewById(R.id.status_text);
        movementText = findViewById(R.id.movement_text);
        pressureText = findViewById(R.id.pressure_text);
        locationText = findViewById(R.id.location_text);
        goalProgress = findViewById(R.id.goal_progress);
        MaterialButton resetButton = findViewById(R.id.reset_button);
        resetButton.setOnClickListener(view -> resetSteps());
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        goalText.setText(getString(R.string.goal_format, formatNumber(DAILY_GOAL)));
        updateSteps(0);
        requestPermissionAndStart();
    }

    private void requestPermissionAndStart() {
        boolean activityDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED;
        boolean locationDenied = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED;
        if (activityDenied || locationDenied) {
            String[] permissions = activityDenied
                    ? new String[]{Manifest.permission.ACTIVITY_RECOGNITION,
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}
                    : new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION};
            ActivityCompat.requestPermissions(this,
                    permissions,
                    ACTIVITY_RECOGNITION_REQUEST);
            statusText.setText(R.string.permission_needed);
            return;
        }
        startStepSensor();
        startExtraSensors();
    }

    private void startStepSensor() {
        Sensor sensor = stepCounter != null ? stepCounter : stepDetector;
        if (sensor == null) {
            statusText.setText(R.string.sensor_unavailable);
            return;
        }

        usingCounter = sensor == stepCounter;
        isListening = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (isListening) {
            statusText.setText(usingCounter ? R.string.counter_active : R.string.detector_active);
        } else {
            statusText.setText(R.string.sensor_error);
        }
    }

    private void startExtraSensors() {
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            } else {
                movementText.setText(R.string.accelerometer_unavailable);
            }
            if (pressureSensor != null) {
                sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL);
            } else {
                pressureText.setText(R.string.pressure_unavailable);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                try {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                            2000L, 2f, this);
                    locationText.setText(R.string.gps_searching);
                } catch (IllegalArgumentException ignored) {
                    locationText.setText(R.string.gps_unavailable);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isListening && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED)) {
            startStepSensor();
            startExtraSensors();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        locationManager.removeUpdates(this);
        isListening = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.values.length == 0) {
            return;
        }

        if (event.sensor == accelerometer) {
            float magnitude = (float) Math.sqrt(event.values[0] * event.values[0]
                    + event.values[1] * event.values[1] + event.values[2] * event.values[2]);
            lastAcceleration = magnitude;
            movementText.setText(magnitude > 12f ? R.string.movement_active
                    : magnitude > 10.2f ? R.string.movement_walking : R.string.movement_resting);
            return;
        }
        if (event.sensor == pressureSensor) {
            if (Float.isNaN(pressureBaseline)) {
                pressureBaseline = event.values[0];
            }
            float altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                    event.values[0]) - SensorManager.getAltitude(
                    SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureBaseline);
            pressureText.setText(getString(R.string.pressure_format, event.values[0], altitude));
            return;
        }
        if (usingCounter) {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String savedDate = preferences.getString(KEY_DATE, "");
            float totalSinceBoot = event.values[0];
            latestCounterValue = totalSinceBoot;
            float baseline = preferences.getFloat(KEY_BASELINE, -1f);
            if (!today.equals(savedDate) || baseline < 0 || totalSinceBoot < baseline) {
                baseline = totalSinceBoot;
                preferences.edit()
                        .putString(KEY_DATE, today)
                        .putFloat(KEY_BASELINE, baseline)
                        .apply();
            }
            sessionSteps = Math.max(0L, Math.round(totalSinceBoot - baseline));
        } else {
            if (lastDetectorTimestamp > 0
                    && event.timestamp - lastDetectorTimestamp < MIN_DETECTOR_INTERVAL_NS) {
                return;
            }
            lastDetectorTimestamp = event.timestamp;
            sessionSteps++;
        }
        updateSteps(sessionSteps);
    }

    private void resetSteps() {
        if (usingCounter && latestCounterValue < 0f) {
            statusText.setText(R.string.reset_waiting_for_sensor);
            return;
        }

        if (usingCounter) {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            preferences.edit()
                    .putString(KEY_DATE, today)
                    .putFloat(KEY_BASELINE, latestCounterValue)
                    .apply();
        }
        sessionSteps = 0L;
        lastDetectorTimestamp = 0L;
        updateSteps(sessionSteps);
        statusText.setText(R.string.reset_success);
    }

    private void updateSteps(long steps) {
        stepsText.setText(formatNumber(steps));
        int progress = (int) Math.min(100L, steps * 100L / DAILY_GOAL);
        goalProgress.setProgress(progress);
    }

    private String formatNumber(long value) {
        return NumberFormat.getIntegerInstance(new Locale("id", "ID")).format(value);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // The step sensors do not require an accuracy calibration flow.
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        if (lastLocation != null) {
            distanceMeters += lastLocation.distanceTo(location);
        }
        lastLocation = location;
        locationText.setText(getString(R.string.gps_format, distanceMeters / 1000f,
                location.getSpeed() * 3.6f));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ACTIVITY_RECOGNITION_REQUEST) {
            startStepSensor();
            startExtraSensors();
        } else {
            statusText.setText(R.string.permission_denied);
        }
    }
}
