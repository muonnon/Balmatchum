package com.example.balmatchum;

import android.content.Context;     // 앱의 리소스(res/raw 소리 파일 등)에 접근할 때 필요한 정보
import android.media.SoundPool;     // 짧은 소리 여러 개를 미리 불러 두고 빠르게 재생하는 도구
import android.util.Log;            // Logcat(개발용 기록 창)에 글을 남기는 도구

import java.util.Random;            // 무작위 숫자를 만드는 도구 (시드를 넣으면 같은 순서로 나옴)

/**
 * 걸음 BPM에 맞춰 음악을 실시간으로 만들어 재생하는 엔진.
 *
 * 동작 방식:
 *  - 한 박(1비트)을 4칸으로 나눈 "16분음표"를 한 칸으로 삼아, 일정한 간격으로 칸을 하나씩 진행해요.
 *  - 칸 번호(step)에 따라 어떤 소리를 낼지 정해요.
 *      · 킥(쿵)    : 4칸마다 = 매 박의 첫 칸
 *      · 하이햇(츳): 4칸 중 3번째 칸 = 박과 박 사이(엇박)
 *      · 멜로디    : 2칸마다 = 8분음표마다, 펜타토닉 음계 안에서 한 칸 위/아래/제자리로 무작위 이동
 *  - 음높이는 멜로디 소리 파일 하나를 빠르게/느리게 재생해서 바꿔요. (빠르게 재생하면 음이 높아짐)
 *  - applySettings()로 음계(스케일), 사운드 테마(8bit/Lo-fi/피아노), 볼륨, 비트 켜기/끄기를 정해요.
 *
 * 두 가지 모드가 있어요.
 *  1) 실시간 모드 (걷기 화면): 걸음이 들어올 때마다 화면이 setBpm()으로 박자를 바꿔 줘요.
 *  2) 다시 듣기 모드 (곡 상세 화면): loadReplay()로 저장된 seed와 bpm_series를 넣으면,
 *     엔진이 스스로 "지금 위치에 해당하는 BPM"을 골라 가며 원래 곡을 그대로 다시 연주해요.
 *
 * 왜 별도 스레드(Thread)를 쓰나요?
 *  - 박자를 맞추려면 "기다렸다가 소리 내기"를 계속 반복해야 해요.
 *  - 이걸 화면(UI) 스레드에서 하면 기다리는 동안 버튼도 안 눌리고 앱이 멈춘 것처럼 돼요.
 *  - 그래서 박자 진행만 담당하는 스레드를 따로 만들어 돌려요.
 */
public class MusicEngine {

    // 펜타토닉(5음) 음계를 "기본음에서 몇 반음 위인지"로 적은 표. (도 레 미 솔 라 + 높은 도)
    // 반음: 피아노 건반 한 칸(흰 건반, 검은 건반 포함). 12반음 = 한 옥타브
    // static final: 모든 MusicEngine이 같이 쓰는, 절대 바뀌지 않는 상수
    //
    // 스케일(음계) 3종. 바깥 배열의 칸 번호가 곧 scale_id (DB와 설정에 저장되는 번호)예요.
    //   0 = 펜타토닉        : 도 레 미 솔 라 도     → 밝고 어디서 멈춰도 자연스러움
    //   1 = 마이너 펜타토닉 : 도 미♭ 파 솔 시♭ 도   → 조금 어둡고 블루스 느낌
    //   2 = 장음계          : 도 레 미 파 솔 라 시 도 → 학교에서 배우는 익숙한 음계
    // 모든 스케일의 가장 높은 음은 12반음(한 옥타브 위 도) 이하여야 해요.
    // 이유: 음높이를 SoundPool의 재생 속도로 만드는데, 재생 속도는 최대 2.0배까지만 돼요. (12반음 = 정확히 2.0배)
    // 주의: 배열 순서를 바꾸면 이미 저장된 곡의 scale_id가 다른 음계를 가리키게 되므로, 새 음계는 뒤에만 추가해요.
    private static final int[][] SCALES = {
            {0, 2, 4, 7, 9, 12},         // 펜타토닉 (기존에 쓰던 음계 그대로)
            {0, 3, 5, 7, 10, 12},        // 마이너 펜타토닉
            {0, 2, 4, 5, 7, 9, 11, 12},  // 장음계
    };
    /** 화면(설정, 곡 상세)에 보여줄 스케일 이름. SCALES와 같은 순서 */
    public static final String[] SCALE_NAMES = {"펜타토닉", "마이너 펜타토닉", "장음계"};

    /** 화면에 보여줄 사운드 테마 이름. 칸 번호가 곧 theme_id */
    public static final String[] THEME_NAMES = {"8bit", "Lo-fi", "피아노"};

    // 소리 파일 목록과 테마별 소리 표는 SoundPalette.java에 한곳으로 모았어요.
    // 불러올 소리 개수 = 표의 길이 (기존 9개 + 새 소리 12개 = 21개). 소리를 추가하면 자동으로 따라 늘어나요.
    private static final int SOUND_COUNT = SoundPalette.ALL.length;

    // 1분(60초)을 나노초로 쓴 값. 1초 = 10억(1_000_000_000) 나노초
    // (숫자 사이 _ 는 읽기 쉽게 쓴 것으로 값에는 영향 없음, 끝의 L은 long 타입이라는 뜻)
    private static final long MINUTE_NS = 60_000_000_000L;

    // ─────────── 동시 재생 방지 ───────────
    // static: 이 변수는 MusicEngine 객체마다 따로 있지 않고, 앱 전체에 딱 하나만 있어요.
    // 걷기 화면과 곡 상세 화면은 각자 MusicEngine을 만들지만, 이 변수는 함께 봐요.
    // "지금 소리를 내고 있는 엔진"을 기억해 두고, 다른 엔진이 start()하면 이 엔진을 먼저 멈춰요.
    private static MusicEngine current;

    private final SoundPool pool;      // 소리들을 담아 두고 재생하는 SoundPool

    // SoundPool에 불러온 소리 각각의 번호(ID). 재생할 때 이 번호로 "어떤 소리"인지 알려줘요.
    // 칸 번호 = SoundPalette.ALL의 칸 번호 (예: soundIds[SoundPalette.indexOf("snare")] = 스네어의 ID)
    private final int[] soundIds = new int[SOUND_COUNT];

    // 옛 알고리즘(melody_version 0)용 테마별 소리 ID. [테마 번호][0=멜로디, 1=킥, 2=하이햇]
    // SoundPalette.LEGACY 표의 이름을 ID로 바꿔 둔 것 → 업데이트 전과 똑같은 9개 소리를 써요.
    private final int[][] legacyIds = new int[SoundPalette.LEGACY.length][3];

    // ─────────── 소리 설정 (applySettings로 바꿈) ───────────
    // 화면 스레드가 바꾸고 음악 스레드가 읽으므로 volatile
    private volatile int[] scale = SCALES[0];    // 지금 쓰는 음계 (반음 배열)
    private volatile int themeId = 0;            // 지금 쓰는 사운드 테마 번호
    private volatile float volume = 0.7f;        // 전체 볼륨 (0.0 ~ 1.0). 모든 play()의 볼륨에 곱해요
    private volatile boolean beatOn = true;      // false면 킥·하이햇을 내지 않고 멜로디만

    // volatile: 여러 스레드가 함께 읽고 쓰는 변수에 붙여요.
    // 화면 스레드가 값을 바꾸면(setBpm, stop), 음악 스레드가 바로 바뀐 값을 보게 해줘요.
    // 안 붙이면 음악 스레드가 예전 값을 계속 보고 있을 수 있어요.
    private volatile int bpm = 100;              // 현재 박자 (1분당 박 수). 처음엔 100
    private volatile boolean running = false;    // 음악 스레드가 돌고 있는지. false가 되면 스레드가 반복을 멈춤
    private volatile int loadedCount = 0;        // 불러오기가 끝난 소리 개수 (SOUND_COUNT개가 되면 재생 준비 완료)

    private Thread thread;                       // 박자를 진행하는 음악 스레드

    // ─────────── 연주 상태 ───────────
    // 아래 값들은 "지금 곡의 어디쯤을 연주하고 있는지"를 나타내요.
    // 스레드 밖(필드)에 두는 이유: 일시정지 후 다시 start()해도 멈춘 곳부터 이어서 연주하려고.
    private int step = 0;                        // 지금 몇 번째 칸인지 (0~15, 16칸 = 4박 = 1마디)
    private int degree = startDegree(SCALES[0]); // 현재 음 위치 (scale 배열의 칸 번호)

    // 멜로디를 고를 때 쓰는 난수 생성기.
    // Math.random()은 매번 결과가 달라서 같은 멜로디를 다시 만들 수 없어요.
    // Random에 같은 시드(seed)를 넣으면 항상 같은 순서로 숫자가 나오므로, 저장한 시드로 멜로디를 재현할 수 있어요.
    private Random random = new Random();
    private long seed;                           // 마지막으로 정한 시드 (처음으로 되감을 때 다시 쓰려고 기억)

    // ─────────── 다시 듣기(재생) 모드용 ───────────
    // series가 null이면 실시간 모드, null이 아니면 다시 듣기 모드예요.
    private int[] series;                        // 걸음마다의 BPM 기록 (예: {112, 113, 110, ...})
    private long[] startNs;                      // series[i]가 시작되는 위치(나노초). 마지막 칸 = 곡 전체 길이
    private int seriesIndex = 0;                 // 지금 쓰고 있는 series의 칸 번호

    // "가상 시계": 곡의 처음부터 지금까지 연주한 길이(나노초).
    // 실제 시계(System.nanoTime)가 아니라, 칸을 하나 연주할 때마다 그 칸의 길이만큼 더해요.
    // 그래서 기기가 잠깐 버벅여도 "몇 번째 음이 어떤 BPM에서 나는지"는 항상 똑같아요. (= 재현 가능)
    // 화면 스레드가 SeekBar를 그리려고 읽으므로 volatile
    private volatile long positionNs = 0;
    private volatile boolean finished = false;   // 다시 듣기가 끝까지 재생되었는지

    // ─────────── 멜로디 확인용 ───────────
    // 같은 곡을 두 번 재생했을 때 멜로디가 똑같은지 확인하려고, 멜로디 음을 숫자 하나로 요약해요.
    private int noteCount = 0;                   // 지금까지 나온 멜로디 음 개수
    private long melodyHash = 0;                 // 멜로디 요약값 (음이 하나라도 다르면 값이 달라짐)

    /**
     * 엔진을 만들 때 SoundPalette의 소리 파일 전부(21개)를 SoundPool에 미리 불러와요.
     * 미리 불러 두면 재생할 때 지연이 거의 없어요.
     *
     * @param c 소리 파일(res/raw)을 읽기 위한 Context (보통 Activity를 넘김)
     */
    public MusicEngine(Context c) {
        // setMaxStreams(10): 동시에 최대 10개 소리까지 겹쳐서 재생 가능
        // 새 알고리즘은 한 칸에서 킥·스네어·하이햇·장식 타악기·베이스·멜로디가 함께 날 수 있고(최대 6개),
        // 길게 울리는 소리(열린 하이햇, 마림바 등)의 꼬리도 남아 있어서 6개로는 모자라 10개로 늘림.
        // (꽉 차면 SoundPool이 가장 오래된 소리를 끊고 새 소리를 냄)
        pool = new SoundPool.Builder().setMaxStreams(10).build();

        // load(Context, 리소스, 우선순위): 소리 파일을 불러오고, 그 소리의 번호를 돌려줌
        // 마지막 1은 우선순위인데, 현재 안드로이드에서는 효과가 없어 관례적으로 1을 넣어요.
        // 재생 중에 테마를 바꾸거나 곡마다 다른 소리를 골라도 바로 낼 수 있게 전부 불러 둬요.
        // (모두 1초 안팎의 작은 파일이라 21개를 다 불러도 메모리 부담이 크지 않음)
        for (int i = 0; i < SOUND_COUNT; i++) {
            soundIds[i] = pool.load(c, SoundPalette.ALL[i].resId, 1);
        }
        // 옛 알고리즘용 표: 이름 → 방금 불러온 ID
        for (int t = 0; t < SoundPalette.LEGACY.length; t++) {     // t = 테마 번호
            for (int k = 0; k < 3; k++) {                           // k = 0 멜로디, 1 킥, 2 하이햇
                legacyIds[t][k] = soundIds[SoundPalette.indexOf(SoundPalette.LEGACY[t][k])];
            }
        }

        // 소리 불러오기가 끝날 때마다 호출돼요. (load()는 바로 끝나지 않고 뒤에서 천천히 불러옴)
        // status가 0이면 성공 → 성공한 개수를 세어 SOUND_COUNT(21)개가 되면 isReady()가 true가 돼요.
        // Logcat에서 태그 "BAL"로 검색하면 볼 수 있어요.
        pool.setOnLoadCompleteListener((p, id, status) -> {
            if (status == 0) loadedCount++;
            Log.d("BAL", "load id=" + id + " status=" + status);
        });
    }

    /**
     * 소리 SOUND_COUNT(21)개를 모두 불러왔는지 알려줘요.
     * 불러오기 전에 재생하면 첫 몇 음이 소리 없이 지나가므로, 재생 버튼에서 확인하는 데 써요.
     */
    public boolean isReady() { return loadedCount >= SOUND_COUNT; }

    /**
     * 음계에 맞는 멜로디 시작 위치를 계산해요. 음계의 가운데쯤에서 시작해야 위/아래로 고르게 움직여요.
     * (길이 - 1) / 2 를 쓰는 이유:
     *  - 펜타토닉(6칸)이면 (6-1)/2 = 2 → 예전에 쓰던 시작 위치(2 = 미)와 같아서, 이미 저장된 곡의 멜로디가 그대로예요.
     *  - 장음계(8칸)면 (8-1)/2 = 3 (= 파). 어떤 길이든 결과가 0 ~ 길이-1 안이라 배열 범위를 벗어나지 않아요.
     */
    private static int startDegree(int[] s) { return (s.length - 1) / 2; }

    /**
     * 소리 설정을 바꿔요. 재생 중에 불러도 다음 칸부터 바로 반영돼요.
     * 잘못된 번호가 들어와도 앱이 죽지 않도록 범위 안으로 맞춰요.
     *
     * @param scaleId       음계 번호 (0 펜타토닉, 1 마이너 펜타토닉, 2 장음계)
     * @param themeId       사운드 테마 번호 (0 8bit, 1 Lo-fi, 2 피아노)
     * @param volumePercent 볼륨 0 ~ 100 (%)
     * @param beatOn        true면 킥·하이햇도 재생, false면 멜로디만
     */
    public void applySettings(int scaleId, int themeId, int volumePercent, boolean beatOn) {
        int[] newScale = SCALES[clamp(scaleId, 0, SCALES.length - 1)];
        // 음계가 바뀌어 칸 수가 줄면(장음계 8칸 → 펜타토닉 6칸) 지금 음 위치가 범위를 벗어날 수 있어서 맞춰 줌
        // (같은 음계를 다시 넣을 때는 아무 변화 없음 → 다시 듣기 재현에는 영향 없음)
        if (newScale != scale) degree = clamp(degree, 0, newScale.length - 1);
        this.scale = newScale;
        this.themeId = clamp(themeId, 0, SoundPalette.LEGACY.length - 1);
        this.volume = clamp(volumePercent, 0, 100) / 100f;   // 70 → 0.7 (100f로 나눠야 소수점이 남음)
        this.beatOn = beatOn;
    }

    /** v를 min ~ max 범위 안으로 맞춰요. 예) clamp(5, 0, 2) → 2 */
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    /**
     * 박자(BPM)를 바꿔요. 재생 중에 바꿔도 다음 칸부터 바로 반영돼요.
     * 너무 느리거나 빠르면 음악이 이상해지므로 50~200 사이로 제한해요.
     *   Math.min(200, b) → 200보다 크면 200으로
     *   Math.max(50, …)  → 50보다 작으면 50으로
     */
    public void setBpm(int b) { bpm = Math.max(50, Math.min(200, b)); }

    /**
     * 멜로디의 무작위 시드를 정해요. 걷기를 시작할 때(음악이 멈춰 있을 때) 호출해요.
     * 같은 시드를 넣으면 멜로디가 같은 순서로 위/아래로 움직여서 같은 곡을 다시 만들 수 있어요.
     * 시작 음 위치, 칸 번호, 가상 시계도 처음으로 되돌려야 매번 같은 곳에서 출발해요.
     *
     * @param seed 이번 곡의 시드 (DB의 seed 열에 저장되는 값)
     */
    public void setSeed(long seed) {
        this.seed = seed;
        resetPosition();
    }

    // ───────────────────────────── 다시 듣기 모드 ─────────────────────────────

    /**
     * bpm_series의 각 값이 곡의 몇 나노초 지점에서 시작하는지 계산해요.
     * 규칙: 값 하나 = 걸음 한 번 = 60000 / BPM 밀리초 길이.
     *   예) {120, 100} → 120 BPM 한 걸음 = 0.5초, 100 BPM 한 걸음 = 0.6초
     *       → 결과 {0, 0.5초, 1.1초} (마지막 칸은 곡 전체 길이)
     * 곡 상세 화면(그래프, 시간 표시)도 같은 계산을 써야 SeekBar와 그래프가 정확히 맞으므로 public static으로 공개해요.
     *
     * @param series 걸음마다의 BPM (0 이하의 값은 없다고 가정. 저장할 때 항상 양수)
     * @return 길이가 series.length + 1 인 배열. [i] = i번째 값이 시작하는 위치(ns), [마지막] = 전체 길이(ns)
     */
    public static long[] seriesStartNs(int[] series) {
        long[] starts = new long[series.length + 1];
        for (int i = 0; i < series.length; i++) {
            // 다음 값의 시작 = 이번 값의 시작 + 이번 걸음 길이(1분 / BPM)
            starts[i + 1] = starts[i] + MINUTE_NS / series[i];
        }
        return starts;
    }

    /**
     * 다시 듣기 모드로 곡을 불러와요. 재생은 하지 않고 처음 위치에 준비만 해요. (재생은 start())
     *
     * @param seed   DB에 저장된 멜로디 시드
     * @param series DB에 저장된 걸음마다의 BPM 기록
     */
    public void loadReplay(long seed, int[] series) {
        stop();                       // 혹시 재생 중이면 멈추고
        waitForThreadEnd();           // 음악 스레드가 완전히 끝날 때까지 기다린 뒤 값을 바꿔요 (두 스레드가 동시에 바꾸면 꼬임)
        this.series = series;
        this.startNs = seriesStartNs(series);
        this.seed = seed;
        resetPosition();              // 시드로 Random을 새로 만들고 위치를 0으로
    }

    /** 다시 듣기 곡의 전체 길이(밀리초). 실시간 모드면 0 */
    public long getTotalMs() { return startNs == null ? 0 : startNs[startNs.length - 1] / 1_000_000; }

    /** 지금 재생 위치(밀리초). 화면이 SeekBar를 움직이는 데 써요. */
    public long getPositionMs() { return positionNs / 1_000_000; }

    /** 음악 스레드가 돌고 있는지 (= 소리가 나고 있는지) */
    public boolean isPlaying() { return running; }

    /** 다시 듣기가 끝까지 재생되어 스스로 멈췄는지 */
    public boolean isFinished() { return finished; }

    /**
     * 재생 위치를 ms 지점으로 옮겨요. (SeekBar를 끌어서 놓았을 때)
     *
     * 그냥 positionNs만 바꾸면 안 되는 이유:
     *  멜로디는 "이전 음에서 한 칸 위/아래"로 정해지므로, 그 지점의 음 높이는 처음부터 차례로 계산해야만 알 수 있어요.
     *  그래서 처음으로 되감은 뒤, 소리 없이(sound=false) 칸을 빠르게 진행해서 목표 지점까지 따라가요.
     *  이렇게 하면 중간으로 건너뛰어도 처음부터 들은 것과 똑같은 멜로디가 이어져요.
     *  (몇 분짜리 곡도 칸 수가 수천 개 정도라 순식간에 끝나요)
     *
     * @param ms 옮길 위치 (밀리초)
     */
    public void seekTo(long ms) {
        stop();
        waitForThreadEnd();
        resetPosition();
        long target = ms * 1_000_000;                 // ms → ns
        long total = startNs == null ? 0 : startNs[startNs.length - 1];
        // 목표 지점에 닿거나 곡 끝에 닿을 때까지 소리 없이 진행
        while (positionNs < target && positionNs < total) tick(false);
    }

    /**
     * 연주 위치를 곡의 맨 처음으로 되돌려요.
     * 시드로 Random을 새로 만들어야 첫 음부터 같은 순서의 무작위 값이 다시 나와요.
     */
    private void resetPosition() {
        random = new Random(seed);
        degree = startDegree(scale);   // 지금 음계의 가운데쯤에서 출발
        step = 0;
        seriesIndex = 0;
        positionNs = 0;
        finished = false;
        noteCount = 0;
        melodyHash = 0;
    }

    // ───────────────────────────── 재생 / 정지 ─────────────────────────────

    /**
     * 음악 재생을 시작해요. (다시 듣기 모드에서는 멈춘 위치부터 이어서)
     * 이미 재생 중이면 아무것도 하지 않아요. (걸음마다 호출돼도 스레드가 여러 개 생기지 않도록)
     */
    public void start() {
        if (running) return;     // 이미 돌고 있으면 그냥 나감

        // 다른 엔진(예: 걷기 화면의 엔진)이 소리를 내고 있으면 먼저 멈춰요. → 두 음악이 겹치지 않음
        // synchronized: 두 스레드가 동시에 current를 바꾸지 못하게 한 번에 하나씩만 들어오게 하는 잠금
        synchronized (MusicEngine.class) {
            if (current != null && current != this) current.stop();
            current = this;
        }

        waitForThreadEnd();      // 방금 stop()한 예전 스레드가 아직 마무리 중이면 끝날 때까지 기다림
        if (series == null) step = 0;   // 실시간 모드는 시작할 때마다 마디의 첫 칸(킥)부터
        finished = false;
        running = true;

        // 새 스레드를 만들고, 그 스레드가 할 일을 () -> { ... } 안에 적어요.
        thread = new Thread(() -> {
            // 다음 칸을 연주할 "목표 시각"(나노초)
            // 매번 "지금부터 얼마 기다리기"로 하면 작은 오차가 쌓여 박자가 점점 밀려요.
            // 그래서 목표 시각을 일정하게 더해 가고, 그 시각까지 남은 만큼만 기다려요.
            long next = System.nanoTime();

            // running이 true인 동안 계속 반복 (stop()이 호출되면 false가 되어 빠져나감)
            while (running) {
                // 다시 듣기 모드에서 곡 끝에 닿았으면 스스로 멈춤
                if (series != null && positionNs >= startNs[startNs.length - 1]) {
                    finished = true;
                    running = false;
                    // 멜로디 요약을 남겨요. 같은 곡을 두 번 끝까지 들었을 때 이 줄이 똑같으면 멜로디가 똑같은 것.
                    Log.d("BAL_MELODY", "곡 끝: seed=" + seed + " notes=" + noteCount + " hash=" + melodyHash);
                    break;
                }

                // 한 칸 연주하고, 그 칸의 길이만큼 다음 목표 시각을 뒤로 미룸
                next += tick(true);

                // 목표 시각까지 남은 시간만큼 기다림
                long wait = next - System.nanoTime();
                if (wait > 0) {   // 이미 늦었으면(소리 재생 등에 시간이 걸린 경우) 기다리지 않고 바로 다음 칸
                    // Thread.sleep(밀리초, 나머지 나노초): 나노초를 ms 부분과 남은 나노초 부분으로 나눠 넘김
                    try { Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000)); }
                    // 기다리는 중에 stop()이 interrupt()를 부르면 이 예외가 발생 → 반복을 바로 끝냄
                    catch (InterruptedException e) { break; }
                }
            }
        });
        thread.start();   // 스레드 실행 시작 (이 줄 이후로 위 반복문이 따로 돌아감)
    }

    /**
     * 16분음표 한 칸을 진행해요. (start()의 반복문과 seekTo()가 함께 쓰는 핵심 메서드)
     *
     * @param sound true면 실제로 소리를 냄, false면 소리 없이 상태(멜로디 위치, 가상 시계)만 진행
     * @return 이번 칸의 길이(나노초). 다음 칸을 언제 연주할지 정하는 데 써요.
     */
    private long tick(boolean sound) {
        // 다시 듣기 모드: 가상 시계(positionNs)가 다음 값의 시작 지점을 지났으면 다음 값으로 넘어감
        // while인 이유: 칸 하나가 아주 짧은 걸음 여러 개를 한 번에 건너뛸 수도 있어서
        if (series != null) {
            while (seriesIndex + 1 < series.length && positionNs >= startNs[seriesIndex + 1]) seriesIndex++;
            setBpm(series[seriesIndex]);   // 지금 위치의 BPM으로 박자를 맞춤 (50~200으로 제한됨)
        }

        if (sound) Log.d("BAL", "step " + step);   // 디버깅용: 지금 칸 번호 기록

        // play(소리번호, 왼쪽볼륨, 오른쪽볼륨, 우선순위, 반복횟수, 재생속도)
        //   볼륨: 0.0 ~ 1.0 / 반복 0 = 한 번만 / 재생속도 1f = 원래 속도
        // 모든 볼륨에 전체 볼륨(volume)을 곱해요. 예) 하이햇 0.6 × 볼륨 0.7 = 0.42
        // 필드를 지역 변수에 한 번 복사해 두는 이유: 이번 칸을 연주하는 도중에 화면이 설정을 바꿔도
        // 한 칸 안에서는 같은 테마·볼륨으로 일관되게 연주하려고
        int[] ids = legacyIds[themeId];     // 지금 테마의 [멜로디, 킥, 하이햇] 소리 번호 (옛 9개 소리)
        int[] sc = scale;                   // 지금 음계
        float vol = volume;

        // 비트(드럼)가 켜져 있을 때만 킥·하이햇을 냄.
        // 비트를 꺼도 아래 멜로디 부분은 그대로 실행되므로 Random 순서(= 멜로디)는 바뀌지 않아요.
        if (sound && beatOn) {
            // 칸 0, 4, 8, 12 (4로 나눈 나머지가 0) → 매 박의 시작에 킥
            if (step % 4 == 0) pool.play(ids[1], vol, vol, 1, 0, 1f);                 // 1,2,3,4박
            // 칸 2, 6, 10, 14 → 박과 박 사이(엇박)에 하이햇. 킥보다 조금 작게(0.6)
            if (step % 4 == 2) pool.play(ids[2], 0.6f * vol, 0.6f * vol, 1, 0, 1f);  // 엇박
        }

        // 짝수 칸(0, 2, 4, …) → 8분음표마다 멜로디 한 음
        // 주의: 소리를 안 낼 때(sound=false)도 무작위 값은 꼭 뽑아야 해요.
        //       안 뽑으면 Random의 순서가 어긋나서 그 뒤 멜로디가 전부 달라져요.
        if (step % 2 == 0) {
            // random.nextInt(3)은 0, 1, 2 중 하나 → -1 하면 -1, 0, +1
            // 즉 음계에서 한 칸 내려가기 / 그대로 / 한 칸 올라가기 중 하나를 무작위로 고름
            // 바깥의 Math.max/min은 degree가 배열 범위(0 ~ sc.length-1)를 벗어나지 않게 막아줌
            degree = Math.max(0, Math.min(sc.length - 1,
                    degree + random.nextInt(3) - 1));               // -1, 0, +1

            // 멜로디 요약값 갱신: 이전 값 × 31 + 이번 음. (문자열 hashCode와 같은 방식)
            // 음 하나만 달라도, 순서만 달라도 결과가 달라져서 "멜로디가 같은지" 비교하기 좋아요.
            noteCount++;
            melodyHash = melodyHash * 31 + degree;

            if (sound) {
                // 태그 BAL_MELODY로 음마다 기록 → Logcat에서 두 번의 재생을 줄 단위로 비교할 수 있어요.
                Log.d("BAL_MELODY", "#" + noteCount + " degree=" + degree + " bpm=" + bpm);

                // 반음 수를 재생 속도로 바꾸기: 속도 = 2^(반음/12)
                // 예) 0반음 → 1.0배(원래 음), 12반음 → 2.0배(한 옥타브 위)
                // 소리를 2배 빠르게 재생하면 한 옥타브 높게 들리는 원리를 이용
                float rate = (float) Math.pow(2, sc[degree] / 12.0);
                pool.play(ids[0], 0.8f * vol, 0.8f * vol, 1, 0, rate);
            }
        }

        // 다음 칸으로. 15 다음은 다시 0 (% 16 은 16으로 나눈 나머지라 0~15를 빙글빙글 돎)
        step = (step + 1) % 16;

        // 이번 칸의 길이 = 16분음표 한 칸 = 한 박(1분 / bpm) / 4
        // 예) 120 BPM → 60_000_000_000 / 120 / 4 = 125,000,000ns = 0.125초
        long len = MINUTE_NS / bpm / 4;
        positionNs += len;      // 가상 시계를 이번 칸 길이만큼 앞으로
        return len;
    }

    /**
     * 음악을 멈춰요. (다시 듣기 모드에서는 위치가 그대로 남아서 start()로 이어 들을 수 있어요)
     * running을 false로 바꿔 반복문이 끝나게 하고,
     * interrupt()로 sleep 중인 스레드를 깨워서 다음 칸을 기다리지 않고 바로 멈추게 해요.
     */
    public void stop() { running = false; if (thread != null) thread.interrupt(); }

    /**
     * 음악 스레드가 완전히 끝날 때까지 기다려요.
     * stop()은 "멈춰!"라고 신호만 보내고 바로 돌아오기 때문에, 스레드가 아직 마지막 칸을 처리 중일 수 있어요.
     * 그 상태에서 위치를 바꾸거나 새 스레드를 만들면 두 스레드가 같은 값을 동시에 바꿔서 꼬여요.
     * join(): 그 스레드가 끝날 때까지 기다리는 메서드 (interrupt로 깨웠으므로 아주 짧게 기다림)
     */
    private void waitForThreadEnd() {
        if (thread == null || thread == Thread.currentThread()) return;
        try { thread.join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); } // 기다리다 방해받으면 그 사실을 다시 표시해 둠
    }

    /**
     * 엔진을 완전히 정리해요. 화면이 사라질 때(onDestroy) 한 번 호출.
     * 음악을 멈추고, SoundPool이 쓰던 메모리를 돌려줘요. 이후에는 이 엔진을 다시 쓰면 안 돼요.
     */
    public void release() {
        stop();
        waitForThreadEnd();   // 스레드가 pool.play()를 하는 중에 pool을 없애면 안 되므로 끝날 때까지 기다림
        synchronized (MusicEngine.class) { if (current == this) current = null; } // 사라지는 엔진을 기억에서 지움
        pool.release();
    }
}
