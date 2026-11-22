# MetaBatch

Android 批量图片元数据编辑器：先看原信息，再决定改什么。当前版本 v0.7.0。

## 功能
- 多选图片（Storage Access Framework），缩略图网格浏览，点一张放大查看，详情页左右滑动切换整批图
- 整文件夹导入（只看第一层，JPG / PNG / WebP）
- 查看每张图的原信息：EXIF + XMP（标题、描述、关键词、作者、版权、来源、拍摄时间、相机、软件、方向、GPS、尺寸）
- 一键把某张图的原值填入批量编辑表单，在原值基础上改
- 批量写入标题、描述、关键词、作者、版权、来源（XMP + 常用 EXIF 字段），原图不覆盖，另存到输出文件夹；不填的字段就是空白，会清除该项原值
- 模板：把当前表单存个名字，下次一键套用
- 历史：每次导出记时间、成功/失败张数、输出文件夹、用时，点一行重新打开那个文件夹
- 输出设置：固定输出文件夹（选一次就记住）、命名方式、保留原图开关、覆盖同名、导出格式（原格式无损，或转 JPEG / PNG / WebP）
- 深色模式跟系统走
- 支持 JPEG、PNG、WebP；HEIC、TIFF、AVIF、RAW 暂时安全跳过
- XMP 按原始字节读取，UTF-8 / GBK / UTF-16 解码，中文标题不再乱码；XMP 按原始字节写入，中文不再变 `?`

## 下一步
HEIC/TIFF/AVIF/RAW 支持、完整 IPTC+XMP 双写、真机元数据往返验证、签名正式版。

## 致谢
本项目用到的主要开源组件：
- Jetpack Compose（含 Material 3、Material Icons）、ExifInterface、DocumentFile —— AndroidX（Apache 2.0）
- Kotlin 协程 —— JetBrains（Apache 2.0）
- 初始 v0.1–v0.3 原型来自一次 ChatGPT 会话，后续由 Muse 重写与扩展

## License
MIT
