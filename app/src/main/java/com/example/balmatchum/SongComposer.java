package com.example.balmatchum;

import java.util.Random;   // 시드를 넣으면 항상 같은 순서로 숫자를 내는 의사난수 생성기

/**
 * 새 곡(melody_version = 1)의 작곡기.
 * 16분음표 한 칸마다 step()을 부르면 "이번 칸에 낼 소리들(소리 번호, 재생 속도, 볼륨)"을 알려줘요.
 * 실제 재생(SoundPool)은 MusicEngine이 하고, 이 클래스는 "무엇을 낼지"만 정해요.
 * 안드로이드 기능을 쓰지 않는 순수 자바라서 PC 단위 테스트로 결과를 검사할 수 있어요.
 *
 * ───────── 같은 곡이 다시 똑같이 나오게 하는 방법 (시드 기반 의사난수) ─────────
 *  곡의 seed 하나로 용도별 Random을 3개 만들어요. (seed ^ 상수: 비트 XOR로 서로 다른 시드를 만듦)
 *   - personalityRandom : 곡 시작 때 한 번, 이 곡의 "개성"(음색, 프레이즈 길이, 리듬 패턴, 코드 진행)을 정함
 *   - melodyRandom      : 프레이즈(짧은 멜로디 조각)를 만들거나 변형할 때만 사용
 *   - rhythmRandom      : 마디가 시작될 때마다 이번 마디의 리듬 소리(킥/스네어/하이햇…)를 고름
 *  용도별로 나눈 이유: 한 Random을 같이 쓰면 리듬 쪽에서 한 번 더 뽑는 순간 멜로디 쪽 숫자가 전부 밀려요.
 *
 *  ★ 호출 횟수 고정 규칙
 *   - melodyRandom은 "프레이즈를 만들 때 음 하나당 3번 + 1번", "변형할 때 3번"만 불러요.
 *     BPM, 비트 on/off, 볼륨, 화면 설정과 상관없이 항상 같은 순서·같은 횟수예요.
 *   - rhythmRandom은 마디마다 5번. 비트를 꺼도 똑같이 뽑아요. (드럼 소리를 "내지 않을" 뿐)
 *   - 이 클래스는 비트 on/off를 아예 몰라요. 드럼 이벤트에 표시(isDrum)만 해 두고, 끄는 건 MusicEngine이 해요.
 *
 * ───────── 음악 구조 ─────────
 *  - 코드 진행: 코드 하나가 4마디 동안 이어지고 4마디마다 다음 코드로 바뀜 (예: I-vi-IV-V → 16마디에 한 바퀴).
 *              베이스는 매 마디 첫 박에 지금 코드의 뿌리음을 침
 *  - 멜로디: 4~8음짜리 프레이즈 A를 만들고 A → A' → B → A' 순서로 반복 (A' = A를 조금 바꾼 것, B = 새 프레이즈)
 *           강박(박의 첫 칸)에 오는 음은 지금 코드의 구성음으로 맞춰서 코드와 잘 어울리게 함
 *  - 음역: 기준 도에서 아래로 12반음 ~ 위로 12반음 (두 옥타브). 재생 속도 = 2^(반음/12) → 0.5 ~ 2.0배
 *  - 걸음 속도: 느리면 음 사이가 넓고, 빠르면 16분음표(1칸 간격)도 섞여 촘촘해짐
 *  - 강약: 박 첫 음은 크게, 약한 자리는 작게. 가끔 쉼표
 */
public final class SongComposer {

    /** 이 작곡기가 만드는 곡의 버전. DB의 melody_version 열에 저장돼요 */
    public static final int VERSION = 1;

    // ───────────────────────────── 시드 섞는 상수 ─────────────────────────────
    // seed ^ 상수 로 용도별 시드를 만들어요. 상수는 아무 값이나 괜찮지만, 한 번 정하면 절대 바꾸면 안 돼요.
    // (바꾸면 이미 저장된 새 곡들의 소리·멜로디가 전부 달라짐)
    private static final long PERSONALITY_SALT = 0x5EED_0001_C0FF_EE01L;
    private static final long MELODY_SALT      = 0x5EED_0002_BEEF_CA02L;
    private static final long RHYTHM_SALT      = 0x5EED_0003_D00D_FA03L;

    // ───────────────────────────── 코드 진행 표 ─────────────────────────────
    // 숫자 = 코드 뿌리음이 도(C)에서 몇 반음 위인지. 4개가 BARS_PER_CHORD(4)마디씩 차례로 나옴.
    // 밝은 음계(펜타토닉, 장음계)용과 어두운 음계(마이너 펜타토닉)용을 따로 둠.
    // 두 표는 항상 같은 개수(4개)여야 해요 → 어떤 음계든 personalityRandom을 똑같이 한 번만 뽑음
    private static final int[][] MAJOR_PROGRESSIONS = {
            {0, 9, 5, 7},   // I - vi - IV - V   (도 - 라 - 파 - 솔)
            {0, 7, 9, 5},   // I - V - vi - IV
            {9, 5, 0, 7},   // vi - IV - I - V
            {0, 5, 0, 7},   // I - IV - I - V
    };
    private static final int[][] MINOR_PROGRESSIONS = {
            {0, 8, 10, 0},  // i - VI - VII - i  (도 - 라♭ - 시♭ - 도)
            {0, 5, 10, 0},  // i - iv - VII - i
            {0, 10, 8, 10}, // i - VII - VI - VII
            {0, 5, 7, 0},   // i - iv - v - i
    };
    /**
     * 코드 하나가 몇 마디 동안 유지되는지.
     * 4 = 4마디마다 뿌리음이 바뀌고, 코드 4개를 다 돌면 16마디. (1로 바꾸면 마디마다 코드가 바뀜)
     * 같은 코드가 길게 이어지면 멜로디가 한 코드 위에서 프레이즈를 충분히 펼친 뒤 다음 코드로 넘어가요.
     * ★ 이 값을 바꾸면 같은 시드라도 코드(= 강박 멜로디 음, 베이스 음)가 달라져요.
     */
    private static final int BARS_PER_CHORD = 4;

    // 코드 구성음을 구할 때 쓰는 "부모 음계" (7음). 3도씩 쌓아서 3화음을 만들어요.
    private static final int[] MAJOR_PARENT = {0, 2, 4, 5, 7, 9, 11};  // 장음계
    private static final int[] MINOR_PARENT = {0, 2, 3, 5, 7, 8, 10};  // 자연단음계

    // ───────────────────────────── 리듬 패턴 표 ─────────────────────────────
    // 16칸(1마디)을 글자 16개로 표시. 'x' = 그 칸에 소리 냄, '.' = 쉼
    // [패턴 번호][0=킥, 1=약박(스네어/클랩), 2=닫힌 하이햇, 3=장식 타악기(셰이커/우드블록)]
    private static final String[][] PATTERNS = {
            // 0: 기본 - 1·3박 킥, 2·4박 스네어, 엇박 하이햇 (예전 소리와 가장 비슷)
            {"x.......x.......", "....x.......x...", "..x...x...x...x.", "................"},
            // 1: 포 온 더 플로어 - 매 박 킥, 걷는 느낌이 가장 또렷함
            {"x...x...x...x...", "....x.......x...", "..x...x...x...x.", ".......x.......x"},
            // 2: 하프타임 - 킥과 스네어가 드문드문, 하이햇은 8분음표마다 → 느긋한 느낌
            {"x.........x.....", "........x.......", "x.x.x.x.x.x.x.x.", "......x.......x."},
            // 3: 그루브 - 엇갈린 킥 + 16분 셰이커 → 통통 튀는 느낌
            {"x......x..x.....", "....x.......x...", "x.x.x.x.x.x.x.x.", ".x.x.x.x.x.x.x.x"},
    };
    private static final int KICK = 0, BACKBEAT = 1, HAT = 2, PERC = 3;   // PATTERNS 안쪽 칸 번호
    private static final int OPEN_HAT_STEP = 14;   // 열린 하이햇을 넣는 칸 (마디의 마지막 엇박)

    // 소리별 기본 볼륨 (0.0 ~ 1.0). 최종 볼륨 = 이 값 × 강약 × 전체 볼륨(MusicEngine)
    private static final float VOL_KICK = 1.0f, VOL_BACKBEAT = 0.75f, VOL_HAT = 0.5f,
            VOL_OPEN_HAT = 0.45f, VOL_PERC = 0.45f, VOL_BASS = 0.7f, VOL_MELODY = 0.8f;

    // ───────────────────────────── 걸음 속도별 음 간격 ─────────────────────────────
    // 프레이즈의 각 음은 "길이 종류"(0 짧게, 1 보통, 2 길게)를 가지고, 실제 칸 수는 걸음 속도로 정해요.
    // [속도 단계][길이 종류] = 다음 음까지의 칸 수 (1칸 = 16분음표, 2칸 = 8분음표, 4칸 = 4분음표)
    private static final int SLOW_BPM = 95;    // 이보다 느리면 "느림"
    private static final int FAST_BPM = 125;   // 이 이상이면 "빠름"
    private static final int[][] GAP_STEPS = {
            {2, 4, 4},   // 느림: 대부분 4분음표 → 음이 듬성듬성
            {2, 2, 4},   // 보통: 대부분 8분음표 (예전과 같은 간격), 긴 음만 4칸
            {1, 2, 2},   // 빠름: 16분음표(1칸)도 섞여 촘촘함
    };

    // ───────────────────────────── 곡 개성 (곡 시작 때 한 번 정함) ─────────────────────────────
    private final int[] timbre = new int[2];   // 멜로디 음색 2칸 (SoundPalette.ALL 번호). 1개만 쓰는 곡은 두 칸이 같음
    private final int phraseLen;               // 프레이즈 음 개수 (4 ~ 8)
    private final int pattern;                 // 리듬 패턴 번호 (PATTERNS의 칸)
    private final int[] progression;           // 코드 진행 (뿌리음 4개)

    // 테마의 소리 풀을 번호로 바꿔 둔 것 (매 마디 이름 검색을 하지 않으려고 미리 계산)
    private final int[] kickPool, hatPool, backbeatPool, percPool;
    private final int openHat, bass;

    // ───────────────────────────── 음계 정보 ─────────────────────────────
    private final int[] ladder;                // 두 옥타브 음계 (반음, -12 ~ +12). 예) 펜타토닉: -12 -10 -8 -5 -3 0 2 4 7 9 12
    private final int center;                  // ladder에서 0(기준 도)의 칸 번호
    private final boolean[][] chordTones;      // [진행의 몇 번째 코드][음이름 0~11] = 그 코드의 구성음인지

    // ───────────────────────────── 난수 ─────────────────────────────
    private final Random melodyRandom;
    private final Random rhythmRandom;

    // ───────────────────────────── 프레이즈 상태 ─────────────────────────────
    // 프레이즈 = 음 phraseLen개. 각 음은 [움직임(음계 몇 칸 위/아래), 길이 종류, 쉼표 여부]
    private final int[] aMove, aLen;  private final boolean[] aRest;   // 원본 A
    private int aStart;                                                 // A 첫 음의 위치(가운데에서 몇 칸)
    private final int[] curMove, curLen; private final boolean[] curRest; // 지금 연주 중인 프레이즈
    private int curStart;
    private int curTimbre;                     // 지금 프레이즈의 멜로디 음색
    private int section = -1;                  // 지금 몇 번째 프레이즈인지 (0부터). section % 4 = A, A', B, A'
    private int noteIndex;                     // 지금 프레이즈에서 다음에 낼 음 번호
    private int wait = 0;                      // 다음 음까지 남은 칸 수 (0이면 이번 칸에 음을 냄)
    private int prevIdx;                       // 직전 음의 ladder 칸 번호

    // ───────────────────────────── 마디 상태 ─────────────────────────────
    private int step = 0;                      // 마디 안의 칸 번호 (0 ~ 15)
    private int bar = 0;                       // 몇 번째 마디인지 (0부터)
    private int barKick, barBackbeat, barHat, barPerc;  // 이번 마디에 쓸 리듬 소리 (SoundPalette 번호)
    private boolean barOpenHat;                // 이번 마디 끝에 열린 하이햇을 넣을지

    // ───────────────────────────── 출력 (step()이 채움) ─────────────────────────────
    // step()마다 새 객체를 만들지 않으려고 배열을 재사용해요. (1초에 수십 번 불리므로)
    private static final int MAX_EVENTS = 8;
    /** 이번 칸에 낼 소리 개수 */
    public int count;
    /** [i]번째 소리의 SoundPalette 번호 */
    public final int[] sound = new int[MAX_EVENTS];
    /** [i]번째 소리의 재생 속도 (0.5 ~ 2.0) */
    public final float[] rate = new float[MAX_EVENTS];
    /** [i]번째 소리의 볼륨 (0.0 ~ 1.0, 전체 볼륨을 곱하기 전) */
    public final float[] volume = new float[MAX_EVENTS];
    /** [i]번째 소리가 드럼인지 (비트를 끄면 MusicEngine이 이 소리만 건너뜀) */
    public final boolean[] isDrum = new boolean[MAX_EVENTS];

    // 로그·검증용: 이번 칸에 멜로디 음(또는 쉼표)이 시작됐으면 그 정보
    /** 이번 칸에 멜로디 음(쉼표 포함)이 시작됐는지 */
    public boolean melodyEvent;
    /** 그 음의 소리 이름 (쉼표면 "rest") */
    public String melodyName;
    /** 그 음의 높이 (기준 도에서 몇 반음, -12 ~ +12) */
    public int melodySemi;
    /** 그 음의 길이 (다음 음까지 칸 수) */
    public int melodyLen;
    /** 그 음의 강약 (0.0 ~ 1.0) */
    public float melodyVel;

    // 멜로디·리듬 요약값(해시). 같은 곡을 두 번 재생해서 같으면 똑같이 연주된 것
    private int noteCount = 0;
    private long melodyHash = 0;
    private long rhythmHash = 0;

    /**
     * 곡을 시작할 때 만들어요. (곡의 처음으로 되감을 때도 새로 만듦)
     *
     * @param seed    곡의 시드 (DB의 seed)
     * @param themeId 사운드 테마 번호 (0 8bit, 1 Lo-fi, 2 피아노)
     * @param scale   음계 (0 ~ 12 반음 배열, MusicEngine.SCALES 중 하나)
     */
    public SongComposer(long seed, int themeId, int[] scale) {
        // ── 용도별 Random 3개 ──
        Random personalityRandom = new Random(seed ^ PERSONALITY_SALT);
        melodyRandom = new Random(seed ^ MELODY_SALT);
        rhythmRandom = new Random(seed ^ RHYTHM_SALT);

        // ── 테마의 소리 풀을 번호로 ──
        kickPool = indices(SoundPalette.KICK_POOL[themeId]);
        hatPool = indices(SoundPalette.HAT_POOL[themeId]);
        backbeatPool = indices(SoundPalette.BACKBEAT_POOL);
        percPool = indices(SoundPalette.PERC_POOL);
        openHat = SoundPalette.indexOf(SoundPalette.OPEN_HAT);
        bass = SoundPalette.indexOf(SoundPalette.BASS);
        int[] melodyPool = indices(SoundPalette.MELODY_POOL[themeId]);

        // ── 곡 개성 정하기 (personalityRandom, 항상 이 순서로 6번) ──
        int timbreCount = 1 + personalityRandom.nextInt(2);                  // 1. 음색 1개 또는 2개
        int t0 = personalityRandom.nextInt(melodyPool.length);               // 2. 첫 음색
        int t1 = (t0 + 1 + personalityRandom.nextInt(melodyPool.length - 1)) // 3. 두 번째 음색 (첫 음색과 다른 것)
                % melodyPool.length;                                          //    1개만 쓰는 곡도 순서를 지키려고 항상 뽑음
        timbre[0] = melodyPool[t0];
        timbre[1] = timbreCount == 2 ? melodyPool[t1] : melodyPool[t0];
        phraseLen = 4 + personalityRandom.nextInt(5);                        // 4. 프레이즈 길이 4 ~ 8음
        pattern = personalityRandom.nextInt(PATTERNS.length);                // 5. 리듬 패턴
        boolean minor = contains(scale, 3);   // 단3도(미♭)가 있으면 어두운 음계 → 단조 코드 진행
        int[][] progs = minor ? MINOR_PROGRESSIONS : MAJOR_PROGRESSIONS;
        progression = progs[personalityRandom.nextInt(progs.length)];        // 6. 코드 진행

        // ── 두 옥타브 음계 만들기 ──
        // 아래 옥타브 = 원래 음계에서 12를 뺀 것 (마지막 12는 빼면 0이라 위쪽과 겹치므로 제외)
        // 예) {0,2,4,7,9,12} → {-12,-10,-8,-5,-3} + {0,2,4,7,9,12}
        ladder = new int[scale.length * 2 - 1];
        for (int i = 0; i < scale.length - 1; i++) ladder[i] = scale[i] - 12;
        System.arraycopy(scale, 0, ladder, scale.length - 1, scale.length);
        center = scale.length - 1;            // 0(기준 도)이 있는 칸

        // ── 코드마다 구성음 표 만들기 ──
        int[] parent = minor ? MINOR_PARENT : MAJOR_PARENT;
        chordTones = new boolean[progression.length][12];
        for (int c = 0; c < progression.length; c++) {
            int k = indexIn(parent, progression[c]);   // 부모 음계에서 뿌리음의 위치
            // 3화음 = 뿌리음 + 음계 2칸 위(3도) + 4칸 위(5도). % 7: 음계 끝을 넘으면 처음으로 돌아감
            chordTones[c][parent[k]] = true;
            chordTones[c][parent[(k + 2) % 7]] = true;
            chordTones[c][parent[(k + 4) % 7]] = true;
        }

        // ── 프레이즈 배열 준비 + 원본 A 만들기 (melodyRandom 첫 사용) ──
        aMove = new int[phraseLen]; aLen = new int[phraseLen]; aRest = new boolean[phraseLen];
        curMove = new int[phraseLen]; curLen = new int[phraseLen]; curRest = new boolean[phraseLen];
        aStart = generatePhrase(aMove, aLen, aRest);
        noteIndex = phraseLen;                // "프레이즈가 끝난 상태"로 시작 → 첫 박에서 A가 시작됨
        prevIdx = center;
    }

    // ───────────────────────────── 한 칸 진행 ─────────────────────────────

    /**
     * 16분음표 한 칸을 진행하고, 이번 칸에 낼 소리를 count / sound / rate / volume / isDrum에 채워요.
     * 소리를 실제로 낼지(seekTo처럼 조용히 건너뛸지)와 상관없이 항상 불러야 다음 결과가 맞아요.
     *
     * @param bpm 지금 박자 (음 간격을 정하는 데만 씀. 난수 호출 횟수에는 영향 없음)
     */
    public void step(int bpm) {
        count = 0;
        melodyEvent = false;

        if (step == 0) startBar();            // 마디 첫 칸: 리듬 소리 고르기 + 베이스
        playDrums();
        playMelody(bpm);

        // 다음 칸으로. 15 다음은 0이고, 이때 마디 번호도 하나 올림
        step = (step + 1) % 16;
        if (step == 0) bar++;
    }

    /**
     * 마디가 시작될 때: 이번 마디의 리듬 소리를 rhythmRandom으로 고르고, 베이스로 코드 뿌리음을 쳐요.
     * rhythmRandom은 항상 5번 뽑아요. (비트가 꺼져 있어도 → 켜고 끈 곡의 리듬 선택이 같음)
     */
    private void startBar() {
        barKick = kickPool[rhythmRandom.nextInt(kickPool.length)];             // 1. 킥
        barBackbeat = backbeatPool[rhythmRandom.nextInt(backbeatPool.length)]; // 2. 스네어 / 클랩
        barHat = hatPool[rhythmRandom.nextInt(hatPool.length)];                // 3. 닫힌 하이햇
        barOpenHat = rhythmRandom.nextInt(4) == 0;                             // 4. 마디 끝 열린 하이햇 (4마디에 1번꼴)
        barPerc = percPool[rhythmRandom.nextInt(percPool.length)];             // 5. 셰이커 / 우드블록

        // 베이스: 지금 코드의 뿌리음. 마림바 베이스(C3)를 재생 속도로 음높이를 바꿈.
        // 뿌리음 0~11을 -5 ~ +6 반음으로 옮겨서(7 이상이면 한 옥타브 내림) 재생 속도가 0.75 ~ 1.41배 안에 들게 함
        // → 0.5 ~ 2.0 제한을 넉넉히 지키고, 베이스가 너무 높아지지 않음
        int root = progression[chordIndex()];
        int semi = root > 6 ? root - 12 : root;
        add(bass, rateOf(semi), VOL_BASS, false);
        rhythmHash = rhythmHash * 31 + bass * 64 + semi + 12;   // 베이스도 리듬 요약에 포함
    }

    /** 패턴표에서 이번 칸에 'x'인 드럼을 출력에 추가해요. 난수는 쓰지 않아요. */
    private void playDrums() {
        String[] p = PATTERNS[pattern];
        if (p[KICK].charAt(step) == 'x') addDrum(barKick, VOL_KICK);
        if (p[BACKBEAT].charAt(step) == 'x') addDrum(barBackbeat, VOL_BACKBEAT);
        if (step == OPEN_HAT_STEP && barOpenHat) {
            addDrum(openHat, VOL_OPEN_HAT);    // 이 칸은 닫힌 하이햇 대신 열린 하이햇
        } else if (p[HAT].charAt(step) == 'x') {
            addDrum(barHat, VOL_HAT * accent(step));
        }
        if (p[PERC].charAt(step) == 'x') addDrum(barPerc, VOL_PERC * accent(step));
    }

    /**
     * 멜로디: 다음 음 차례가 됐으면 프레이즈의 다음 음(또는 쉼표)을 내요.
     * 프레이즈가 끝났으면 다음 박 첫 칸을 기다렸다가 새 프레이즈를 시작해요.
     */
    private void playMelody(int bpm) {
        if (wait > 0) { wait--; return; }      // 아직 이전 음이 울리는 중

        if (noteIndex >= phraseLen) {          // 프레이즈가 끝남
            if (step % 4 != 0) return;         // 새 프레이즈는 박의 첫 칸에서만 시작 (마디 흐름과 맞추려고)
            startSection();
        }

        int i = noteIndex++;
        // 이번 음의 길이(칸 수): 프레이즈의 길이 종류 × 걸음 속도 단계
        int gap = GAP_STEPS[speedLevel(bpm)][curLen[i]];
        wait = gap - 1;                        // 이번 칸을 포함해 gap칸 뒤에 다음 음

        melodyEvent = true;
        melodyLen = gap;
        if (curRest[i]) {                      // 쉼표: 소리는 안 내고 위치(prevIdx)도 그대로
            melodyName = "rest";
            melodySemi = 0;
            melodyVel = 0f;
            hashNote(Integer.MIN_VALUE, gap, 0);
            return;
        }

        // ── 음높이 정하기 ──
        int idx;
        if (i == 0) {
            // 프레이즈 첫 음: 가운데 근처의 시작 위치에서 가장 가까운 코드 구성음
            idx = nearestChordTone(center + curStart);
        } else {
            // 이전 음에서 움직임만큼 이동. 음역(0 ~ 끝)을 벗어나면 반대 방향으로 움직임(되튕기기)
            idx = prevIdx + curMove[i];
            if (idx < 0 || idx >= ladder.length) idx = prevIdx - curMove[i];
            idx = Math.max(0, Math.min(ladder.length - 1, idx));
            // 박의 첫 칸(강박)에 오는 음은 코드 구성음으로 맞춤 → 코드와 부딪히지 않음
            if (step % 4 == 0) idx = nearestChordTone(idx);
        }
        prevIdx = idx;

        int semi = ladder[idx];
        float vel = accent(step);
        melodyName = SoundPalette.ALL[curTimbre].name;
        melodySemi = semi;
        melodyVel = vel;
        add(curTimbre, rateOf(semi), VOL_MELODY * vel, false);
        hashNote(semi, gap, melodyName.hashCode());
    }

    /**
     * 다음 프레이즈(구간)를 준비해요. 4개가 한 묶음: A → A' → B → A' → (다시 A …)
     * melodyRandom 사용 횟수: A = 0번(이미 있음), A' = 3번, B = 1 + 3 × 음 개수 → 항상 같은 순서
     */
    private void startSection() {
        section++;
        int kind = section % 4;
        if (kind == 2) {
            // B: 새 프레이즈. 시작 위치를 2칸 높여서 A와 다른 느낌(대비)을 줌. 두 번째 음색으로 연주
            curStart = generatePhrase(curMove, curLen, curRest) + 2;
            curTimbre = timbre[1];
        } else {
            // A 또는 A': 원본 A를 복사
            System.arraycopy(aMove, 0, curMove, 0, phraseLen);
            System.arraycopy(aLen, 0, curLen, 0, phraseLen);
            System.arraycopy(aRest, 0, curRest, 0, phraseLen);
            curStart = aStart;
            curTimbre = timbre[0];
            if (kind == 1 || kind == 3) varyPhrase();   // A' = 끝부분을 조금 바꿈
        }
        noteIndex = 0;
    }

    /**
     * 프레이즈 하나를 새로 만들어요. melodyRandom을 정확히 1 + 3 × phraseLen 번 써요.
     *
     * @return 첫 음의 시작 위치 (가운데에서 -2 ~ +2 칸)
     */
    private int generatePhrase(int[] move, int[] len, boolean[] rest) {
        int start = melodyRandom.nextInt(5) - 2;                 // 시작 위치
        for (int i = 0; i < phraseLen; i++) {
            // 움직임: 음계 한 칸 이동이 가장 많고, 가끔 제자리나 두 칸 도약 (사람이 부르기 쉬운 선율)
            move[i] = MOVES[melodyRandom.nextInt(MOVES.length)];
            // 길이 종류: 짧게 25%, 보통 50%, 길게 25%
            len[i] = LEN_CODES[melodyRandom.nextInt(LEN_CODES.length)];
            // 쉼표: 10%. 단 첫 음은 쉬지 않음 (그래도 순서를 지키려고 숫자는 항상 뽑음)
            rest[i] = melodyRandom.nextInt(10) == 0 && i > 0;
        }
        return start;
    }
    private static final int[] MOVES = {-2, -1, -1, 0, 0, 1, 1, 2};
    private static final int[] LEN_CODES = {0, 1, 1, 2};

    /**
     * A' 만들기: 지금 프레이즈(A의 복사본)의 마지막 두 음 중 하나를 조금 바꿔요. melodyRandom을 정확히 3번 써요.
     * 처음은 같고 끝만 살짝 달라서 "같은 노래인데 조금 다르게" 들려요.
     */
    private void varyPhrase() {
        int pos = phraseLen - 1 - melodyRandom.nextInt(2);       // 1. 바꿀 음: 마지막 또는 그 앞
        int delta = melodyRandom.nextInt(2) == 0 ? -1 : 1;       // 2. 한 칸 아래 또는 위로
        boolean toggleLen = melodyRandom.nextInt(3) == 0;        // 3. 길이도 바꿀지 (1/3)
        curMove[pos] += delta;
        if (toggleLen) curLen[pos] = curLen[pos] == 2 ? 1 : 2;   // 보통 ↔ 길게
    }

    // ───────────────────────────── 도우미 ─────────────────────────────

    /**
     * 지금 마디가 코드 진행의 몇 번째 코드인지 (0 ~ 3)
     * 예) BARS_PER_CHORD = 4: 마디 0~3 → 0번 코드, 4~7 → 1번, 8~11 → 2번, 12~15 → 3번, 16~ → 다시 0번
     */
    private int chordIndex() { return (bar / BARS_PER_CHORD) % progression.length; }

    /**
     * ladder의 idx 칸에서 가장 가까운 "지금 코드의 구성음" 칸을 찾아요.
     * 같은 거리면 가운데(기준 도)에 가까운 쪽 → 멜로디가 음역 끝으로 쏠리지 않음.
     */
    private int nearestChordTone(int idx) {
        idx = Math.max(0, Math.min(ladder.length - 1, idx));
        boolean[] tones = chordTones[chordIndex()];
        for (int d = 0; d < ladder.length; d++) {
            // 가운데 쪽 방향을 먼저 검사
            int toward = idx > center ? idx - d : idx + d;
            int away = idx > center ? idx + d : idx - d;
            if (toward >= 0 && toward < ladder.length && tones[pitchClass(ladder[toward])]) return toward;
            if (away >= 0 && away < ladder.length && tones[pitchClass(ladder[away])]) return away;
        }
        return idx;   // 음계에 구성음이 하나도 없으면(일어나지 않음) 그대로
    }

    /** 반음 수 → 음이름 번호 0~11 (도=0). 음수도 처리: -3 → 9(라) */
    private static int pitchClass(int semi) { return ((semi % 12) + 12) % 12; }

    /**
     * 반음 수 → SoundPool 재생 속도. 2배 빠르게 재생하면 한 옥타브(12반음) 높게 들려요.
     * 멜로디·베이스 샘플은 모두 "도" 음이라, 어떤 샘플이든 semi가 같으면 같은 음이름이 나요.
     * (샘플마다 옥타브(C4, C5, C6…)는 달라서 음색마다 높이가 다르게 들리는 건 자연스러운 것)
     * semi가 -12 ~ +12 안이므로 결과는 항상 0.5 ~ 2.0 (SoundPool이 허용하는 범위)
     */
    static float rateOf(int semi) { return (float) Math.pow(2, semi / 12.0); }

    /** 걸음 속도 단계: 0 느림, 1 보통, 2 빠름 */
    private static int speedLevel(int bpm) { return bpm < SLOW_BPM ? 0 : bpm >= FAST_BPM ? 2 : 1; }

    /**
     * 칸 위치에 따른 강약.
     * 마디 첫 칸(1박) 가장 크게 → 다른 박의 첫 칸 → 8분음표 엇박 → 16분음표 자리 순으로 작게
     */
    private static float accent(int step) {
        if (step == 0) return 1.0f;
        if (step % 4 == 0) return 0.85f;
        if (step % 2 == 0) return 0.65f;
        return 0.5f;
    }

    /** 출력 배열에 소리 하나를 추가 */
    private void add(int snd, float r, float vol, boolean drum) {
        sound[count] = snd;
        rate[count] = r;
        volume[count] = vol;
        isDrum[count] = drum;
        count++;
    }

    /** 드럼 소리 추가 (재생 속도 1.0 = 원래 소리) + 리듬 요약값 갱신 */
    private void addDrum(int snd, float vol) {
        add(snd, 1f, vol, true);
        rhythmHash = rhythmHash * 31 + snd * 16 + step;
    }

    /**
     * 멜로디 요약값 갱신: 음높이 · 길이 · 소리 이름을 모두 섞어요.
     * 셋 중 하나만 달라도, 순서만 달라도 결과가 달라져요. (쉼표는 높이 자리에 특별한 값)
     */
    private void hashNote(int semi, int gap, int nameHash) {
        noteCount++;
        melodyHash = melodyHash * 31 + semi;
        melodyHash = melodyHash * 31 + gap;
        melodyHash = melodyHash * 31 + nameHash;
    }

    /** 지금까지 나온 멜로디 음(쉼표 포함) 개수 */
    public int getNoteCount() { return noteCount; }
    /** 멜로디 요약값 (음높이·길이·소리 이름) */
    public long getMelodyHash() { return melodyHash; }
    /** 리듬 요약값 (드럼 소리·위치, 베이스) */
    public long getRhythmHash() { return rhythmHash; }
    /** 이 곡에서 고른 멜로디 음색 이름들 (로그용) */
    public String describe() {
        return "timbre=" + SoundPalette.ALL[timbre[0]].name + "," + SoundPalette.ALL[timbre[1]].name
                + " phraseLen=" + phraseLen + " pattern=" + pattern
                + " progression=" + java.util.Arrays.toString(progression);
    }

    /** 이름 배열 → SoundPalette 번호 배열 */
    private static int[] indices(String[] names) {
        int[] r = new int[names.length];
        for (int i = 0; i < names.length; i++) r[i] = SoundPalette.indexOf(names[i]);
        return r;
    }

    private static boolean contains(int[] a, int v) { return indexIn(a, v) >= 0; }

    private static int indexIn(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }
}
