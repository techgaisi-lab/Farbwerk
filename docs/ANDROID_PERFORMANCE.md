# Android: Performance-Untersuchung und erster Fix

Untersuchter Stand: `5fd70aad76c659dbd62490e72e2b738befc9ca10` (main).
Untersucht wurden Android-Eingabe, Pinsel, Compositor, Undo, Zeitraffer und Speicherung. Kein Tablet war für Messungen angeschlossen.

## Belegte Lastverstärker

1. **Automatische Auflösungsvergrößerung.** `CanvasQuality.detailFor` verwendet bisher standardmäßig hohe Qualität; auch bei ausgeschalteter hoher Qualität wurden kleine Dokumente bis zur Bildschirmgröße vergrößert. `NewCanvasMemory.highQuality` startet mit true. `Importers.fromBitmap` verwendet den Standardwert ebenfalls.
   Beispiel bei 3200 px Bildschirm-Langseite: 1920 × 1080 wird intern 5760 × 3240, also 9-fache Pixelzahl. Eine ARGB-Ebene benötigt dann rund 71,2 MiB statt 7,9 MiB, ohne GPU-Kopie, Strichpuffer und Undo. Bei Faktor 4 sind es 16-mal so viele Pixel.
   `BrushEngine.diameterFor` multipliziert bei dynamischer Pinselskalierung auch den Durchmesser mit detailScale. Texturarbeit wächst damit ungefähr quadratisch.

2. **CPU-Zeichnen auf dem UI-Thread.** `CanvasView.onMove` ruft die Pinsel-Engine direkt auf. `BrushEngine` berechnet die Live-Pixel beim Zeichnen des Overrides. `DabPainter.drawSingle` erzeugt für gekörnte Tupfer Form, Körnung und Übertragung; größere Tupfer teilen die Arbeit und warten über `Future.get` synchron. Der Schwamm nutzt Körnung, zufällige Rotation und nasse Kanten; `StrokeEffects.apply` kommt beim Absetzen hinzu. Dies sind belegte teure Pfade, aber ohne Trace kein Nachweis, welcher Anteil die beobachteten drei Sekunden verursacht.

3. **Ungebündelte Eingaben.** `onFirstDown` fordert ungebündelte Zustellung an. Jede MOVE-Zustellung löst Verwaltungsarbeit und Vorhersage aus. Die Anwendung verarbeitet bereits historische Messpunkte, sodass frameweise Bündelung alle gelieferten Messpunkte erhalten kann.

4. **Unbegrenzte Zeitraffer-Warteschlange.** Die standardmäßig aktive Aufnahme rendert auf dem Main-Thread und übergibt Bitmaps an einen Single-Thread-Executor mit unbegrenzter Queue. Langsames Schreiben kann mehrere Bitmaps festhalten. Die bestehende Ruhezeit von 350 ms vermindert die Aufnahmefrequenz beim Malen, begrenzt aber diese Queue nicht. Ein tatsächlich wachsender Rückstau wurde auf keinem Gerät gemessen.

## Implementierte Änderungen

- Standardmodus und Bildimporte behalten die gewünschten Pixelmaße (detailScale 1). Hohe Qualität bleibt ausdrücklich wählbar und behält ihre bisherigen Grenzen.
- Bestehende Dokumente behalten Auflösung und Inhalt. Dieser Fix beschleunigt sie durch die Auflösungsänderung deshalb nicht automatisch.
- Ungebündelte Zustellung entfällt; historische Samples werden weiterhin verarbeitet. Zeicheninvalidierung nach MOVE erfolgt zum nächsten Bild.
- Maximal eine Zeitraffer-Bitmap wird zum Schreiben vorgemerkt oder geschrieben. Zwischenzeitliche Änderungen werden vor dem nächsten Rendern zusammengefasst. Der Main-Thread wartet nicht auf den Schreiber. Fehler geben die Sperre wieder frei.
- Regressionstests sichern Standardauflösung, explizite hohe Qualität und Größenbegrenzungen.

Es wird keine neue GPU-Pinsel-Engine eingeführt. Der CPU-Pinselpfad, Undo-Limit (384 MiB), große Ebenentexturen, Masken und aufwendige Mischmodi bleiben mögliche Engpässe. Eine Speicherleck- oder Thermal-Throttling-Diagnose ist ohne Laufzeitprofil nicht belegt.

## Android-Abnahme (noch offen)

Auf demselben Tablet, derselben App-Buildart, mit gleicher Helligkeit und Bildwiederholrate vergleichen:

- Frisches 1920 × 1080 Dokument im Standardmodus, dann explizit hohe Qualität; jeweils zwei normale Ebenen.
- Einfacher Stift und Schwamm bei identischer sichtbarer Breite; kurze und lange Striche über mindestens zehn Minuten.
- Zusätzlich ein bereits betroffenes Dokument unverändert öffnen und testen.
- Zeitraffer an/aus, bei 1080p und höchster Aufnahmeauflösung; langsames Schreiben simulieren. Während blockiertem Schreiber darf nur eine Aufnahmebitmap ausstehen; nach Freigabe müssen neue Änderungen wieder aufgenommen werden.
- Stiftlatenz per Hochgeschwindigkeitsvideo oder Input-/Frame-Trace messen (Median, p95, Maximum), UI-Framezeiten, native/Java/GPU-Speicher und GC über die gesamte Dauer beobachten.
- CPU-/GPU-Auslastung, Thermalstatus und Energieverbrauch im gleichen Testablauf vergleichen. Akku-Prozentwerte allein sind für kurze Läufe zu grob.
- Strichbild, Druck, historische Punkte, Radieren, Undo/Redo, Auswahl, Alpha-Sperre und Symmetrie visuell prüfen.
- Nach Ende eines Strichs darf keine dauerhafte Zeichenlast bleiben.

Erfolg ist erst nachgewiesen, wenn die Stiftlatenz über die Testdauer stabil bleibt und weder Schreibwarteschlange noch Speicher kontinuierlich wachsen. Der beschriebene Drei-Sekunden-Nachlauf und die Hitzeprobleme gelten bis dahin als nicht vollständig behoben.
