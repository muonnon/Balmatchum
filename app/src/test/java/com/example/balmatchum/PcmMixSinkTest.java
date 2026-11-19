package com.example.balmatchum;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * PcmMixSink(내보내기 믹서) 단위 테스트: 위치 계산, 재생 속도에 따른 길이, 선형 보간, 볼륨, 클리핑 방지.
 */
public class PcmMixSinkTest {

    /** 소리 0번 = 0, 1, 2, …, 9 (값이 위치와 같아서 보간 결과를 계산하기 쉬움) */
    private static float[][] ramp() {
        float[][] s = new float[SoundPalette.ALL.length][];
        s[0] = new float[10];
        for (int i = 0; i < 10; i++) s[0][i] = i;
        return s;
    }

    @Test
    public void rate1_copiesAtRightPosition() {
        PcmMixSink m = new PcmMixSink(ramp(), 100);
        // 1초 = 22050번째 샘플이지만 버퍼가 작으니, 1 샘플 = 1/22050초 → 5번째 샘플 위치에 넣기
        long ns = 5L * 1_000_000_000L / PcmMixSink.SAMPLE_RATE + 1;
        m.onNote(0, 1f, 0.5f, ns);
        float[] b = m.buffer();
        assertEquals(0f, b[4], 0f);
        for (int i = 0; i < 10; i++) assertEquals(i * 0.5f, b[5 + i], 1e-6f);   // 볼륨 0.5 곱
        assertEquals(0f, b[15], 0f);
    }

    @Test
    public void rate2_halfLength_skipsSamples() {
        PcmMixSink m = new PcmMixSink(ramp(), 100);
        m.onNote(0, 2f, 1f, 0);
        float[] b = m.buffer();
        // (10 - 1) / 2 + 1 = 5개: 0, 2, 4, 6, 8
        for (int j = 0; j < 5; j++) assertEquals(j * 2f, b[j], 1e-6f);
        assertEquals(0f, b[5], 0f);
    }

    @Test
    public void rateHalf_doubleLength_interpolates() {
        PcmMixSink m = new PcmMixSink(ramp(), 100);
        m.onNote(0, 0.5f, 1f, 0);
        float[] b = m.buffer();
        // 9 / 0.5 + 1 = 19개: 0, 0.5, 1, 1.5, … 9 (사이 값은 선형 보간)
        for (int j = 0; j < 19; j++) assertEquals(j * 0.5f, b[j], 1e-6f);
        assertEquals(0f, b[19], 0f);
    }

    @Test
    public void overlappingNotesAdd_andPeakIsNormalized() {
        PcmMixSink m = new PcmMixSink(ramp(), 100);
        m.onNote(0, 1f, 1f, 0);
        m.onNote(0, 1f, 1f, 0);                    // 같은 자리에 두 번 → 값이 두 배 (최댓값 18)
        assertEquals(18f, m.buffer()[9], 1e-6f);
        float gain = m.normalizePeak();
        assertEquals(0.95f / 18f, gain, 1e-7f);
        assertEquals(0.95f, m.buffer()[9], 1e-6f);  // 가장 큰 값이 정확히 0.95
    }

    @Test
    public void notePastEndIsCut() {
        PcmMixSink m = new PcmMixSink(ramp(), 7);
        m.onNote(0, 1f, 1f, 0);                    // 10개짜리를 7칸 버퍼에 → 7개만 들어감, 예외 없음
        assertEquals(6f, m.buffer()[6], 0f);
    }
}
