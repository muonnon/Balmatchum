package com.example.balmatchum;

/**
 * 앱이 쓰는 모든 소리 파일과 "테마별 소리 풀"을 한곳에 모은 표.
 *
 * 왜 따로 모았나요?
 *  - 소리를 추가하거나 테마에 어떤 소리를 넣을지 바꿀 때, 이 파일의 표만 고치면 되게 하려고.
 *  - 안드로이드 기능(SoundPool, Context)을 전혀 쓰지 않는 순수 자바 클래스라서
 *    PC에서 도는 단위 테스트(app/src/test)에서도 그대로 쓸 수 있어요.
 *
 * 소리는 "이름"(문자열)으로 가리켜요. 이름 = res/raw 파일 이름(확장자 제외).
 * 실제 재생할 때는 indexOf(이름)으로 ALL 배열의 칸 번호를 찾아 써요.
 *
 * ★ 주의: 기존 9개 소리(note, kick, hat, lofi_*, piano_*)의 파일과 LEGACY 표는 바꾸면 안 돼요.
 *   옛 곡(melody_version 0)은 이 9개 소리만으로 예전과 똑같이 재생해야 하기 때문이에요.
 */
public final class SoundPalette {

    // static 메서드와 상수만 있는 클래스라 객체를 만들 필요가 없음 → 생성자를 막아 둠
    private SoundPalette() { }

    /**
     * 소리 파일 하나의 정보.
     * final 필드만 있어서 한 번 만들면 값이 바뀌지 않아요. (여러 스레드가 같이 읽어도 안전)
     */
    public static final class Sound {
        /** 소리 이름 (= res/raw 파일 이름). 로그와 멜로디 해시에 들어감 */
        public final String name;
        /** R.raw.xxx 리소스 번호. SoundPool.load()에 넘겨요 */
        public final int resId;
        /**
         * 이 샘플을 원래 속도(1.0배)로 재생했을 때 나는 음높이 (MIDI 번호, 60 = 가운데 도 C4).
         * 음정 없는 리듬 소리는 -1.
         * 멜로디·베이스 샘플은 모두 "도(C)" 음이라서, 재생 속도 = 2^(반음/12) 계산이 어느 샘플에서나 같은 음이름을 만들어요.
         * (값은 파일을 직접 분석해서 측정한 실제 음높이)
         */
        public final int rootMidi;

        Sound(String name, int resId, int rootMidi) {
            this.name = name;
            this.resId = resId;
            this.rootMidi = rootMidi;
        }
    }

    /** 음정 없는 소리(드럼 등)의 rootMidi 값 */
    private static final int NO_PITCH = -1;

    /**
     * 앱이 불러오는 모든 소리 (SoundPool에 이 순서대로 load).
     * 새 소리를 추가할 때는 res/raw에 파일을 넣고 여기 한 줄을 추가하면 돼요. 개수 비교(isReady)도 이 배열 길이를 따라가요.
     */
    public static final Sound[] ALL = {
            // ── 기존 9개 (자체 제작, Python 합성) : 옛 곡 재생용. 절대 바꾸지 않음 ──
            new Sound("note",       R.raw.note,       60),       // 8bit 멜로디 (C4)
            new Sound("kick",       R.raw.kick,       NO_PITCH), // 8bit 킥
            new Sound("hat",        R.raw.hat,        NO_PITCH), // 8bit 하이햇
            new Sound("lofi_note",  R.raw.lofi_note,  60),       // Lo-fi 멜로디 (C4)
            new Sound("lofi_kick",  R.raw.lofi_kick,  NO_PITCH),
            new Sound("lofi_hat",   R.raw.lofi_hat,   NO_PITCH),
            new Sound("piano_note", R.raw.piano_note, 60),       // 피아노 멜로디 (C4)
            new Sound("piano_kick", R.raw.piano_kick, NO_PITCH),
            new Sound("piano_hat",  R.raw.piano_hat,  NO_PITCH),

            // ── 새 소리 12개 (VCSL, CC0) ──
            // 멜로디: 모두 도(C) 음. 실제 측정한 옥타브를 적음
            new Sound("mel_fmpiano",      R.raw.mel_fmpiano,      60), // C4
            new Sound("mel_glockenspiel", R.raw.mel_glockenspiel, 84), // C6 (글로켄슈필은 악보보다 2옥타브 높게 울리는 악기)
            new Sound("mel_marimba",      R.raw.mel_marimba,      72), // C5
            new Sound("mel_piano",        R.raw.mel_piano,        60), // C4
            // 베이스
            new Sound("bass_marimba",     R.raw.bass_marimba,     48), // C3
            // 리듬
            new Sound("v2_kick",       R.raw.v2_kick,       NO_PITCH),
            new Sound("snare",         R.raw.snare,         NO_PITCH),
            new Sound("clap",          R.raw.clap,          NO_PITCH),
            new Sound("v2_hat_closed", R.raw.v2_hat_closed, NO_PITCH),
            new Sound("hat_open",      R.raw.hat_open,      NO_PITCH),
            new Sound("shaker",        R.raw.shaker,        NO_PITCH),
            new Sound("woodblock",     R.raw.woodblock,     NO_PITCH),
    };

    // ───────────────────────────── 옛 곡(melody_version 0)용 ─────────────────────────────

    /**
     * 옛 알고리즘이 쓰는 테마별 소리. [테마 번호][0=멜로디, 1=킥, 2=하이햇]
     * 업데이트 전의 MusicEngine.THEME_RES와 똑같은 내용이에요. (바꾸면 옛 곡 소리가 달라짐)
     */
    public static final String[][] LEGACY = {
            {"note", "kick", "hat"},                    // 0 = 8bit
            {"lofi_note", "lofi_kick", "lofi_hat"},     // 1 = Lo-fi
            {"piano_note", "piano_kick", "piano_hat"},  // 2 = 피아노
    };

    // ───────────────────────────── 새 곡(melody_version 1)용 테마별 소리 풀 ─────────────────────────────
    // 바깥 배열의 칸 번호 = theme_id (0 8bit, 1 Lo-fi, 2 피아노)
    // 곡을 시작할 때 SongComposer가 이 풀에서 "이 곡에 쓸 소리"를 시드로 골라요.

    /** 멜로디 음색 풀. 곡마다 이 중 1~2개를 골라 씀 */
    public static final String[][] MELODY_POOL = {
            {"note", "mel_glockenspiel"},                     // 8bit
            {"lofi_note", "mel_fmpiano", "mel_marimba"},      // Lo-fi
            {"piano_note", "mel_piano", "mel_glockenspiel"},  // 피아노
    };

    /** 베이스: 모든 테마 공통 */
    public static final String BASS = "bass_marimba";

    /** 킥 풀: 테마의 기존 킥 + 새 킥. 마디마다 하나를 고름 */
    public static final String[][] KICK_POOL = {
            {"kick", "v2_kick"},
            {"lofi_kick", "v2_kick"},
            {"piano_kick", "v2_kick"},
    };

    /** 닫힌 하이햇 풀: 테마의 기존 하이햇 + 새 하이햇. 마디마다 하나를 고름 */
    public static final String[][] HAT_POOL = {
            {"hat", "v2_hat_closed"},
            {"lofi_hat", "v2_hat_closed"},
            {"piano_hat", "v2_hat_closed"},
    };

    /** 약박(2·4박) 강조 소리 풀: 모든 테마 공통. 마디마다 하나를 고름 */
    public static final String[] BACKBEAT_POOL = {"snare", "clap"};

    /** 열린 하이햇: 마디 끝 엇박에 가끔 넣어 "치익" 하고 길게 울림 */
    public static final String OPEN_HAT = "hat_open";

    /** 장식 타악기 풀: 모든 테마 공통. 마디마다 하나를 고름 */
    public static final String[] PERC_POOL = {"shaker", "woodblock"};

    /**
     * 이름으로 ALL 배열의 칸 번호를 찾아요.
     * 소리가 21개뿐이라 처음부터 하나씩 비교해도 충분히 빨라요. (곡을 시작할 때만 부름)
     *
     * @throws IllegalArgumentException 표에 없는 이름이면 (오타를 바로 알아차리게 일부러 앱을 멈춤)
     */
    public static int indexOf(String name) {
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].name.equals(name)) return i;
        }
        throw new IllegalArgumentException("SoundPalette에 없는 소리: " + name);
    }
}
