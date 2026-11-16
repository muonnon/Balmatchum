<div align="center">

# 🎵 발맞춤 (Balmatchum)

**걸음이 음악이 되는 안드로이드 앱 — 걷는 속도에 맞춰 음악을 실시간으로 만들어 줍니다**

[![Java](https://img.shields.io/badge/Java-Android-orange?style=for-the-badge&logo=openjdk&logoColor=white)](https://developer.android.com/)
[![Android Studio](https://img.shields.io/badge/Android_Studio-IDE-3DDC84?style=for-the-badge&logo=androidstudio&logoColor=white)](https://developer.android.com/studio)
[![SQLite](https://img.shields.io/badge/SQLite-Database-blue?style=for-the-badge&logo=sqlite&logoColor=white)](https://www.sqlite.org/)
[![License](https://img.shields.io/badge/License-Educational-green?style=for-the-badge)](#-라이선스)

</div>

---

## 📋 목차

- [소개](#-소개)
- [주요 기능](#-주요-기능)
- [구현 현황](#-구현-현황)
- [화면 구성](#-화면-구성)
- [스크린샷](#-스크린샷)
- [시작하기](#-시작하기)
    - [사전 요구사항](#사전-요구사항)
    - [설치](#설치)
    - [실행](#실행)
- [사용법](#-사용법)
- [음악 생성 방식](#-음악-생성-방식)
- [프로젝트 구조](#-프로젝트-구조)
- [기술 스택](#-기술-스택)
- [리소스 출처](#-리소스-출처)
- [개발자](#-개발자)
- [라이선스](#-라이선스)

---

## 🎯 소개

발맞춤(Balmatchum)은 걷는 속도를 BPM으로 바꾸고, 그 박자에 맞춰 음악을 실시간으로 생성하는 안드로이드 앱입니다.

### 배경

산책이나 러닝 중에 음악을 들을 때, 재생 중인 곡의 박자는 지금의 걸음 속도와 무관합니다. 천천히 걷고 있는데 빠른 곡이 나오거나, 속도를 올렸는데 느린 곡이 이어지는 일이 흔합니다.

### 목적

본 프로젝트는 **걸음 간격을 BPM으로 환산하여 외부 음원 없이 음악을 직접 생성**하고, 걷는 속도와 음악의 박자가 항상 맞도록 하는 것을 목표로 합니다. 걸음을 마치면 그 걸음을 '걸음 곡'으로 저장하여 다시 들을 수 있습니다.

---

## ✨ 주요 기능

### 🚶 걸음 기반 실시간 음악
| 기능 | 설명 |
|------|------|
| **걸음 감지** | 걸음 감지 센서(`TYPE_STEP_DETECTOR`)로 걸음마다 이벤트 수신 |
| **BPM 계산** | 최근 걸음 간격의 평균으로 실시간 BPM 계산 |
| **음악 생성** | BPM에 맞춰 킥, 하이햇, 멜로디를 규칙 기반으로 재생 |

### 💾 걸음 곡 저장
- 걷기 종료 시 날짜, 걸음 수, 평균 BPM 등을 SQLite에 저장
- 저장된 곡 목록 조회, 정렬, 삭제
- 저장한 곡 정보로 같은 곡 다시 듣기

### 🎛️ 설정
- 스케일(분위기) 선택
- 사운드 테마 선택 (8bit, Lo-fi, 피아노)
- 볼륨 조절, 비트 켜기, 걷는 동안 화면 켜두기

---

## 🚧 구현 현황

- [x] 걸음 감지와 BPM 계산 (시뮬레이션 버튼 포함)
- [x] SoundPool 기반 박자 재생 (킥, 하이햇, 펜타토닉 멜로디)
- [x] 시작/정지 버튼, 걷기 결과 표시
- [ ] 걸음 곡 저장 (SQLite)
- [ ] 걸음 곡 목록 화면
- [ ] 곡 상세와 다시 듣기, BPM 그래프
- [ ] 설정 화면
- [ ] 걸음 반응 비주얼라이저 (Custom View)

---

## 🖼️ 화면 구성

| 화면 | 역할 |
|------|------|
| **걷기 (홈)** | 시작·정지, 실시간 BPM, 비주얼라이저, 걷은 시간·걸음 수·평균 BPM |
| **걸음 곡 목록** | 저장된 곡 조회, 정렬, 삭제 |
| **곡 상세** | BPM 변화 그래프, 다시 듣기, 삭제 |
| **설정** | 스케일, 사운드 테마, 볼륨, 스위치 |

하단 탭(걷기, 기록, 설정)으로 화면을 이동하고, 곡 목록에서 곡을 선택하면 곡 상세로 이동합니다.

---

## 📸 스크린샷

> 📷 *스크린샷 추가 예정*

<!--
<div align="center">
<img src="./screenshots/walk.png" width="22%" />
<img src="./screenshots/list.png" width="22%" />
<img src="./screenshots/detail.png" width="22%" />
<img src="./screenshots/settings.png" width="22%" />
</div>
-->

---

## 🚀 시작하기

### 사전 요구사항

- **Android Studio** (최신 안정 버전 권장)
- **Java** 기반 프로젝트 (Android Studio에 포함된 JDK 사용)
- **실행 기기**: 안드로이드 폰(USB 또는 무선 디버깅) 또는 에뮬레이터
- **최소 SDK**: 26

### 설치

1. **저장소 클론**
   ```bash
   git clone [저장소 주소]
   ```

2. **Android Studio에서 열기**
    - `File` → `Open` → 클론한 `Balmatchum` 폴더 선택
    - Gradle 동기화가 끝날 때까지 기다립니다.

### 실행

#### 📱 실제 폰에서 실행
1. 폰의 `설정` → `개발자 옵션`에서 **USB 디버깅** 또는 **무선 디버깅**을 켭니다.
2. Android Studio 상단 기기 목록에서 폰을 선택합니다.
3. `Run` 버튼 클릭 또는 `Shift + F10`
4. 처음 실행하면 **신체 활동** 권한을 허용합니다.

#### 💻 에뮬레이터에서 실행
1. 기기 목록에서 에뮬레이터(예: Pixel 8 Pro)를 선택하고 실행합니다.
2. 에뮬레이터에는 걸음 센서가 동작하지 않으므로 **"걸음 시뮬레이션"** 버튼으로 걸음을 만듭니다.

---

## 📖 사용법

### 1️⃣ 걷기 시작
1. 앱을 실행하고 **[시작]** 버튼 클릭
2. 폰을 들고 걸으면 걸음 수와 BPM이 표시되고, 박자에 맞춰 소리가 재생됩니다.
3. 에뮬레이터에서는 **[걸음 시뮬레이션]** 버튼을 일정한 박자로 누릅니다.

### 2️⃣ 걷기 종료
1. **[정지]** 버튼 클릭
2. 걷은 시간, 걸음 수, 평균 BPM이 표시됩니다.

### 3️⃣ 걸음 곡 보기 (구현 예정)
1. 하단 탭에서 **기록** 선택
2. 저장된 곡을 선택하면 곡 상세에서 다시 들을 수 있습니다.

---

## 🎼 음악 생성 방식

| 요소 | 규칙 |
|------|------|
| **박자 단위** | 16분음표 (한 칸 길이 = 60000 / BPM / 4 ms) |
| **킥** | 매 박(4칸마다) |
| **하이햇** | 엇박(4칸 중 3번째 칸) |
| **멜로디** | 8분음표마다 펜타토닉 음계에서 한 칸 위, 아래, 제자리 중 무작위 이동 |
| **음높이** | 기본음 샘플 하나의 재생 속도(rate)를 바꿔 조절 |

---

## 📁 프로젝트 구조

```
Balmatchum/
├── README.md                     # 프로젝트 소개 (현재 파일)
└── app/src/main/
    ├── java/com/example/balmatchum/
    │   ├── MainActivity.java     # 걷기 화면, 걸음 감지, BPM 계산
    │   └── MusicEngine.java      # SoundPool 기반 박자·멜로디 재생
    └── res/
        ├── layout/               # 화면 레이아웃
        └── raw/                  # 소리 샘플 (note.wav, kick.wav, hat.wav)
```

---

## 🛠️ 기술 스택

| 분류 | 기술 |
|------|------|
| **언어** | Java |
| **플랫폼** | Android |
| **걸음 감지** | `SensorManager`, `TYPE_STEP_DETECTOR` |
| **소리 재생** | `SoundPool`, 별도 스레드로 박자 진행 |
| **데이터 저장** | SQLite |
| **화면** | 기본 레이아웃, Custom View (Canvas) |

### 테스트 환경

| 구분 | 내용 |
|------|------|
| **실기기** | 갤럭시 S25 (SM-S931N), Android 17 |
| **에뮬레이터** | Pixel 8 Pro, API 37.1 |
| **필요 권한** | `ACTIVITY_RECOGNITION` (신체 활동) |

---

## 🎧 리소스 출처

- 소리 샘플(`note.wav`, `kick.wav`, `hat.wav`): 프로그램으로 생성한 임시 8bit풍 소리 (외부 음원 사용 없음)
- 외부 코드 인용: -

---

## 📄 라이선스

이 프로젝트는 **교육 목적**으로 개발되었습니다.

2026-2학기 안드로이드 앱 프로젝트

---

<div align="center">

Made with 🎵 by muonnon

</div>
