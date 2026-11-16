package com.example.balmatchum;

import android.content.Context;
import android.graphics.Canvas;              // 그림을 그리는 도화지
import android.graphics.Color;
import android.graphics.Paint;               // 붓 (색, 굵기, 채우기/선 방식)
import android.os.SystemClock;               // 부팅 후 흐른 시간(ms). 애니메이션 시간 계산에 사용
import android.util.AttributeSet;
import android.view.View;

import com.google.android.material.color.MaterialColors; // 앱 테마 색 가져오기

import java.util.Arrays;

/**
 * 걷기 화면 가운데의 "걸음 반응 비주얼라이저" Custom View.
 *
 * 그리는 것:
 *  - 가운데의 채운 작은 원 (발)
 *  - 고정된 안내 동심원 3개 (와이어프레임 그림)
 *  - 걸음마다 가운데에서 바깥으로 퍼지며 점점 투명해지는 원 (pulse)
 *  - 걷기 전에는 아래쪽에 "걸음마다 원이 퍼져 나감" 안내 글자
 *
 * 애니메이션 원리 (postInvalidateOnAnimation):
 *  1. pulse()가 불리면 "이 원이 시작된 시각"을 배열에 적어 두고 다시 그려 달라고 요청해요.
 *  2. onDraw()에서 "지금 시각 - 시작 시각"으로 원이 얼마나 퍼졌는지(0~1) 계산해서 그려요.
 *  3. 아직 퍼지는 중인 원이 하나라도 있으면 postInvalidateOnAnimation()으로 "다음 화면 프레임에 또 그려 줘"를 요청해요.
 *     → 화면이 새로 그려질 때마다(보통 1초에 60번) onDraw가 불려서 원이 부드럽게 움직여요.
 *  4. 모든 원이 다 퍼져서 사라지면 더 이상 요청하지 않으므로 그림이 멈춰요. (배터리 절약)
 *
 * 주의: onDraw()는 1초에 수십 번 불리므로 그 안에서 new로 객체를 만들면 느려지고 버벅여요.
 *       그래서 Paint와 배열은 모두 미리 만들어 두고 재사용해요.
 */
public class WalkVisualizerView extends View {

    // 동시에 보여줄 수 있는 퍼지는 원의 최대 개수.
    // 원 하나가 1.2초 동안 퍼지므로, 아주 빨리 걸어도(초당 4걸음) 5개 정도면 충분해요. 여유 있게 8개.
    private static final int MAX_PULSES = 8;
    private static final long PULSE_DURATION_MS = 1200;   // 원 하나가 다 퍼질 때까지 걸리는 시간

    // 퍼지는 원 각각이 시작된 시각(ms). 0이면 "빈 칸"(지금 퍼지는 원이 없음).
    // 고정 크기 배열을 "빙글빙글 돌려 쓰는" 방식(원형 버퍼): 8개를 다 쓰면 가장 오래된 칸부터 덮어씀.
    private final long[] pulseStart = new long[MAX_PULSES];
    private int nextSlot = 0;                  // 다음 원을 적을 칸 번호

    private boolean walking = false;           // 걷는 중인지 (false면 안내 글자를 보여줌)

    // 붓들 (onDraw에서 new 하지 않도록 미리 만들어 둠)
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);    // 가운데 채운 원
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // 고정 안내 동심원
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // 퍼지는 원
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);   // 안내 글자

    private final float density;               // 1dp = 몇 px

    /** 코드에서 new WalkVisualizerView(context)로 만들 때 쓰는 생성자 */
    public WalkVisualizerView(Context context) {
        this(context, null);
    }

    /** XML 레이아웃에 적었을 때 안드로이드가 부르는 생성자 (이게 없으면 XML에서 쓸 때 앱이 죽음) */
    public WalkVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        // 테마 색 가져오기 (다크 모드에서도 잘 보이게). 세 번째 값은 테마에 색이 없을 때 쓸 기본색
        int primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.DKGRAY);
        int outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, Color.GRAY);
        int onSurfaceVariant = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);

        dotPaint.setColor(primary);
        dotPaint.setStyle(Paint.Style.FILL);         // FILL: 안을 채움

        guidePaint.setColor(outline);
        guidePaint.setStyle(Paint.Style.STROKE);     // STROKE: 테두리 선만
        guidePaint.setStrokeWidth(2 * density);

        pulsePaint.setColor(primary);
        pulsePaint.setStyle(Paint.Style.STROKE);

        textPaint.setColor(onSurfaceVariant);
        textPaint.setTextSize(13 * density);
        textPaint.setTextAlign(Paint.Align.CENTER);  // drawText의 x 좌표가 글자의 "가운데"가 되게
    }

    /**
     * 한 걸음이 감지될 때 불러요. 가운데에서 퍼지는 원을 하나 추가해요.
     * (걷기 화면의 onStep()에서 호출 — 메인 스레드에서만 불러야 함)
     */
    public void pulse() {
        pulseStart[nextSlot] = SystemClock.uptimeMillis();  // 이 칸에 "지금" 시작한다고 적음
        nextSlot = (nextSlot + 1) % MAX_PULSES;             // 다음 칸으로 (7 다음은 0)
        postInvalidateOnAnimation();                        // 다음 프레임에 다시 그려 달라고 요청
    }

    /**
     * 걷기 상태를 알려줘요.
     * 걷기를 멈추면(false) 퍼지던 원을 모두 지워서 그림도 바로 멈춰요. 그리고 안내 글자를 다시 보여줘요.
     */
    public void setWalking(boolean walking) {
        this.walking = walking;
        if (!walking) Arrays.fill(pulseStart, 0);   // 모든 칸을 0(빈 칸)으로
        invalidate();                               // 한 번 다시 그림 (애니메이션 요청은 하지 않음)
    }

    /**
     * 화면에 그릴 때마다 안드로이드가 호출해요.
     */
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2f;                 // 가운데 x
        float cy = getHeight() / 2f;                // 가운데 y
        // 가장 큰 원의 반지름: 가로·세로 중 짧은 쪽의 절반에서 여백 12dp를 뺌 (원이 테두리에 닿지 않게)
        // 세로는 아래 안내 글자 자리(36dp)를 위아래 똑같이 빼고 계산 → 원은 가운데에 있고 글자와 겹치지 않음
        // 단, 작은 기기에서 이 영역이 낮으면(160dp 미만) 글자 자리를 빼면 원이 너무 작아지거나 안 보이므로
        // 그때는 글자를 생략하고 원을 그리는 데 높이를 다 써요.
        // 글자 자리는 걷는 중에도 똑같이 비워 둬요. (걷기 시작할 때 원 크기가 갑자기 바뀌지 않게)
        boolean roomForHint = getHeight() >= 160 * density;  // 글자 자리를 둘 만큼 높은지
        boolean showHint = !walking && roomForHint;          // 실제로 글자를 그릴지 (걷기 전에만)
        float textSpace = roomForHint ? 36 * density : 0;
        float maxR = Math.min(getWidth(), getHeight() - 2 * textSpace) / 2f - 12 * density;
        if (maxR <= 0) return;                      // 화면이 너무 작으면 그리지 않음

        // 크기 비율은 와이어프레임에서 잰 값 (가장 큰 원 190 기준: 안내 원 90·140·190, 가운데 원 40)
        float dotR = maxR * 0.21f;

        // ── 고정 안내 동심원 3개: 바깥으로 갈수록 연하게 ──
        guidePaint.setAlpha(150); canvas.drawCircle(cx, cy, maxR * 0.47f, guidePaint);
        guidePaint.setAlpha(110); canvas.drawCircle(cx, cy, maxR * 0.74f, guidePaint);
        guidePaint.setAlpha(70);  canvas.drawCircle(cx, cy, maxR, guidePaint);

        // ── 퍼지는 원들 ──
        long now = SystemClock.uptimeMillis();
        boolean anyAlive = false;                   // 아직 퍼지는 중인 원이 있는지
        for (int i = 0; i < MAX_PULSES; i++) {
            if (pulseStart[i] == 0) continue;       // 빈 칸은 건너뜀

            // t = 진행도. 0(막 시작) → 1(다 퍼짐)
            float t = (now - pulseStart[i]) / (float) PULSE_DURATION_MS;
            if (t >= 1f) {                          // 다 퍼진 원은 지움
                pulseStart[i] = 0;
                continue;
            }
            anyAlive = true;

            // 반지름: 가운데 원 크기에서 시작해 가장 큰 원까지 커짐
            float r = dotR + (maxR - dotR) * t;
            // 투명도: 255(불투명) → 0(투명)으로 점점 사라짐
            pulsePaint.setAlpha((int) (255 * (1f - t)));
            // 선 굵기: 처음엔 굵게(5dp) → 퍼질수록 가늘게(1dp)
            pulsePaint.setStrokeWidth((1 + 4 * (1f - t)) * density);
            canvas.drawCircle(cx, cy, r, pulsePaint);
        }

        // ── 가운데 채운 원 (퍼지는 원 위에 그려서 항상 또렷하게) ──
        canvas.drawCircle(cx, cy, dotR, dotPaint);

        // ── 걷기 전 안내 글자: 아래쪽 가운데 (영역이 충분히 높을 때만) ──
        if (showHint) {
            canvas.drawText("걸음마다 원이 퍼져 나감", cx, getHeight() - 16 * density, textPaint);
        }

        // 퍼지는 원이 남아 있으면 다음 프레임에 또 그림 → 애니메이션이 이어짐.
        // 없으면 요청하지 않으므로 여기서 그림이 멈춤.
        if (anyAlive) postInvalidateOnAnimation();
    }
}
