# Books — a reader tool for the Light Phone III

Read EPUB, PDF and TXT books on your Light Phone, in the LightOS style.

## What it does

| | |
|---|---|
| **Library** | Your books, last-read first, with author and % read. **EDIT** removes a book. |
| **Reading** | Tap the right side of the page (or swipe left) for the next page, and the left side for the previous one. Tap the middle for the menu. |
| **Menu** | Back · page and chapter · a bar you drag to jump anywhere in the book · Contents · Bookmark (star) · Settings |
| **Resume** | Every book reopens on the page you left. |
| **Contents** | The book's own chapter list (EPUB), detected chapters (TXT), or a page list (PDF). |
| **Bookmarks** | Star a page, then find it again under Settings → Bookmarks. |
| **Settings** | Text size (small / medium / large), font (sans / serif), and theme (dark / light). |
| **PDF** | Shown as page images. Pinch to zoom, and drag to move around a zoomed page. In the dark theme pages are inverted to white-on-black. |

Books needs no permissions. It never uses the internet, and nothing leaves the phone.

## Adding books

Books uses the **LightOS Tool Manager**:

1. Connect your phone and computer to the same Wi-Fi.
2. Start the Tool Manager on the phone. It shows an address.
3. Open that address in your computer's browser, then choose **Books → Books** and upload your files.
4. Open Books on the phone. New books appear in the library.

Android and the Light SDK don't let tools read shared phone storage, so copying files over USB into a folder doesn't work. During development you can copy a book in with ADB instead (debug builds only):

```
adb push mybook.epub /data/local/tmp/
adb shell run-as com.yousefalrushaid.lightbooks sh -c 'mkdir -p files/shared/books && cp /data/local/tmp/mybook.epub files/shared/books/'
```

Then reopen the library.

## Running in the LightOS emulator

Follow [docs/system_app](../docs/system_app/README.md) once to install the LightOS emulator app as a system app on an API 34 `test-keys` AVD (1080 × 1240, 420 dpi). Then, for each build:

1. In `lighttool.toml`, set `serverPackage = "com.thelightphone.sdk.emulator"` (the commented line).
2. Build and install with the alternate server package allowed:

```
./gradlew -DlightSdk.toolOnly=true -DlightSdk.allowAltServerPackage=true :tool:installDebug
```

3. Copy books in with the ADB commands above, then open Books from the LightOS emulator.

Set `serverPackage` back to `"com.lightos"` before committing or submitting a build.

## Code map

| File | Role |
|---|---|
| `LibraryScreen.kt` | Start screen (`@InitialScreen`), the book list, and the remove confirmation |
| `ReaderScreen.kt` | EPUB/TXT reader: page turns, menu, keys |
| `Paginator.kt` | Splits chapters into pages that exactly fit the screen |
| `PdfReaderScreen.kt` | PDF reader built on Android's `PdfRenderer` |
| `ContentsScreen.kt`, `BookmarksScreen.kt`, `ReaderOptionsScreen.kt` | The menu screens |
| `EpubParser.kt`, `HtmlToBlocks.kt`, `TxtParser.kt` | Turn files into chapters and paragraphs |
| `BookStore.kt` | Files on disk: the books, a parsed cache, and reading positions and bookmarks |
| `Settings.kt` | Text size, font and theme, stored in DataStore |
| `ToolEntryPoint.kt` | Registers the Books folder with the Tool Manager |
| `Ui.kt` | Shared LightOS-styled pieces |

Run the parser tests with `./gradlew :tool:testDebugUnitTest`.
