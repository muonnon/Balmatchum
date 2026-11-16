package com.example.balmatchum;

import android.content.ContentValues;
import android.content.Context;
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
}