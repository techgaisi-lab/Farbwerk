# Android: gekachelte Zeichen- und Darstellungsengine

## Umfang

Implementiert auf Basis von `6f412c76661d01510df46630d68db43681db5fef` (vorheriger Diagnose-PR). Ausgangspunkt auf main: `5fd70aad76c659dbd62490e72e2b738befc9ca10`.
Zielgerät laut Rückmeldung: Galaxy Tab S10 Ultra, aktuelle One UI. Bisheriger Gerätetest: bei 2× ab etwa Ebene 3 starker Einbruch, ohne 2× erst um Ebene 12. Eine direkte Hardwaremessung dieses Geräts war hier nicht verfügbar.

Die Dokumentauflösung bleibt erhalten. Hohe Qualität ist im Dialog für neue Leinwände wieder standardmäßig aktiviert. Bildimporte behalten ihre eigenen Pixelmaße.

## Umgesetzte Architektur

### Sparse Pixelspeicherung

`PixelTiles` verwaltet 256×256-Pixel-Kacheln. Vollständig leere Kacheln werden nicht gespeichert. Andere Kacheln halten verlustfrei komprimierte, vormultiplizierte Originalbytes; wenn Kompression nichts spart, bleiben die Rohbytes erhalten.

`Layer.empty` legt keine dokumentgroße Bitmap an. Die normale Darstellung, Malen/Radieren, Pixelsicherung, Undo/Redo und Layer-Picking greifen auf Kacheln zu. Ein begrenzter gemeinsamer Cache hält dekodierte Bitmaps für die Darstellung. Unveränderliche Kacheldaten können zwischen Ebene, Sicherung und Speichermomentaufnahme geteilt werden.

Ältere Werkzeuge können weiterhin eine vollständige Bitmap anfordern. Nach abgeschlossenen normalen Aktionen wird diese kompatible Ansicht wieder in Kacheln überführt. Masken-Stellvertreter behalten ausdrücklich die Identität ihrer gemeinsam verwendeten Maskenbitmap, damit Änderungen weiterhin auf die echte Maske wirken. Der Stellvertreter-Cache hält seine Werte schwach.

### Begrenzte Darstellung und Zoomstufen

`TiledCompositor` speichert zusammengesetzte Bereiche statt vollständiger hochauflösender Ebenen als Bildschirmtexturen. Unveränderte Kacheln werden wiederverwendet. Eine Pixeländerung aktualisiert die betroffene Quellkachel und gegebenenfalls ihre unmittelbaren Filter-Nachbarn. Ebeneneigenschaften, Masken und Strukturänderungen gehen in die Signaturen ein.

Beim Herauszoomen werden passende Anzeigestufen erzeugt, beim Hineinzoomen feinere. Der Anzeigecache verändert weder Dokumentpixel noch Exportauflösung. Filterränder und Dokumentränder werden berücksichtigt. Unterschiedliche Zoomstufen können innerhalb des begrenzten LRU-Caches bestehen bleiben.

Während eines kompatiblen Pinselstrichs werden die unveränderten Ebenen darunter/darüber getrennt von aktiver Ebene und Strich behandelt. Auch der unveränderte Inhalt der aktiven Bildebene erhält einen begrenzten Anzeigecache. Die bisherigen zwei bildschirmgroßen Split-RenderNodes entfallen.

### GPU-Pinselvorschau

`GpuStroke` zeichnet geeignete laufende Pinselstriche über hardwarebeschleunigte Android-RenderNodes in kleine Kacheln. Der texturierte Pfad von `DabPainter` rastert Form und Körnung dabei auf dem Hardware-Canvas.

Die GPU-Vorschau ist ein zusätzlicher Pfad für geeignete Mal-/Radierpinsel. Der Schwamm gehört bei normalem Malen ohne Symmetrie zu diesen Kandidaten. CPU-Pfade bleiben für andere Fälle bestehen. Bei überschrittenem Vorschau-Budget wird auf den CPU-Pfad zurückgefallen.

Beim Absetzen wird der gespeicherte Tupferverlauf mit dem bisherigen CPU-Maler festgeschrieben. Dadurch bleibt dieser die Referenz für gespeicherte Pixel und Effekte wie nasse Kanten. Dies ist keine vollständig auf GPU umgeschriebene Engine: Commit, Smudge, komplexe Sonderfälle und ältere Werkzeuge können weiterhin CPU-Arbeit verursachen. GPU-Vorschau und CPU-Commit können aufgrund unterschiedlicher Rasterizer visuell leicht abweichen; eine Hardware-Bildprüfung ist offen.

### Speicherbudget und Messung

`EngineMemory` begrenzt die verwalteten Arbeitsmengen geräteabhängig. Der Gesamtansatz liegt zwischen 128 und 512 MiB; komprimierte Java-Pixel erhalten zusätzlich höchstens die Hälfte des maximalen Java-Heaps, gedeckelt auf 384 MiB. Auf Geräten mit großem Budget sind bis zu 48 MiB dekodierter Kachelcache, 128 MiB zusammengesetzter Anzeigecache und 32 MiB GPU-Strichvorschau vorgesehen.

Undo berücksichtigt Quellpixel, Masken, Caches und Pinselarbeit und bleibt höchstens bei 128 MiB. Unter Speicherdruck wird Verlauf gekürzt. Zusätzliche Ebenen werden anhand tatsächlicher Belegung statt einer vollen Bitmap pro leerer Ebene zugelassen. Zu große Importe/Zeichenarbeiten werden abgewiesen, statt die Dokumentauflösung still zu reduzieren. Android-Speicherwarnungen geben Caches frei und kürzen History.

Diese Grenzen betreffen vom Programm verwaltete Daten und konservative Schätzungen für GPU-Texturen/Kommandos. Sie sind keine harte Obergrenze für den gesamten Prozess oder die vom Grafiktreiber verwalteten GPU-Ressourcen. Temporäre Bitmaps älterer Werkzeuge sind weiterhin ein zu prüfender Bereich.

Debug-Builds zeigen Android-Framezeiten, Frames/s während des Zeichnens, p95, GPU-Dauer, verwendeten Vorschaupfad, Commit-Dauer sowie Java-/Native-Speicher und verwaltete Teilbudgets. Dieselben Angaben erscheinen unter dem Logcat-Tag `Farbwerk-Engine`.

## Projekte und Exporte

Das bestehende interne FWL-Format und das portable PNG-Projektformat bleiben lesbar und schreibbar. Interne Projekte werden kachelweise geladen; leere Ebenen benötigen beim Laden keine volle Bitmap. Speichermomentaufnahmen sind für die Pixel unveränderlich, sodass spätere Striche sie nicht verändern. PNG-Projektexporte materialisieren jeweils eine Ebene; PSD-Exporte geben die temporäre Ebenenansicht nach deren Kodierung wieder auf.

Komplexe Werkzeuge und besondere Mischmodi besitzen weiterhin kompatible CPU-/Bitmap-Fallbacks. Sie sind noch nicht sämtlich in vollständig sparse Algorithmen umgeschrieben. Keine Desktop-Dateien wurden bearbeitet; andere Plattformen und die dort mitgenutzten Quellen wurden auf Wunsch nicht gebaut oder geprüft.

## Automatisierte Validierung

Native Grafiktests mit Robolectric (Android API 34) prüfen:

- 50 leere Ebenen mit 6320×3950 Pixeln belegen 0 Ebenen-Pixelbytes; Metadaten und Ansichtspuffer kommen zusätzlich hinzu.
- Schreiben über Kachelgrenzen, Entfernen leerer Kacheln und unveränderliche Sicherungen.
- Bitgenaue vormultiplizierte Pixel nach Kompression sowie kompatible Bitmap-Bearbeitung.
- FWL-Kodierung und direktes sparse Laden.
- Transparente Undo/Redo-Zustände und gemeinsam verwendete Maskenbitmap.
- Gleichheit von gekachelter und direkter Zusammensetzung, darunter Multiply und sparse Schnittmasken.
- Wiederverwendung unveränderter zusammengesetzter Kacheln.
- Begrenzter dekodierter Cache und Vorschau-Budget.
- Anzeigepyramide ohne Reduktion der gespeicherten Auflösung.
- CPU-Festschreiben aufgezeichneter texturierter Tupfer.
- Runde durch internes und portables Projektformat mit 50 Ebenen und detailScale 2.
- Gerätebudget und History-Budget sowie bestehende Auflösungstests.

Lokaler Build: JDK 21, Gradle 8.14.3. Build Tools 36.0.0 werden über ein externes Init-Skript verwendet, da lokal 35.0.0 fehlt. Die vorgesehene Projektversion bleibt unverändert. Die separate Test-APK verwendet VersionCode 19 / `1.0.0-engine-test3`, Paket `at.techgaisi.farbwerk.perftest`, denselben lokalen Testschlüssel wie die vorigen separaten Test-APKs und den Namen „Farbwerk Engine-Test 3“.

Der lokale Tablet-Emulator konnte wegen nicht verfügbarer VM-Beschleunigung in dieser Umgebung nicht starten. Native Unit-Grafiktests ersetzen keine Prüfung des GPU-Treibers auf dem Samsung-Tablet.

## Noch erforderliche Geräteabnahme

1. Test-App aktualisieren. Die normale Farbwerk-Installation bleibt separat.
2. Neue Bildschirm-Leinwand mit hoher Qualität anlegen; sichtbare Pixelmaße im Diagnosefeld prüfen.
3. Auf Ebene 1, 3, 12, 25 und 50 jeweils mehrere Sekunden mit demselben Schwamm und derselben sichtbaren Pinselbreite zeichnen. Ebenen teils leer lassen, teils bemalen; Fotos separat hinzufügen.
4. Diagnosefeld für jeden Stand festhalten: Vorschaupfad, Frames/s, p95, Commit-Dauer, Java/Native-Speicher und Teilbudgets.
5. Dasselbe Projekt speichern, schließen, erneut öffnen; Strichbild, Ebenenreihenfolge, Transparenz, Undo/Redo, Masken und Zoomgrenzen prüfen.
6. Mindestens zehn Minuten weiterzeichnen und Temperatur/Energieverbrauch unter gleichen Geräteeinstellungen vergleichen. Es darf keine fortlaufende Verschlechterung oder wachsender Rückstau entstehen.

Eine bestimmte FPS-Zahl, 50 vollflächige Fotoebenen bei beliebiger Auflösung oder die Behebung von Überhitzung/Akkuverbrauch sind bisher nicht am Gerät nachgewiesen. Die Änderungen bleiben deshalb ein prüfbarer Entwurf.
