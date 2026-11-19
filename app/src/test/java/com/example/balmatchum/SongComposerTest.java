package com.example.balmatchum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * SongComposer(새 멜로디 알고리즘) 단위 테스트.
 * 안드로이드 기기 없이 PC에서 돌아가요:  ./gradlew test --tests "com.example.balmatchum.SongComposerTest"
 *
 * 검사하는 것:
 *  1. 같은 시드 → 소리·음높이·길이·볼륨이 처음부터 끝까지 완전히 같은지 (다시 듣기 재현)
 *  2. 모든 테마 × 음계 × 여러 시드 × 여러 걸음 속도에서 재생 속도가 SoundPool 허용 범위(0.5 ~ 2.0) 안인지
 *  3. 멜로디 음높이가 두 옥타브(-12 ~ +12반음) 안인지, 아래 옥타브도 실제로 쓰이는지
 *  4. 한 칸에 동시에 나는 소리 수가 SoundPool 동시 재생 수(10)보다 적은지
 *  5. 다른 시드는 다른 곡이 되는지
 */
public class SongComposerTest {

    private static final int STEPS = 16 * 64;   // 64마디 (보통 걸음으로 약 2~3분)

    /** 걸음 속도 패턴: 칸 번호 → BPM. 느림·보통·빠름과 오르내리는 경우를 모두 시험 */
    private static int bpmAt(int pattern, int step) {
        switch (pattern) {
            case 0: return 80;                                   // 느림
            case 1: return 110;                                  // 보통
            case 2: return 140;                                  // 빠름
            default: return 70 + (step / 37) % 90;               // 70 ~ 159를 천천히 오르내림
        }
    }

    /** 곡 전체를 돌리면서 모든 출력을 하나의 문자열 요약값으로 만들어요. (완전히 같은지 비교용) */
    private static long fingerprint(long seed, int theme, int[] scale, int bpmPattern) {
        SongComposer c = new SongComposer(seed, theme, scale);
        long h = 17;
        for (int s = 0; s < STEPS; s++) {
            c.step(bpmAt(bpmPattern, s));
            for (int i = 0; i < c.count; i++) {
                h = h * 31 + c.sound[i];
                h = h * 31 + Float.floatToIntBits(c.rate[i]);
                h = h * 31 + Float.floatToIntBits(c.volume[i]);
                h = h * 31 + (c.isDrum[i] ? 1 : 0);
            }
            h = h * 31 + c.count;
        }
        return h * 31 + c.getMelodyHash() * 7 + c.getRhythmHash();
    }

    @Test
    public void sameSeed_sameSong() {
        for (int theme = 0; theme < 3; theme++) {
            for (int[] scale : MusicEngine.SCALES) {
                for (int p = 0; p < 4; p++) {
                    long seed = 1794805099274L + theme * 1000 + p;
                    assertEquals("같은 시드인데 결과가 다름 (theme=" + theme + ", bpm패턴=" + p + ")",
                            fingerprint(seed, theme, scale, p), fingerprint(seed, theme, scale, p));
                }
            }
        }
    }

    @Test
    public void differentSeed_differentSong() {
        int[] scale = MusicEngine.SCALES[0];
        assertNotEquals(fingerprint(1L, 0, scale, 1), fingerprint(2L, 0, scale, 1));
    }

    @Test
    public void ratesAndPitchesStayInRange() {
        int checkedNotes = 0;
        boolean usedLowOctave = false, usedHighOctave = false;
        for (int theme = 0; theme < 3; theme++) {
            for (int[] scale : MusicEngine.SCALES) {
                for (long seed = 0; seed < 40; seed++) {
                    for (int p = 0; p < 4; p++) {
                        SongComposer c = new SongComposer(seed * 7919, theme, scale);
                        for (int s = 0; s < STEPS; s++) {
                            c.step(bpmAt(p, s));
                            // 4. 동시 소리 수 (킥·스네어·하이햇·장식·베이스·멜로디 = 최대 6)
                            assertTrue("한 칸 소리 수 " + c.count, c.count <= 6);
                            for (int i = 0; i < c.count; i++) {
                                // 2. SoundPool은 0.5 ~ 2.0배만 재생 가능
                                assertTrue("재생 속도 범위 밖: " + c.rate[i], c.rate[i] >= 0.5f && c.rate[i] <= 2.0f);
                                assertTrue("볼륨 범위 밖: " + c.volume[i], c.volume[i] > 0f && c.volume[i] <= 1f);
                            }
                            if (c.melodyEvent && !"rest".equals(c.melodyName)) {
                                // 3. 두 옥타브 안
                                assertTrue("음높이 범위 밖: " + c.melodySemi, c.melodySemi >= -12 && c.melodySemi <= 12);
                                // 멜로디 소리는 반드시 음정이 있는(rootMidi가 있는) 샘플
                                assertTrue(SoundPalette.ALL[SoundPalette.indexOf(c.melodyName)].rootMidi > 0);
                                if (c.melodySemi < 0) usedLowOctave = true;
                                if (c.melodySemi > 0) usedHighOctave = true;
                                checkedNotes++;
                            }
                        }
                    }
                }
            }
        }
        assertTrue("아래 옥타브(-12 ~ -1반음)가 한 번도 안 쓰임", usedLowOctave);
        assertTrue("위 옥타브가 한 번도 안 쓰임", usedHighOctave);
        assertTrue("검사한 음이 너무 적음: " + checkedNotes, checkedNotes > 100_000);
    }

    @Test
    public void melodyTimbresComeFromThemePool() {
        for (int theme = 0; theme < 3; theme++) {
            for (long seed = 0; seed < 50; seed++) {
                SongComposer c = new SongComposer(seed, theme, MusicEngine.SCALES[0]);
                for (int s = 0; s < 16 * 16; s++) {
                    c.step(110);
                    if (c.melodyEvent && !"rest".equals(c.melodyName)) {
                        assertTrue(c.melodyName + "는 테마 " + theme + "의 멜로디 풀에 없음",
                                java.util.Arrays.asList(SoundPalette.MELODY_POOL[theme]).contains(c.melodyName));
                    }
                }
            }
        }
    }
}
