# 📝 AcNotes - Modern Offline-First Android Notes

AcNotes is a lightning-fast, pure native Android note-taking application designed for seamless markdown writing, distraction-free reading, native KaTeX math rendering, and silent cloud synchronization.

---

## ✨ Features

- **⚡ Offline-First & Instant Local Performance:**
  - Built directly on top of native Android SQLite with zero bloat.
  - Sub-millisecond read/write speeds with non-blocking asynchronous coroutine execution.
  - Zero-allocation precomputed previews for silky smooth 120 FPS scrolling in long lists.

- **🔍 True In-Memory Full-Text Search:**
  - Instant real-time search across titles, folders, previews, and full body content without latency.

- **🔗 Logseq-Grade Bidirectional WikiLinks & Backlinks:**
  - **WikiLink Syntax (`[[Note Title]]`):** Interconnect notes into a Personal Knowledge Network.
  - **Editor Helper:** Quick-insert toolbar chip `[[ Note` with interactive title search and on-the-fly note creation.
  - **Clickable Reader Pills:** Seamlessly jump to referenced notes with a single tap in the reader.
  - **Automatic Linked References (Backlinks):** Expandable bottom drawer listing all incoming references to the current note with previews.
  - **Deep Navigation History:** Effortlessly navigate back and forth through webs of interconnected notes.

- **✍️ Smart Markdown Writing Engine:**
  - **Intelligent List & Task Continuation:** Pressing `Enter` on `- [ ] ` or `- ` automatically continues the next item with matching indentation.
  - **Smart Cleanup:** Pressing `Enter` on an empty list/task marker automatically removes the prefix and exits list mode.
  - **Smart Heading Insertion:** `H1` and `H2` toolbar chips automatically snap to the start of the current line.
  - **LaTeX Math Toolbar:** Quick-insert chips for display math (`$$`) and inline math (`$`).
  - **Bulletproof Auto-Save:** Automatically saves changes when navigating away or using system back gestures.

- **📐 KaTeX & Markdown Reader:**
  - 100% offline math rendering powered by local KaTeX assets (zero internet connection needed).
  - Isolated math-token substitution prevents Markdown parsers from interfering with LaTeX expressions.
  - Interactive checklists in reading mode: tap any task checkbox to update state and instantly persist changes.

- **☁️ Cloudflare R2 Two-Way Sync:**
  - S3-compatible cloud storage sync using pure Java/Kotlin AWS SigV4 request signing (no heavyweight AWS SDK dependencies).
  - Handles 10,000+ notes seamlessly with S3 pagination markers.
  - Deletion tombstone synchronization ensures deleted notes are never resurrected across devices.
  - Obsidian-compatible `.md` files with YAML frontmatter saved under `.Vault/`.

- **🔐 Cryptographically Signed Releases:**
  - Configured with a dedicated persistent release keystore.
  - Enables seamless in-place APK updates without needing to uninstall previous versions.

---

## 🛠️ Tech Stack & Architecture

- **Language:** Kotlin 1.9.24
- **UI Framework:** Jetpack Compose with Material 3 (BOM 2024.04.01)
- **Architecture:** Unidirectional Data Flow (UDF) with MVVM pattern (`NotesViewModel` & Kotlin `StateFlow`)
- **Database:** Native Android `SQLiteOpenHelper` with transactional batching
- **Networking:** OkHttp 4.12.0 + AWS Signature Version 4 implementation
- **Math & Markdown:** KaTeX + Marked.js + DOMPurify hosted locally in Android Assets
- **CI/CD:** Automated GitHub Actions pipeline building signed Release APKs

---

## 🚀 Building & Releasing

To build a signed release APK locally:

```bash
./gradlew assembleRelease
```

The signed output APK will be located at:
```
app/build/outputs/apk/release/app-release.apk
```

---

## 📄 License

Private & Proprietary. All rights reserved.
