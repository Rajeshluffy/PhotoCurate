# PhotoCurate 📸

[![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Build](https://img.shields.io/badge/Build-Maven-C71A36?logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![UI](https://img.shields.io/badge/GUI-Java%20Swing-blue)](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/javax/swing/package-summary.html)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey)](#prerequisites)

**PhotoCurate** is a high-performance desktop photo culling and curation application built with Java Swing. Designed for photographers who need to quickly review large photo sessions (thousands of high-resolution images), inspect sharpness at 100% 1:1 pixel fidelity, compare similar bursts side-by-side, and export selections non-destructively to an output directory.

---

## ✨ Key Features

- **⚡ Progressive Multi-Pass Downscaling**
  - Standard Java2D single-pass downscaling causes severe aliasing and softness on large 24–60MP photos. PhotoCurate uses **progressive 2x halving steps with bicubic interpolation**, rendering sharp, artifact-free previews matching native OS photo viewers.
  - Pixel-perfect **1:1 rendering**: when viewed at 100% zoom, original raster pixels are directly blitted with zero interpolation.

- **🔄 Automatic EXIF Orientation Correction**
  - Scans JPEG APP1 (`0xE1`) metadata segments to extract tag `0x0112` (Orientation) without heavy third-party dependencies.
  - Portrait and rotated shots are displayed upright automatically with lossless transformations.

- **🔍 Fluid Zoom & Pan Inspection**
  - Zoom smoothly from **5% to 800%** using mouse wheel or keyboard shortcuts.
  - Click and drag to pan across high-resolution photos to verify eye focus and critical sharpness.
  - Double-click or press `0` to instantly reset to "fit-to-window".

- **⚖️ Side-by-Side Comparison Workspace (Compare Tray)**
  - Quickly stage candidate photos into a comparison tray by pressing `C`.
  - Open a dedicated multi-cell comparison grid (`G`) where each candidate has an independent zoom/pan panel.
  - Toggle **Keep** or **Remove** per photo to eliminate near-duplicates before final export.

- **📋 Dedicated Review of Selected Images**
  - Filter and inspect only marked photos before finalizing (`V`).
  - Drop unwanted selections on a second pass with `D`.

- **🧵 Responsive Off-EDT Asynchronous Loading**
  - Background loading via `SwingWorker` with request-token invalidation ensures rapid keyboard navigation (`←`/`→`) never stutters or locks the UI.

- **🛡️ Safe & Non-Destructive Export**
  - Source directories and original files remain untouched.
  - Export copies selected photos to your chosen destination folder with collision detection (auto-renames duplicates like `photo_1.jpg`) to prevent data loss.

- **⌨️ Keyboard-First Workflow**
  - Designed for speed: full curation and review can be completed without reaching for the mouse.

---

## ⌨️ Keyboard Shortcuts Cheat Sheet

| Key | Action | Description |
| :--- | :--- | :--- |
| <kbd>→</kbd> | **Next Image** | Navigate to the next photo in the sequence |
| <kbd>←</kbd> | **Previous Image** | Navigate to the previous photo in the sequence |
| <kbd>Y</kbd> | **Toggle Select** | Mark / unmark current photo for export |
| <kbd>R</kbd> | **Rotate 90° CW** | Lossless 90-degree clockwise rotation |
| <kbd>C</kbd> | **Toggle Compare** | Add or remove current photo from the Compare Tray |
| <kbd>G</kbd> | **Open Comparison** | Open side-by-side comparison window for staged photos |
| <kbd>V</kbd> | **Review Selected** | Open review mode containing only selected photos |
| <kbd>0</kbd> | **Reset Zoom** | Fit image to window (also available via double-click) |
| <kbd>+</kbd> / <kbd>=</kbd> | **Zoom In** | Increase magnification (up to 800%) |
| <kbd>-</kbd> | **Zoom Out** | Decrease magnification (down to 5%) |
| <kbd>Mouse Wheel</kbd> | **Smooth Zoom** | Zoom centered around current view |
| <kbd>Mouse Drag</kbd> | **Pan** | Move around when zoomed in |
| <kbd>D</kbd> | **Remove (Review Mode)** | Deselect image while in the "Review Selected" window |
| <kbd>ESC</kbd> | **Finish / Close** | Exit comparison/review dialogs or prompt to copy selected photos |

---

## 🖼️ Supported Image Formats

PhotoCurate scans folders recursively and supports common photographic image formats:
- **JPEG / JPG** (`.jpg`, `.jpeg`) — *with automated EXIF orientation decoding*
- **PNG** (`.png`)
- **Bitmap** (`.bmp`)
- **GIF** (`.gif`)
- **Sony RAW** (`.arw`, `.ARW`) — *when supported by local ImageIO plugins*

---

## 🚀 Getting Started

### Prerequisites

- **Java JDK 17** or higher installed and configured on your `PATH`.
  ```bash
  java -version
  ```
- **Apache Maven 3.8+** (optional, recommended for build automation) or Eclipse / IntelliJ IDEA.

---

### Installation & Running

#### Option 1: Run with Maven
```bash
# Clone or navigate to the project root
cd PhotoCurate

# Compile the project
mvn clean compile

# Launch the application
mvn exec:java -Dexec.mainClass="com.photocurate.app.PhotoCurateApp"
```

#### Option 2: Run via Eclipse IDE
1. Open Eclipse IDE.
2. Select **File > Import... > Existing Maven Projects**.
3. Choose the `PhotoCurate` root directory.
4. Locate `src/main/java/com/photocurate/app/PhotoCurateApp.java`.
5. Right-click and choose **Run As > Java Application**.

#### Option 3: Compile & Run via CLI (Standard JDK)
```bash
# Compile
javac -d bin src/main/java/com/photocurate/app/PhotoCurateApp.java

# Run
java -cp bin com.photocurate.app.PhotoCurateApp
```

---

## 📖 How to Use

1. **Launch PhotoCurate**:
   - The main setup window will appear.
2. **Select Folders**:
   - **Source Folder**: Choose the folder containing your raw or exported shoot photos (subdirectories are scanned recursively).
   - **Target Folder**: Choose or create a folder where selected "keepers" should be copied.
3. **Scan**:
   - Click **Scan for Images** to index all photos.
4. **Start Review**:
   - Click **Start Review** to enter full-screen review mode.
5. **Cull & Select**:
   - Press <kbd>→</kbd> / <kbd>←</kbd> to cycle through photos.
   - Press <kbd>Y</kbd> to select keepers (highlighted with a green border and checkmark).
   - Press <kbd>C</kbd> on similar shots to stage them into the comparison tray, then press <kbd>G</kbd> to compare them side-by-side.
   - Use <kbd>V</kbd> anytime to review your current selection list.
6. **Finish & Export**:
   - Press <kbd>ESC</kbd> or click **Finish**.
   - Confirm export when prompted. All selected photos will be copied safely into the target folder.

---

## 📂 Project Structure

```
PhotoCurate/
├── .classpath                       # Eclipse classpath definition
├── .project                         # Eclipse project metadata
├── pom.xml                          # Maven build configuration
├── README.md                        # Project documentation
└── src/
    └── main/
        └── java/
            └── com/
                └── photocurate/
                    └── app/
                        └── PhotoCurateApp.java   # Core application entry point and GUI components
```

---

## 🛠️ Architecture & Under the Hood

- **Asynchronous Worker Threads**: Image decoding occurs on background worker threads (`SwingWorker`), preventing large file I/O from stuttering the Event Dispatch Thread (EDT). Stale loads are discarded via token counters if the user navigates past an image before it finishes loading.
- **Progressive Halving Downscaling**: Instead of downscaling a 6000×4000 photo to 1200×800 in one aggressive bicubic pass (which drops up to 80% of pixels and creates jaggies), PhotoCurate repeatedly halves resolution by at most 50% per pass until reaching the target dimensions.
- **Low-Level EXIF Segment Parsing**: Direct binary parsing of the JPEG APP1 marker segment extracts the standard TIFF orientation tag without requiring bulky external metadata extraction libraries.
- **Collision-Safe File System Copying**: Uses Java NIO `Files.copy` with automated index suffix generation (`<name>_1.ext`, `<name>_2.ext`) to ensure zero accidental overwrites.

---

