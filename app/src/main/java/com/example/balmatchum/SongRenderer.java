package com.example.balmatchum;

import android.content.Context;     // res/raw 소리 파일을 읽을 때 필요
import android.os.SystemClock;      // 렌더링에 걸린 시간 측정
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;   // 여러 스레드가 안전하게 읽고 쓰는 true/false (취소 신호용)

/**
 * 저장된 걸음 곡을 WAV 파일로 만드는 "오프라인 렌더러".
 *
 * 재생과 같은 곡이 나오는 이유:
 *  재생(MusicEngine)과 똑같은 NoteSequencer로 곡을 계산하고, 음을 스피커 대신 PcmMixSink(버퍼에 더하기)로 보내요.
 *  가상 시계도 같아서(칸 길이 = 60_000_000_000 / BPM / 4 ns), 음이 나는 시각까지 재생과 같아요.
 *  다른 점은 칸마다 실제 시간을 기다리지 않는다는 것뿐 → 5분짜리 곡도 몇 초 만에 계산돼요.
 *
 * 처리 순서:
 *  1. res/raw의 WAV 21개를 float 배열로 디코딩 (한 번 읽으면 캐시해서 다음 내보내기는 바로 시작)
 *  2. NoteSequencer로 곡 끝까지 진행하며 PcmMixSink에 믹싱 (진행률 0 ~ 85%)
 *  3. 곡 끝에 1.5초 여운, 최댓값이 0.95를 넘으면 전체를 같은 비율로 줄임 (클리핑 방지)
 *  4. 22050Hz 모노 16bit WAV로 파일에 씀 (진행률 85 ~ 100%)
 *
 * 볼륨: 설정 화면의 볼륨은 "듣는 환경" 설정이라 반영하지 않고 100%로 만들어요.
 *       곡에 저장된 음계·테마·비트는 그대로 반영해요.
 */
public final class SongRenderer {

    private SongRenderer() { }

    /** 곡 끝에 붙이는 여운 길이 (마지막 음이 자연스럽게 사라지도록) */
    static final long TAIL_NS = 1_500_000_000L;   // 1.5초

    /** 렌더링할 곡 정보 (DB의 한 줄에서 필요한 값만) */
    public static final class Song {
        final long seed;
        final int[] series;
        final int scaleId, themeId, melodyVersion;
        final boolean beatOn;

        public Song(long seed, int[] series, int scaleId, int themeId, boolean beatOn, int melodyVersion) {
            this.seed = seed;
            this.series = series;
            this.scaleId = scaleId;
            this.themeId = themeId;
            this.beatOn = beatOn;
            this.melodyVersion = melodyVersion;
        }
    }

    /** 렌더링 결과 */
    public static final class Result {
        /** 곡 끝 요약 줄 (재생 때의 BAL_MELODY "곡 끝" 줄과 같아야 함) */
        public final String summary;
        /** 완성된 파형 (-1.0 ~ 0.95) */
        public final float[] pcm;
        /** 클리핑 방지로 곱한 비율 (1.0이면 줄이지 않음) */
        public final float gain;

        Result(String summary, float[] pcm, float gain) {
            this.summary = summary;
            this.pcm = pcm;
            this.gain = gain;
        }
    }

    /** 진행률(0.0 ~ 1.0)을 알려 받는 곳 */
    public interface Progress { void onProgress(float fraction); }

    // ───────────────────────────── 1. 샘플 불러오기 (캐시) ─────────────────────────────

    // 디코딩한 소리 파형 [SoundPalette 번호]. 한 번 읽으면 앱이 살아 있는 동안 다시 읽지 않아요.
    // (21개 합쳐 약 0.4MB 파일 → float로 약 0.8MB 메모리)
    private static float[][] sampleCache;

    /**
     * res/raw의 소리 파일 전부를 float 배열로 읽어요. 처음 한 번만 실제로 읽고, 그다음부터는 캐시를 돌려줘요.
     * synchronized: 두 스레드가 동시에 처음 읽으려 해도 한 번만 읽게 하는 잠금
     *
     * @throws IOException 파일을 못 읽거나 형식이 다르면. 메시지에 어떤 소리인지 담음
     */
    static synchronized float[][] loadSamples(Context context) throws IOException {
        if (sampleCache != null) return sampleCache;
        float[][] s = new float[SoundPalette.ALL.length][];
        for (int i = 0; i < s.length; i++) {
            SoundPalette.Sound snd = SoundPalette.ALL[i];
            // openRawResource: res/raw 파일을 읽는 통로. try-with-resources라 다 읽으면 자동으로 닫혀요.
            try (InputStream in = context.getResources().openRawResource(snd.resId)) {
                s[i] = WavIO.decode(in);
            } catch (IOException e) {
                throw new IOException("소리 파일 '" + snd.name + "'을 읽지 못했어요: " + e.getMessage(), e);
            }
        }
        sampleCache = s;
        return s;
    }

    // ───────────────────────────── 2·3. 믹싱 (안드로이드 기능 없음 → PC 테스트 가능) ─────────────────────────────

    /**
     * 곡 전체를 계산해서 파형으로 만들어요. 실제 시간을 기다리지 않고 최대한 빠르게 진행해요.
     *
     * @param samples  소리별 파형 (loadSamples 결과)
     * @param progress 진행률 알림 (0.0 ~ 1.0, null 가능)
     * @param cancel   true가 되면 멈추고 InterruptedIOException (null 가능)
     */
    static Result mixSong(float[][] samples, Song song, Progress progress, AtomicBoolean cancel)
            throws InterruptedIOException {
        // 재생과 똑같은 순서로 준비: 소리 설정 → 곡 불러오기 (버전 1은 이때 테마·음계로 곡 개성이 정해짐)
        NoteSequencer seq = new NoteSequencer();
        seq.setSound(song.scaleId, song.themeId, song.beatOn);
        seq.loadSong(song.seed, song.series, song.melodyVersion);

        long totalNs = seq.getTotalNs();
        // 버퍼 길이 = (곡 길이 + 여운 1.5초) × 22050. 나노초 → 샘플 수로 바꿀 때 올림(+ 999_999_999)해서 끝이 잘리지 않게
        long frames = ((totalNs + TAIL_NS) * PcmMixSink.SAMPLE_RATE + 999_999_999L) / 1_000_000_000L;
        PcmMixSink sink = new PcmMixSink(samples, (int) frames);

        int ticks = 0;
        while (!seq.isAtEnd()) {           // MusicEngine 재생과 같은 반복: 끝인지 확인 → 한 칸
            seq.tick(sink);
            // 256칸(약 16마디)마다 취소 확인과 진행률 알림. 매 칸마다 하면 그만큼 느려져서 가끔만
            if ((++ticks & 255) == 0) {
                if (cancel != null && cancel.get()) throw new InterruptedIOException("취소됨");
                if (progress != null && totalNs > 0) progress.onProgress((float) seq.getPositionNs() / totalNs);
            }
        }
        float gain = sink.normalizePeak();   // 클리핑 방지
        return new Result(seq.summary(), sink.buffer(), gain);
    }

    // ───────────────────────────── 전체 (안드로이드) ─────────────────────────────

    /**
     * 곡을 WAV 파일로 만들어요. 오래 걸릴 수 있으니 꼭 백그라운드 스레드에서 부르세요.
     * 실패하거나 취소되면 만들다 만 파일은 지워요.
     *
     * @param out 만들 파일 (보통 캐시 폴더 안)
     * @throws InterruptedIOException 취소됐을 때
     * @throws IOException            소리 파일을 못 읽거나 파일 쓰기에 실패했을 때
     */
    public static void renderToFile(Context context, Song song, File out, Progress progress, AtomicBoolean cancel)
            throws IOException {
        long t0 = SystemClock.elapsedRealtime();
        float[][] samples = loadSamples(context);

        // 믹싱이 전체 시간의 대부분이라 진행률 0 ~ 85%, 파일 쓰기 85 ~ 100%로 나눠 보여줘요
        Result r = mixSong(samples, song, f -> report(progress, f * 0.85f), cancel);
        long t1 = SystemClock.elapsedRealtime();
        // 재생 때와 같은 형식의 요약 줄 → Logcat에서 재생 해시와 비교할 수 있어요
        Log.d("BAL_MELODY", "내보내기 " + r.summary);

        boolean ok = false;
        // BufferedOutputStream: 작은 쓰기를 모아서 한 번에 → 파일 쓰기가 빨라짐
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 64 * 1024)) {
            WavIO.write(os, r.pcm, f -> report(progress, 0.85f + f * 0.15f), cancel);
            ok = true;
        } finally {
            // 실패·취소면 만들다 만 파일 삭제 (반쯤 쓴 WAV가 남지 않게)
            if (!ok && out.exists() && !out.delete()) Log.w("BAL", "임시 파일 삭제 실패: " + out);
        }
        long t2 = SystemClock.elapsedRealtime();
        Log.d("BAL", "내보내기 완료: 길이 " + (r.pcm.length * 1000L / PcmMixSink.SAMPLE_RATE) + "ms, 믹싱 "
                + (t1 - t0) + "ms, 쓰기 " + (t2 - t1) + "ms, 파일 " + out.length() + "바이트, 볼륨 비율 " + r.gain);
    }

    private static void report(Progress p, float f) { if (p != null) p.onProgress(f); }
}
