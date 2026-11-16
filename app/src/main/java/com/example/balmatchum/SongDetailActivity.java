package com.example.balmatchum;

import android.database.Cursor;                 // DB 조회 결과를 한 줄씩 읽는 객체
import android.os.Bundle;                       // 화면이 다시 만들어질 때 저장해 둔 값을 담는 상자
import android.os.Handler;                      // "나중에(예: 0.1초 뒤) 이 일을 해 줘"라고 예약하는 도구
import android.os.Looper;                       // Handler가 어느 스레드에서 일할지 정할 때 사용 (메인 = 화면 스레드)
import android.widget.Button;
import android.widget.SeekBar;                  // 끌어서 위치를 바꾸는 진행 막대
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;      // "삭제할까요?" 확인 창
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;              // 날짜를 글자로 바꾸는 도구
import java.util.Arrays;                        // 배열 도우미 (배열 길이 자르기에 사용)
import java.util.Date;
import java.util.Locale;

/**
 * 곡 상세 화면.
 * 목록에서 곡을 짧게 누르면 열려요. (어떤 곡인지는 Intent에 담긴 _id로 알아냄)
 *
 * 화면 구성 (하단 탭 없음):
 *  - 상단 바: 뒤로가기(<) + 곡 제목
 *  - 한 줄 정보: 날짜·시간 · 테마 · 스케일 (곡에 저장된 값)
 *  - BPM 변화 그래프 (BpmGraphView)
 *  - 통계 타일 3개: 걸은 시간 / 걸음 수 / 평균 BPM
 *  - 진행 SeekBar + 왼쪽 현재 시간 / 오른쪽 전체 길이
 *  - [다시 듣기 / 일시정지] 버튼, [삭제] 버튼
 *
 * 다시 듣기 원리:
 *  DB의 seed(멜로디 시드)와 bpm_series(걸음마다의 BPM)를 MusicEngine.loadReplay()에 넣으면,
 *  엔진이 같은 순서의 멜로디를 같은 BPM 변화에 맞춰 다시 연주해요.
 */
public class SongDetailActivity extends AppCompatActivity {

    // Intent에 곡 번호를 담을 때 쓰는 이름표. 목록 화면과 상세 화면이 같은 이름을 써야 해서 상수로 공개
    public static final String EXTRA_SONG_ID = "song_id";

    // 재생 중 화면(SeekBar, 시간, 그래프)을 몇 ms마다 새로 그릴지. 0.1초면 눈으로 보기에 충분히 부드러움
    private static final long UI_UPDATE_MS = 100;

    private SongDbHelper dbHelper;
    private MusicEngine engine;          // 이 화면 전용 음악 엔진 (걷기 화면의 엔진과는 별개의 객체)

    private BpmGraphView graph;
    private SeekBar seek;
    private TextView tvTimeNow, tvTimeTotal;   // 막대 아래 왼쪽(현재 재생 시간), 오른쪽(전체 길이)
    private Button btnPlay;

    private long songId;                 // 지금 보고 있는 곡의 _id

    // 이 곡을 저장할 때 쓰던 소리 설정 (DB의 songs 테이블에서 읽음)
    // 다시 듣기는 "지금 설정"이 아니라 이 값으로 재생해야 저장 당시와 똑같은 소리가 나요.
    private int songScaleId, songThemeId;
    private boolean songBeatOn;
    private long totalMs;                // 다시 듣기 전체 길이 (bpm_series로 계산한 값)

    private boolean userSeeking = false;     // 사용자가 SeekBar를 손으로 끌고 있는 중인지
    private boolean wasPlayingBeforeSeek;    // 끌기 시작할 때 재생 중이었는지 (놓으면 다시 재생하려고)

    // 메인(화면) 스레드에서 일을 예약하는 Handler.
    // 화면 요소(SeekBar, TextView)는 메인 스레드에서만 바꿀 수 있어서, 음악 스레드가 아닌 여기서 갱신해요.
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * 0.1초마다 실행되는 "화면 갱신" 작업.
     * 엔진의 재생 위치를 읽어 SeekBar·시간·그래프를 움직이고, 아직 재생 중이면 0.1초 뒤에 자기 자신을 다시 예약해요.
     * (Runnable = "실행할 수 있는 일 하나"를 담는 객체. run() 안에 할 일을 적음)
     */
    private final Runnable progressUpdater = new Runnable() {
        @Override public void run() {
            updateProgressUi();
            if (engine.isFinished()) {          // 끝까지 재생되어 엔진이 스스로 멈췄으면
                setPlayingUi(false);            // 버튼을 [다시 듣기]로 되돌리고 예약을 그만둠
            } else if (engine.isPlaying()) {
                handler.postDelayed(this, UI_UPDATE_MS);   // this = 이 Runnable 자신을 0.1초 뒤에 또 실행
            }
        }
    };

    // ───────────────────────────── 생명주기 ─────────────────────────────

    /**
     * 화면이 처음 만들어질 때 호출돼요.
     * 화면 요소를 찾고, DB에서 곡을 읽어 채우고, 버튼과 SeekBar 동작을 연결해요.
     */
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_song_detail);

        dbHelper = new SongDbHelper(this);
        engine = new MusicEngine(this);

        graph = findViewById(R.id.graph);
        seek = findViewById(R.id.seek);
        tvTimeNow = findViewById(R.id.tvTimeNow);
        tvTimeTotal = findViewById(R.id.tvTimeTotal);
        btnPlay = findViewById(R.id.btnPlay);

        // 목록 화면이 Intent에 넣어 준 곡 번호 꺼내기. 값이 없으면 기본값 -1
        songId = getIntent().getLongExtra(EXTRA_SONG_ID, -1);

        // 곡을 못 찾으면(이미 지워졌거나 번호가 잘못됨) 안내하고 화면을 닫음
        if (!loadSong()) {
            Toast.makeText(this, "곡을 찾을 수 없어요", Toast.LENGTH_SHORT).show();
            finish();      // 이 화면 닫기 → 이전 화면(목록)으로 돌아감
            return;        // 아래 코드는 실행하지 않음
        }

        btnPlay.setOnClickListener(v -> onPlayClicked());
        findViewById(R.id.btnDelete).setOnClickListener(v -> confirmDelete());

        // ── SeekBar 동작 ──
        // OnSeekBarChangeListener는 메서드 3개를 모두 적어야 하는 인터페이스예요.
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            /** 막대 위치가 바뀔 때마다 호출. fromUser = 사용자가 손으로 바꿨는지 (코드에서 바꾼 건 false) */
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                // 끄는 동안 시간 글자와 그래프 막대가 손가락을 따라오게 함 (실제 이동은 손을 놓을 때)
                if (fromUser) showPosition(progress);
            }

            /** 손가락으로 막대를 잡았을 때 호출 */
            @Override public void onStartTrackingTouch(SeekBar s) {
                userSeeking = true;
                wasPlayingBeforeSeek = engine.isPlaying();
                // 끄는 동안 소리가 계속 나면 어색하므로 잠시 멈춤 (버튼 글자는 그대로 둠)
                if (wasPlayingBeforeSeek) {
                    engine.stop();
                    handler.removeCallbacks(progressUpdater); // 예약된 화면 갱신 취소 (막대가 손가락과 싸우지 않게)
                }
            }

            /** 손가락을 뗐을 때 호출 → 그 위치로 실제로 이동 */
            @Override public void onStopTrackingTouch(SeekBar s) {
                engine.seekTo(s.getProgress());   // SeekBar의 값 = 밀리초 (setMax(totalMs)로 맞춰 둠)
                userSeeking = false;
                if (wasPlayingBeforeSeek) {
                    engine.start();               // 재생 중이었으면 새 위치에서 이어서 재생
                    setPlayingUi(true);
                } else {
                    updateProgressUi();
                }
            }
        });

        updateProgressUi();   // 처음 화면: 0:00 / 전체 길이
    }

    /**
     * 화면이 보일 때마다 호출돼요.
     * 곡의 소리 설정(스케일·테마·비트)과 "지금의" 볼륨을 엔진에 적용해요.
     * 볼륨은 곡에 저장하지 않는 "듣는 환경" 설정이라 설정 화면의 현재 값을 써요.
     * (스케일은 곡의 값 그대로라 몇 번을 불러도 멜로디 위치가 바뀌지 않아요)
     */
    @Override protected void onResume() {
        super.onResume();
        applySongSound();
    }

    /**
     * 곡에 저장된 스케일·테마·비트 + 현재 볼륨을 엔진에 넣어요.
     */
    private void applySongSound() {
        int volume = dbHelper.getIntSetting(SongDbHelper.KEY_VOLUME, SongDbHelper.DEFAULT_VOLUME);
        engine.applySettings(songScaleId, songThemeId, volume, songBeatOn);
    }

    /**
     * 화면이 가려질 때(뒤로 가기, 홈 버튼, 다른 앱) 호출돼요.
     * 소리를 멈춰요. 위치는 엔진에 남아 있어서 돌아와서 [다시 듣기]를 누르면 이어서 재생돼요.
     */
    @Override protected void onPause() {
        super.onPause();
        engine.stop();
        handler.removeCallbacks(progressUpdater);
        setPlayingUi(false);
    }

    /**
     * 화면이 완전히 사라질 때 호출돼요.
     * 예약해 둔 일을 모두 취소하고, 엔진(SoundPool)과 DB 연결을 정리해요.
     */
    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(progressUpdater);
        engine.release();
        dbHelper.close();
    }

    // ───────────────────────────── 곡 불러오기 ─────────────────────────────

    /**
     * DB에서 songId 곡을 읽어 화면을 채우고, 엔진에 다시 듣기용 데이터를 넣어요.
     *
     * @return 곡을 찾았으면 true, 없으면 false
     */
    private boolean loadSong() {
        Cursor c = dbHelper.querySong(songId);
        // try-finally: 중간에 무슨 일이 생겨도 finally의 c.close()는 반드시 실행 → Cursor가 새지 않음
        try {
            // moveToFirst(): 결과의 첫 줄로 이동. 결과가 0줄이면 false (= 그런 곡이 없음)
            if (!c.moveToFirst()) return false;

            // getColumnIndexOrThrow("열이름")로 열 위치를 찾고, 그 위치의 값을 꺼냄
            String title = c.getString(c.getColumnIndexOrThrow("title"));
            long startedAt = c.getLong(c.getColumnIndexOrThrow("started_at"));
            int durationSec = c.getInt(c.getColumnIndexOrThrow("duration_sec"));
            int steps = c.getInt(c.getColumnIndexOrThrow("steps"));
            int avgBpm = c.getInt(c.getColumnIndexOrThrow("avg_bpm"));
            long seed = c.getLong(c.getColumnIndexOrThrow("seed"));
            String seriesText = c.getString(c.getColumnIndexOrThrow("bpm_series"));
            // DB 버전 2에서 추가된 열. 예전 곡은 DEFAULT 값(0, 0, 1)이 들어 있음
            songScaleId = c.getInt(c.getColumnIndexOrThrow("scale_id"));
            songThemeId = c.getInt(c.getColumnIndexOrThrow("theme_id"));
            songBeatOn = c.getInt(c.getColumnIndexOrThrow("beat_on")) == 1;   // 1 → true, 0 → false

            // ── 글자 채우기 ──
            // 상단 바: 뒤로가기(<) 화살표 + 곡 제목. 화살표를 누르면 finish() → 목록으로
            NavHelper.setupToolbar(this, title, true);

            // 한 줄 정보 (예: "10/23 (금) 13:05 · 테마 8bit · 스케일 펜타토닉") - 곡에 저장된 값 기준
            // 형식 기호: M=월, d=일, E=요일 한 글자(일, 월…), HH=시(0~23), mm=분(두 자리)
            String date = new SimpleDateFormat("M/d (E) HH:mm", Locale.KOREA).format(new Date(startedAt));
            ((TextView) findViewById(R.id.tvDate)).setText(date
                    + " · 테마 " + nameOf(MusicEngine.THEME_NAMES, songThemeId)
                    + " · 스케일 " + nameOf(MusicEngine.SCALE_NAMES, songScaleId));

            // 통계 타일 3개 (include_stat_tiles.xml 안의 TextView)
            ((TextView) findViewById(R.id.tvStatTime)).setText(formatTime(durationSec * 1000L));
            // %,d: 천 단위 쉼표 (1240 → "1,240")
            ((TextView) findViewById(R.id.tvStatSteps)).setText(String.format(Locale.KOREA, "%,d", steps));
            ((TextView) findViewById(R.id.tvStatAvg)).setText(String.valueOf(avgBpm));

            // ── 그래프와 엔진 준비 ──
            int[] series = parseSeries(seriesText);
            graph.setData(series);
            // 순서 중요: 스케일을 먼저 정해야 loadReplay가 그 스케일에 맞는 시작 음 위치로 되감아요.
            // (반대로 하면 이전 스케일 기준으로 시작 위치가 정해져서 멜로디가 달라질 수 있음)
            applySongSound();
            engine.loadReplay(seed, series);
            totalMs = engine.getTotalMs();
            // SeekBar의 최댓값을 전체 길이(ms)로 → SeekBar 값 1 = 1ms라서 위치 계산이 간단해짐
            // (int)로 바꾸는 이유: setMax는 int만 받음. int 최대 약 24일이라 걷기 곡 길이로는 충분
            seek.setMax((int) totalMs);

            // BPM 기록이 없는 곡(예전 버전에서 저장된 곡 등)은 재생할 수 없으므로 버튼을 끔
            if (series.length == 0) {
                btnPlay.setEnabled(false);
                seek.setEnabled(false);
            }
            return true;
        } finally {
            c.close();
        }
    }

    /**
     * 이름 목록에서 번호에 맞는 이름을 꺼내요. 번호가 범위 밖이면(잘못 저장된 경우) "알 수 없음"
     * 예) nameOf({"8bit", "Lo-fi", "피아노"}, 1) → "Lo-fi"
     */
    private static String nameOf(String[] names, int id) {
        return id >= 0 && id < names.length ? names[id] : "알 수 없음";
    }

    /**
     * "112,113,110" 같은 글자를 {112, 113, 110} 숫자 배열로 바꿔요.
     * 잘못된 글자나 0 이하의 값은 건너뛰어요. (0으로 나누기 오류 방지)
     */
    private static int[] parseSeries(String text) {
        if (text == null || text.isEmpty()) return new int[0];
        String[] parts = text.split(",");          // 쉼표를 기준으로 잘라 글자 배열로
        int[] result = new int[parts.length];
        int count = 0;                             // 실제로 넣은 개수
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p.trim()); // 글자 → 숫자 (trim: 앞뒤 공백 제거)
                if (v > 0) result[count++] = v;     // result[count]에 넣고 count를 1 증가
            } catch (NumberFormatException e) {
                // 숫자가 아닌 글자는 무시
            }
        }
        // 건너뛴 값이 있으면 배열 뒤쪽이 비므로, 실제 개수만큼만 잘라서 돌려줌
        return Arrays.copyOf(result, count);
    }

    // ───────────────────────────── 재생 ─────────────────────────────

    /**
     * [다시 듣기 / 일시정지] 버튼을 눌렀을 때 호출돼요.
     *  - 재생 중이면 → 일시정지 (위치 유지)
     *  - 멈춰 있으면 → 재생. 단, 이미 끝까지 들었으면 처음부터
     */
    private void onPlayClicked() {
        if (engine.isPlaying()) {
            engine.stop();
            setPlayingUi(false);
            return;
        }
        // 소리 파일을 아직 불러오는 중이면 재생해도 소리가 안 나므로 잠깐 기다리게 안내
        if (!engine.isReady()) {
            Toast.makeText(this, "소리를 준비하고 있어요. 잠시 후 다시 눌러 주세요", Toast.LENGTH_SHORT).show();
            return;
        }
        if (engine.isFinished() || engine.getPositionMs() >= totalMs) {
            engine.seekTo(0);     // 처음으로 되감기 (시드도 처음 상태로 → 같은 멜로디가 처음부터)
        }
        engine.start();
        setPlayingUi(true);
    }

    /**
     * 재생 중/멈춤 상태에 맞게 버튼 글자를 바꾸고, 재생 중이면 화면 갱신을 시작해요.
     */
    private void setPlayingUi(boolean playing) {
        btnPlay.setText(playing ? "일시정지" : "다시 듣기");
        handler.removeCallbacks(progressUpdater);   // 예약이 두 겹으로 쌓이지 않게 먼저 지움
        if (playing) handler.post(progressUpdater); // 바로 한 번 실행 → 그 뒤로 0.1초마다 자기 자신을 예약
    }

    /**
     * 엔진의 현재 위치로 SeekBar·시간·그래프를 맞춰요.
     * 사용자가 SeekBar를 끌고 있는 중에는 막대를 건드리지 않아요. (손가락과 반대로 움직이면 이상하니까)
     */
    private void updateProgressUi() {
        // 마지막 칸이 곡 끝을 살짝 넘을 수 있어서 전체 길이를 넘지 않게 자름
        long pos = Math.min(engine.getPositionMs(), totalMs);
        if (!userSeeking) seek.setProgress((int) pos);
        showPosition(pos);
    }

    /**
     * 막대 아래 시간 글자(왼쪽 "00:05", 오른쪽 "00:11")와 그래프의 재생 위치 막대를 pos(ms) 위치로 표시해요.
     */
    private void showPosition(long pos) {
        tvTimeNow.setText(formatTime(pos));          // 왼쪽: 현재 재생 시간
        tvTimeTotal.setText(formatTime(totalMs));    // 오른쪽: 전체 길이
        // 0으로 나누기 방지: 전체 길이가 0이면 위치도 0
        graph.setProgress(totalMs == 0 ? 0f : (float) pos / totalMs);
    }

    /**
     * 밀리초를 "mm:ss" 글자로 바꿔요. 예) 65000 → "01:05", 750000 → "12:30"
     * %02d = 두 자리 정수(한 자리면 앞에 0을 붙임). 와이어프레임의 "04:22", "12:30" 형식에 맞춤
     */
    private static String formatTime(long ms) {
        long sec = ms / 1000;
        return String.format(Locale.KOREA, "%02d:%02d", sec / 60, sec % 60);
    }

    // ───────────────────────────── 삭제 ─────────────────────────────

    /**
     * [삭제] 버튼: 확인 창을 띄우고, [삭제]를 누르면 곡을 지운 뒤 목록으로 돌아가요.
     * 목록 화면은 onResume()에서 목록을 다시 불러오므로 지운 곡이 바로 사라져요.
     */
    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("곡 삭제")
                .setMessage("이 걸음 곡을 삭제할까요? 삭제하면 되돌릴 수 없어요.")
                .setPositiveButton("삭제", (dialog, which) -> {
                    engine.stop();                 // 재생 중이었으면 소리부터 멈춤
                    dbHelper.deleteSong(songId);
                    Toast.makeText(this, "삭제했어요", Toast.LENGTH_SHORT).show();
                    finish();                      // 이 화면을 닫고 목록으로
                })
                .setNegativeButton("취소", null)
                .show();
    }
}
