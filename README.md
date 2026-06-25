# Audio Extractor for Android

极简安卓音频提取应用 — 选择本地 MP4/MP3 文件，提取音频并转为 AAC 格式，统一响度到 -14 LUFS。

## 功能

- ✅ 多选本地 MP4 / MP3 文件
- ✅ 自动检测音频轨道（无音频轨道的文件自动跳过）
- ✅ 提取音频并转换为 AAC 格式（192kbps）
- ✅ EBU R128 响度标准化至 -14 LUFS（使用 FFmpeg loudnorm 滤镜）
- ✅ 采样率可选：原始 / 44100 Hz / 48000 Hz / 96000 Hz
- ✅ 输出到 Download/AudioExtractor/ 目录
- ✅ 批量处理，实时进度显示

## 技术方案

### 报错解决

1. **"无音频轨道"问题**：使用 `MediaExtractor` + `FFprobeKit` 双重检测，无音频流的文件自动跳过并标注
2. **"Only the original thread that created a view hierarchy can touch its views"**：FFmpegKit 回调在后台线程，所有 UI 更新通过 `Handler(Looper.getMainLooper()).post {}` 切回主线程

### FFmpeg 命令

```
ffmpeg -y -i input.mp4 -af "loudnorm=I=-14:TP=-1.5:LRA=11,aresample=44100" -c:a aac -b:a 192k -vn output.aac
```

### 依赖

- FFmpegKit 6.0-2.LTS（最后一个稳定版）
- AndroidX / Material Components
- 最低 SDK 24（Android 7.0）

## 编译

需要 Android Studio Hedgehog (2023.1.1) 或更高版本，JDK 17。

```bash
./gradlew assembleRelease
```

APK 输出：`app/build/outputs/apk/release/app-release.apk`

## 适用设备

- 骁龙 865 / 4GB RAM 或更高
- Android 7.0+ (API 24+)
