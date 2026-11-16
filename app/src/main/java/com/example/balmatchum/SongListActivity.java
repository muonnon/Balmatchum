package com.example.balmatchum;

import android.content.Intent;                  // 다른 화면을 열 때 보내는 요청서
import android.database.Cursor;                 // DB 조회 결과를 한 줄씩 읽는 객체
import android.os.Bundle;                       // 화면이 다시 만들어질 때 저장해 둔 값을 담는 상자
import android.view.View;                       // 화면 요소(버튼, 글자 등)의 기본 클래스
import android.widget.AdapterView;              // ListView, Spinner처럼 "목록"을 보여주는 뷰의 부모 클래스
import android.widget.ArrayAdapter;             // 문자열 배열을 목록(Spinner)에 연결해 주는 어댑터
import android.widget.ListView;                 // 세로로 스크롤되는 목록
import android.widget.SimpleCursorAdapter;      // DB Cursor의 열을 목록 한 줄의 TextView에 연결해 주는 어댑터
import android.widget.Spinner;                  // 펼쳐서 하나를 고르는 선택 상자
import android.widget.TextView;                 // 글자를 보여주는 뷰
import android.widget.Toast;                    // 화면 아래에 잠깐 뜨는 알림 메시지

import androidx.appcompat.app.AlertDialog;      // "삭제할까요?" 같은 확인 창
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;              // 날짜를 "10/23 (금) 13:05" 같은 글자로 바꾸는 도구
import java.util.Date;
import java.util.Locale;

/**
 * 걸음 곡 목록 화면.
 *
 * - DB(songs 테이블)에 저장된 곡을 카드 모양 ListView로 보여줘요. 위에는 "저장된 곡 N개"를 표시해요.
 * - 맨 위 상단 바("걸음 곡 기록")와 맨 아래 하단 탭(기록 선택됨)은 NavHelper로 설정해요.
 * - 위쪽 선택 상자(Spinner)로 정렬 방식을 바꿀 수 있어요.
 * - 곡을 길게 누르면 삭제 확인 창이 뜨고, [삭제]를 누르면 지워져요.
 * - 곡을 짧게 누르면 곡 상세 화면(SongDetailActivity)이 열려요.
 */
public class SongListActivity extends AppCompatActivity {

    // Spinner에 보여줄 글자와, 그 글자에 해당하는 정렬 SQL을 같은 순서(같은 칸 번호)로 맞춰 둔다.
    // 예) 사용자가 1번째 칸 "오래된순"을 고르면 SORT_ORDERS[1] = "started_at ASC"를 쓴다.
    private static final String[] SORT_LABELS = {"최신순", "오래된순", "BPM 높은순", "걸음 많은순"};
    private static final String[] SORT_ORDERS = {
            SongDbHelper.SORT_NEWEST, SongDbHelper.SORT_OLDEST,
            SongDbHelper.SORT_BPM, SongDbHelper.SORT_STEPS};

    private SongDbHelper dbHelper;          // DB 도우미
    private SimpleCursorAdapter adapter;    // DB 조회 결과(Cursor)를 ListView에 보여주는 어댑터
    private TextView tvCount;               // "저장된 곡 N개"

    // 카드 둘째 줄의 날짜 형식. 예) "10/23 (금) 13:05"  (E = 요일 한 글자, HH = 0~23시)
    // 곡마다 new 하지 않도록 필드로 한 번만 만들어 둠
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("M/d (E) HH:mm", Locale.KOREA);
    private String currentOrder = SongDbHelper.SORT_NEWEST; // 지금 선택된 정렬 (처음엔 최신순)

    /**
     * 화면이 처음 만들어질 때 호출돼요.
     * 레이아웃을 붙이고, 정렬 선택 상자와 목록(어댑터, 클릭 동작)을 준비해요.
     */
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_song_list);

        dbHelper = new SongDbHelper(this);

        // 공통 상단 바(제목만)와 하단 탭(지금 화면 = 기록). 떠나기 전에 따로 할 일은 없어서 null
        NavHelper.setupToolbar(this, "걸음 곡 기록", false);
        NavHelper.setupBottomNav(this, R.id.nav_records, null);

        tvCount = findViewById(R.id.tvCount);

        ListView lv = findViewById(R.id.lvSongs);
        // 목록이 비어 있으면 ListView 대신 tvEmpty("아직 저장된 곡이 없어요")를 자동으로 보여준다
        lv.setEmptyView(findViewById(R.id.tvEmpty));

        // ── 어댑터 만들기 ──
        // SimpleCursorAdapter(Context, 한 줄 레이아웃, Cursor, 가져올 열 이름들, 넣을 TextView id들, 옵션)
        //   from[0]="title"      → to[0]=tvTitle 에 제목을 그대로 넣음
        //   from[1]="started_at" → to[1]=tvDate  (아래 ViewBinder에서 날짜 + 걸은 시간 글자를 직접 만듦)
        //   from[2]="steps"      → to[2]=tvInfo  (아래 ViewBinder에서 걸음 수 + 평균 BPM 글자를 직접 만듦)
        // Cursor는 처음엔 null로 두고, loadSongs()에서 조회한 결과로 바꿔 끼운다.
        // 마지막 0: 자동 새로고침 기능(옛 방식)을 쓰지 않겠다는 뜻. 새로고침은 우리가 직접 한다.
        adapter = new SimpleCursorAdapter(this, R.layout.item_song, null,
                new String[]{"title", "started_at", "steps"},
                new int[]{R.id.tvTitle, R.id.tvDate, R.id.tvInfo}, 0);

        // ViewBinder: 열 값을 TextView에 넣기 직전에 끼어들어 "어떻게 보여줄지"를 직접 정하는 장치.
        // 여러 열을 합쳐 한 줄로 보여주고 싶어서 사용.
        adapter.setViewBinder((view, cursor, columnIndex) -> {
            // getColumnIndexOrThrow("열이름"): 그 열이 몇 번째 칸인지 찾기 (없으면 오류를 내서 실수를 바로 알 수 있음)
            if (view.getId() == R.id.tvDate) {
                long startedAt = cursor.getLong(cursor.getColumnIndexOrThrow("started_at"));
                int sec = cursor.getInt(cursor.getColumnIndexOrThrow("duration_sec"));
                // 예) "10/23 (금) 13:05 · 12분 30초"
                ((TextView) view).setText(dateFormat.format(new Date(startedAt)) + " · " + formatDuration(sec));
                return true;                               // true = "내가 직접 넣었으니 기본 동작은 하지 마"
            }
            if (view.getId() == R.id.tvInfo) {
                int steps = cursor.getInt(cursor.getColumnIndexOrThrow("steps"));
                int avgBpm = cursor.getInt(cursor.getColumnIndexOrThrow("avg_bpm"));
                // %,d: 천 단위 쉼표 (1240 → "1,240"). 예) "1,240걸음 · 평균 108 BPM"
                ((TextView) view).setText(String.format(Locale.KOREA, "%,d걸음 · 평균 %d BPM", steps, avgBpm));
                return true;
            }
            return false;                                  // false = "제목은 기본 방식대로 넣어줘"
        });
        lv.setAdapter(adapter);

        // ── 짧게 누르기: 곡 상세 화면으로 ──
        // id: SimpleCursorAdapter는 각 줄의 _id 열 값을 id로 넘겨준다 (그래서 테이블에 _id가 필요했음)
        // putExtra(이름표, 값): Intent에 곡 번호를 담아 보냄 → 상세 화면이 getLongExtra로 꺼내 씀
        lv.setOnItemClickListener((parent, view, position, id) -> {
            Intent intent = new Intent(this, SongDetailActivity.class);
            intent.putExtra(SongDetailActivity.EXTRA_SONG_ID, id);
            startActivity(intent);
        });

        // ── 길게 누르기: 삭제 확인 ──
        lv.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(id);
            return true; // true = 길게 누르기를 여기서 처리했음 (짧게 누르기 동작이 이어서 실행되지 않게)
        });

        // ── 정렬 선택 상자 ──
        Spinner sp = findViewById(R.id.spSort);
        // ArrayAdapter: 글자 배열을 Spinner에 연결. simple_spinner_item은 안드로이드가 기본 제공하는 한 줄 모양
        ArrayAdapter<String> sortAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, SORT_LABELS);
        // 펼쳤을 때 나오는 목록의 모양 (기본 제공)
        sortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(sortAdapter);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            /** 사용자가 정렬 방식을 고르면 호출돼요. (화면이 처음 뜰 때도 0번째 칸으로 1번 호출됨) */
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentOrder = SORT_ORDERS[position]; // 고른 칸 번호에 맞는 정렬 SQL
                loadSongs();
            }
            /** 선택이 없어졌을 때 호출되는데, 이 화면에서는 할 일이 없어요. (인터페이스라 꼭 적어야 함) */
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    /**
     * 화면이 보일 때마다 호출돼요.
     * 다른 화면에 갔다가 돌아왔을 때(예: 새 곡을 저장하고 돌아옴) 목록을 최신 상태로 다시 불러와요.
     */
    @Override protected void onResume() {
        super.onResume();
        loadSongs();
    }

    /**
     * 화면이 완전히 사라질 때 호출돼요.
     * 어댑터가 들고 있던 Cursor와 DB 연결을 닫아 메모리를 돌려줘요.
     */
    @Override protected void onDestroy() {
        super.onDestroy();
        adapter.changeCursor(null); // changeCursor: 새 Cursor(null)로 바꾸면서 예전 Cursor를 자동으로 close()
        dbHelper.close();
    }

    /**
     * 현재 정렬 방식으로 DB에서 곡을 다시 조회해 목록에 보여줘요.
     * changeCursor()는 예전 Cursor를 닫고 새 Cursor로 바꿔 주므로 메모리가 새지 않아요.
     */
    private void loadSongs() {
        Cursor c = dbHelper.querySongs(currentOrder);
        adapter.changeCursor(c);
        // getCount(): 조회 결과의 줄 수 = 저장된 곡 수
        tvCount.setText("저장된 곡 " + c.getCount() + "개");
    }

    /**
     * 초를 "N분 SS초" 글자로 바꿔요. 1분 미만이면 "N초". (와이어프레임 형식)
     * 예) 750 → "12분 30초", 545 → "9분 05초", 45 → "45초"
     * sec / 60 = 분, sec % 60 = 남은 초
     * %02d: 초를 항상 두 자리로 (5 → "05"). 분은 %d라서 앞에 0을 붙이지 않음 (9분 → "9분")
     */
    private static String formatDuration(int sec) {
        return sec >= 60
                ? String.format(Locale.KOREA, "%d분 %02d초", sec / 60, sec % 60)
                : sec + "초";
    }

    /**
     * 곡을 지우기 전에 "정말 지울까요?" 확인 창을 띄워요.
     * 실수로 길게 눌러 곡이 바로 사라지는 것을 막기 위해서예요.
     *
     * @param id 지울 곡의 _id
     */
    private void confirmDelete(long id) {
        new AlertDialog.Builder(this)
                .setTitle("곡 삭제")
                .setMessage("이 걸음 곡을 삭제할까요? 삭제하면 되돌릴 수 없어요.")
                // [삭제]를 누르면 DB에서 지우고 목록을 새로고침
                .setPositiveButton("삭제", (dialog, which) -> {
                    dbHelper.deleteSong(id);
                    loadSongs();
                    Toast.makeText(this, "삭제했어요", Toast.LENGTH_SHORT).show();
                })
                // [취소]는 null: 아무것도 하지 않고 창만 닫음
                .setNegativeButton("취소", null)
                .show();
    }
}
