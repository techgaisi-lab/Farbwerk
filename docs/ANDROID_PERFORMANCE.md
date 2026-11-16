# Android: Performance-Untersuchung und erster Fix

Untersuchter Stand: `5fd70aad76c659dbd62490e72e2b738befc9ca10` (main).
Gemeldetes Gerät: Galaxy Tab S10 Ultra mit laut Nutzer aktueller One UI. Der Einbruch tritt beim Hinzufügen von Ebenen und Verwenden mehrerer unterschiedlicher Pinsel auf; genaue Android-/One-UI-Buildnummer und Leinwandgröße sind noch unbekannt.

Untersucht wurden Android-Eingabe, Pinsel, Compositor, Undo, Zeitraffer und Speicherung. Kein Tablet war für Messungen angeschlossen.

## Aktualisierte Eingrenzung: zusätzliche Ebene genügt

Der anschließende Nutzertest präzisiert und ersetzt die Bildimport-Hypothese: Auf einem frischen Dokument läuft der Schwamm auf der ersten Ebene flüssig. Schon das Hinzufügen einer zweiten leeren Ebene führt ohne Bild zum massiven Einbruch. Nach einem Bildimport bringen Ausblenden und Löschen zusätzlicher Ebenen laut Nutzer die Flüssigkeit nicht zurück; ein neues Dokument läuft wieder flüssig.

Damit ist ein Bildimport keine notwendige Bedingung. Der Fix für die Standardauflösung löst diesen Ebenen-Trigger nicht. Noch offen ist, ob der Einbruch am aktiven Ebenenindex, an der Anzahl vorhandener/retinierter Bitmaps oder an GPU-/Ansichtsressourcen hängt.

Als gezielte Diagnose wird lokal eine Variante `1.0.0-render-test2` gebaut: In `CanvasView` ist ausschließlich der Split-Renderpfad über eine Diagnosekonstante ausgeschaltet. `Compositor.draw` zeichnet das vollständige Dokument direkt; Ebenen, Masken, Deckkraft, Mischmodi und Pinselparameter bleiben bestehen. Die separate Paketkennung ist `at.techgaisi.farbwerk.perftest` (VersionCode 18), derselbe Testschlüssel wie bei der bisherigen separaten Test-App. Diese Renderänderung ist noch nicht als bewiesener Fix in den PR übernommen.

Prüfablauf für diese Variante: frisches Dokument → Schwamm auf Ebene 1 → zweite leere Ebene hinzufügen → gleicher Schwamm → zweite Ebene löschen → weiterzeichnen. Anschließend Bildimport vergleichen. Auf dem Gerät noch nicht geprüft; bloßer Build-/Unit-Test-Erfolg bestätigt keine GPU-Performance.

## Frühere Eingrenzung durch den Gerätetest (überholt)

Der Nutzer meldet nun einen unmittelbaren A/B-Unterschied bei Bildschirmgröße, sowohl mit normaler als auch 2×-Leinwand: Auf leerer Leinwand laufen auch aufwendige Pinsel flüssig; nach dem Einfügen eines Bildes stottert derselbe Pinsel stark, mit geschätzt 0,2 FPS und deutlichem Nachlauf. Dies ist eine Nutzerbeobachtung, keine instrumentierte FPS-Messung. Die frühere Auflösungsänderung erklärt diesen Auslöser nicht und behebt ihn nach diesem Test nicht ausreichend.

Im geprüften Code kopiert `Importers.insertImageAsLayer` das Bild einmal in eine reguläre dokumentgroße Bitmap und aktiviert Transformieren. Beim regulären Werkzeugwechsel wird die Transformation übernommen oder verworfen. Während des Malens zeichnet `BrushEngine.override` den unveränderten Inhalt der aktiven Ebene zusätzlich zum Strichpuffer; `Compositor.drawLayer` isoliert Overrides über `saveLayer`. `CanvasView.drawSplit` kann unveränderte Ebenen darunter/darüber cachen, jedoch nicht den Inhalt der aktiven Ebene. Das sind untersuchte Codepfade, noch kein Nachweis eines Fehlers darin.

Noch zu klären:
- Zeichnen direkt auf dem eingefügten Bild oder auf einer leeren Ebene darüber?
- Wird Zeichnen auf einer leeren Ebene sofort wieder flüssig, wenn ausschließlich die Bildebene ausgeblendet wird (gleiche Ansicht, Pinselbreite und Einstellungen)?
- Welche installierte Variante wurde getestet?
- Bleibt der Einbruch nach Speichern und erneutem Öffnen bestehen? Dies würde einen nur vorübergehend aktiven Transformations-Override weiter eingrenzen.

Der nächste Fix muss diesen Bild-einfügen-Vergleich bestehen. Weitere Änderungen an Pinselqualität oder Auflösung allein gelten dafür nicht als ausreichende Abnahme.

## Belegte Lastverstärker

1. **Automatische Auflösungsvergrößerung.** `CanvasQuality.detailFor` verwendet bisher standardmäßig hohe Qualität; auch bei ausgeschalteter hoher Qualität wurden kleine Dokumente bis zur Bildschirmgröße vergrößert. `NewCanvasMemory.highQuality` startet mit true. `Importers.documentFromImage` verwendet den Standardwert ebenfalls.
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

## Lokale Prüfung

- Android-Kotlin-Kompilierung erfolgreich.
- Beide Tests in `CanvasQualityTest`: bestanden, keine Fehler, keine übersprungenen Tests.
- `:app:assembleDebug`: erfolgreich; APK erstellt.
- `git diff --check`: erfolgreich.
- Verwendet wurden JDK 21 und Gradle 8.14.3. Lokal fehlt Build Tools 35.0.0; daher erfolgte die Prüfung mit den installierten Build Tools 36.0.0 über ein externes Init-Skript. Ein lokaler Testschlüssel wurde ebenfalls nur darüber gesetzt. Die Projektdateien wurden hierfür nicht verändert.
- Kein per ADB angeschlossenes Android-Gerät: Eingabegefühl, Bildgleichheit, Zeitraffer-Rückstau und Langzeittemperatur sind nicht am Gerät verifiziert.

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
