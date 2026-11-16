package com.example.balmatchum;

import android.app.Activity;                    // 모든 화면(Activity)의 부모 클래스
import android.content.Intent;                  // 다른 화면을 여는 요청서
import android.os.Build;                        // 안드로이드 버전 확인용

import com.google.android.material.appbar.MaterialToolbar;                      // 상단 바
import com.google.android.material.bottomnavigation.BottomNavigationView;      // 하단 탭

/**
 * 상단 바(Toolbar)와 하단 탭(BottomNavigationView)을 설정하는 공통 도우미.
 *
 * 왜 따로 만드나요?
 *  - 걷기 / 기록 / 설정 세 화면이 똑같은 하단 탭 코드를 가져야 해요.
 *  - 세 화면에 같은 코드를 복사해 두면, 나중에 고칠 때 세 군데를 다 고쳐야 하고 한 곳을 빠뜨리기 쉬워요.
 *  - 그래서 여기에 한 번만 적고, 각 화면은 한 줄로 불러 써요.
 *
 * static 메서드만 있는 클래스라서 new NavHelper()로 객체를 만들 필요가 없어요. (NavHelper.setupToolbar(...)처럼 바로 호출)
 *
 * ── 탭 이동 규칙 (뒤로가기 스택이 쌓이지 않게) ──
 *  안드로이드는 화면을 열 때마다 "스택(쌓아 둔 접시)" 위에 올려요. 뒤로가기를 누르면 맨 위 화면이 닫히고 그 아래가 보여요.
 *  탭을 누를 때마다 새 화면을 그냥 올리면 걷기 → 기록 → 설정 → 기록 → … 이 계속 쌓여서
 *  뒤로가기를 여러 번 눌러야 앱이 끝나요. 그래서 이렇게 해요:
 *   - 걷기 화면은 항상 맨 아래(첫 화면)에 남겨 둬요.
 *   - 걷기 → 기록/설정 : 새 화면을 위에 올림            → 스택: [걷기, 기록]
 *   - 기록 ↔ 설정       : 새 화면을 올리고 지금 화면은 닫음 → 스택: [걷기, 설정] (계속 2개)
 *   - 기록/설정 → 걷기  : 위에 있는 화면들을 치우고 아래의 걷기 화면을 다시 보여 줌 → 스택: [걷기]
 *  결과: 기록·설정에서 뒤로가기 → 걷기 화면, 걷기 화면에서 뒤로가기 → 앱 종료.
 */
public final class NavHelper {

    // 객체를 만들지 못하게 생성자를 private으로 막아 둠 (static 메서드만 쓰는 클래스의 관례)
    private NavHelper() { }

    /**
     * 화면 맨 위의 Toolbar에 제목을 넣고, 필요하면 왼쪽에 뒤로가기 화살표(<)를 달아요.
     * 레이아웃에 include_toolbar.xml이 include되어 있어야 해요. (id: toolbar)
     *
     * @param act      지금 화면
     * @param title    바에 보여줄 제목
     * @param showBack true면 "<" 화살표를 보여주고, 누르면 이 화면을 닫음(finish)
     * @return 설정한 Toolbar (곡 상세처럼 나중에 제목을 바꿔야 할 때 쓰라고 돌려줌)
     */
    public static MaterialToolbar setupToolbar(Activity act, CharSequence title, boolean showBack) {
        MaterialToolbar toolbar = act.findViewById(R.id.toolbar);
        toolbar.setTitle(title);
        if (showBack) {
            toolbar.setNavigationIcon(R.drawable.ic_back);           // 제목 왼쪽 아이콘
            toolbar.setNavigationContentDescription("뒤로 가기");     // 화면 읽기 기능(TalkBack)이 읽어 줄 설명
            toolbar.setNavigationOnClickListener(v -> act.finish()); // 누르면 이 화면 닫기 → 이전 화면으로
        }
        return toolbar;
    }

    /**
     * 하단 탭을 설정해요. 걷기 / 기록 / 설정 화면의 onCreate에서 한 번 불러요.
     * 레이아웃에 include_bottom_nav.xml이 include되어 있어야 해요. (id: bottomNav)
     *
     * @param act            지금 화면
     * @param selectedItemId 지금 화면에 해당하는 탭 id (R.id.nav_walk / nav_records / nav_settings)
     * @param beforeLeave    다른 탭으로 떠나기 직전에 할 일 (걷기 화면: 걷는 중이면 정지·저장). 없으면 null
     */
    public static void setupBottomNav(Activity act, int selectedItemId, Runnable beforeLeave) {
        BottomNavigationView nav = act.findViewById(R.id.bottomNav);

        // Material3 기본 "알약 모양 선택 표시"(아이콘 뒤 작은 배경)는 끔.
        // 대신 bg_nav_item.xml로 칸 전체를 칠해서 와이어프레임처럼 보이게 함.
        nav.setItemActiveIndicatorEnabled(false);

        // ★ 순서 중요: 리스너를 연결하기 "전에" 선택 표시를 해요.
        //   setSelectedItemId()는 내부에서 "탭이 선택됨" 리스너를 불러요.
        //   리스너를 먼저 달아 두면 → 리스너가 화면을 열고 → 열린 화면이 또 setSelectedItemId() → 또 화면을 열고 …
        //   이렇게 끝없이 화면이 열릴 수 있어요. 리스너가 없을 때 선택해 두면 그런 일이 없어요.
        nav.setSelectedItemId(selectedItemId);

        // 이미 선택된 탭을 다시 누르면 호출되는 리스너. 아무 일도 하지 않음.
        // (이 리스너를 달아 두면, 같은 탭을 다시 눌렀을 때 아래의 "선택됨" 리스너가 불리지 않아요)
        nav.setOnItemReselectedListener(item -> { });

        // 다른 탭을 눌렀을 때 호출되는 리스너
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == selectedItemId) return true;           // 혹시 같은 탭이면 아무것도 안 함 (안전장치)

            // 떠나기 전에 할 일 (걷기 화면이면 걷기를 정지하고 곡을 저장)
            if (beforeLeave != null) beforeLeave.run();

            Class<?> target = targetOf(id);
            if (target == null) return false;                // 모르는 탭 id (일어나지 않아야 함)

            Intent intent = new Intent(act, target);
            // FLAG_ACTIVITY_NO_ANIMATION: 화면이 옆에서 밀려 들어오는 전환 애니메이션 없이 바로 바뀜 (탭처럼 보이게)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            if (target == MainActivity.class) {
                // 걷기 화면은 이미 스택 맨 아래에 있으므로 새로 만들지 않고 그걸 다시 보여 줘요.
                // CLEAR_TOP  : 걷기 화면 위에 쌓인 화면(기록/설정)을 모두 닫음
                // SINGLE_TOP : 걷기 화면을 새로 만들지 않고 기존 것을 그대로 씀 (걷기 기록, 음악 엔진이 유지됨)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            }
            act.startActivity(intent);

            // 기록·설정 화면에서 떠날 때는 지금 화면을 닫아 스택이 쌓이지 않게 함.
            // 걷기 화면은 닫지 않음 (맨 아래에 남아 있어야 뒤로가기로 돌아올 수 있음)
            if (!(act instanceof MainActivity)) finishWithoutAnimation(act);

            // false = "이 탭을 선택된 상태로 바꾸지 마".
            // 걷기 화면은 닫히지 않고 남아 있으므로, true를 돌려주면 나중에 돌아왔을 때
            // 걷기 화면인데 "기록" 탭에 선택 표시가 되어 있는 이상한 상태가 돼요.
            return false;
        });
    }

    /**
     * 탭 id → 열어야 할 화면 클래스.
     * MainActivity.class 처럼 클래스 이름 뒤에 .class를 붙이면 "그 클래스 자체"를 값으로 쓸 수 있어요. (Intent에 넣을 때 필요)
     */
    private static Class<?> targetOf(int itemId) {
        if (itemId == R.id.nav_walk) return MainActivity.class;
        if (itemId == R.id.nav_records) return SongListActivity.class;
        if (itemId == R.id.nav_settings) return SettingsActivity.class;
        return null;
    }

    /**
     * 화면을 닫는 애니메이션 없이 닫아요.
     * 안드로이드 14(API 34)부터 방법이 바뀌어서 버전에 따라 다르게 호출해요.
     *  - 14 이상: overrideActivityTransition()으로 "닫힐 때 애니메이션 없음"을 먼저 정한 뒤 finish()
     *  - 13 이하: finish() 직후 overridePendingTransition(0, 0)  (0 = 애니메이션 없음)
     */
    @SuppressWarnings("deprecation") // overridePendingTransition은 14부터 "옛 방법" 표시가 붙었지만 13 이하에서는 이것뿐이라 사용
    private static void finishWithoutAnimation(Activity act) {
        if (Build.VERSION.SDK_INT >= 34) {
            act.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
            act.finish();
        } else {
            act.finish();
            act.overridePendingTransition(0, 0);
        }
    }
}
