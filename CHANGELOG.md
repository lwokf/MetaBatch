# Changelog

## 0.4.2
- Fixed Chinese keywords/titles turning into "??" on write. Root cause, proven against exifinterface 1.4.2: ExifInterface encodes TAG_XMP as US-ASCII, so every non-ASCII character became '?'. XMP is now written as raw UTF-8 bytes by a new XmpPacketWriter (JPEG APP1 segment / PNG iTXt chunk / WebP XMP chunk surgery), never through ExifInterface. Chinese round-trips intact; verified by unit tests.
- Fixed a write wiping the photo's original metadata: the old writer built a fresh XMP packet from the form alone, so any field left blank erased that field. Blank form fields now keep the photo's existing values (merge-on-write); only filled fields overwrite. The edit screen says so.
- EXIF Description/Artist/Copyright are now written only when the value is pure ASCII (EXIF ASCII tags cannot hold other scripts; ExifInterface would turn them into '?'). Non-ASCII values live in the XMP packet, which the reader prefers.

## 0.4.1
- Fixed garbled (mojibake) original metadata: the XMP packet is now read as raw bytes straight from the file (JPEG APP1 / PNG iTXt / WebP XMP chunk) and decoded with BOM detection plus a UTF-8 → GBK → Latin-1 fallback, instead of relying on ExifInterface's UTF-8-only string, which turned GBK/UTF-16 titles into replacement characters.

## 0.4.0
- Added photo browsing: picked images show as a thumbnail grid; tap one to view it full-size.
- Added original-metadata inspection: EXIF + XMP are read and shown per photo (file, size, dimensions, date, camera, GPS, title, description, keywords, creator, copyright, source) before any edit.
- Added "fill edit form with these values": one tap copies a photo's original values into the batch edit form so they can be tweaked instead of typed from scratch.
- Added unit tests for XMP parsing, including a builder round-trip.

## 0.3.0
- Extracted XMP packet serialization for isolated tests.
- Added tests for XML escaping, keyword RDF Bag representation and empty metadata.
- Added GitHub Actions Android test/build workflow.
- Improved empty source file handling and unsupported-format messaging.

## 0.2.0
- Initial batch metadata-writing implementation for JPEG, PNG and WebP.
