# Farbwerk

Native Android-Zeichen-App für Tablets mit Stift. Funktionsumfang, Gesten, Tastenkürzel und Anordnung der Bedienelemente orientieren sich an etablierten iPad-Mal-Apps – Icons, Pinsel und Gestaltung sind eigenständig.

> Privates Projekt für den Eigengebrauch.

---

## Funktionen

| Bereich | Umfang |
|---|---|
| **Pinsel-Engine** | Stempel-Rendering mit Stiftdruck, Neigung, Tempo · StreamLine & Stabilisierung · Verjüngung · Streuung, Rotation, Körnung · Strichdeckkraft wie bei echten Mal-Apps · Nasse Farbe, Farbdynamik |
| **Werkzeuge** | Malen · Wischen · Radieren (auch per Radierer-Ende des Stifts) |
| **Pinselbibliothek** | 180 eigene Pinsel in 18 Sets, Favoriten, Zuletzt verwendet, eigene Pinsel, Pinsel-Studio mit Live-Testfläche |
| **QuickShape** | Linie, Ellipse/Kreis, Rechteck/Quadrat, Polygon, Bogen – Zeichnen & Halten, danach skalieren/drehen |
| **Ebenen** | 18 Mischmodi · Deckkraft · Alpha-Sperre · Schnittmasken · Ebenenmasken · Gruppen · Referenzebene · Sperren · Zusammenfügen per Kneifgeste |
| **Farben** | Scheibe · Klassisch · Harmonie · Werte (HSB/RGB/Hex) · Paletten (auch aus Fotos) · Pipetten-Lupe · ColorDrop-Füllung mit Schwellenwert |
| **Auswahl** | Automatisch · Freihand/Polygon · Rechteck · Ellipse · Hinzufügen/Entfernen · Umkehren · Weiche Kante · Speichern & Laden |
| **Transformieren** | Freiform · Gleichmäßig · Verzerren · Verkrümmen · Einrasten · Spiegeln · Interpolation |
| **Anpassungen** | Farbton/Sättigung/Helligkeit · Farbbalance · Kurven · Verlaufsumsetzung · Gauß-, Bewegungs- und perspektivische Unschärfe · Rauschen · Scharfzeichnen · Bloom · Glitch · Halbton · Chromatische Aberration · Verflüssigen · Klonen (Ebenen- und Stift-Modus) |
| **Zeichenhilfen** | 2D-Raster · Isometrisch · Symmetrie (vertikal, horizontal, Quadrant, radial, rotierend) |
| **Text** | Bearbeitbare Textebenen, Stil-Panel, Schriften-Import (TTF/OTF) |
| **Animation** | Zeitleiste, Zwiebelschicht, Export GIF/MP4 |
| **Dateien** | Galerie mit Stapeln · eigenes Projektformat `.farbwerk` · Export PNG, JPEG, PSD (mit Ebenen), TIFF, PDF, Ebenen als PNG · PSD-Import |

## Gesten

| Geste | Aktion |
|---|---|
| 2 Finger tippen | Rückgängig (halten = schnell rückgängig) |
| 3 Finger tippen | Wiederholen (halten = schnell wiederholen) |
| 4 Finger tippen | Vollbild ein/aus |
| 2 Finger kneifen / drehen / ziehen | Zoomen, Drehen, Verschieben |
| Schnell zusammenkneifen | Leinwand einpassen |
| 3 Finger hin und her | Ebene leeren |
| 3 Finger nach unten wischen | Kopieren-&-Einfügen-Menü |
| Finger berühren & halten | Pipette |
| Zeichnen & halten | QuickShape |

## Tastenkürzel

| Taste | Aktion | Taste | Aktion |
|---|---|---|---|
| `Strg+Z` | Rückgängig | `Strg+Umschalt+Z` | Wiederholen |
| `B` / `S` / `E` | Malen / Wischen / Radieren | `L` / `C` | Ebenen / Farben |
| `[` / `]` | Pinselgröße −/+ | `Umschalt+[` / `]` | Deckkraft −/+ |
| `X` | Farbe tauschen | `I` | Pipette |
| `Leertaste` halten | Leinwand verschieben | `Strg+0` | Einpassen |
| `Strg+T` | Transformieren | `Strg+U` | Farbton/Sättigung |
| `Strg+B` | Gaußscher Weichzeichner | `Strg+J` | Duplizieren |
| `Strg+Umschalt+N` | Neue Ebene | `Strg+E` | Nach unten zusammenfügen |
| `Strg+A` / `Strg+D` | Alles auswählen / Auswahl aufheben | `Strg+C` / `X` / `V` | Kopieren / Ausschneiden / Einfügen |
| `Tab` | Vollbild | `Strg` halten | Übersicht aller Kürzel |

## Technik

- **Sprache:** Kotlin 2.1 · **UI:** Jetpack Compose (Material 3) + eigene `View` für die Leinwand
- **Rendering:** Android-Canvas (hardwarebeschleunigt), Ebenen als `ARGB_8888`-Bitmaps, `BlendMode` (API 29+)
- **Min. SDK:** 29 (Android 10) · **Target SDK:** 35
- **Build:** Gradle 8.14, AGP 8.11

## Projektstruktur

```
app/src/main/java/at/techgaisi/farbwerk/
├── EditorState.kt        Zentraler App-Zustand
├── MainActivity.kt
├── canvas/               Leinwand, Ansicht, Mehrfinger-Gesten
├── model/                Dokument, Ebenen, Werkzeuge, Ebenen-Operationen
├── undo/                 Verlauf und Pixel-Bearbeitungssitzungen
├── brush/                Pinsel-Engine, Bibliothek, QuickShape, Wischen
├── render/               Compositor (Mischmodi, Masken, Gruppen)
├── color/                Pipette, ColorDrop, Paletten
├── selection/            Auswahlwerkzeuge, Zwischenablage
├── transform/            Transformieren inkl. Verkrümmen
├── adjust/               Filter, Verflüssigen, Klonen
├── guides/               Raster und Symmetrie
├── io/                   Projektspeicher, PSD/TIFF/PDF-Export, Importe
├── input/                Tastenkürzel
├── prefs/                Einstellungen
└── ui/                   Compose-Oberfläche (Panels, Galerie, Leisten)
```

## Bauen & installieren

```bash
# Debug-APK bauen
./gradlew assembleDebug

# Auf verbundenes Tablet installieren
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Voraussetzungen: JDK 17+, Android SDK (Platform 35). Pfad zum SDK in `local.properties` (`sdk.dir=...`).

## S Pen

- Druck, Neigung und Ausrichtung je Messpunkt, Druckkurve und -glättung
- Bewegungsvorhersage und ungepufferte Eingabe für geringe Latenz
- Handballenerkennung; nach Stifterkennung malt nur noch der Stift
- Schwebevorschau des Pinsels
- Seitentaste: beim Berühren Pipette (einstellbar), in der Luft Radierer an/aus (einstellbar)

## Dokumentation

- [`docs/FUNKTIONSANLEITUNG.md`](docs/FUNKTIONSANLEITUNG.md) – Funktions- und Verhaltensbeschreibung aller Bereiche
- [`docs/LUECKEN.md`](docs/LUECKEN.md) – Abgleich mit dem Umsetzungsstand und Arbeitspakete

## Windows-Version

Das Modul `desktop/` baut dieselbe App als Windows-Programm (Compose Multiplatform Desktop). Es kompiliert die
unveränderten Quellen aus `app/` gegen eine Kompatibilitätsschicht, die die benutzten Android-APIs auf Skia/AWT abbildet.

- **Installer:** wird von GitHub Actions (`.github/workflows/windows.yml`) bei jedem Push auf `main` als `.exe` und `.msi` gebaut → Artefakt „Farbwerk-Windows“
- **Lokal starten (Linux/Windows):** `./gradlew :desktop:run`
- **Eingabe:** Maus/Stift malen, Mausrad zoomt, mittlere Taste verschiebt, rechte Taste = Pipette, Touch-Gesten wie auf dem Tablet
- **Einschränkungen:** kein Stiftdruck über AWT, kein MP4-Export (GIF/PNG stattdessen)

## Bekannte Einschränkungen

- Gruppen sind nicht verschachtelbar
- Nasse/verbrannte Pinselkanten erscheinen erst beim Absetzen
- Maximale Leinwandgröße 8192 px pro Seite bzw. 25 Megapixel
- Kein Zeitraffer-Video, kein PDF-Import
- Doppelpinsel und ABR-Import fehlen

## Schrift

Die Oberfläche nutzt **Inter** (SIL Open Font License 1.1, siehe [`licenses/Inter-OFL.txt`](licenses/Inter-OFL.txt)).

## Lizenz

Privat – alle Rechte vorbehalten.
