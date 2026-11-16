package com.example.balmatchum;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayDeque;

public class MainActivity extends AppCompatActivity implements SensorEventListener {
    private SensorManager sm;
    private Sensor stepSensor;
    private TextView tvBpm, tvSteps;
    private final ArrayDeque<Long> times = new ArrayDeque<>();
    private MusicEngine engine;
    private int steps = 0;
    private Button btnStart;
    private boolean walking = false;
    private long startMs, bpmSum;
    private int bpmCount;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        tvBpm = findViewById(R.id.tvBpm);
        tvSteps = findViewById(R.id.tvSteps);
        findViewById(R.id.btnSim).setOnClickListener(v -> onStep());
        btnStart = findViewById(R.id.btnStart);
        btnStart.setOnClickListener(v -> { if (walking) stopWalk(); else startWalk(); });
        engine = new MusicEngine(this);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(
                Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 1);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (stepSensor != null) sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override protected void onPause() {
        super.onPause();
        sm.unregisterListener(this);
        engine.stop();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        engine.release();
    }

    @Override public void onSensorChanged(SensorEvent e) { onStep(); }
    @Override public void onAccuracyChanged(Sensor s, int a) {}

    private void startWalk() {
        walking = true;
        steps = 0; bpmSum = 0; bpmCount = 0;
        times.clear();
        startMs = SystemClock.elapsedRealtime();
        tvSteps.setText("걸음 수 0");
        tvBpm.setText("0");
        btnStart.setText("정지");
    }

    private void stopWalk() {
        walking = false;
        engine.stop();
        btnStart.setText("시작");
        long sec = (SystemClock.elapsedRealtime() - startMs) / 1000;
        int avg = bpmCount == 0 ? 0 : (int) (bpmSum / bpmCount);
        Toast.makeText(this, "걷은 시간 " + sec + "초, 걸음 " + steps + ", 평균 " + avg + " BPM",
                Toast.LENGTH_LONG).show();
    }

    private void onStep() {
        if (!walking) return;
        long now = SystemClock.elapsedRealtime();
        if (!times.isEmpty() && now - times.peekLast() > 3000) times.clear(); // 멈췄다 다시 걸을 때
        times.addLast(now);
        if (times.size() > 8) times.removeFirst();
        steps++;
        tvSteps.setText("걸음 수 " + steps);
        if (times.size() >= 2) {
            long avg = (times.peekLast() - times.peekFirst()) / (times.size() - 1);
            int bpmNow = (int)(60000/avg);
            bpmSum += bpmNow; bpmCount++;
            tvBpm.setText(String.valueOf(60000 / bpmNow));
            engine.setBpm((int) (60000 / bpmNow));
            engine.start();
        }
    }
}