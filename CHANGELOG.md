# Changelog

## 0.10.0
- HEIC/HEIF support (2026-11-27): HEIC files now read and write like the other images — same seven fields, same blank-clears rule. New pure-Kotlin BMFF parser (`HeicXmp`, no new dependencies): XMP lives in a `mime` item in `meta`, EXIF in an `Exif` item (TIFF header parsed for title/description/author/copyright/software/date/camera), dimensions from `ispe`. Write is XMP-only: the EXIF tags inside the file's Exif item are NOT updated (rewriting the TIFF IFD is a second surgery — documented, not silently skipped in the sense that the fields do round-trip through XMP, which the reader prefers). New packet data is always appended at end of file, never overwriting existing item bytes; registering a first XMP item grows `meta` and patches every `iloc` offset by the same delta (same trick as MP4 `stco` patching). Refused with a clear message, source untouched: no `meta`/`iinf`/`iloc` box, `idat`-stored item data (construction_method 1). GPS is not read from HEIC in this build. Format conversion out of HEIC (JPEG/PNG/WebP capsules) goes through the normal decode path and works on API 28+; on older devices that file fails with a clear message.
- IPTC+XMP dual write (2026-11-27): JPEG writes now also write IPTC-IIM in an APP13 "Photoshop 3.0" segment (record 2: title 2:05, description 2:120, keywords 2:25, creator 2:80, copyright 2:116, source 2:115; UTF-8 declared with 2:90 ESC % G when non-ASCII). Software has no IPTC dataset, so it stays XMP-only. Blank clears: blank fields are omitted, an all-blank write removes the IPTC block; other 8BIM blocks in the segment are preserved. Reading priority is now XMP > IPTC > EXIF (IPTC fills only the fields XMP leaves blank). PNG/WebP have no standard IPTC container, so dual-write is JPEG-only. Both writes are verified by reading the values back; a mismatch fails that file with a message, source untouched.

## 0.9.4
- Critical data-loss fix (bo, 2026-11-23): exporting with 覆盖同名文件 on, output folder = source folder, and 不保留原图 off deleted the whole batch. Cause: after replacing a file in place, the system file provider gives the recreated file the same document URI as the source (URIs are path-based), so the post-export "delete originals" step deleted the files just written. My 0.9.3 naming fix exposed this — before it, the _1 names accidentally protected the new files. Now a source is never deleted when its URI is one of the outputs (new `deletableSources`, unit-tested). Files already deleted by 0.9.3 cannot be brought back by the app — re-export from wherever the originals still exist.

## 0.9.3
- Overwrite fix (bo, 2026-11-23): with 覆盖同名文件 on, re-exporting a batch came out as "name_1" instead of overwriting. Cause: the name check ran before the overwrite delete, so the old file still sitting in the folder counted as a collision and the name got numbered — the delete then removed nothing. Now, with overwrite on, files already in the folder don't count as collisions (the overwrite step deletes them); two sources in one batch mapping to the same name are still numbered.

## 0.9.2
- Parallel export (bo, 2026-11-23): batch export now runs up to 3 files at once instead of one after another — each file is independent (own temp file, own output name), so this is the main speed lever. Output names are reserved atomically (a new NameAllocator, unit-tested with 20 threads), results are reported to the UI in input order so the progress list stays correct, and videos take all 3 slots and run alone because Mp4Xmp holds a whole video in RAM.

## 0.9.1
- Naming fix (bo, 2026-11-23): in 原文件名 mode with no format conversion, the output name is now the original filename verbatim. Before, the code split the name and rebuilt it from the lowercased extension, so "photo.jpeg" came out as "photo.jpg" and "PHOTO.JPG" as "PHOTO.jpg". Only a real format change (e.g. JPG → PNG) rewrites the suffix now.

## 0.9.0
- Software is now editable (bo, 2026-11-23): the detail view always showed 软件 (e.g. ComfyUI - ChillImageSavePlus) but the form had no field for it. The form now has a seventh field 软件 at the end, same blank-clears rule as the other six. It is stored as `tiff:Software` in XMP (UTF-8, so Chinese names survive) and in EXIF TAG_SOFTWARE when the value is pure ASCII; reading prefers XMP and falls back to EXIF, so files other apps wrote still show their software. Templates save and apply it too; videos get it through XMP as well.

## 0.8.2
- Keywords no longer pre-filled (bo, 2026-11-23): the form used to start with 风景、旅行、自然 as sample keywords (a leftover from the v0.3 prototype). Now keywords start blank like every other field, and the "默认模板" menu entry restores blank too.

## 0.8.1
- Video playback controls (bo, 2026-11-23): the detail view now has a simple control bar — play/pause button, a thin 2dp progress line (drag to scrub, tap to jump), current/total time (0:12 / 1:05), and a mute/unmute chip. Tapping the video itself still toggles play/pause, and a big play button shows while paused.

## 0.8.0
- Video support (bo, 2026-11-23): MP4 and MOV now work like images — same six fields (title, description, keywords, author, copyright, source), same blank-clears rule, same "view original first, fill the form in one tap" flow. XMP is written as raw UTF-8 bytes into a `uuid` box in `moov/udta` (pure Kotlin, no new dependencies); MP4 chunk offsets (`stco`/`co64`) are patched when moov grows before mdat so playback doesn't break, and fragmented MP4s are refused with a clear message instead of being silently corrupted. Reading falls back to the standard atoms (©nam/©art) when a video has no XMP yet. Detail view plays the video (muted autoplay, tap to pause/resume) and shows duration; thumbnails use the first frame. Videos always keep their original format — the format capsules only apply to images. MKV/WebM/AVI are not supported (different containers).

## 0.7.0
- Bottom nav rework (bo, 2026-11-22): the Picker and Editor tabs duplicated what Home already does (photo grid + metadata form), so they are gone from the nav. The Picker screen is still reachable from Home ("已选 N 张 ＞"); the form lives on Home only. The two freed tabs are now **模板** and **历史** (the two remaining modules from the design spec): Templates saves the current form under a name and applies it in one tap (also reachable from the form's "模板 ▾" menu → "管理模板…"); History records every export (time, success/fail counts, folder, duration, newest first, max 50) and tapping a row reopens that folder. Both persist in SharedPreferences as JSON.
- Duplicate settings removed (bo, 2026-11-22): the Home header gear is gone — Settings lives only in the bottom nav now.

## 0.6.3
- Swipe browsing (bo, 2026-11-22): the detail view is now a HorizontalPager — swipe left/right to switch photos through the current batch, with a "N / M" counter in the top bar. Neighbouring pages are pre-composed and their 2048px images pre-decoded on an IO thread, so the swipe animation stays smooth. The "把原值填入编辑表单" button fills from the page you are on.

## 0.6.2
- Folder-import crash (bo, 2026-11-22): after importing a whole folder the app sometimes crashed. Two real defects in that path, both fixed: (1) the folder enumeration ran on the main thread — a big folder stalls it; it now runs on an IO coroutine with a "正在读取文件夹…" notice. (2) The metadata prefetch launched one coroutine + one full recomposition per photo (hundreds at once for a folder); it now reads 25 at a time with one state update per chunk. Also added a crash catcher: if it ever crashes again, the next launch shows the crash reason on the Home screen (one time), so the exact cause can be pinned down.

## 0.6.1
- Back button (bo, 2026-11-22): pressing the system back key on a sub-page (Picker / Editor / Settings) now returns to Home instead of exiting the app — those screens had no BackHandler, so the press fell through to the Activity. Home still exits as before; the detail view still closes first; the progress screen still blocks back for its whole lifetime (including the 600 ms tail before the Done screen).

## 0.6.0
- Dark mode (bo, 2026-11-22): the whole UI now follows the system dark theme — page, cards, fields, chips, bottom nav, top bars and the Material3 color scheme all switch to a dark palette; status-bar / nav-bar icons and colors follow too. The export-progress screen was already dark and is unchanged. Palette lives in `Mb` (state-driven getters, no call-site changes).
- Folder import (bo, 2026-11-22): Home now has "选择文件夹" next to "选择图片" — it imports every JPG/PNG/WebP in the first level of a folder at once, sorted by name, all selected by default. The system file picker cannot pick a folder and has no select-all, so whole-folder import only works this way; the app's own picker screen already has 全选 in its top bar.
- Picker grid is now a lazy grid (LazyVerticalGrid): a folder import can bring hundreds of images, only visible thumbnails are decoded now.

## 0.5.0
- Full UI redesign per MetaBatch · Modern UI V3.0 (bo's final six-screen spec, 2026-11-22): Home / Pick images / Edit metadata / Output settings / Export progress / Export done, with a 4-tab bottom nav (Home, Images, Edit, Settings). Visual language: solid premium purple, mist-white / light-gray background, fewer decorative gradients, more whitespace and hierarchy. The original-metadata detail view is kept (tap a thumbnail) — it is not one of the six screens, but bo's rule stands: inspect the original first, then decide what to change.
- Output settings screen (new): output folder with pin/change, naming mode (original name + _metabatch, or original name), "keep original" switch (on = keep; off = delete the original after a successful export), "overwrite same-name files" switch (off = auto-rename, as before), export format capsules (Original / JPEG / PNG / WebP). Format conversion is real: a different format decodes the image, bakes in EXIF rotation, and re-encodes (JPEG/WebP quality 95, PNG lossless); Original keeps the lossless byte-copy path.
- Export progress screen (dark): progress ring, current-file card, per-file task list; cancel is disabled by design. Done screen: success/failure counts, failed-file list, "View files" (opens the output folder) and "Back to home".
- Metadata editor: keywords are now removable chips with an add row; description shows a character count (capped at 500); a "Template" menu offers Default template and Clear form. The form is shared between Home and the editor screen.
- Blank = CLEAR rule (v0.4.4) is unchanged; Chinese XMP still written as raw UTF-8 bytes; pinned output folder unchanged.

## 0.4.4
- Blank means CLEAR (bo's rule, 2026-11-22): a form field left empty is written as blank and removes that field's original value from the photo. This reverts the v0.4.2 merge-on-write behavior, which kept originals for blank fields. EXIF Description/Artist/Copyright are removed with setAttribute(tag, null) when blank (verified: null removes the tag) or non-ASCII; the XMP packet already omits blank fields.

## 0.4.3
- Output folder is now pinned: pick it once and it stays fixed across launches (SAF tree URI + persistable permission saved in SharedPreferences; the stored permission is checked on startup and a stale entry is dropped silently). The folder name is shown, with "Change folder" and "Unpin folder" actions.

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
