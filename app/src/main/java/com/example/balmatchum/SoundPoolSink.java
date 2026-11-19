package com.example.balmatchum;

import android.media.SoundPool;   // 짧은 소리 여러 개를 미리 불러 두고 빠르게 재생하는 도구

/**
 * 재생용 NoteSink: 음 신호를 받는 즉시 SoundPool로 스피커에 소리를 내요.
 * (리팩터링 전 MusicEngine이 pool.play()를 직접 부르던 것과 똑같은 동작)
 *
 * timeNs는 쓰지 않아요. 재생에서는 음악 스레드가 "그 시각이 될 때까지 기다렸다가" onNote를 부르므로
 * 신호가 온 지금이 곧 소리를 낼 시각이에요.
 */
public final class SoundPoolSink implements NoteSink {

    private final SoundPool pool;
    private final int[] soundIds;          // [SoundPalette 번호] = SoundPool이 준 소리 ID

    // 설정 화면의 전체 볼륨 (0.0 ~ 1.0). 화면 스레드가 바꾸고 음악 스레드가 읽으므로 volatile
    private volatile float masterVolume = 0.7f;

    /**
     * @param pool     소리를 불러 둔 SoundPool
     * @param soundIds SoundPalette 번호 → SoundPool 소리 ID 표 (MusicEngine이 load하면서 채움)
     */
    public SoundPoolSink(SoundPool pool, int[] soundIds) {
        this.pool = pool;
        this.soundIds = soundIds;
    }

    /** 전체 볼륨을 바꿔요. 다음 음부터 바로 반영돼요. */
    public void setMasterVolume(float v) { masterVolume = v; }

    @Override
    public void onNote(int sound, float rate, float volume, long timeNs) {
        // 곡이 정한 볼륨 × 전체 볼륨. 예) 하이햇 0.6 × 전체 0.7 = 0.42
        float v = volume * masterVolume;
        // play(소리ID, 왼쪽볼륨, 오른쪽볼륨, 우선순위, 반복횟수(0=한 번), 재생속도)
        pool.play(soundIds[sound], v, v, 1, 0, rate);
    }
}
