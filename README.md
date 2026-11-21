# MetaBatch

Android 批量图片元数据编辑器：先看原信息，再决定改什么。

## 功能
- 多选图片（Storage Access Framework），缩略图网格浏览，点一张放大查看
- 查看每张图的原信息：EXIF + XMP（标题、描述、关键词、作者、版权、来源、拍摄时间、相机、软件、方向、GPS、尺寸）
- 一键把某张图的原值填入批量编辑表单，在原值基础上改
- 批量写入标题、描述、关键词、作者、版权、来源（XMP + 常用 EXIF 字段），原图不覆盖，另存到输出文件夹
- 支持 JPEG、PNG、WebP；HEIC、TIFF、AVIF、RAW 暂时安全跳过
- XMP 按原始字节读取，UTF-8 / GBK / UTF-16 解码，中文标题不再乱码

## 构建
Android Studio + JDK 17 + Android SDK 35，同步 Gradle 后 Build > Build APK(s)。
命令行：`./gradlew testDebugUnitTest assembleDebug`（若缺少 gradle-wrapper.jar，先跑一次 `gradle wrapper --gradle-version 8.9`）。
推到 GitHub 后 Actions 自动跑测试、出 APK（见 `.github/workflows/android.yml`，APK 发布到 `debug-latest`）。
Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

## 下一步
HEIC/TIFF/AVIF/RAW 支持、完整 IPTC+XMP 双写、真机元数据往返验证、签名正式版。

## License
MIT
