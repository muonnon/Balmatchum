package com.example.balmatchum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WAV 내보내기 단위 테스트 (PC에서 실행).
 * 앱의 실제 소리 파일(app/src/main/res/raw)을 읽어서 곡을 렌더링해 봐요.
 * Gradle 단위 테스트는 app 폴더에서 실행되므로 "src/main/res/raw/..." 경로로 읽을 수 있어요.
 */
public class SongRendererTest {

    private static float[][] samples;

    /** 모든 테스트 전에 한 번: res/raw의 21개 WAV를 디코딩 (실패하면 여기서 바로 알 수 있음) */
    @BeforeClass
    public static void loadSamples() throws IOException {
        samples = new float[SoundPalette.ALL.length][];
        for (int i = 0; i < samples.length; i++) {
            File f = new File("src/main/res/raw/" + SoundPalette.ALL[i].name + ".wav");
            try (InputStream in = new FileInputStream(f)) {
                samples[i] = WavIO.decode(in);
            }
            assertTrue(f + " 이 너무 짧음", samples[i].length > 1000);
        }
    }

    private static int[] parse(String text) {
        String[] p = text.split(",");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Integer.parseInt(p[i]);
        return r;
    }

    /** 내보내기 계산의 요약 줄 = 에뮬레이터 재생 때 찍힌 줄 (NoteSequencerTest의 기준값과 같음) */
    @Test
    public void renderSummary_equalsPlaybackSummary() throws IOException {
        SongRenderer.Result v1 = SongRenderer.mixSong(samples, new SongRenderer.Song(1795063288327L,
                parse("50,51,52,53,54,54,54,53,58,64,71,80,94,113,151,143,146,146,143,147,147,129,122,111,103,97,86"),
                0, 0, true, 1), null, null);
        assertEquals("곡 끝: v1 seed=1795063288327 notes=36 hash=1110791569411406145 rhythm=7055089485416064173",
                v1.summary);

        SongRenderer.Result v0 = SongRenderer.mixSong(samples, new SongRenderer.Song(1794805099274L,
                parse("122,121,116,119,117,118,118"), 2, 1, false, 0), null, null);
        assertEquals("곡 끝: seed=1794805099274 notes=15 hash=3576135876412387713", v0.summary);
    }

    /** 결과 파형: 최댓값 0.95 이하, 소리가 실제로 들어 있음, 여운 1.5초 포함 길이 */
    @Test
    public void renderedPcm_isNormalizedAndHasTail() throws IOException {
        int[] series = parse("103,97,103,104,102");
        SongRenderer.Result r = SongRenderer.mixSong(samples,
                new SongRenderer.Song(1794804125476L, series, 0, 0, true, 0), null, null);
        float peak = 0;
        for (float v : r.pcm) peak = Math.max(peak, Math.abs(v));
        assertTrue("최댓값 " + peak, peak <= 0.95f + 1e-6f && peak > 0.1f);

        long totalNs = NoteSequencer.seriesStartNs(series)[series.length];
        long expected = ((totalNs + SongRenderer.TAIL_NS) * 22050 + 999_999_999L) / 1_000_000_000L;
        assertEquals(expected, r.pcm.length);
    }

    /** WAV 헤더를 바이트 단위로 다시 읽어서 확인 + 디코딩하면 원래 파형과 같은지(16bit 오차 이내) */
    @Test
    public void wavHeader_isExact_andRoundTrips() throws IOException {
        float[] pcm = new float[22050 + 7];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (float) Math.sin(i * 0.05) * 0.9f;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        WavIO.write(bos, pcm, null, null);
        byte[] b = bos.toByteArray();

        ByteBuffer h = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(44 + pcm.length * 2, b.length);
        assertEquals("RIFF", new String(b, 0, 4, "US-ASCII"));
        assertEquals(b.length - 8, h.getInt(4));          // RIFF 크기 = 파일 크기 - 8
        assertEquals("WAVE", new String(b, 8, 4, "US-ASCII"));
        assertEquals("fmt ", new String(b, 12, 4, "US-ASCII"));
        assertEquals(16, h.getInt(16));
        assertEquals(1, h.getShort(20));                  // PCM
        assertEquals(1, h.getShort(22));                  // 모노
        assertEquals(22050, h.getInt(24));                // 샘플레이트
        assertEquals(44100, h.getInt(28));                // 초당 바이트
        assertEquals(2, h.getShort(32));                  // 블록 크기
        assertEquals(16, h.getShort(34));                 // 비트
        assertEquals("data", new String(b, 36, 4, "US-ASCII"));
        assertEquals(pcm.length * 2, h.getInt(40));       // 데이터 크기

        float[] back = WavIO.decode(new ByteArrayInputStream(b));
        assertEquals(pcm.length, back.length);
        for (int i = 0; i < pcm.length; i++) assertEquals(pcm[i], back[i], 1.5f / 32767f);
    }

    /**
     * 5분짜리 곡: 길이·파일 크기 계산이 맞는지, PC에서 얼마나 걸리는지 (기기 측정은 앱에서 Logcat으로 따로 함)
     * 걸음 BPM 90 ~ 130을 오르내리는 5분 분량 기록을 만들어 렌더링.
     */
    @Test
    public void fiveMinuteSong_sizeAndSpeed() throws IOException {
        java.util.List<Integer> list = new java.util.ArrayList<>();
        long ns = 0;
        for (int i = 0; ns < 300_000_000_000L; i++) {
            int bpm = 110 + (int) Math.round(20 * Math.sin(i / 40.0));
            list.add(bpm);
            ns += 60_000_000_000L / bpm;
        }
        int[] series = new int[list.size()];
        for (int i = 0; i < series.length; i++) series[i] = list.get(i);

        long t0 = System.nanoTime();
        SongRenderer.Result r = SongRenderer.mixSong(samples,
                new SongRenderer.Song(42L, series, 0, 2, true, 1), null, null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        WavIO.write(bos, r.pcm, null, null);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        double seconds = r.pcm.length / 22050.0;
        System.out.println("5분 곡(PC): 걸음 " + series.length + "개, 길이 " + seconds + "초, 파일 "
                + bos.size() + "바이트, 렌더링+쓰기 " + ms + "ms");
        // 곡 길이(마지막 걸음이 5분을 살짝 넘길 수 있음) + 여운 1.5초. 샘플 1개(1/22050초) 오차 이내
        double songSeconds = NoteSequencer.seriesStartNs(series)[series.length] / 1e9;
        assertTrue("5분 이상이어야 함: " + songSeconds, songSeconds >= 300);
        assertEquals(songSeconds + 1.5, seconds, 1.0 / 22050);
        assertEquals(44 + r.pcm.length * 2L, bos.size());        // 약 13.3MB
    }
}
