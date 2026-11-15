# Farbwerk 1.0.0

Die erste vollständige Version von Farbwerk: eine native Zeichen-App für Android-Tablets mit S Pen, dazu eine Windows-Version mit demselben Funktionsumfang.

## Highlights

- **184 Pinsel in 18 Sets** mit selbsterklärenden Namen – von Fineliner und Bleistift über Aquarell, Nass in Nass und Kohle bis Airbrush, Texturen und Leuchten. Pinsel-Studio mit Live-Testfläche, Favoriten und eigenen Pinseln.
- **Gestochen scharf:** Leinwände werden intern bis zu 4× feiner berechnet. Striche bleiben auch stark gezoomt glatt. Das gilt auch beim Malen auf importierten Bildern mit niedriger Auflösung.
- **Was du siehst, ist was du bekommst:** Der Strich beim Malen entspricht pixelgenau dem Ergebnis nach dem Absetzen.
- **S Pen wie ein Profi-Stift:** Druck, Neigung, Bewegungsvorhersage, Handballenerkennung, Schwebevorschau, Seitentaste für Pipette und Radierer.
- **Ebenen:** 18 Mischmodi, Masken, Schnittmasken, Gruppen, Alpha-Sperre, Referenzebene.
- **Farben:** Scheibe, Klassisch, Harmonie, Werte, Paletten (auch aus Fotos), ColorDrop-Füllung durch Ziehen der Farbe auf die Leinwand.
- **Werkzeuge:** Auswahl, Transformieren, QuickShape (Zeichnen & Halten), Symmetrie und Raster, Text, Animation mit Zwiebelschicht, Zeitraffer.
- **Anpassungen:** Farbton/Sättigung, Kurven, Verlaufsumsetzung, Unschärfen, Bloom, Glitch, Halbton, Verflüssigen, Klonen und mehr.
- **Dateien:** Galerie mit Stapeln, eigenes Format `.farbwerk`, Export PNG, JPEG, PSD mit Ebenen, TIFF, PDF, GIF, MP4. PSD-Import.
- **Gesten und Tastenkürzel** wie bei professionellen Zeichen-Apps – Zwei-Finger-Tippen für Rückgängig, Kneifen zum Einpassen, `Strg` halten für die Übersicht aller Kürzel.

## Neu seit 0.7.0

- Versionssprung auf 1.0, keine funktionalen Änderungen gegenüber 0.7.0.

## Verbesserungen aus den letzten Versionen

- **Flüssigere Pinsel:** Nass in Nass ist meist 5–7× schneller, Schwamm etwa 3–7×. Airbrush, Nebel, Farbrolle und Lavieren laufen deutlich ruhiger. Das Absetzen großer Striche geht spürbar schneller.
- **Kein Schleier mehr:** Bleistift-artige Pinsel ziehen keinen feinen grauen Rand mehr neben dem Strich.
- **Scharfes Malen auf kleinen Bildern:** Importierte Fotos und kleine eigene Leinwände werden automatisch feiner berechnet.
- **Schnelle Galerie:** Die Rückkehr zur Galerie erfolgt sofort, gespeichert wird im Hintergrund.
- **Sofortige Pinselvorschauen:** Die Vorschauen in der Pinselbibliothek sind vorgeladen.
- **Ruhiger Start:** Lade-Skelett statt Logo beim Öffnen, stabilerer Foto-Import, Absturzbericht.

## Bekannte Einschränkungen

- Nasse und verbrannte Pinselkanten erscheinen erst beim Absetzen.
- Sehr große Airbrushes (ab etwa 2000 px) auf 4×-Leinwänden reagieren noch verzögert.
- Maximale Leinwandgröße 8192 px pro Seite bzw. 25 Megapixel.
- Windows: kein Stiftdruck, kein MP4-Export.

## Installation

**Android** (ab Android 10): APK auf das Tablet laden und öffnen, Installation aus unbekannten Quellen erlauben. Oder per ADB:

```
adb install -r Farbwerk-1.0.0.apk
```

**Windows:** Installer (`.exe` / `.msi`) aus dem GitHub-Actions-Artefakt „Farbwerk-Windows“.

**Linux** (Debian/Ubuntu, x86-64): `sudo apt install ./farbwerk_1.0.0_amd64.deb`, danach im App-Menü unter Grafik oder per `/opt/farbwerk/bin/Farbwerk`.

Die APK ist debug-signiert.
