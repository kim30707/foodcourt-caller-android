# 서명 키 만들기·백업

앱은 **항상 같은 서명 키**로 만들어야 합니다. 키가 바뀌면 새 버전을 덮어 설치할 수 없고, 앱을 지웠다 다시 깔면 기기 안의 설정이 모두 지워집니다.
**키를 잃어버리면 모든 태블릿과 방송 폰에서 앱을 지우고 다시 깔아야 합니다.**

## 1. 키 만들기(한 번만)

JDK 17이 필요합니다(macOS: `brew install openjdk@17`).

```bash
keytool -genkeypair -v \
  -keystore foodcourt-release.keystore \
  -alias foodcourt \
  -keyalg RSA -keysize 2048 -validity 36500 \
  -dname "CN=Foodcourt Caller, O=sehotech, C=KR"
```

`foodcourt-release.keystore` 파일과 비밀번호가 생깁니다. 키스토어 비밀번호와 키 비밀번호는 같게 두면 관리가 쉽습니다.

## 2. GitHub Secrets에 넣기

```bash
base64 -i foodcourt-release.keystore | gh secret set KEYSTORE_BASE64 -R <계정>/foodcourt-caller-android
gh secret set KEYSTORE_PASSWORD -R <계정>/foodcourt-caller-android   # 물어보면 비밀번호 입력
gh secret set KEY_ALIAS -R <계정>/foodcourt-caller-android -b foodcourt
gh secret set KEY_PASSWORD -R <계정>/foodcourt-caller-android        # 물어보면 비밀번호 입력
```

워크플로(`.github/workflows/build.yml`)는 이 네 값으로 매번 같은 키로 서명합니다. `KEYSTORE_BASE64`가 없으면 빌드를 일부러 실패시킵니다(서명 안 된 APK가 나가지 않게).

## 3. 백업(대표 PC)

- 키스토어 파일과 비밀번호는 **저장소에 올리지 않습니다**(`.gitignore`에 `*.keystore`, `*.jks`가 들어 있음).
- 현재 대표 PC 백업 위치: `~/Documents/푸드코트/서명키/foodcourt-release.keystore`
- 비밀번호는 같은 폴더의 `비밀번호.txt`에 있습니다. 가능하면 비밀번호 관리자로 옮기고 이 파일은 지우세요.
- 한 곳 더 백업하세요: 암호를 건 USB, 개인 클라우드 드라이브(비공개) 등. 키스토어 파일과 비밀번호는 서로 다른 곳에 두면 더 안전합니다.

## 4. 확인

GitHub Actions 로그의 "APK 이름 고정·서명 확인" 단계에 서명 인증서 SHA-256이 찍힙니다. 이 값이 빌드마다 같으면 같은 키입니다.

로컬에서 확인할 때:

```bash
keytool -list -v -keystore foodcourt-release.keystore -alias foodcourt | grep SHA256
```
