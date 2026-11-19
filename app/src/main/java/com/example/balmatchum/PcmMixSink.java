package com.example.balmatchum;

/**
 * 내보내기용 NoteSink: 음 신호를 받으면 그 소리의 파형(샘플 값 배열)을 곡 버퍼의 해당 위치에 더해요(믹싱).
 * 곡 전체를 다 더하고 나면 버퍼가 곧 완성된 곡의 소리 파형이에요. (SongRenderer가 이걸 WAV로 저장)
 *
 * 디지털 소리 기초:
 *  - 소리는 1초에 22050번 잰 공기 떨림의 크기(샘플)를 늘어놓은 숫자 배열이에요. (22050Hz)
 *  - 여기서는 각 샘플을 -1.0 ~ 1.0 사이의 float로 다뤄요. (WAV의 16bit 정수 -32768 ~ 32767을 32768로 나눈 값)
 *  - 두 소리를 동시에 내는 것 = 같은 시각의 샘플 값을 더하는 것 (믹싱)
 *
 * 안드로이드 기능을 쓰지 않는 순수 자바라 PC 단위 테스트로 검사할 수 있어요.
 */
public final class PcmMixSink implements NoteSink {

    /** 샘플레이트: 1초에 샘플 몇 개인지. res/raw의 모든 WAV가 22050Hz라서 같은 값으로 섞어요 */
    public static final int SAMPLE_RATE = 22050;

    private final float[][] samples;   // [SoundPalette 번호] = 그 소리 파일을 float로 디코딩한 파형
    private final float[] mix;         // 곡 전체 버퍼 (여기에 모든 음을 더함)
    private int noteCount = 0;         // 받은 음 개수 (로그·테스트용)

    /**
     * @param samples      소리별 파형 (SongRenderer가 res/raw에서 읽어 둔 것)
     * @param lengthFrames 곡 버퍼 길이 (샘플 개수). 곡 길이 + 여운 길이
     */
    public PcmMixSink(float[][] samples, int lengthFrames) {
        this.samples = samples;
        this.mix = new float[lengthFrames];
    }

    /**
     * 음 하나를 버퍼에 더해요.
     *
     * 재생 속도(rate) 바꾸기 = 샘플을 rate 간격으로 건너뛰며 읽기:
     *  - rate 2.0 → 원래 샘플을 2칸씩 건너뛰며 읽음 → 길이 절반, 한 옥타브 높은 소리
     *  - rate 0.5 → 0.5칸씩 읽음 → 길이 2배, 한 옥타브 낮은 소리
     *  - 0.5칸처럼 샘플과 샘플 "사이"를 읽을 때는 양옆 두 샘플을 거리 비율로 섞어요 (선형 보간).
     *    예) 위치 3.25 → 3번 샘플 75% + 4번 샘플 25%
     *  SoundPool도 같은 원리로 음높이를 바꾸므로 재생과 같은 높이의 소리가 나요.
     */
    @Override
    public void onNote(int sound, float rate, float volume, long timeNs) {
        float[] s = samples[sound];
        if (s == null || s.length == 0 || rate <= 0f) return;
        noteCount++;

        // 시각(ns) → 버퍼 위치(샘플 번호). 반올림하려고 0.5초 단위의 절반(500_000_000)을 더하고 나눔.
        // long으로 계산: 1시간(3.6조 ns) × 22050도 long 범위(약 922경) 안이라 넘치지 않아요.
        long start = (timeNs * SAMPLE_RATE + 500_000_000L) / 1_000_000_000L;
        if (start >= mix.length) return;   // 버퍼 끝을 넘는 음은 버림 (일어나지 않음)

        int last = s.length - 1;
        // 다시 뽑은 소리의 길이 = 원래 길이 / rate  (마지막 샘플까지 읽을 수 있는 만큼)
        int outLen = (int) (last / rate) + 1;
        for (int j = 0; j < outLen; j++) {
            int dst = (int) start + j;
            if (dst >= mix.length) break;          // 곡 버퍼(여운 포함) 끝에서 자름
            float pos = j * rate;                  // 원래 샘플에서 읽을 위치 (소수점 가능)
            int i0 = (int) pos;                    // 왼쪽 샘플
            float frac = pos - i0;                 // 오른쪽 샘플 쪽으로 얼마나 갔는지 (0 ~ 1)
            int i1 = Math.min(i0 + 1, last);       // 오른쪽 샘플 (배열 끝을 넘지 않게)
            float v = s[i0] + (s[i1] - s[i0]) * frac;   // 선형 보간
            mix[dst] += v * volume;                // 볼륨을 곱해서 기존 소리에 더함
        }
    }

    /**
     * 클리핑 방지: 여러 소리를 더하면 1.0을 넘을 수 있는데, 16bit로 저장할 때 넘친 부분이 잘려서 "지직" 소리가 나요.
     * 가장 큰 값(절댓값)이 0.95를 넘으면 곡 전체를 같은 비율로 줄여서 최댓값을 0.95로 맞춰요.
     * (일부만 줄이면 소리 크기 균형이 깨지므로 전체를 같은 비율로)
     *
     * @return 곱한 비율 (줄이지 않았으면 1.0)
     */
    public float normalizePeak() {
        float peak = 0f;
        for (float v : mix) peak = Math.max(peak, Math.abs(v));
        if (peak <= 0.95f) return 1f;
        float gain = 0.95f / peak;
        for (int i = 0; i < mix.length; i++) mix[i] *= gain;
        return gain;
    }

    /** 완성된 곡 버퍼 (-1.0 ~ 1.0) */
    public float[] buffer() { return mix; }

    /** 받은 음 개수 */
    public int getNoteCount() { return noteCount; }
}
