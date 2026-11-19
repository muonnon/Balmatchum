package com.example.balmatchum;

/**
 * "음 하나를 내라"는 신호를 받는 곳(싱크, sink = 물이 흘러 들어가는 곳이라는 뜻).
 *
 * 왜 인터페이스로 나눴나요?
 *  - 곡을 연주하는 계산(NoteSequencer)은 하나인데, 결과를 쓰는 곳은 두 군데예요.
 *      · 재생      : 지금 바로 스피커로 소리를 냄           → SoundPoolSink
 *      · 내보내기  : 소리 파형을 버퍼에 더해서 WAV 파일로 만듦 → PcmMixSink
 *  - 계산 코드를 복사해서 두 벌 만들면, 한쪽만 고쳤을 때 재생과 파일 소리가 달라져요.
 *    그래서 계산은 한 곳(NoteSequencer)에서만 하고, "결과를 어디로 보낼지"만 이 인터페이스로 바꿔 끼워요.
 *
 * interface: "이런 메서드가 있어야 한다"는 약속만 적은 것. 실제 내용은 구현하는 클래스(implements)가 채워요.
 */
public interface NoteSink {

    /**
     * 음(또는 드럼 소리) 하나를 내요.
     *
     * @param sound  소리 번호 (SoundPalette.ALL의 칸 번호. 이름은 SoundPalette.ALL[sound].name)
     * @param rate   재생 속도 (0.5 ~ 2.0). 1.0 = 원래 음높이, 2.0 = 한 옥타브 위
     * @param volume 볼륨 (0.0 ~ 1.0). 설정 화면의 전체 볼륨은 곱하기 전 값 (재생 쪽에서 곱함)
     * @param timeNs 곡의 처음부터 이 음이 나는 위치까지의 시간 (가상 시계, 나노초)
     */
    void onNote(int sound, float rate, float volume, long timeNs);
}
