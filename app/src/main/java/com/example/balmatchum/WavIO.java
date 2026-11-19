package com.example.balmatchum;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * WAV 파일 읽기(디코딩)와 쓰기. 안드로이드 기능 없이 순수 자바로 만들어서 PC 단위 테스트로 검사할 수 있어요.
 *
 * WAV 파일 구조 (RIFF 형식): 이름 4글자 + 크기 4바이트 + 내용, 이런 "청크(덩어리)"가 이어져 있어요.
 *   "RIFF" [파일 크기-8] "WAVE"
 *     "fmt " [16] [형식 1=PCM][채널 수][샘플레이트][초당 바이트][블록 크기][비트 수]   ← 소리의 형식
 *     (다른 청크가 끼어 있을 수 있음: "LIST" 같은 정보 청크)
 *     "data" [데이터 크기] [샘플들…]                                                   ← 실제 소리
 *  숫자는 모두 "리틀 엔디언": 작은 자리 바이트가 먼저 나와요. 예) 0x1234 → 34 12
 *
 * 이 앱은 22050Hz · 모노(1채널) · 16bit PCM 형식만 다뤄요.
 */
public final class WavIO {

    private WavIO() { }

    /** 앱이 쓰는 형식 */
    public static final int SAMPLE_RATE = PcmMixSink.SAMPLE_RATE;   // 22050
    private static final int CHANNELS = 1;                           // 모노
    private static final int BITS = 16;                              // 샘플 하나 = 16bit = 2바이트
    /** WAV 헤더 크기 (RIFF 12 + fmt 24 + data 머리 8 = 44바이트) */
    public static final int HEADER_SIZE = 44;

    // ───────────────────────────── 읽기 ─────────────────────────────

    /**
     * WAV 파일 내용을 읽어 -1.0 ~ 1.0 float 배열로 바꿔요.
     * 헤더를 처음부터 훑으며 "fmt " 청크로 형식을 확인하고, "data" 청크를 찾아 디코딩해요.
     * (data 청크가 항상 44바이트 뒤에 있다고 가정하면, 중간에 다른 청크가 낀 파일에서 잡음이 나요)
     *
     * @throws IOException WAV가 아니거나 22050Hz 모노 16bit가 아니면 (이유를 메시지에 담음)
     */
    public static float[] decode(InputStream in) throws IOException {
        byte[] b = readAll(in);
        if (b.length < 12 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) {
            throw new IOException("WAV 파일이 아니에요");
        }
        boolean fmtOk = false;
        int p = 12;                                    // 첫 청크 시작 위치
        while (p + 8 <= b.length) {
            int size = le32(b, p + 4);                 // 이 청크 내용의 크기
            int body = p + 8;                          // 내용 시작 위치
            if (size < 0 || body + size > b.length) size = b.length - body;   // 크기가 잘못 적힌 파일도 끝까지만 읽게
            if (tag(b, p, "fmt ")) {
                int format = le16(b, body), channels = le16(b, body + 2);
                int rate = le32(b, body + 4), bits = le16(b, body + 14);
                if (format != 1 || channels != CHANNELS || rate != SAMPLE_RATE || bits != BITS) {
                    throw new IOException("지원하지 않는 WAV 형식이에요 (형식 " + format + ", " + channels + "채널, "
                            + rate + "Hz, " + bits + "bit)");
                }
                fmtOk = true;
            } else if (tag(b, p, "data")) {
                if (!fmtOk) throw new IOException("WAV 형식 정보(fmt)가 data보다 먼저 없어요");
                int n = size / 2;                      // 샘플 개수 (샘플 하나 = 2바이트)
                float[] out = new float[n];
                for (int i = 0; i < n; i++) {
                    // 2바이트 → 부호 있는 16bit 정수(-32768 ~ 32767). (short) 변환이 부호를 살려 줌
                    short v = (short) ((b[body + 2 * i] & 0xFF) | (b[body + 2 * i + 1] << 8));
                    out[i] = v / 32768f;               // → -1.0 ~ 1.0
                }
                return out;
            }
            // 다음 청크로. 크기가 홀수면 1바이트 채움(padding)이 붙는 규칙이 있어요.
            p = body + size + (size & 1);
        }
        throw new IOException("WAV에 소리 데이터(data)가 없어요");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    private static boolean tag(byte[] b, int p, String t) {
        if (p + 4 > b.length) return false;
        for (int i = 0; i < 4; i++) if (b[p + i] != t.charAt(i)) return false;
        return true;
    }

    /** 리틀 엔디언 4바이트 → int.  & 0xFF: byte(-128~127)를 0~255로 바꿔서 더해야 값이 맞음 */
    private static int le32(byte[] b, int p) {
        return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 | (b[p + 2] & 0xFF) << 16 | (b[p + 3] & 0xFF) << 24;
    }

    private static int le16(byte[] b, int p) { return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8; }

    // ───────────────────────────── 쓰기 ─────────────────────────────

    /** 쓰기 진행률을 알려 받는 곳 (0.0 ~ 1.0) */
    public interface Progress { void onProgress(float fraction); }

    /**
     * float 파형(-1.0 ~ 1.0)을 22050Hz 모노 16bit PCM WAV로 써요.
     * 스트림은 닫지 않아요. (연 쪽에서 닫기)
     *
     * @param progress 진행률 알림 (null 가능)
     * @param cancel   true가 되면 쓰기를 멈추고 InterruptedIOException (null 가능)
     */
    public static void write(OutputStream out, float[] pcm, Progress progress,
                             java.util.concurrent.atomic.AtomicBoolean cancel) throws IOException {
        int dataSize = pcm.length * 2;                     // 샘플 하나 = 2바이트
        byte[] h = new byte[HEADER_SIZE];
        put(h, 0, "RIFF");
        putLe32(h, 4, 36 + dataSize);                      // RIFF 크기 = 파일 크기 - 8 = (44 - 8) + 데이터 크기
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        putLe32(h, 16, 16);                                // fmt 청크 내용 크기 (PCM은 16바이트)
        putLe16(h, 20, 1);                                 // 형식 1 = PCM (압축 없음)
        putLe16(h, 22, CHANNELS);                          // 채널 수
        putLe32(h, 24, SAMPLE_RATE);                       // 샘플레이트
        putLe32(h, 28, SAMPLE_RATE * CHANNELS * BITS / 8); // 초당 바이트 = 22050 × 1 × 2
        putLe16(h, 32, CHANNELS * BITS / 8);               // 블록 크기 = 한 시점의 바이트 수 = 2
        putLe16(h, 34, BITS);                              // 비트 수
        put(h, 36, "data");
        putLe32(h, 40, dataSize);                          // 실제 소리 데이터 크기
        out.write(h);

        // 샘플을 8192개씩 묶어서 바이트로 바꿔 씀 (하나씩 write하면 매우 느림)
        byte[] buf = new byte[8192 * 2];
        for (int i = 0; i < pcm.length; i += 8192) {
            if (cancel != null && cancel.get()) throw new java.io.InterruptedIOException("취소됨");
            int n = Math.min(8192, pcm.length - i);
            for (int k = 0; k < n; k++) {
                // -1.0 ~ 1.0 → -32767 ~ 32767. 범위를 넘는 값은 잘라서(클램프) 넘침으로 인한 잡음을 막음
                float f = Math.max(-1f, Math.min(1f, pcm[i + k]));
                int v = Math.round(f * 32767f);
                buf[2 * k] = (byte) v;                     // 아래 8bit
                buf[2 * k + 1] = (byte) (v >> 8);          // 위 8bit
            }
            out.write(buf, 0, n * 2);
            if (progress != null) progress.onProgress((i + n) / (float) pcm.length);
        }
    }

    private static void put(byte[] b, int p, String t) { for (int i = 0; i < 4; i++) b[p + i] = (byte) t.charAt(i); }

    private static void putLe32(byte[] b, int p, int v) {
        b[p] = (byte) v; b[p + 1] = (byte) (v >> 8); b[p + 2] = (byte) (v >> 16); b[p + 3] = (byte) (v >> 24);
    }

    private static void putLe16(byte[] b, int p, int v) { b[p] = (byte) v; b[p + 1] = (byte) (v >> 8); }
}
