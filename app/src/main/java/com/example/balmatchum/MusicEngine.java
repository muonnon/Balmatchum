package com.example.balmatchum;

import android.content.Context;     // 앱의 리소스(res/raw 소리 파일 등)에 접근할 때 필요한 정보
import android.media.SoundPool;     // 짧은 소리 여러 개를 미리 불러 두고 빠르게 재생하는 도구
import android.util.Log;            // Logcat(개발용 기록 창)에 글을 남기는 도구

/**
 * 걸음 BPM에 맞춰 음악을 실시간으로 재생하는 엔진.
 *
 * 역할 나누기 (WAV 내보내기를 만들면서 나눔):
 *  - NoteSequencer : "이번 칸에 어떤 소리를 어떤 높이·볼륨으로 낼지" 계산 (가상 시계, 옛/새 멜로디 알고리즘, 해시)
 *  - MusicEngine   : 칸 길이만큼 실제 시간을 기다리면서 NoteSequencer를 한 칸씩 진행하고,
 *                    나온 음을 SoundPoolSink로 보내 스피커로 재생 (이 클래스)
 *  - SongRenderer  : 같은 NoteSequencer를 기다리지 않고 끝까지 진행해서 WAV 파일로 만듦 (내보내기)
 *  계산 코드가 한 곳뿐이라, 재생과 내보낸 파일은 항상 같은 곡이에요.
 *
 * 연주 방식 요약 (자세한 건 NoteSequencer, SongComposer):
 *  - 한 박을 4칸으로 나눈 16분음표를 한 칸으로 삼아 칸을 하나씩 진행해요.
 *  - 버전 0 (옛 곡): 매 박 킥, 엇박 하이햇, 8분음표마다 음계에서 한 칸 위/아래/제자리로 무작위 이동
 *  - 버전 1 (새 곡): SongComposer가 프레이즈 반복·코드 진행·베이스·리듬 패턴을 만듦
 *  - 음높이는 샘플을 빠르게/느리게 재생해서 바꿔요. (빠르게 재생하면 음이 높아짐)
 *
 * 두 가지 모드가 있어요.
 *  1) 실시간 모드 (걷기 화면): 걸음이 들어올 때마다 화면이 setBpm()으로 박자를 바꿔 줘요.
 *  2) 다시 듣기 모드 (곡 상세 화면): loadReplay()로 저장된 seed와 bpm_series를 넣으면,
 *     "지금 위치에 해당하는 BPM"을 골라 가며 원래 곡을 그대로 다시 연주해요.
 *
 * 왜 별도 스레드(Thread)를 쓰나요?
 *  - 박자를 맞추려면 "기다렸다가 소리 내기"를 계속 반복해야 해요.
 *  - 이걸 화면(UI) 스레드에서 하면 기다리는 동안 버튼도 안 눌리고 앱이 멈춘 것처럼 돼요.
 */
public class MusicEngine {

    // 스케일(음계) 3종. 바깥 배열의 칸 번호가 곧 scale_id (DB와 설정에 저장되는 번호)예요.
    // 숫자 = 기본음(도)에서 몇 반음 위인지. 반음 = 피아노 건반 한 칸, 12반음 = 한 옥타브
    //   0 = 펜타토닉        : 도 레 미 솔 라 도     → 밝고 어디서 멈춰도 자연스러움
    //   1 = 마이너 펜타토닉 : 도 미♭ 파 솔 시♭ 도   → 조금 어둡고 블루스 느낌
    //   2 = 장음계          : 도 레 미 파 솔 라 시 도 → 학교에서 배우는 익숙한 음계
    // 모든 스케일의 가장 높은 음은 12반음 이하여야 해요. (재생 속도는 최대 2.0배 = 12반음)
    // 주의: 배열 순서를 바꾸면 이미 저장된 곡의 scale_id가 다른 음계를 가리키게 되므로, 새 음계는 뒤에만 추가해요.
    // private이 아닌 이유: 같은 패키지의 NoteSequencer와 단위 테스트가 읽음
    static final int[][] SCALES = {
            {0, 2, 4, 7, 9, 12},         // 펜타토닉 (기존에 쓰던 음계 그대로)
            {0, 3, 5, 7, 10, 12},        // 마이너 펜타토닉
            {0, 2, 4, 5, 7, 9, 11, 12},  // 장음계
    };
    /** 화면(설정, 곡 상세)에 보여줄 스케일 이름. SCALES와 같은 순서 */
    public static final String[] SCALE_NAMES = {"펜타토닉", "마이너 펜타토닉", "장음계"};

    /** 화면에 보여줄 사운드 테마 이름. 칸 번호가 곧 theme_id */
    public static final String[] THEME_NAMES = {"8bit", "Lo-fi", "피아노"};

    // 소리 파일 목록과 테마별 소리 표는 SoundPalette.java에 한곳으로 모았어요.
    // 불러올 소리 개수 = 표의 길이 (기존 9개 + 새 소리 12개 = 21개)
    private static final int SOUND_COUNT = SoundPalette.ALL.length;

    // ─────────── 동시 재생 방지 ───────────
    // static: 앱 전체에 딱 하나. 걷기 화면과 곡 상세 화면이 각자 엔진을 만들어도 이 변수는 함께 봐요.
    // "지금 소리를 내고 있는 엔진"을 기억해 두고, 다른 엔진이 start()하면 이 엔진을 먼저 멈춰요.
    private static MusicEngine current;

    private final SoundPool pool;              // 소리들을 담아 두고 재생하는 SoundPool
    // SoundPool에 불러온 소리 ID. 칸 번호 = SoundPalette.ALL의 칸 번호
    private final int[] soundIds = new int[SOUND_COUNT];
    private final SoundPoolSink sink;          // 음 신호 → SoundPool 재생
    private final NoteSequencer seq = new NoteSequencer();   // 곡 계산 담당

    // volatile: 여러 스레드가 함께 읽고 쓰는 변수에 붙여요. (한쪽이 바꾸면 다른 쪽이 바로 보게)
    private volatile boolean running = false;    // 음악 스레드가 돌고 있는지
    private volatile int loadedCount = 0;        // 불러오기가 끝난 소리 개수
    private volatile boolean finished = false;   // 다시 듣기가 끝까지 재생되었는지

    private Thread thread;                       // 박자를 진행하는 음악 스레드

    /**
     * 엔진을 만들 때 SoundPalette의 소리 파일 전부(21개)를 SoundPool에 미리 불러와요.
     * 미리 불러 두면 재생할 때 지연이 거의 없어요.
     *
     * @param c 소리 파일(res/raw)을 읽기 위한 Context (보통 Activity를 넘김)
     */
    public MusicEngine(Context c) {
        // setMaxStreams(10): 동시에 최대 10개 소리까지 겹쳐서 재생 가능
        // 새 알고리즘은 한 칸에서 최대 6개 소리가 함께 나고, 길게 울리는 소리의 꼬리도 남아 있어서 10개로 넉넉히.
        // (꽉 차면 SoundPool이 가장 오래된 소리를 끊고 새 소리를 냄)
        pool = new SoundPool.Builder().setMaxStreams(10).build();

        // load(Context, 리소스, 우선순위): 소리 파일을 불러오고, 그 소리의 ID를 돌려줌 (우선순위는 관례상 1)
        // 곡마다 다른 소리를 골라도 바로 낼 수 있게 전부 불러 둬요. (모두 1초 안팎이라 메모리 부담이 작음)
        for (int i = 0; i < SOUND_COUNT; i++) {
            soundIds[i] = pool.load(c, SoundPalette.ALL[i].resId, 1);
        }
        sink = new SoundPoolSink(pool, soundIds);

        // 소리 불러오기가 끝날 때마다 호출돼요. (load()는 뒤에서 천천히 불러옴)
        // status가 0이면 성공 → 성공한 개수가 SOUND_COUNT(21)개가 되면 isReady()가 true
        pool.setOnLoadCompleteListener((p, id, status) -> {
            if (status == 0) loadedCount++;
            Log.d("BAL", "load id=" + id + " status=" + status);
        });
    }

    /**
     * 소리를 모두 불러왔는지 알려줘요.
     * 불러오기 전에 재생하면 첫 몇 음이 소리 없이 지나가므로, 재생 버튼에서 확인하는 데 써요.
     */
    public boolean isReady() { return loadedCount >= SOUND_COUNT; }

    /**
     * 소리 설정을 바꿔요. 재생 중에 불러도 다음 칸부터 바로 반영돼요.
     *
     * @param scaleId       음계 번호 (0 펜타토닉, 1 마이너 펜타토닉, 2 장음계)
     * @param themeId       사운드 테마 번호 (0 8bit, 1 Lo-fi, 2 피아노)
     * @param volumePercent 볼륨 0 ~ 100 (%) → 재생할 때만 곱함 (곡 계산과 내보내기에는 영향 없음)
     * @param beatOn        true면 드럼도 재생, false면 드럼 없이
     */
    public void applySettings(int scaleId, int themeId, int volumePercent, boolean beatOn) {
        seq.setSound(scaleId, themeId, beatOn);
        sink.setMasterVolume(Math.max(0, Math.min(100, volumePercent)) / 100f);   // 70 → 0.7
    }

    /** 박자(BPM)를 바꿔요. 50~200 사이로 제한. 재생 중에 바꿔도 다음 칸부터 반영 */
    public void setBpm(int b) { seq.setBpm(b); }

    /**
     * 실시간 모드(걷기)용: 멜로디 시드와 알고리즘 버전을 정하고 처음으로 되감아요. (음악이 멈춰 있을 때 호출)
     * 주의: applySettings()를 먼저 불러 둬야 해요. (버전 1은 여기서 지금 테마·음계로 곡의 개성을 정함)
     *
     * @param seed          이번 곡의 시드 (DB의 seed)
     * @param melodyVersion 멜로디 알고리즘 버전 (새 곡은 SongComposer.VERSION)
     */
    public void setSeed(long seed, int melodyVersion) {
        seq.setSeed(seed, melodyVersion);
        logPersonality();
    }

    /** 곡의 시작 위치마다 NoteSequencer가 정한 v1 곡 개성을 로그로 남겨요 (버전 0이면 아무것도 안 함) */
    private void logPersonality() {
        String d = seq.describe();
        if (d != null) Log.d("BAL_MELODY", "v1 곡 개성: " + d);
    }

    // ───────────────────────────── 다시 듣기 모드 ─────────────────────────────

    /**
     * bpm_series의 각 값이 곡의 몇 ns 지점에서 시작하는지. (곡 상세 화면의 그래프·시간 표시용)
     * 계산은 NoteSequencer에 있어요. 재생·그래프·내보내기가 같은 계산을 써야 위치가 정확히 맞아요.
     */
    public static long[] seriesStartNs(int[] series) { return NoteSequencer.seriesStartNs(series); }

    /**
     * 다시 듣기 모드로 곡을 불러와요. 재생은 하지 않고 처음 위치에 준비만 해요. (재생은 start())
     * 주의: applySettings()로 곡의 테마·음계를 먼저 넣어 둔 뒤 불러야 해요.
     *
     * @param seed          DB에 저장된 멜로디 시드
     * @param series        DB에 저장된 걸음마다의 BPM 기록
     * @param melodyVersion DB에 저장된 멜로디 알고리즘 버전 (옛 곡 0, 새 곡 1)
     */
    public void loadReplay(long seed, int[] series, int melodyVersion) {
        stop();                       // 혹시 재생 중이면 멈추고
        waitForThreadEnd();           // 음악 스레드가 완전히 끝난 뒤에 상태를 바꿔요 (두 스레드가 동시에 바꾸면 꼬임)
        seq.loadSong(seed, series, melodyVersion);
        finished = false;
        logPersonality();
    }

    /** 다시 듣기 곡의 전체 길이(밀리초). 실시간 모드면 0 */
    public long getTotalMs() { return seq.getTotalNs() / 1_000_000; }

    /** 지금 재생 위치(밀리초). 화면이 SeekBar를 움직이는 데 써요. */
    public long getPositionMs() { return seq.getPositionNs() / 1_000_000; }

    /** 음악 스레드가 돌고 있는지 (= 소리가 나고 있는지) */
    public boolean isPlaying() { return running; }

    /** 다시 듣기가 끝까지 재생되어 스스로 멈췄는지 */
    public boolean isFinished() { return finished; }

    /**
     * 재생 위치를 ms 지점으로 옮겨요. (SeekBar를 끌어서 놓았을 때)
     * 멜로디는 이전 음에 이어서 정해지므로, 처음으로 되감은 뒤 소리 없이(sink = null) 목표 지점까지 빠르게 진행해요.
     * 이렇게 하면 중간으로 건너뛰어도 처음부터 들은 것과 똑같은 멜로디가 이어져요.
     */
    public void seekTo(long ms) {
        stop();
        waitForThreadEnd();
        seq.reset();
        finished = false;
        long target = ms * 1_000_000;                 // ms → ns
        long total = seq.getTotalNs();
        while (seq.getPositionNs() < target && seq.getPositionNs() < total) seq.tick(null);
    }

    // ───────────────────────────── 재생 / 정지 ─────────────────────────────

    /**
     * 음악 재생을 시작해요. (다시 듣기 모드에서는 멈춘 위치부터 이어서)
     * 이미 재생 중이면 아무것도 하지 않아요. (걸음마다 호출돼도 스레드가 여러 개 생기지 않도록)
     */
    public void start() {
        if (running) return;

        // 다른 엔진이 소리를 내고 있으면 먼저 멈춰요. → 두 음악이 겹치지 않음
        // synchronized: 두 스레드가 동시에 current를 바꾸지 못하게 하는 잠금
        synchronized (MusicEngine.class) {
            if (current != null && current != this) current.stop();
            current = this;
        }

        waitForThreadEnd();          // 방금 stop()한 예전 스레드가 마무리 중이면 끝날 때까지 기다림
        seq.restartBarIfLive();      // 실시간 모드는 시작할 때마다 마디의 첫 칸(킥)부터
        finished = false;
        running = true;

        thread = new Thread(() -> {
            // 다음 칸을 연주할 "목표 시각"(ns). 목표 시각을 일정하게 더해 가야 오차가 쌓여 박자가 밀리지 않아요.
            long next = System.nanoTime();

            while (running) {
                // 다시 듣기 모드에서 곡 끝에 닿았으면 스스로 멈춤
                if (seq.isAtEnd()) {
                    finished = true;
                    running = false;
                    // 곡 끝 요약 (멜로디 해시). 내보내기(SongRenderer)도 같은 줄을 찍으므로 비교할 수 있어요.
                    Log.d("BAL_MELODY", seq.summary());
                    break;
                }

                Log.d("BAL", "step " + seq.getStep());   // 디버깅용: 지금 칸 번호
                // 한 칸 연주 (음은 sink → SoundPool로 바로 재생됨) + 그 칸의 길이만큼 다음 목표 시각을 미룸
                next += seq.tick(sink);
                String melody = seq.getMelodyLog();
                if (melody != null) Log.d("BAL_MELODY", melody);   // 음마다 기록

                // 목표 시각까지 남은 시간만큼 기다림 (이미 늦었으면 바로 다음 칸)
                long wait = next - System.nanoTime();
                if (wait > 0) {
                    try { Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000)); }
                    // 기다리는 중에 stop()이 interrupt()를 부르면 이 예외가 발생 → 반복을 바로 끝냄
                    catch (InterruptedException e) { break; }
                }
            }
        });
        thread.start();
    }

    /**
     * 음악을 멈춰요. (다시 듣기 모드에서는 위치가 남아서 start()로 이어 들을 수 있어요)
     * interrupt()로 sleep 중인 스레드를 깨워서 다음 칸을 기다리지 않고 바로 멈추게 해요.
     */
    public void stop() { running = false; if (thread != null) thread.interrupt(); }

    /**
     * 음악 스레드가 완전히 끝날 때까지 기다려요.
     * stop()은 신호만 보내고 바로 돌아오므로, 스레드가 아직 마지막 칸을 처리 중일 수 있어요.
     * join(): 그 스레드가 끝날 때까지 기다리는 메서드
     */
    private void waitForThreadEnd() {
        if (thread == null || thread == Thread.currentThread()) return;
        try { thread.join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * 엔진을 완전히 정리해요. 화면이 사라질 때(onDestroy) 한 번 호출.
     */
    public void release() {
        stop();
        waitForThreadEnd();   // 스레드가 pool.play() 중에 pool을 없애면 안 되므로 끝날 때까지 기다림
        synchronized (MusicEngine.class) { if (current == this) current = null; }
        pool.release();
    }
}
