package com.example.balmatchum;

// ───────────────────────────── import 설명 ─────────────────────────────
// import는 "다른 곳에 정의된 클래스를 이 파일에서 짧은 이름으로 쓰겠다"는 선언이에요.
import android.Manifest;                       // 권한 이름 상수 모음 (예: ACTIVITY_RECOGNITION)
import android.content.pm.PackageManager;      // 권한이 허용됐는지 비교할 때 쓰는 상수
import android.hardware.Sensor;                // 센서 하나를 나타내는 클래스
import android.hardware.SensorEvent;           // 센서에서 값이 들어왔을 때 전달되는 정보
import android.hardware.SensorEventListener;   // "센서 값이 오면 알려줘"를 받기 위한 인터페이스
import android.hardware.SensorManager;         // 폰의 센서들을 관리하는 시스템 서비스
import android.os.Build;                       // 현재 폰의 안드로이드 버전 확인용
import android.os.Bundle;                      // 화면이 다시 만들어질 때 상태를 담아 오는 상자
import android.os.SystemClock;                 // 부팅 후 흐른 시간(ms)을 재는 시계
import android.widget.TextView;                // 글자를 보여주는 화면 요소
import android.widget.Button;                  // 버튼 화면 요소
import android.widget.Toast;                   // 화면 아래에 잠깐 뜨는 짧은 메시지

// 아래 4개는 프로젝트를 처음 만들 때 자동으로 들어간 import로, 지금 코드에서는 쓰이지 않아요.
// (지워도 동작은 같지만, "코드 동작을 바꾸지 않기"를 위해 그대로 둠)
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity; // ← 이건 사용 중: 화면(Activity)의 기본 부모 클래스
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayDeque;                   // 앞/뒤 양쪽에서 넣고 뺄 수 있는 목록(큐)

/**
 * 걷기(홈) 화면.
 *
 * 하는 일:
 *  1. 걸음 감지 센서에서 걸음 신호를 받는다. (에뮬레이터에서는 "걸음 시뮬레이션" 버튼으로 대신)
 *  2. 최근 걸음 간격으로 BPM(1분당 걸음 수)을 계산해 화면에 보여준다.
 *  3. 계산한 BPM을 MusicEngine에 넘겨서 걸음 박자에 맞는 음악을 재생한다.
 *  4. 시작/정지 버튼으로 걷기를 시작하고, 끝나면 결과(시간, 걸음 수, 평균 BPM)를 보여준다.
 *
 * extends AppCompatActivity  → "나는 안드로이드 화면(Activity)이다"
 * implements SensorEventListener → "센서 값이 오면 나에게 알려줘" (onSensorChanged가 호출됨)
 */
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    // ───────────────────────────── 멤버 변수 ─────────────────────────────
    // 여러 메서드에서 함께 쓰는 값들이라 클래스 안(메서드 밖)에 선언해요.

    private SensorManager sm;          // 센서 관리자 (센서를 찾고, 등록/해제할 때 사용)
    private Sensor stepSensor;         // 걸음 감지 센서. 센서가 없는 기기(에뮬레이터 등)에서는 null
    private TextView tvBpm, tvSteps;   // 화면의 BPM 글자, 걸음 수 글자

    // 최근 걸음들이 "언제" 일어났는지 시각(ms)을 담는 목록. 최대 8개까지만 보관해요.
    // final: 이 변수가 가리키는 목록 자체는 바뀌지 않는다는 뜻 (목록 안의 내용은 바뀔 수 있음)
    private final ArrayDeque<Long> times = new ArrayDeque<>();

    private MusicEngine engine;        // 박자에 맞춰 소리를 내는 음악 엔진 (MusicEngine.java)
    private int steps = 0;             // 이번 걷기에서 걸은 총 걸음 수
    private Button btnStart;           // 시작/정지 버튼 (누를 때마다 글자가 "시작" ↔ "정지"로 바뀜)
    private boolean walking = false;   // 지금 걷기 중인지 여부. false면 걸음 신호를 무시해요.

    // startMs: 걷기를 시작한 시각(ms) → 끝날 때 "걸은 시간" 계산에 사용
    // bpmSum : 계산된 BPM들을 전부 더한 값 → 평균 BPM 계산에 사용
    private long startMs, bpmSum;
    private int bpmCount;              // BPM을 몇 번 계산했는지 (평균 = bpmSum / bpmCount)

    // ───────────────────────────── 생명주기 메서드 ─────────────────────────────
    // 안드로이드는 화면 상태가 바뀔 때마다 정해진 메서드를 자동으로 불러줘요.
    //   onCreate  : 화면이 처음 만들어질 때 1번
    //   onResume  : 화면이 사용자에게 보이기 시작할 때 (앱으로 돌아올 때마다)
    //   onPause   : 화면이 가려지거나 다른 앱으로 나갈 때
    //   onDestroy : 화면이 완전히 없어질 때

    /**
     * 화면이 처음 만들어질 때 호출돼요.
     * 레이아웃을 붙이고, 버튼 동작을 연결하고, 음악 엔진과 센서를 준비하고, 권한을 요청해요.
     */
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);                             // 부모 클래스의 기본 준비 작업 (꼭 먼저 호출해야 함)
        setContentView(R.layout.activity_main);        // res/layout/activity_main.xml 을 이 화면의 모양으로 사용

        // findViewById: XML에서 android:id 로 이름 붙인 화면 요소를 자바 코드로 가져오기
        tvBpm = findViewById(R.id.tvBpm);
        tvSteps = findViewById(R.id.tvSteps);

        // "걸음 시뮬레이션" 버튼을 누르면 실제 걸음이 감지된 것처럼 onStep()을 호출
        // v -> onStep() 는 람다(짧게 쓴 함수)로, "클릭되면 onStep()을 실행해라"라는 뜻
        findViewById(R.id.btnSim).setOnClickListener(v -> onStep());

        btnStart = findViewById(R.id.btnStart);
        // 버튼 하나로 시작/정지를 번갈아 처리: 걷는 중이면 정지, 아니면 시작
        btnStart.setOnClickListener(v -> { if (walking) stopWalk(); else startWalk(); });

        // 음악 엔진 생성. this(= 이 화면)를 넘기는 이유는 res/raw 소리 파일을 읽으려면 Context가 필요해서
        engine = new MusicEngine(this);

        // 시스템에서 센서 관리자를 받아오고, 그중 "걸음 감지 센서"를 찾음
        // TYPE_STEP_DETECTOR: 한 걸음 디딜 때마다 신호를 1번 보내주는 센서
        // 기기에 이 센서가 없으면 null이 돌아와요.
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);

        // 안드로이드 10(API 29)부터는 걸음 센서를 쓰려면 "신체 활동" 권한을 사용자에게 직접 받아야 해요.
        // 아직 허용되지 않았다면 권한 요청 팝업을 띄움. 마지막 숫자 1은 요청을 구분하는 번호(requestCode)
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(
                Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 1);
        }
    }

    /**
     * 화면이 보이기 시작할 때 호출돼요.
     * 이때 센서 리스너를 등록해야 걸음 신호를 받기 시작해요.
     */
    @Override protected void onResume() {
        super.onResume();
        // 센서가 있는 기기에서만 등록. this = 이 화면이 신호를 받겠다 (onSensorChanged가 불림)
        // SENSOR_DELAY_FASTEST: 가능한 한 빨리 신호를 전달받기 (박자 계산이 늦지 않도록)
        if (stepSensor != null) sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_FASTEST);
    }

    /**
     * 화면이 가려질 때(홈 버튼, 다른 앱 전환 등) 호출돼요.
     * 센서를 계속 켜 두면 배터리를 쓰므로 해제하고, 음악도 멈춰요.
     */
    @Override protected void onPause() {
        super.onPause();
        sm.unregisterListener(this);   // 센서 신호 그만 받기
        engine.stop();                 // 음악 재생 스레드 멈추기
    }

    /**
     * 화면이 완전히 사라질 때 호출돼요.
     * SoundPool이 잡고 있던 메모리(소리 데이터)를 돌려줘요.
     */
    @Override protected void onDestroy() {
        super.onDestroy();
        engine.release();
    }

    // ───────────────────────────── 센서 콜백 ─────────────────────────────
    // SensorEventListener 인터페이스를 구현(implements)했기 때문에 반드시 만들어야 하는 2개의 메서드

    /** 걸음 센서가 "한 걸음!" 신호를 보낼 때마다 호출돼요. 버튼을 눌렀을 때와 똑같이 onStep()으로 처리. */
    @Override public void onSensorChanged(SensorEvent e) { onStep(); }

    /** 센서 정확도가 바뀔 때 호출되는데, 이 앱에서는 쓸 일이 없어서 비워 둠. */
    @Override public void onAccuracyChanged(Sensor s, int a) {}

    // ───────────────────────────── 걷기 시작 / 정지 ─────────────────────────────

    /**
     * [시작] 버튼을 눌렀을 때 호출돼요.
     * 이전 걷기 기록을 모두 0으로 초기화하고 걷기 상태로 바꿔요.
     * (음악은 여기서 바로 켜지 않고, 걸음이 2번 이상 들어와 BPM이 계산된 뒤 onStep()에서 켜져요.)
     */
    private void startWalk() {
        walking = true;                              // 이제부터 걸음 신호를 받아들임
        steps = 0; bpmSum = 0; bpmCount = 0;         // 이전 기록 초기화
        times.clear();                               // 이전 걸음 시각 목록 비우기
        startMs = SystemClock.elapsedRealtime();     // 시작 시각 기억 (걸은 시간 계산용)
        tvSteps.setText("걸음 수 0");
        tvBpm.setText("0");
        btnStart.setText("정지");                     // 같은 버튼을 이제 "정지" 버튼으로 사용
    }

    /**
     * [정지] 버튼을 눌렀을 때 호출돼요.
     * 음악을 멈추고, 이번 걷기의 결과(걸은 시간, 걸음 수, 평균 BPM)를 Toast로 보여줘요.
     */
    private void stopWalk() {
        walking = false;                             // 이제부터 걸음 신호 무시
        engine.stop();
        btnStart.setText("시작");

        // 걸은 시간(초) = (지금 시각 - 시작 시각) / 1000   (ms → 초)
        long sec = (SystemClock.elapsedRealtime() - startMs) / 1000;

        // 평균 BPM = BPM 합계 / 계산 횟수
        // 걸음이 너무 적어 BPM을 한 번도 계산 못 했으면(bpmCount == 0) 0으로 나누면 안 되니 0으로 표시
        // (조건 ? A : B) 는 "조건이 참이면 A, 거짓이면 B"라는 뜻 (삼항 연산자)
        int avg = bpmCount == 0 ? 0 : (int) (bpmSum / bpmCount);

        // LENGTH_LONG: 메시지를 조금 길게(약 3.5초) 보여줌
        Toast.makeText(this, "걷은 시간 " + sec + "초, 걸음 " + steps + ", 평균 " + avg + " BPM",
                Toast.LENGTH_LONG).show();
    }

    // ───────────────────────────── 걸음 처리 (핵심) ─────────────────────────────

    /**
     * 한 걸음이 감지될 때마다 호출돼요. (실제 센서 또는 시뮬레이션 버튼)
     *
     * 1. 이번 걸음의 시각을 목록(times)에 추가하고, 최근 8개만 남겨요.
     * 2. 목록의 처음과 끝 시각으로 "평균 걸음 간격"을 구해요.
     * 3. 간격을 BPM으로 바꿔 화면에 보여주고, 음악 엔진의 박자를 맞춰요.
     *
     * 최근 8걸음만 쓰는 이유: 너무 적으면 BPM이 걸음마다 들쭉날쭉하고,
     * 너무 많으면 속도를 바꿨을 때 BPM이 늦게 따라와요. 8개는 그 중간 정도.
     */
    private void onStep() {
        if (!walking) return;                        // 걷기 중이 아니면 아무것도 안 함

        long now = SystemClock.elapsedRealtime();    // 이번 걸음의 시각(ms)

        // 마지막 걸음 후 3초(3000ms)가 넘게 지났다면 "멈췄다가 다시 걷기 시작한 것"으로 보고 목록을 비움.
        // 안 비우면 멈춰 있던 긴 시간까지 평균에 들어가서 BPM이 갑자기 아주 낮게 나와요.
        // peekLast(): 목록의 마지막(가장 최근) 값을 꺼내지 않고 보기만 함
        if (!times.isEmpty() && now - times.peekLast() > 3000) times.clear(); // 멈췄다 다시 걸을 때

        times.addLast(now);                          // 이번 걸음 시각을 목록 끝에 추가
        if (times.size() > 8) times.removeFirst();   // 8개를 넘으면 가장 오래된 것(맨 앞)을 버림

        steps++;                                     // 총 걸음 수 1 증가
        tvSteps.setText("걸음 수 " + steps);

        // 걸음 시각이 2개 이상 모여야 "간격"을 잴 수 있으므로 그때부터 BPM 계산
        if (times.size() >= 2) {
            // 평균 걸음 간격(ms) = (가장 최근 걸음 시각 - 가장 오래된 걸음 시각) / 간격 개수
            // 예) 시각이 5개면 간격은 4개라서 size() - 1 로 나눔
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
            engine.setBpm(bpmNow);                   // 음악 박자를 지금 걸음 속도에 맞춤
            engine.start();                          // 음악 시작 (이미 재생 중이면 MusicEngine이 알아서 무시)
        }
    }
}
