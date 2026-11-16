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
        // 걸음 시각이 2개 이상 모여야 "간격"을 잴 수 있으므로 그때부터 BPM 계산
        if (times.size() >= 2) {
            // 평균 걸음 간격(ms) = (가장 최근 걸음 시각 - 가장 오래된 걸음 시각) / 간격 개수
            long avg = (times.peekLast() - times.peekFirst()) / (times.size() - 1);
            // 간격이 0이면 0으로 나누기 오류가 나므로 건너뜀 (버튼을 아주 빠르게 연타한 경우)
            if (avg <= 0) return;
            // BPM = 1분(60000ms) 동안 걸을 수 있는 걸음 수 = 60000 / 평균 간격
            // 예) 간격 500ms → 60000 / 500 = 120 BPM
            int bpmNow = (int) (60000 / avg);
            // 평균 BPM을 나중에 구하기 위해 합계와 개수를 누적
            bpmSum += bpmNow; bpmCount++;
            // bpmNow는 이미 BPM 값이므로 그대로 화면에 표시하고 엔진에 전달
            // (이전에는 60000 / bpmNow로 한 번 더 나눠서 ms 간격으로 되돌아가는 버그가 있었음)
            tvBpm.setText(String.valueOf(bpmNow));
            engine.setBpm(bpmNow);
            engine.start();
        }
    }
}