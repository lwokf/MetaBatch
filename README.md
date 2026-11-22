# MetaBatch

Android 批量图片元数据编辑器：先看原信息，再决定改什么。当前版本 v0.7.0。

## 功能
- 批量写入标题、描述、关键词、作者、版权、来源，不填的字段就是空白、会清除原值
- 先看原信息再改：每张图的 EXIF、XMP 都能看，一键把原值填入表单
- 模板：存一次，下次一键套用
- 历史：每次导出都有记录，点一行打开输出文件夹

## 下一步
HEIC/TIFF/AVIF/RAW 支持、完整 IPTC+XMP 双写、真机元数据往返验证、签名正式版。

## 致谢
本应用由 Muse 设计与构建，ChatGPT 亦有参与。
本项目用到的主要开源组件：
- Jetpack Compose（含 Material 3、Material Icons）、ExifInterface、DocumentFile —— AndroidX（Apache 2.0）
- Kotlin 协程 —— JetBrains（Apache 2.0）

## License
MIT
