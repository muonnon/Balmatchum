package com.example.balmatchum;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * 걸음 곡을 저장하는 SQLite 도우미 클래스
 * SQLiteOpenHelper를 상속하면 DB 파일 생성과 버전 관리를 안드로이드가 대신 해 준다.
 */
public class SongDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "balmatchum.db"; // 폰 안에 만들어질 DB 파일 이름
    // 테이블 구조를 바꿀 때 숫자를 올린다.
    //   1: songs 테이블
    //   2: songs에 scale_id, theme_id, beat_on 열 추가 + settings 테이블 추가
    private static final int DB_VERSION = 2;
    public static final String TABLE = "songs";            // 테이블 이름 (다른 클래스에서도 쓰려고 public)
    public static final String SETTINGS_TABLE = "settings"; // 설정값 테이블 이름

    // ───────────── 설정 이름(key)과 기본값 ─────────────
    // settings 테이블은 "이름 → 값" 한 쌍을 한 줄로 저장한다. (key-value 구조)
    // 이름을 글자로 여기저기 직접 쓰면 오타가 나기 쉬워서 상수로 한곳에 모아 둔다.
    public static final String KEY_SCALE = "scale_id";        // 스케일 번호 (MusicEngine.SCALE_NAMES 순서)
    public static final String KEY_THEME = "theme_id";        // 사운드 테마 번호 (MusicEngine.THEME_NAMES 순서)
    public static final String KEY_VOLUME = "volume";         // 볼륨 0~100
    public static final String KEY_BEAT = "beat_on";          // 비트(드럼) 켜기 1/0
    public static final String KEY_KEEP_SCREEN = "keep_screen_on"; // 걷는 동안 화면 켜두기 1/0

    public static final int DEFAULT_SCALE = 0;                // 펜타토닉
    public static final int DEFAULT_THEME = 0;                // 8bit
    public static final int DEFAULT_VOLUME = 70;              // 70%
    public static final boolean DEFAULT_BEAT = true;          // 비트 켜짐
    public static final boolean DEFAULT_KEEP_SCREEN = false;  // 화면 켜두기 꺼짐

    public SongDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    /** DB 파일이 처음 만들어질 때 딱 한 번 호출된다. 여기서 테이블을 만든다. */
    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                // _id: 곡마다 자동으로 붙는 번호. ListView에 연결하는 어댑터가 _id 이름을 요구한다.
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "title TEXT, "           // 곡 제목 (예: "11/16 13:05의 걸음 곡")
                + "started_at INTEGER, "   // 걷기 시작한 시각 (밀리초, 날짜 표시와 정렬에 사용)
                + "duration_sec INTEGER, " // 걸은 시간 (초)
                + "steps INTEGER, "        // 걸음 수
                + "avg_bpm INTEGER, "      // 평균 BPM
                + "seed INTEGER, "         // 멜로디 무작위 시드 (다시 듣기에서 같은 멜로디를 만들 때 사용)
                + "bpm_series TEXT, "      // 걸음마다의 BPM 기록 "112,113,110,..." (다시 듣기용)
                // ↓ 버전 2에서 추가된 열. 다시 듣기를 저장 당시와 똑같은 소리로 하려고 곡마다 기록한다.
                + "scale_id INTEGER DEFAULT 0, "   // 스케일 번호
                + "theme_id INTEGER DEFAULT 0, "   // 사운드 테마 번호
                + "beat_on INTEGER DEFAULT 1)");   // 비트 켜짐 1 / 꺼짐 0 (SQLite에는 boolean이 없어 정수로 저장)
        createSettingsTable(db);
    }

    /**
     * settings 테이블을 만든다. (처음 설치할 때와 버전 1 → 2로 올릴 때 둘 다 필요해서 메서드로 분리)
     * key가 PRIMARY KEY(중복 불가)라서 같은 이름의 설정은 한 줄만 존재한다.
     */
    private static void createSettingsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + SETTINGS_TABLE + " ("
                + "key TEXT PRIMARY KEY, "   // 설정 이름 (예: "volume")
                + "value TEXT)");            // 설정 값을 글자로 저장 (예: "70")
    }

    /**
     * 앱을 업데이트해서 DB_VERSION이 올라갔을 때 호출된다. (처음 설치하는 기기에서는 onCreate만 불리고 이건 안 불림)
     *
     * 예전에는 테이블을 지우고(DROP) 새로 만들었지만, 그러면 사용자가 저장한 곡이 전부 사라진다.
     * 그래서 이제는 기존 테이블에 열만 추가(ALTER TABLE ... ADD COLUMN)해서 곡을 그대로 지킨다.
     *
     * if (oldVersion < 2) 처럼 쓰는 이유:
     *  나중에 버전 3, 4가 생겨도 "버전 1인 기기는 2, 3, 4 변경을 차례로 모두 적용"할 수 있게 하려고.
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // DEFAULT 값을 주면 이미 있던 곡들의 새 열에도 그 값이 자동으로 채워진다.
            // → 기존 곡은 펜타토닉(0), 8bit(0), 비트 켜짐(1) = 예전에 실제로 들었던 소리 그대로
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN scale_id INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN theme_id INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN beat_on INTEGER DEFAULT 1");
            createSettingsTable(db);
        }
    }

    /**
     * 곡 한 개를 저장하고, 새로 붙은 _id를 돌려준다. 실패하면 -1.
     * scaleId, themeId, beatOn: 걸을 때 쓰던 소리 설정. 다시 듣기에서 같은 소리로 재생하려고 함께 저장한다.
     * (볼륨과 화면 켜두기는 "듣는 환경" 설정이라 곡에 저장하지 않는다)
     */
    public long insertSong(String title, long startedAt, int durationSec,
                           int steps, int avgBpm, long seed, String bpmSeries,
                           int scaleId, int themeId, boolean beatOn) {
        // ContentValues: "열 이름 → 값" 묶음. INSERT 문을 직접 쓰지 않고 이걸로 넣는다.
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("started_at", startedAt);
        values.put("duration_sec", durationSec);
        values.put("steps", steps);
        values.put("avg_bpm", avgBpm);
        values.put("seed", seed);
        values.put("bpm_series", bpmSeries);
        values.put("scale_id", scaleId);
        values.put("theme_id", themeId);
        values.put("beat_on", beatOn ? 1 : 0);     // true → 1, false → 0

        SQLiteDatabase db = getWritableDatabase(); // 쓰기 가능한 DB를 연다
        return db.insert(TABLE, null, values);     // songs 테이블에 한 줄 추가
    }

    // ───────────── 정렬 방식 ─────────────
    // 정렬 순서를 SQL 문장 조각("started_at DESC" 등)으로 미리 정해 둔다.
    // 사용자가 입력한 글자를 그대로 SQL에 넣으면 위험하므로(SQL 인젝션), 이 중 하나만 쓰게 한다.
    // DESC = 큰 값부터(내림차순), ASC = 작은 값부터(오름차순)
    public static final String SORT_NEWEST = "started_at DESC"; // 최신순
    public static final String SORT_OLDEST = "started_at ASC";  // 오래된순
    public static final String SORT_BPM    = "avg_bpm DESC";    // 평균 BPM 높은순
    public static final String SORT_STEPS  = "steps DESC";      // 걸음 많은순

    /**
     * 저장된 곡 전체를 정해진 순서로 가져온다.
     * Cursor는 "조회 결과 표를 한 줄씩 읽을 수 있는 손가락" 같은 객체다.
     * 다 쓴 Cursor는 꼭 close() 해야 메모리가 새지 않는다. (목록 화면에서는 어댑터가 대신 관리)
     *
     * @param orderBy 위의 SORT_ 상수 중 하나
     */
    public Cursor querySongs(String orderBy) {
        SQLiteDatabase db = getReadableDatabase(); // 읽기용 DB를 연다
        // query(테이블, 가져올 열(null=전부), 조건, 조건값, 그룹, 그룹조건, 정렬)
        // 조건이 null이면 모든 줄을 가져온다.
        return db.query(TABLE, null, null, null, null, null, orderBy);
    }

    /**
     * _id가 id인 곡 하나만 가져온다. (곡 상세 화면에서 사용)
     * 결과는 0줄(그런 곡이 없음) 또는 1줄이다. 다 읽은 뒤 Cursor를 꼭 close() 해야 한다.
     *
     * @param id 가져올 곡의 _id
     */
    public Cursor querySong(long id) {
        SQLiteDatabase db = getReadableDatabase();
        // 조건 "_id = ?" : ? 자리에 id 값이 들어간다 → _id가 id인 줄만 가져옴
        return db.query(TABLE, null, "_id = ?", new String[]{String.valueOf(id)},
                null, null, null);
    }

    /**
     * _id가 id인 곡 하나를 지운다.
     * "_id = ?" 의 ?에는 두 번째 배열의 값이 안전하게 들어간다. (값을 SQL 문장에 직접 이어 붙이지 않기 위해)
     *
     * @return 지워진 줄 수 (성공하면 1, 해당 곡이 없으면 0)
     */
    public int deleteSong(long id) {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete(TABLE, "_id = ?", new String[]{String.valueOf(id)});
    }

    // ───────────────────────────── 설정값 ─────────────────────────────

    /**
     * 설정값 하나를 읽는다.
     *
     * @param key 설정 이름 (KEY_ 상수)
     * @param def 저장된 값이 없을 때 돌려줄 기본값 (처음 실행했을 때 등)
     * @return 저장된 값 또는 기본값
     */
    public String getSetting(String key, String def) {
        SQLiteDatabase db = getReadableDatabase();
        // value 열만, key가 일치하는 줄을 가져온다
        Cursor c = db.query(SETTINGS_TABLE, new String[]{"value"}, "key = ?", new String[]{key},
                null, null, null);
        try {
            // 첫 줄이 있으면 그 값(0번 열 = value), 없으면 기본값
            return c.moveToFirst() ? c.getString(0) : def;
        } finally {
            c.close(); // 무슨 일이 있어도 Cursor는 닫는다
        }
    }

    /**
     * 설정값 하나를 저장한다. 같은 key가 이미 있으면 새 값으로 바꾼다.
     *
     * insertWithOnConflict(..., CONFLICT_REPLACE):
     *  key는 PRIMARY KEY라 같은 key로 두 번 넣으면 원래는 오류가 난다.
     *  CONFLICT_REPLACE를 주면 "이미 있으면 기존 줄을 지우고 새로 넣기" → 저장/수정을 한 줄로 처리할 수 있다.
     */
    public void setSetting(String key, String value) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("value", value);
        getWritableDatabase().insertWithOnConflict(SETTINGS_TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * 정수 설정값을 읽는다. (값은 글자로 저장되므로 숫자로 바꿔서 돌려줌)
     * 저장된 글자가 숫자가 아니면(손상 등) 앱이 죽지 않게 기본값을 돌려준다.
     */
    public int getIntSetting(String key, int def) {
        try {
            return Integer.parseInt(getSetting(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 켜기/끄기 설정값을 읽는다. "1"이면 true, "0"이면 false로 저장해 둔다. */
    public boolean getBoolSetting(String key, boolean def) {
        return getIntSetting(key, def ? 1 : 0) == 1;
    }

    /** 정수 설정값을 저장한다. */
    public void setIntSetting(String key, int value) { setSetting(key, String.valueOf(value)); }

    /** 켜기/끄기 설정값을 저장한다. true → "1", false → "0" */
    public void setBoolSetting(String key, boolean value) { setSetting(key, value ? "1" : "0"); }
}
