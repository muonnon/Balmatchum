package com.example.balmatchum;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;              // Spinner 선택 이벤트를 받는 데 필요
import android.widget.ArrayAdapter;             // 글자 배열을 Spinner에 연결해 주는 어댑터
import android.widget.RadioGroup;               // 라디오 버튼 묶음 (하나만 선택)
import android.widget.SeekBar;                  // 끌어서 값을 고르는 막대 (볼륨)
import android.widget.Spinner;                  // 펼쳐서 하나를 고르는 선택 상자 (스케일)
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;      // "초기화할까요?" 확인 창
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;  // 켜기/끄기 스위치

/**
 * 설정 화면.
 *
 * - 스케일(음계), 사운드 테마, 볼륨, 비트 켜기, 걷는 동안 화면 켜두기를 바꿔요.
 * - 저장 버튼은 없어요. 값을 바꾸는 즉시 SQLite의 settings 테이블에 저장돼요.
 * - 걷기 화면은 onResume()에서 이 값들을 다시 읽으므로, 걷기 탭이나 뒤로가기로 돌아가면 바로 반영돼요.
 * - 맨 위 상단 바와 맨 아래 하단 탭(설정 선택됨)은 NavHelper로 설정해요.
 *
 * 흐름: onCreate → 화면 요소 찾기 → showSettings()로 저장된 값 보여주기 → 리스너(값이 바뀌면 저장) 연결
 */
public class SettingsActivity extends AppCompatActivity {

    // RadioButton id를 테마 번호 순서(0 8bit, 1 Lo-fi, 2 피아노)로 나열.
    // 칸 번호 = 테마 번호가 되도록 맞춰 두면, 번호 ↔ 버튼 변환을 반복문 하나로 할 수 있어요.
    private static final int[] THEME_BUTTON_IDS = {
            R.id.rbTheme8bit, R.id.rbThemeLofi, R.id.rbThemePiano};

    private SongDbHelper dbHelper;

    private Spinner spScale;
    private RadioGroup rgTheme;
    private SeekBar seekVolume;
    private TextView tvVolume;
    private SwitchCompat swBeat, swKeepScreen;

    /**
     * 화면이 처음 만들어질 때 호출돼요.
     * 화면 요소를 찾고, 저장된 설정을 보여준 뒤, 값이 바뀔 때마다 저장하도록 리스너를 연결해요.
     */
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);

        dbHelper = new SongDbHelper(this);

        // 공통 상단 바(제목만)와 하단 탭(지금 화면 = 설정). 값은 바꾸는 즉시 저장되므로 떠나기 전에 할 일은 없음(null)
        NavHelper.setupToolbar(this, "설정 · 테마", false);
        NavHelper.setupBottomNav(this, R.id.nav_settings, null);

        spScale = findViewById(R.id.spScale);
        rgTheme = findViewById(R.id.rgTheme);
        seekVolume = findViewById(R.id.seekVolume);
        tvVolume = findViewById(R.id.tvVolume);
        swBeat = findViewById(R.id.swBeat);
        swKeepScreen = findViewById(R.id.swKeepScreen);

        // ── 스케일 Spinner에 항목 채우기 ──
        // 이름 목록은 MusicEngine.SCALE_NAMES 하나만 쓰므로, 음계를 추가해도 여기는 고칠 필요가 없어요.
        ArrayAdapter<String> scaleAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, MusicEngine.SCALE_NAMES);
        scaleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spScale.setAdapter(scaleAdapter);

        // 리스너를 연결하기 "전에" 저장된 값을 먼저 보여줘요.
        // (먼저 연결하면 화면을 채우는 동안 리스너가 불려서 쓸데없이 저장이 일어남)
        showSettings();

        // ── 스케일: 고르면 바로 저장 ──
        spScale.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            /** 항목을 골랐을 때. position = 고른 칸 번호 = 스케일 번호 */
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                dbHelper.setIntSetting(SongDbHelper.KEY_SCALE, position);
            }
            /** 선택이 사라졌을 때 (이 화면에서는 일어나지 않지만 인터페이스라 꼭 적어야 함) */
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        // ── 사운드 테마: 라디오 버튼을 누르면 바로 저장 ──
        // checkedId = 눌린 RadioButton의 id → 테마 번호로 바꿔서 저장
        rgTheme.setOnCheckedChangeListener((group, checkedId) -> {
            int themeId = themeIdOf(checkedId);
            if (themeId >= 0) dbHelper.setIntSetting(SongDbHelper.KEY_THEME, themeId);
        });

        // ── 볼륨: 막대를 움직이는 동안 % 글자를 바꾸고, 바로 저장 ──
        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            /** 막대 값이 바뀔 때마다 호출. fromUser = 사용자가 손으로 바꿨는지 */
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                tvVolume.setText(progress + "%");
                // 코드에서 setProgress로 바꾼 경우(초기화 등)는 그쪽에서 이미 저장하므로 손으로 바꿨을 때만 저장
                if (fromUser) dbHelper.setIntSetting(SongDbHelper.KEY_VOLUME, progress);
            }
            /** 손가락으로 잡았을 때 (할 일 없음) */
            @Override public void onStartTrackingTouch(SeekBar s) { }
            /** 손가락을 뗐을 때 (할 일 없음) */
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        // ── 스위치 2개: 켜고 끌 때마다 바로 저장 ──
        // isChecked = 바뀐 뒤의 상태 (true = 켜짐)
        swBeat.setOnCheckedChangeListener((button, isChecked) ->
                dbHelper.setBoolSetting(SongDbHelper.KEY_BEAT, isChecked));
        swKeepScreen.setOnCheckedChangeListener((button, isChecked) ->
                dbHelper.setBoolSetting(SongDbHelper.KEY_KEEP_SCREEN, isChecked));

        // ── 설정 초기화 버튼 ──
        findViewById(R.id.btnReset).setOnClickListener(v -> confirmReset());
    }

    /**
     * 화면이 완전히 사라질 때 DB 연결을 닫아요.
     */
    @Override protected void onDestroy() {
        super.onDestroy();
        dbHelper.close();
    }

    /**
     * DB에 저장된 설정값을 읽어 화면 요소(Spinner, 라디오, 막대, 스위치)에 보여줘요.
     * 저장된 값이 없으면(처음 실행) 기본값을 보여줘요.
     */
    private void showSettings() {
        int scaleId = dbHelper.getIntSetting(SongDbHelper.KEY_SCALE, SongDbHelper.DEFAULT_SCALE);
        int themeId = dbHelper.getIntSetting(SongDbHelper.KEY_THEME, SongDbHelper.DEFAULT_THEME);
        int volume = dbHelper.getIntSetting(SongDbHelper.KEY_VOLUME, SongDbHelper.DEFAULT_VOLUME);

        // 잘못 저장된 번호가 있어도 앱이 죽지 않게 범위 안일 때만 적용 (범위 밖이면 첫 칸)
        spScale.setSelection(scaleId >= 0 && scaleId < MusicEngine.SCALE_NAMES.length ? scaleId : 0);
        rgTheme.check(THEME_BUTTON_IDS[themeId >= 0 && themeId < THEME_BUTTON_IDS.length ? themeId : 0]);
        seekVolume.setProgress(volume);
        tvVolume.setText(volume + "%");   // setProgress가 같은 값이면 리스너가 안 불릴 수 있어서 글자를 직접 맞춤
        swBeat.setChecked(dbHelper.getBoolSetting(SongDbHelper.KEY_BEAT, SongDbHelper.DEFAULT_BEAT));
        swKeepScreen.setChecked(dbHelper.getBoolSetting(SongDbHelper.KEY_KEEP_SCREEN, SongDbHelper.DEFAULT_KEEP_SCREEN));
    }

    /**
     * RadioButton id를 테마 번호로 바꿔요. 예) R.id.rbThemeLofi → 1
     *
     * @return 테마 번호, 못 찾으면 -1
     */
    private static int themeIdOf(int buttonId) {
        for (int i = 0; i < THEME_BUTTON_IDS.length; i++) {
            if (THEME_BUTTON_IDS[i] == buttonId) return i;
        }
        return -1;
    }

    /**
     * [설정 초기화]: 확인 창을 띄우고, [초기화]를 누르면 모든 설정을 기본값으로 되돌려요.
     * 저장된 곡에는 영향이 없어요. (곡마다 자기 소리 설정을 따로 저장해 두었으므로)
     */
    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("설정 초기화")
                .setMessage("모든 설정을 기본값으로 되돌릴까요?")
                .setPositiveButton("초기화", (dialog, which) -> {
                    // 기본값을 DB에 저장
                    dbHelper.setIntSetting(SongDbHelper.KEY_SCALE, SongDbHelper.DEFAULT_SCALE);
                    dbHelper.setIntSetting(SongDbHelper.KEY_THEME, SongDbHelper.DEFAULT_THEME);
                    dbHelper.setIntSetting(SongDbHelper.KEY_VOLUME, SongDbHelper.DEFAULT_VOLUME);
                    dbHelper.setBoolSetting(SongDbHelper.KEY_BEAT, SongDbHelper.DEFAULT_BEAT);
                    dbHelper.setBoolSetting(SongDbHelper.KEY_KEEP_SCREEN, SongDbHelper.DEFAULT_KEEP_SCREEN);
                    // 화면도 기본값으로 다시 그림 (이때 리스너가 불려 같은 값을 한 번 더 저장해도 문제없음)
                    showSettings();
                    Toast.makeText(this, "설정을 기본값으로 되돌렸어요", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("취소", null)
                .show();
    }
}
