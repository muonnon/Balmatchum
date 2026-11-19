package com.example.balmatchum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * NoteSequencer(재생과 내보내기가 함께 쓰는 곡 계산) 단위 테스트.
 *
 * 기준값(EXPECTED)은 리팩터링 "전" 앱을 에뮬레이터에서 실제로 재생해서 Logcat에 찍힌 BAL_MELODY "곡 끝" 줄이에요.
 * 곡 데이터(seed, BPM 기록, 음계·테마·비트, 버전)도 에뮬레이터 DB에서 그대로 가져왔어요.
 * → 계산 코드를 옮기거나 고쳐도 이 테스트가 통과하면 저장된 곡이 예전과 똑같이 연주된다는 뜻이에요.
 */
public class NoteSequencerTest {

    /** 곡을 끝까지 진행(MusicEngine과 같은 순서: 끝인지 먼저 확인 → 한 칸)하고 요약 줄을 돌려줘요. */
    private static String playToEnd(long seed, String series, int scale, int theme, boolean beat,
                                    int version, NoteSink sink) {
        NoteSequencer s = new NoteSequencer();
        s.setSound(scale, theme, beat);
        s.loadSong(seed, parse(series), version);
        while (!s.isAtEnd()) s.tick(sink);
        return s.summary();
    }

    private static int[] parse(String text) {
        String[] parts = text.split(",");
        int[] r = new int[parts.length];
        for (int i = 0; i < parts.length; i++) r[i] = Integer.parseInt(parts[i]);
        return r;
    }

    /** 받은 음을 모두 기록하는 싱크 (비교용) */
    private static final class RecordingSink implements NoteSink {
        final List<String> notes = new ArrayList<>();
        @Override public void onNote(int sound, float rate, float volume, long timeNs) {
            notes.add(SoundPalette.ALL[sound].name + "@" + timeNs + " r=" + rate + " v=" + volume);
        }
    }

    // ── 에뮬레이터 DB의 곡 #5: 옛 곡, 펜타토닉, 8bit, 비트 켬 ──
    @Test
    public void legacySong5_matchesBeforeRefactor() {
        assertEquals("곡 끝: seed=1794804125476 notes=10 hash=81961882057246",
                playToEnd(1794804125476L, "103,97,103,104,102", 0, 0, true, 0, new RecordingSink()));
    }

    // ── 곡 #6: 옛 곡, 장음계, Lo-fi, 비트 끔 ──
    @Test
    public void legacySong6_matchesBeforeRefactor() {
        assertEquals("곡 끝: seed=1794805099274 notes=15 hash=3576135876412387713",
                playToEnd(1794805099274L, "122,121,116,119,117,118,118", 2, 1, false, 0, new RecordingSink()));
    }

    // ── 곡 #9: 새 곡(v1), 펜타토닉, 8bit, 비트 켬, BPM 50 → 151 → 86 ──
    private static final String SONG9 = "50,51,52,53,54,54,54,53,58,64,71,80,94,113,151,143,146,146,143,147,147,129,122,111,103,97,86";

    @Test
    public void v1Song9_matchesBeforeRefactor() {
        assertEquals("곡 끝: v1 seed=1795063288327 notes=36 hash=1110791569411406145 rhythm=7055089485416064173",
                playToEnd(1795063288327L, SONG9, 0, 0, true, 1, new RecordingSink()));
    }

    /** 소리 없이(sink = null, seekTo 방식) 진행해도 요약값이 같아야 해요. (난수 순서가 소리 여부와 무관) */
    @Test
    public void silentRun_sameSummary() {
        assertEquals(playToEnd(1795063288327L, SONG9, 0, 0, true, 1, new RecordingSink()),
                playToEnd(1795063288327L, SONG9, 0, 0, true, 1, null));
        assertEquals(playToEnd(1794804125476L, "103,97,103,104,102", 0, 0, true, 0, new RecordingSink()),
                playToEnd(1794804125476L, "103,97,103,104,102", 0, 0, true, 0, null));
    }

    /** 비트를 끄면 드럼만 빠지고, 드럼이 아닌 음(멜로디·베이스)은 시각·높이·볼륨까지 똑같아야 해요. */
    @Test
    public void beatOff_onlyRemovesDrums() {
        for (int version = 0; version <= 1; version++) {
            RecordingSink on = new RecordingSink(), off = new RecordingSink();
            playToEnd(1795063288327L, SONG9, 0, 0, true, version, on);
            playToEnd(1795063288327L, SONG9, 0, 0, false, version, off);
            List<String> onWithoutDrums = new ArrayList<>();
            for (String n : on.notes) if (!isDrum(n)) onWithoutDrums.add(n);
            assertEquals("버전 " + version, onWithoutDrums, off.notes);
            assertTrue(on.notes.size() > off.notes.size());
        }
    }

    private static boolean isDrum(String note) {
        String name = note.substring(0, note.indexOf('@'));
        return SoundPalette.ALL[SoundPalette.indexOf(name)].rootMidi < 0;   // 음정 없는 소리 = 드럼
    }
}
