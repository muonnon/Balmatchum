package com.example.balmatchum;

import android.content.Context;     // 앱의 리소스(res/raw 소리 파일 등)에 접근할 때 필요한 정보
import android.media.SoundPool;     // 짧은 소리 여러 개를 미리 불러 두고 빠르게 재생하는 도구

/**
 * 걸음 BPM에 맞춰 음악을 실시간으로 만들어 재생하는 엔진.
 *
 * 동작 방식:
 *  - 한 박(1비트)을 4칸으로 나눈 "16분음표"를 한 칸으로 삼아, 일정한 간격으로 칸을 하나씩 진행해요.
 *  - 칸 번호(step)에 따라 어떤 소리를 낼지 정해요.
 *      · 킥(쿵)    : 4칸마다 = 매 박의 첫 칸
 *      · 하이햇(츳): 4칸 중 3번째 칸 = 박과 박 사이(엇박)
 *      · 멜로디    : 2칸마다 = 8분음표마다, 펜타토닉 음계 안에서 한 칸 위/아래/제자리로 무작위 이동
 *  - 음높이는 note.wav 하나를 빠르게/느리게 재생해서 바꿔요. (빠르게 재생하면 음이 높아짐)
 *
 * 왜 별도 스레드(Thread)를 쓰나요?
 *  - 박자를 맞추려면 "기다렸다가 소리 내기"를 계속 반복해야 해요.
 *  - 이걸 화면(UI) 스레드에서 하면 기다리는 동안 버튼도 안 눌리고 앱이 멈춘 것처럼 돼요.
 *  - 그래서 박자 진행만 담당하는 스레드를 따로 만들어 돌려요.
 */
public class MusicEngine {

    // 펜타토닉(5음) 음계를 "기본음에서 몇 반음 위인지"로 적은 표. (도 레 미 솔 라 + 높은 도)
    // 반음: 피아노 건반 한 칸(흰 건반, 검은 건반 포함). 12반음 = 한 옥타브
    // static final: 모든 MusicEngine이 같이 쓰는, 절대 바뀌지 않는 상수
    private static final int[] SCALE = {0, 2, 4, 7, 9, 12};   // 펜타토닉 (반음 수)

    private final SoundPool pool;      // 소리들을 담아 두고 재생하는 SoundPool

    // SoundPool에 불러온 소리 각각의 번호(ID). 재생할 때 이 번호로 "어떤 소리"인지 알려줘요.
    private final int note, kick, hat;

    // volatile: 여러 스레드가 함께 읽고 쓰는 변수에 붙여요.
    // 화면 스레드가 값을 바꾸면(setBpm, stop), 음악 스레드가 바로 바뀐 값을 보게 해줘요.
    // 안 붙이면 음악 스레드가 예전 값을 계속 보고 있을 수 있어요.
    private volatile int bpm = 100;              // 현재 박자 (1분당 박 수). 처음엔 100
    private volatile boolean running = false;    // 음악 스레드가 돌고 있는지. false가 되면 스레드가 반복을 멈춤

    private Thread thread;                       // 박자를 진행하는 음악 스레드
    private int degree = 2;                                    // 현재 음 위치 (SCALE 배열의 칸 번호, 2 = 미)

    /**
     * 엔진을 만들 때 소리 파일 3개를 SoundPool에 미리 불러와요.
     * 미리 불러 두면 재생할 때 지연이 거의 없어요.
     *
     * @param c 소리 파일(res/raw)을 읽기 위한 Context (보통 Activity를 넘김)
     */
    public MusicEngine(Context c) {
        // setMaxStreams(6): 동시에 최대 6개 소리까지 겹쳐서 재생 가능
        // (킥, 하이햇, 멜로디가 같은 칸에서 동시에 나기도 하고, 이전 소리 꼬리가 남아 있기도 해서 여유 있게)
        pool = new SoundPool.Builder().setMaxStreams(6).build();

        // load(Context, 리소스, 우선순위): 소리 파일을 불러오고, 그 소리의 번호를 돌려줌
        // 마지막 1은 우선순위인데, 현재 안드로이드에서는 효과가 없어 관례적으로 1을 넣어요.
        note = pool.load(c, R.raw.note, 1);   // 멜로디용 기본음
        kick = pool.load(c, R.raw.kick, 1);   // 킥 드럼 (쿵)
        hat  = pool.load(c, R.raw.hat, 1);    // 하이햇 (츳)

        // 소리 불러오기가 끝날 때마다 Logcat에 기록 (디버깅용)
        // status가 0이면 성공. Logcat에서 태그 "BAL"로 검색하면 볼 수 있어요.
        pool.setOnLoadCompleteListener((p, id, status) ->
                android.util.Log.d("BAL", "load id=" + id + " status=" + status));
    }

    /**
     * 박자(BPM)를 바꿔요. 재생 중에 바꿔도 다음 칸부터 바로 반영돼요.
     * 너무 느리거나 빠르면 음악이 이상해지므로 50~200 사이로 제한해요.
     *   Math.min(200, b) → 200보다 크면 200으로
     *   Math.max(50, …)  → 50보다 작으면 50으로
     */
    public void setBpm(int b) { bpm = Math.max(50, Math.min(200, b)); }

    /**
     * 음악 재생을 시작해요.
     * 이미 재생 중이면 아무것도 하지 않아요. (걸음마다 호출돼도 스레드가 여러 개 생기지 않도록)
     */
    public void start() {
        if (running) return;     // 이미 돌고 있으면 그냥 나감
        running = true;

        // 새 스레드를 만들고, 그 스레드가 할 일을 () -> { ... } 안에 적어요.
        thread = new Thread(() -> {
            int step = 0;                       // 지금 몇 번째 칸인지 (0~15, 16칸 = 4박 = 1마디)

            // 다음 칸을 연주할 "목표 시각"(나노초, 1초 = 10억 나노초)
            // 매번 "지금부터 얼마 기다리기"로 하면 작은 오차가 쌓여 박자가 점점 밀려요.
            // 그래서 목표 시각을 일정하게 더해 가고, 그 시각까지 남은 만큼만 기다려요.
            long next = System.nanoTime();

            // running이 true인 동안 계속 반복 (stop()이 호출되면 false가 되어 빠져나감)
            while (running) {
                android.util.Log.d("BAL", "step " + step);   // 디버깅용: 지금 칸 번호 기록

                // play(소리번호, 왼쪽볼륨, 오른쪽볼륨, 우선순위, 반복횟수, 재생속도)
                //   볼륨: 0.0 ~ 1.0 / 반복 0 = 한 번만 / 재생속도 1f = 원래 속도

                // 칸 0, 4, 8, 12 (4로 나눈 나머지가 0) → 매 박의 시작에 킥
                if (step % 4 == 0) pool.play(kick, 1, 1, 1, 0, 1f);        // 1,2,3,4박

                // 칸 2, 6, 10, 14 → 박과 박 사이(엇박)에 하이햇. 킥보다 조금 작게(0.6)
                if (step % 4 == 2) pool.play(hat, 0.6f, 0.6f, 1, 0, 1f);   // 엇박

                // 짝수 칸(0, 2, 4, …) → 8분음표마다 멜로디 한 음
                if (step % 2 == 0) {                                        // 8분음표마다 멜로디
                    // Math.random()은 0 이상 1 미만 → ×3 하면 0~2.999 → (int)로 0,1,2 → -1 하면 -1, 0, +1
                    // 즉 음계에서 한 칸 내려가기 / 그대로 / 한 칸 올라가기 중 하나를 무작위로 고름
                    // 바깥의 Math.max/min은 degree가 배열 범위(0 ~ SCALE.length-1)를 벗어나지 않게 막아줌
                    degree = Math.max(0, Math.min(SCALE.length - 1,
                            degree + (int) (Math.random() * 3) - 1));       // -1, 0, +1

                    // 반음 수를 재생 속도로 바꾸기: 속도 = 2^(반음/12)
                    // 예) 0반음 → 1.0배(원래 음), 12반음 → 2.0배(한 옥타브 위)
                    // 소리를 2배 빠르게 재생하면 한 옥타브 높게 들리는 원리를 이용
                    float rate = (float) Math.pow(2, SCALE[degree] / 12.0);
                    pool.play(note, 0.8f, 0.8f, 1, 0, rate);
                }

                // 다음 칸으로. 15 다음은 다시 0 (% 16 은 16으로 나눈 나머지라 0~15를 빙글빙글 돎)
                step = (step + 1) % 16;

                // 다음 목표 시각 = 이전 목표 시각 + 16분음표 한 칸 길이
                // 한 박 길이 = 60초 / bpm, 한 칸 = 한 박 / 4
                // 60초 = 60,000,000,000 나노초 (숫자 사이 _ 는 읽기 쉽게 쓴 것으로 값에는 영향 없음)
                // 예) 120 BPM → 60_000_000_000 / 120 / 4 = 125,000,000ns = 0.125초
                next += (long) (60_000_000_000L / bpm / 4);                 // 16분음표 길이

                // 목표 시각까지 남은 시간만큼 기다림
                long wait = next - System.nanoTime();
                if (wait > 0) {   // 이미 늦었으면(소리 재생 등에 시간이 걸린 경우) 기다리지 않고 바로 다음 칸
                    // Thread.sleep(밀리초, 나머지 나노초): 나노초를 ms 부분과 남은 나노초 부분으로 나눠 넘김
                    try { Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000)); }
                    // 기다리는 중에 stop()이 interrupt()를 부르면 이 예외가 발생 → 반복을 바로 끝냄
                    catch (InterruptedException e) { break; }
                }
            }
        });
        thread.start();   // 스레드 실행 시작 (이 줄 이후로 위 반복문이 따로 돌아감)
    }

    /**
     * 음악을 멈춰요.
     * running을 false로 바꿔 반복문이 끝나게 하고,
     * interrupt()로 sleep 중인 스레드를 깨워서 다음 칸을 기다리지 않고 바로 멈추게 해요.
     */
    public void stop() { running = false; if (thread != null) thread.interrupt(); }

    /**
     * 엔진을 완전히 정리해요. 화면이 사라질 때(onDestroy) 한 번 호출.
     * 음악을 멈추고, SoundPool이 쓰던 메모리를 돌려줘요. 이후에는 이 엔진을 다시 쓰면 안 돼요.
     */
    public void release() { stop(); pool.release(); }
}
