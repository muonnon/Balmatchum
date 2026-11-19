package com.example.balmatchum;

import java.util.Random;   // 시드를 넣으면 항상 같은 순서로 숫자를 내는 의사난수 생성기

/**
 * 곡을 16분음표 한 칸씩 진행하면서 "이번 칸에 어떤 소리를 어떤 높이·볼륨으로 낼지"를 계산하는 클래스.
 * 계산한 음은 NoteSink로 보내요.  → 재생(SoundPoolSink)과 WAV 내보내기(PcmMixSink)가 이 코드를 함께 써요.
 *
 * 원래 MusicEngine 안에 있던 계산(가상 시계, BPM 기록 따라가기, 옛/새 멜로디 알고리즘, 멜로디 해시)을
 * 그대로 옮겨 온 것이에요. 기다리기(sleep)나 SoundPool 같은 안드로이드 기능은 전혀 없어서
 *  - MusicEngine은 칸마다 실제 시간을 기다리며 tick()을 부르고 (= 실시간 재생)
 *  - SongRenderer는 기다리지 않고 tick()을 연속으로 불러서 곡 전체를 순식간에 계산해요 (= 내보내기)
 * PC 단위 테스트에서도 그대로 돌릴 수 있어요.
 *
 * 스레드: 설정(음계·테마·비트·BPM)은 화면 스레드가 바꾸고 tick()은 음악 스레드가 불러서 volatile을 붙였어요.
 *        그 밖의 상태는 MusicEngine이 "음악 스레드가 멈춘 뒤에만" 바꾸도록 지켜요. (기존과 같은 규칙)
 */
public final class NoteSequencer {

    /** 1분을 나노초로 쓴 값 (1초 = 10억 나노초) */
    static final long MINUTE_NS = 60_000_000_000L;

    // 옛 알고리즘용 테마별 소리 번호 [테마][0=멜로디, 1=킥, 2=하이햇] (SoundPalette 번호)
    // static: 모든 NoteSequencer가 같이 쓰는 표. 클래스가 처음 쓰일 때 한 번만 만들어요.
    private static final int[][] LEGACY_SOUNDS = new int[SoundPalette.LEGACY.length][3];
    static {
        for (int t = 0; t < SoundPalette.LEGACY.length; t++) {
            for (int k = 0; k < 3; k++) LEGACY_SOUNDS[t][k] = SoundPalette.indexOf(SoundPalette.LEGACY[t][k]);
        }
    }

    // ─────────── 곡의 소리 설정 (화면 스레드가 바꾸고 음악 스레드가 읽음) ───────────
    private volatile int[] scale = MusicEngine.SCALES[0];   // 음계 (반음 배열)
    private volatile int themeId = 0;                       // 사운드 테마 번호
    private volatile boolean beatOn = true;                 // false면 드럼을 내지 않음 (멜로디는 그대로)
    private volatile int bpm = 100;                         // 지금 박자

    // ─────────── 곡 정보 ───────────
    private long seed;                     // 멜로디 시드
    private int melodyVersion = 0;         // 0 = 옛 알고리즘, 1 = SongComposer
    private int[] series;                  // 걸음마다의 BPM 기록 (null이면 실시간 모드)
    private long[] startNs;                // series[i]가 시작되는 위치(ns). 마지막 칸 = 곡 전체 길이

    // ─────────── 연주 상태 ───────────
    private int step = 0;                  // 마디 안의 칸 번호 (0 ~ 15)
    private int degree = startDegree(MusicEngine.SCALES[0]);   // 옛 알고리즘: 현재 음 위치 (scale 칸 번호)
    private Random random = new Random();  // 옛 알고리즘의 난수
    private SongComposer composer;         // 새 알고리즘의 작곡기 (버전 1일 때만)
    private int seriesIndex = 0;           // 지금 쓰고 있는 series 칸
    // 가상 시계: 곡의 처음부터 연주한 길이(ns). 칸을 하나 진행할 때마다 그 칸의 길이를 더함.
    // 실제 시계가 아니라서, 재생이든 내보내기든 "몇 번째 음이 몇 ns에 나는지"가 항상 같아요.
    // 화면 스레드가 SeekBar를 그리려고 읽으므로 volatile
    private volatile long positionNs = 0;

    // ─────────── 옛 알고리즘의 멜로디 요약값 ───────────
    private int noteCount = 0;
    private long melodyHash = 0;

    // ─────────── 로그용 ───────────
    // 이번 tick에서 멜로디 음이 났으면 그 음의 설명(로그 한 줄), 아니면 null.
    // 이 클래스는 안드로이드 Log를 쓰지 않으므로(PC 테스트용), 실제로 Logcat에 찍는 건 MusicEngine이 해요.
    private String melodyLog;

    /**
     * 음계에 맞는 옛 알고리즘의 시작 위치: 가운데쯤. (6칸 펜타토닉이면 2 = 예전 그대로)
     */
    private static int startDegree(int[] s) { return (s.length - 1) / 2; }

    /** v를 min ~ max 범위 안으로 */
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    // ───────────────────────────── 설정 ─────────────────────────────

    /**
     * 곡의 소리 설정(음계·테마·비트)을 바꿔요. 볼륨은 여기서 다루지 않아요(재생 쪽 SoundPoolSink가 곱함).
     * 음계가 바뀌어 칸 수가 줄면 옛 알고리즘의 음 위치가 범위를 벗어날 수 있어서 맞춰 줘요. (예전과 같은 동작)
     */
    public void setSound(int scaleId, int themeId, boolean beatOn) {
        int[] newScale = MusicEngine.SCALES[clamp(scaleId, 0, MusicEngine.SCALES.length - 1)];
        if (newScale != scale) degree = clamp(degree, 0, newScale.length - 1);
        this.scale = newScale;
        this.themeId = clamp(themeId, 0, SoundPalette.LEGACY.length - 1);
        this.beatOn = beatOn;
    }

    /** 박자를 바꿔요. 50 ~ 200으로 제한 */
    public void setBpm(int b) { bpm = Math.max(50, Math.min(200, b)); }

    /** 지금 박자 */
    public int getBpm() { return bpm; }

    /**
     * 실시간 모드(걷기 화면)용: 시드와 알고리즘 버전을 정하고 처음으로 되감아요.
     * 주의: setSound()를 먼저 불러야 해요. (버전 1은 여기서 지금 테마·음계로 곡의 개성을 정함)
     */
    public void setSeed(long seed, int melodyVersion) {
        this.seed = seed;
        this.melodyVersion = melodyVersion;
        reset();
    }

    /**
     * 다시 듣기·내보내기용: 저장된 곡을 불러와 처음으로 되감아요. (setSound()를 먼저 불러 둘 것)
     *
     * @param seed          DB의 seed
     * @param series        DB의 걸음마다 BPM 기록
     * @param melodyVersion DB의 melody_version
     */
    public void loadSong(long seed, int[] series, int melodyVersion) {
        this.series = series;
        this.startNs = seriesStartNs(series);
        this.seed = seed;
        this.melodyVersion = melodyVersion;
        reset();
    }

    /**
     * 곡의 맨 처음으로 되돌려요. 시드로 Random(또는 작곡기)을 새로 만들어야 첫 음부터 같은 순서가 다시 나와요.
     */
    public void reset() {
        random = new Random(seed);     // 옛 알고리즘(버전 0)용
        // 버전 1: 작곡기를 시드로 새로 만들면 용도별 Random 3개도 처음 상태로 돌아가요
        composer = melodyVersion >= SongComposer.VERSION ? new SongComposer(seed, themeId, scale) : null;
        degree = startDegree(scale);   // 지금 음계의 가운데쯤에서 출발
        step = 0;
        seriesIndex = 0;
        positionNs = 0;
        noteCount = 0;
        melodyHash = 0;
    }

    /**
     * 실시간 모드에서 음악을 다시 시작할 때 마디의 첫 칸(킥)부터 시작하게 해요.
     * (예전 MusicEngine.start()의 "실시간 모드는 시작할 때마다 step = 0"과 같은 동작. 다시 듣기 모드에서는 아무 일도 안 함)
     */
    public void restartBarIfLive() {
        if (series == null) step = 0;
    }

    // ───────────────────────────── 위치 정보 ─────────────────────────────

    /**
     * bpm_series의 각 값이 곡의 몇 ns 지점에서 시작하는지 계산해요.
     * 규칙: 값 하나 = 걸음 한 번 = 60000 / BPM 밀리초. 결과의 마지막 칸 = 곡 전체 길이.
     * (곡 상세 화면의 그래프·SeekBar도 이 계산을 써요 → MusicEngine.seriesStartNs가 이걸 불러요)
     */
    public static long[] seriesStartNs(int[] series) {
        long[] starts = new long[series.length + 1];
        for (int i = 0; i < series.length; i++) {
            starts[i + 1] = starts[i] + MINUTE_NS / series[i];
        }
        return starts;
    }

    /** 곡 전체 길이(ns). 실시간 모드면 0 */
    public long getTotalNs() { return startNs == null ? 0 : startNs[startNs.length - 1]; }

    /** 지금 위치(ns) */
    public long getPositionNs() { return positionNs; }

    /** 다시 듣기·내보내기 모드에서 곡 끝에 닿았는지 */
    public boolean isAtEnd() { return series != null && positionNs >= getTotalNs(); }

    /** 다음 tick이 연주할 칸 번호 (디버깅 로그용) */
    public int getStep() { return step; }

    /** 이번 tick에서 난 멜로디 음의 로그 한 줄 (없으면 null) */
    public String getMelodyLog() { return melodyLog; }

    /**
     * 곡 끝 요약 한 줄. 같은 곡을 두 번 끝까지 들었을 때(또는 재생과 내보내기에서) 이 줄이 같으면 연주가 같은 것.
     * 버전 0은 업데이트 전과 완전히 같은 형식이에요. (옛 기록과 비교하려고 바꾸지 않음)
     */
    public String summary() {
        if (composer == null) {
            return "곡 끝: seed=" + seed + " notes=" + noteCount + " hash=" + melodyHash;
        }
        return "곡 끝: v1 seed=" + seed + " notes=" + composer.getNoteCount()
                + " hash=" + composer.getMelodyHash() + " rhythm=" + composer.getRhythmHash();
    }

    /** 버전 1 곡의 개성 설명 (로그용). 버전 0이면 null */
    public String describe() { return composer == null ? null : composer.describe(); }

    // ───────────────────────────── 한 칸 진행 ─────────────────────────────

    /**
     * 16분음표 한 칸을 진행해요.
     *
     * @param sink 이번 칸의 음을 받을 곳. null이면 소리 없이 상태(멜로디 위치, 가상 시계)만 진행 (seekTo에서 사용)
     * @return 이번 칸의 길이(ns). 재생에서는 다음 칸까지 기다릴 시간이에요.
     */
    public long tick(NoteSink sink) {
        // 다시 듣기 모드: 가상 시계가 다음 BPM 값의 시작 지점을 지났으면 다음 값으로 넘어감
        // while인 이유: 칸 하나가 아주 짧은 걸음 여러 개를 한 번에 건너뛸 수도 있어서
        if (series != null) {
            while (seriesIndex + 1 < series.length && positionNs >= startNs[seriesIndex + 1]) seriesIndex++;
            setBpm(series[seriesIndex]);   // 지금 위치의 BPM으로 박자를 맞춤 (50~200으로 제한됨)
        }

        melodyLog = null;
        long now = positionNs;             // 이번 칸이 시작하는 시각 = 이번 칸 음들의 시각
        if (composer != null) composerTick(sink, now);
        else legacyTick(sink, now);

        // 이번 칸의 길이 = 한 박(1분 / bpm) / 4.  예) 120 BPM → 0.125초
        long len = MINUTE_NS / bpm / 4;
        positionNs += len;                 // 가상 시계를 이번 칸 길이만큼 앞으로
        return len;
    }

    /**
     * 새 알고리즘(버전 1)으로 한 칸. 무엇을 낼지는 SongComposer가 정하고, 여기서는 싱크로 보내기만 해요.
     * 비트를 꺼도 작곡기 계산은 항상 같고(난수 순서 고정), 드럼 표시가 붙은 소리만 보내지 않아요.
     */
    private void composerTick(NoteSink sink, long now) {
        SongComposer c = composer;
        c.step(bpm);                        // 이번 칸 계산 (소리를 내든 안 내든 꼭 불러야 함)
        step = (step + 1) % 16;             // 로그의 칸 번호를 맞추려고 같이 진행
        if (sink == null) return;

        boolean beat = beatOn;
        for (int i = 0; i < c.count; i++) {
            if (c.isDrum[i] && !beat) continue;    // 비트를 껐으면 드럼만 건너뜀 (베이스·멜로디는 냄)
            sink.onNote(c.sound[i], c.rate[i], c.volume[i], now);
        }
        if (c.melodyEvent) {
            // 음마다 기록: 소리 이름 · 높이(반음) · 길이(칸) · 강약
            melodyLog = "#" + c.getNoteCount() + " " + c.melodyName + " semi=" + c.melodySemi
                    + " len=" + c.melodyLen + " vel=" + c.melodyVel + " bpm=" + bpm;
        }
    }

    /**
     * 옛 알고리즘(버전 0)으로 한 칸. 업데이트 전 MusicEngine.tick()의 계산을 그대로 옮긴 것.
     * ★ 옛 곡이 예전과 똑같이 나와야 하므로 이 메서드 안의 계산은 고치지 마세요.
     *   (바뀐 것은 pool.play() 대신 sink.onNote()를 부르는 것과, 전체 볼륨을 곱하지 않는 것뿐.
     *    전체 볼륨은 SoundPoolSink가 곱해서 결과 볼륨은 예전과 같아요)
     */
    private void legacyTick(NoteSink sink, long now) {
        int[] ids = LEGACY_SOUNDS[themeId];   // 지금 테마의 [멜로디, 킥, 하이햇] (옛 9개 소리)
        int[] sc = scale;

        // 비트(드럼)가 켜져 있을 때만 킥·하이햇
        if (sink != null && beatOn) {
            if (step % 4 == 0) sink.onNote(ids[1], 1f, 1f, now);       // 매 박의 첫 칸에 킥
            if (step % 4 == 2) sink.onNote(ids[2], 1f, 0.6f, now);     // 엇박에 하이햇 (0.6)
        }

        // 짝수 칸 → 8분음표마다 멜로디 한 음
        // 주의: 소리를 안 낼 때(sink == null)도 무작위 값은 꼭 뽑아야 Random 순서가 어긋나지 않아요.
        if (step % 2 == 0) {
            // 0, 1, 2 중 하나 - 1 → -1, 0, +1 : 음계에서 한 칸 아래 / 그대로 / 한 칸 위
            degree = Math.max(0, Math.min(sc.length - 1,
                    degree + random.nextInt(3) - 1));

            // 멜로디 요약값: 이전 값 × 31 + 이번 음 (음 하나만 달라도 결과가 달라짐)
            noteCount++;
            melodyHash = melodyHash * 31 + degree;

            if (sink != null) {
                melodyLog = "#" + noteCount + " degree=" + degree + " bpm=" + bpm;
                // 반음 → 재생 속도: 2^(반음/12). 12반음 = 2배 = 한 옥타브 위
                float rate = (float) Math.pow(2, sc[degree] / 12.0);
                sink.onNote(ids[0], rate, 0.8f, now);
            }
        }

        // 다음 칸으로. 15 다음은 0
        step = (step + 1) % 16;
    }
}
