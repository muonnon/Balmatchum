package com.example.balmatchum;

import android.content.Context;
import android.media.SoundPool;

public class MusicEngine {
    private static final int[] SCALE = {0, 2, 4, 7, 9, 12};   // 펜타토닉 (반음 수)
    private final SoundPool pool;
    private final int note, kick, hat;
    private volatile int bpm = 100;
    private volatile boolean running = false;
    private Thread thread;
    private int degree = 2;                                    // 현재 음 위치

    public MusicEngine(Context c) {
        pool = new SoundPool.Builder().setMaxStreams(6).build();
        note = pool.load(c, R.raw.note, 1);
        kick = pool.load(c, R.raw.kick, 1);
        hat  = pool.load(c, R.raw.hat, 1);
        pool.setOnLoadCompleteListener((p, id, status) ->
                android.util.Log.d("BAL", "load id=" + id + " status=" + status));
    }

    public void setBpm(int b) { bpm = Math.max(50, Math.min(200, b)); }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(() -> {
            int step = 0;
            long next = System.nanoTime();
            while (running) {
                android.util.Log.d("BAL", "step " + step);
                if (step % 4 == 0) pool.play(kick, 1, 1, 1, 0, 1f);        // 1,2,3,4박
                if (step % 4 == 2) pool.play(hat, 0.6f, 0.6f, 1, 0, 1f);   // 엇박
                if (step % 2 == 0) {                                        // 8분음표마다 멜로디
                    degree = Math.max(0, Math.min(SCALE.length - 1,
                            degree + (int) (Math.random() * 3) - 1));       // -1, 0, +1
                    float rate = (float) Math.pow(2, SCALE[degree] / 12.0);
                    pool.play(note, 0.8f, 0.8f, 1, 0, rate);
                }
                step = (step + 1) % 16;
                next += (long) (60_000_000_000L / bpm / 4);                 // 16분음표 길이
                long wait = next - System.nanoTime();
                if (wait > 0) {
                    try { Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000)); }
                    catch (InterruptedException e) { break; }
                }
            }
        });
        thread.start();
    }

    public void stop() { running = false; if (thread != null) thread.interrupt(); }
    public void release() { stop(); pool.release(); }
}