package com.example.balmatchum;

import android.content.Context;          // 뷰를 만들 때 필요한 앱 정보
import android.graphics.Canvas;          // 그림을 그리는 도화지. 선, 글자, 도형을 그리는 메서드를 가짐
import android.graphics.Paint;           // 붓. 색, 굵기, 글자 크기 같은 "그리는 방식"을 담음
import android.graphics.Path;            // 여러 점을 이은 선(경로). 꺾은선 그래프를 한 번에 그리려고 사용
import android.util.AttributeSet;        // XML에 적은 속성들(layout_width 등)
import android.view.View;                // 모든 화면 요소의 부모 클래스

import androidx.core.content.ContextCompat; // res/values/colors.xml에 정의한 색을 꺼내 오는 도구

/**
 * BPM 변화 선 그래프를 그리는 Custom View.
 *
 * 왜 Custom View를 만드나요?
 *  - 안드로이드 기본 위젯에는 그래프가 없어요. 외부 라이브러리 없이 그리려면
 *    View를 상속해서 onDraw()에서 Canvas에 직접 선을 그어야 해요.
 *
 * 그래프 모양:
 *  - 가로(x축) = 시간. 곡의 처음(왼쪽) ~ 끝(오른쪽)
 *  - 세로(y축) = BPM. 아래가 느림, 위가 빠름
 *  - 꺾은선 = 걸음마다의 BPM을 이은 선 (주 색). 선 아래는 보조색으로 연하게 채움
 *  - 세로 막대 = 지금 재생 위치 (SeekBar와 같은 위치)
 *
 * 사용법: setData()로 데이터를 넣고, 재생 중에는 setProgress()로 위치를 알려 줘요.
 */
public class BpmGraphView extends View {

    private int[] values = new int[0];          // 걸음마다의 BPM
    private long[] startNs = new long[]{0};     // 각 값이 시작하는 시간 (MusicEngine.seriesStartNs와 같은 계산)
    private float progress = 0f;                // 재생 위치 (0.0 = 처음, 1.0 = 끝)

    // Paint(붓)는 onDraw()에서 매번 새로 만들면 느려지므로, 미리 만들어 두고 계속 써요.
    // ANTI_ALIAS_FLAG: 선의 계단 모양을 부드럽게 다듬어 줌
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);      // 그래프 선
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);      // 선 아래 영역 채우기 (보조색)
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);      // 축, 보조선(격자선)
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);      // 숫자 글자
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // 재생 위치 막대
    private final Path path = new Path();                                  // 꺾은선 경로 (재사용)
    private final Path fillPath = new Path();                              // 선 아래 채우기 경로 (재사용)

    private final float density;                // 1dp가 몇 픽셀인지 (기기마다 다름)

    /**
     * 코드에서 new BpmGraphView(context)로 만들 때 쓰는 생성자.
     */
    public BpmGraphView(Context context) {
        this(context, null); // 아래 생성자를 그대로 호출 (속성 없음)
    }

    /**
     * XML 레이아웃에 <com.example.balmatchum.BpmGraphView .../>로 적었을 때 안드로이드가 부르는 생성자.
     * 이 생성자가 없으면 XML에서 쓸 때 앱이 죽어요.
     */
    public BpmGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        // 팔레트 색 가져오기: res/values/colors.xml에 이름 붙여 둔 색을 꺼내 와요.
        // ContextCompat.getColor(context, R.color.이름)은 "그 이름의 색 값(int)"을 돌려줘요.
        // 코드에 색 숫자(16진수 색 코드)를 직접 쓰지 않으면, 나중에 colors.xml만 고쳐도 그래프 색이 같이 바뀌어요.
        int primary = ContextCompat.getColor(context, R.color.bal_primary);     // 그래프 선 (진한 녹색)
        int secondary = ContextCompat.getColor(context, R.color.bal_secondary); // 선 아래 채우기 (보조 녹색)
        int grid = ContextCompat.getColor(context, R.color.bal_outline);        // 격자선 (패널 배경보다 약간 어두운 색)
        int textSub = ContextCompat.getColor(context, R.color.bal_text_sub);    // BPM 숫자·안내 글자
        int accent = ContextCompat.getColor(context, R.color.bal_accent);       // 재생 위치 막대 (주황)

        linePaint.setColor(primary);
        linePaint.setStyle(Paint.Style.STROKE);          // STROKE: 안을 채우지 않고 선만 그림
        linePaint.setStrokeWidth(3 * density);           // 선 굵기 3dp
        linePaint.setStrokeJoin(Paint.Join.ROUND);       // 꺾이는 곳을 둥글게

        fillPaint.setColor(secondary);
        fillPaint.setStyle(Paint.Style.FILL);            // FILL: 경로 안쪽을 채움
        fillPaint.setAlpha(110);                         // 0(투명) ~ 255(불투명). 반투명하게 깔아서 격자선이 비쳐 보이게

        axisPaint.setColor(grid);                        // 격자색 자체가 연한 색이라 투명도는 따로 주지 않음
        axisPaint.setStrokeWidth(1 * density);

        textPaint.setColor(textSub);
        textPaint.setTextSize(12 * density);             // 글자 크기 12dp

        progressPaint.setColor(accent);
        progressPaint.setStrokeWidth(2 * density);
    }

    /**
     * 그래프에 그릴 데이터를 넣어요.
     *
     * @param values 걸음마다의 BPM
     */
    public void setData(int[] values) {
        this.values = values;
        this.startNs = MusicEngine.seriesStartNs(values); // 엔진과 똑같은 계산 → SeekBar 위치와 그래프 위치가 일치
        invalidate(); // "다시 그려 줘" 요청 → 안드로이드가 곧 onDraw()를 다시 불러요
    }

    /**
     * 재생 위치 막대를 옮겨요.
     *
     * @param p 0.0(처음) ~ 1.0(끝)
     */
    public void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p)); // 0~1 범위를 벗어나지 않게
        invalidate();
    }

    /**
     * 화면에 그릴 때마다 안드로이드가 호출해요. 여기서 Canvas에 그래프를 그려요.
     * 주의: 자주 불리므로 여기서 new로 객체를 만드는 일은 피해요. (Paint, Path를 필드로 둔 이유)
     */
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // ── 그릴 영역 정하기 ──
        // 왼쪽은 BPM 숫자를 쓸 자리(36dp)를 비우고, 위아래는 글자가 잘리지 않게 8dp씩 비움
        float left = 36 * density;
        float right = getWidth() - 8 * density;
        float top = 8 * density;
        float bottom = getHeight() - 8 * density;

        if (values.length == 0) {                          // 데이터가 없으면 안내 글자만
            canvas.drawText("BPM 기록이 없어요", left, getHeight() / 2f, textPaint);
            return;
        }

        // ── 세로(BPM) 범위 정하기 ──
        // 가장 작은 값과 큰 값을 찾고, 위아래로 5씩 여유를 줘서 선이 테두리에 붙지 않게 함
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int v : values) { min = Math.min(min, v); max = Math.max(max, v); }
        min -= 5; max += 5;
        float range = max - min;                            // 위아래 폭 (최소 10이라 0으로 나눌 일 없음)

        long total = startNs[startNs.length - 1];           // 곡 전체 길이(ns)
        float width = right - left, height = bottom - top;

        // ── 축과 보조선, BPM 숫자 ──
        // 위(최댓값), 가운데, 아래(최솟값)에 가로 보조선을 긋고 왼쪽에 숫자를 씀
        for (int i = 0; i <= 2; i++) {
            float y = top + height * i / 2f;                // i=0 → 맨 위, 1 → 가운데, 2 → 맨 아래
            int bpmLabel = Math.round(max - range * i / 2f);
            canvas.drawLine(left, y, right, y, axisPaint);
            // 글자는 기준선(baseline) 위에 그려지므로, 글자 높이의 1/3만큼 내려서 선과 가운데가 맞게 함
            canvas.drawText(String.valueOf(bpmLabel), 0, y + textPaint.getTextSize() / 3, textPaint);
        }

        // ── 꺾은선 ──
        // 각 값의 시작 시각을 x, BPM을 y로 바꿔 점을 찍고 차례로 이음
        // y는 화면 좌표가 아래로 갈수록 커지므로, 큰 BPM일수록 위(작은 y)에 오게 bottom에서 빼 줌
        path.reset();                                       // 이전에 그린 경로 지우기
        for (int i = 0; i < values.length; i++) {
            float x = left + width * startNs[i] / total;
            float y = bottom - height * (values[i] - min) / range;
            if (i == 0) path.moveTo(x, y);                  // 첫 점: 붓을 여기로 옮김 (선 없이)
            else path.lineTo(x, y);                         // 그다음 점: 이전 점에서 여기까지 선을 그음
        }
        // 마지막 값은 곡 끝까지 이어지므로, 오른쪽 끝까지 같은 높이로 선을 연장
        path.lineTo(right, bottom - height * (values[values.length - 1] - min) / range);

        // ── 선 아래 채우기 (보조색) ──
        // 꺾은선 경로를 복사한 뒤 오른쪽 아래 → 왼쪽 아래로 내려 닫으면 "선과 바닥 사이 영역"이 돼요.
        // 선보다 먼저 그려야 선이 채우기 위에 또렷하게 보여요.
        fillPath.set(path);                                 // path를 그대로 복사 (새 객체를 만들지 않음)
        fillPath.lineTo(right, bottom);                     // 오른쪽 끝에서 바닥으로
        fillPath.lineTo(left + width * startNs[0] / total, bottom); // 바닥을 따라 첫 점 아래로
        fillPath.close();                                   // 첫 점까지 이어서 닫음
        canvas.drawPath(fillPath, fillPaint);

        canvas.drawPath(path, linePaint);

        // ── 재생 위치 막대 ──
        float px = left + width * progress;
        canvas.drawLine(px, top, px, bottom, progressPaint);
    }
}
