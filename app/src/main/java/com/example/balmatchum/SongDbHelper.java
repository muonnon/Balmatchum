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
    private static final int DB_VERSION = 1;               // 테이블 구조를 바꿀 때 숫자를 올린다
    public static final String TABLE = "songs";            // 테이블 이름 (다른 클래스에서도 쓰려고 public)

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
                + "bpm_series TEXT)");     // 걸음마다의 BPM 기록 "112,113,110,..." (다시 듣기용)
    }

    /** DB_VERSION이 올라갔을 때 호출된다. 개발 중이라 기존 테이블을 지우고 새로 만든다. */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    /** 곡 한 개를 저장하고, 새로 붙은 _id를 돌려준다. 실패하면 -1. */
    public long insertSong(String title, long startedAt, int durationSec,
                           int steps, int avgBpm, long seed, String bpmSeries) {
        // ContentValues: "열 이름 → 값" 묶음. INSERT 문을 직접 쓰지 않고 이걸로 넣는다.
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("started_at", startedAt);
        values.put("duration_sec", durationSec);
        values.put("steps", steps);
        values.put("avg_bpm", avgBpm);
        values.put("seed", seed);
        values.put("bpm_series", bpmSeries);

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
     * _id가 id인 곡 하나를 지운다.
     * "_id = ?" 의 ?에는 두 번째 배열의 값이 안전하게 들어간다. (값을 SQL 문장에 직접 이어 붙이지 않기 위해)
     *
     * @return 지워진 줄 수 (성공하면 1, 해당 곡이 없으면 0)
     */
    public int deleteSong(long id) {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete(TABLE, "_id = ?", new String[]{String.valueOf(id)});
    }
}