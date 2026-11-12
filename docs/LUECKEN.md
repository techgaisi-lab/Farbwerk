# Farbwerk – Lückenanalyse gegen die Funktionsanleitung

Stand: Code unter `app/src/main/java/at/techgaisi/farbwerk/**` (ca. 20 000 Zeilen) gegen
`docs/FUNKTIONSANLEITUNG.md`. Kapitelverweise (§) beziehen sich auf die Anleitung.

**Legende Status:** ✅ vorhanden · 🟡 teilweise (was fehlt steht dabei) · ❌ fehlt
**Priorität:** **P1** = Kernworkflow, sofort spürbar · **P2** = wichtig, fortgeschrittene Nutzung · **P3** = selten / Ausbau
**Paket:** Arbeitspaket aus Abschnitt C (WP0 = Kern-Änderung durch den Koordinator).

**Ausgeklammert (laufen bereits parallel):**
- **[A] S-Pen-Agent:** CanvasView-Eingabe, Hover-Cursor, Seitentaste, Palm Rejection, Latenz (§31). Punkte mit „→ A“
  sind dort zu erledigen bzw. brauchen Hooks von dort.
- **[B] Farb-Agent:** `ui/color/**`, `color/**`, Farbkreis in `ui/EditorScreen.kt`, ColorDrop aus der oberen Leiste
  (§14, §15.1). Punkte mit „→ B“ gehören dorthin.

---

## A. Kurzfazit

Die Grundarchitektur steht und ist breit: Pinsel-Engine mit Studio, Ebenen inkl. Gruppen/Masken/Schnittmasken,
alle Anpassungen des Vorbilds, Auswahl, Transform mit Verkrümmen, Galerie mit Stapeln, PSD-Ein-/Ausgabe, QuickShape,
2D/Isometrie/Symmetrie. Die größten Lücken liegen bei

1. **Bedien-„Feinmechanik“ des Kernworkflows** (P1): Popover-Animation/-Pfeil, Seitenleiste (Regler-Vorschau, Größen-
   speicher, Halten zum Wiederholen, sichtbar auch in Auswahl/Transform/Anpassungen), Modifizierer-Taste-Semantik,
   Hinweisleiste mit Aktions-Knopf („Form bearbeiten“, „Weiter füllen“), Tastenbelegung wie im Vorbild.
2. **Fehlenden Ebenen-Gesten und Mischmodi** (P1/P2): 2-Finger-Tippen (Deckkraft), 2-Finger-Wischen (Alpha-Sperre),
   2-Finger-Halten (Inhalt auswählen), Solo-Sichtbarkeit, 9 fehlende Mischmodi, Live-Vorschau-Liste.
3. **Anpassungen-Bedienung** (P1): obere „Zum Anpassen wischen“-Leiste, Tipp-Menü auf der Leinwand (Vorschau/Anwenden/
   Zurücksetzen/Rückgängig/Abbrechen), Stift-Modus mit aktuellem Pinsel.
4. **Ganze Module fehlen** (P2/P3): QuickMenu, Perspektiv-Hilfe, Zeichenassistent pro Ebene/für Raster, Vektor-Text,
   Animationsassistent, Seitenassistent, Referenzfenster, Zeitraffer, Doppelpinsel, eigene Pinselsets.

---

## B. Abgleich je Funktion

### B.1 Oberfläche, Popover, Hinweisleiste (§1, §30)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Obere Leiste links/rechts mit allen Symbolen | ✅ | – | – | – |
| Popover unter dem Symbol, Wegtippen schließt ohne zu malen | 🟡 | Schließen ✅ (CanvasView). **Keine Öffnen/Schließen-Animation**, **kein Pfeil** zum Symbol, Positionen fest per `padding(end=80/40)` statt am Symbol verankert | P1 | WP1 |
| Popover-Maße/Radien/Schatten | 🟡 | Breiten einheitlich 360 dp; Pinselbibliothek sollte ~480 dp, Ebenen/Farben ~320 dp sein; Schatten 16 dp ok | P2 | WP1 |
| Aktives Werkzeug blau | ✅ | – | – | – |
| Hinweisleiste oben Mitte | 🟡 | vorhanden (`flash`, 1,2 s, ohne Ein-/Ausblendanimation). **Keine Aktions-Schaltflächen** in der Leiste („Form bearbeiten“, „Weiter füllen“, Deckkraft-%, Schwellenwert-Leiste). Braucht API `flash(msg, actionLabel, onAction)` bzw. dauerhaften „Statusleisten“-Zustand | P1 | WP0 + WP1 |
| Vollbild + Indikator | ✅ | Indikator-Symbol nur Textzeichen „⤡“ → eigenes Icon | P3 | WP1 |
| Automatisches Vollbild beim Malen | ❌ | | P3 | WP1 |
| Helle Oberfläche | ✅ | (Arbeitsflächen-Farbe hart in `EditorScreen`/`CanvasView` statt aus Theme) | P3 | WP1 |
| Rechtshändige Oberfläche | ✅ | – | – | – |
| Seitenleiste in Auswahl/Transform/Anpassen sichtbar | ❌ | wird derzeit nur bei Malen/Wischen/Radieren gezeigt; Klonen/Verflüssigen sollen Größe/Stärke über die Seitenleiste steuern (Hook nötig: Seitenleisten-„Quelle“ umschaltbar) | P1 | WP0 + WP1 |
| Dunkles Theme-Farbwerte | 🟡 | Palette vorhanden; Akzent/Sekundärblau, Warn-/Destruktivfarben als Tokens fehlen (aktuell verstreute Hex-Literale `0xFFE5483F` etc.) | P2 | WP1 |

### B.2 Gesten auf der Leinwand (§2)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Zoomen/Drehen/Verschieben mit 2 Fingern | ✅ | Option „Drehen beim Zoomen“ aus fehlt | P3 | → A / WP1 (Pref) |
| Schnelles Kneifen = Einpassen, umgekehrt = zurück | ✅ | – | – | – |
| 2-/3-Finger-Tippen, Halten = schnell | ✅ | Verzögerungsbereich 200–1500 ms statt 0–1500 ms | P3 | WP1 (Settings) |
| 3-Finger-Schrubben leeren | 🟡 | leert ganze Ebene auch bei aktiver Auswahl (sollte nur Auswahlbereich) | P2 | WP2 (`LayerOps.clearActiveLayer`) |
| 3-Finger nach unten = Kopieren & Einfügen | ✅ | – | – | – |
| 4-Finger-Tippen Vollbild | ✅ | – | – | – |
| Berühren & halten = Pipette | ✅ | 2. Finger tippen → aktive Ebene/alle umschalten fehlt (→ B, Eyedropper) | P2 | → B |
| Präzise Reglersteuerung (seitlich wegziehen) | ✅ | (Seitenleiste). In Panels-Reglern nicht | P3 | WP1 |
| Schwebe-Gesten (Kneifen = Größe, Wischen = Deckkraft) | ❌ | | P2 | → A |
| Gestensteuerung als Matrix (Auslöser je Funktion, Konfliktwarnung, Verzögerung je Funktion) | 🟡 | nur Ein/Aus-Schalter + 3 Verzögerungen; keine Zuordnung Auslöser→Funktion (Wischen/Radieren per Finger, Modifizierer-Belegung, Stifttaste), keine Konfliktwarnung | P2 | WP0 (Prefs) + WP1 (UI) + A (Auswertung) |
| „Touch-Aktionen deaktivieren“ | ✅ | („Mit Finger malen“) | – | – |
| Ebene wählen per Halten auf Leinwand (Popup) | ❌ | | P2 | WP2 + Hook A |

### B.3 Seitenleiste (§3)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Größe-/Deckkraftregler mit Feinsteuerung | ✅ | – | – | – |
| Vorschaublase beim Ziehen | 🟡 | nur %-Text; **Pinselabdruck in aktueller Größe** fehlt | P1 | WP1 |
| Größen-/Deckkraftspeicher (4 Marken pro Pinsel/Werkzeug, +/−) | ❌ | Datenhaltung pro Pinsel+Werkzeug (in `brush/` oder `ToolSettings`) | P2 | WP1 (+ WP3 Speicherung) |
| Modifizierer: Tippen = schwebende Pipette an letzter Malstelle | 🟡 | aktuell Umschalter „armed“; nächster Leinwandkontakt = Pipette. Schwebende Pipette an letzter Position fehlt | P1 | WP1 + → B (Eyedropper) |
| Modifizierer: Halten + Leinwand berühren | 🟡 | Halten wird nicht unterschieden (toggle bei `awaitFirstDown`) | P1 | WP1 |
| Modifizierer frei belegbar | ❌ | | P2 | WP1 |
| Rückgängig/Wiederholen halten = wiederholt | ❌ | Knöpfe reagieren nur auf Klick | P1 | WP1 |
| Seitenleiste verschiebbar (Höhe) | ❌ | | P3 | WP1 |
| Regler auf Min/Max des Pinsels abgebildet | ✅ | (BrushEngine `diameterFor`) | – | – |
| Getrennte Werte je Werkzeug | ✅ | – | – | – |
| Seitenleiste ausblendbar (Einstellung) | ❌ | | P3 | WP1 |

### B.4 Tastenkürzel (§4)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Strg+Z/Umschalt+Z, X/C/V, J, D, [ ], B, E, L, C, X, V | ✅ | – | – | – |
| B/E bei aktivem Werkzeug → Bibliothek öffnen | ❌ | | P2 | WP1 |
| Strg+[ ]/Umschalt+[ ] = 1 %/10 % | 🟡 | Umschalt+[ ] ändert Deckkraft (abweichend) | P3 | WP1 |
| S = Auswahl (Vorbild) | 🟡 | S = Wischen; Vorschlag S = Auswahl, W = Wischen | P2 | WP1 |
| Leertaste = QuickMenu | ❌ | Leertaste = Verschieben (beibehalten als „halten“) | P2 | WP1 |
| Strg+A = Alles kopieren | 🟡 | Strg+A = Alles auswählen (abweichend, behalten möglich) | P3 | WP1 |
| Strg+Rücktaste = Ebene/Auswahl leeren | 🟡 | Entf leert Ebene | P3 | WP1 |
| Strg+B = Farbbalance | 🟡 | Strg+B = Weichzeichner | P3 | WP1 |
| Alt halten = Modifizierer, Strg+K Aktionen, Strg+; Perspektive, Strg+0 Vollbild | ❌ | | P2 | WP1 |
| Pfeiltasten im Transformieren = 1 px | ❌ | Pfeile wechseln Ebene | P2 | WP1 (+ API in WP5) |
| Strg halten = Übersicht | ✅ | – | – | – |

### B.5 QuickMenu (§5)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Radiales Menü, 6 Knöpfe, Schnellwahl per Ziehen | ❌ | komplett | P2 | WP1 |
| Belegung pro Knopf (~60 Aktionen), Profile | ❌ | | P2 | WP1 |
| Auslöser (Finger-Tippen, Stift-Taste, Leertaste) | ❌ | Hook in CanvasView (→ A) | P2 | WP1 + A |

### B.6 Kopieren & Einfügen (§6)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| 6 Aktionen | ✅ | – | – | – |
| System-Zwischenablage (andere Apps) | ❌ | `ClipboardOps` nutzt nur internen Speicher (kein `ClipboardManager`); Bild-Clip ins System und Einfügen fremder Bilder fehlen | P3 | WP5 |

### B.7 Barrierefreiheit (§7)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Globale Stabilisierung/Bewegungsfilterung | ✅ | Bewegungsfilter-Ausdruck, Spitzenbindung fehlen | P3 | WP3 |
| Einfinger-Begleitfenster, Farbkarten, Töne, Tipp-Assistenz | ❌ | | P3 | WP1 |

### B.8 Galerie (§8)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Raster, Titel, Maße | ✅ | – | – | – |
| Neue Leinwand: Vorlagen + eigene (Maße, Einheit, DPI, max. Ebenen, Farbprofil, Hintergrund) | ✅ | Vorlagen bearbeiten/löschen (Wischen), als Vorlage speichern | P3 | WP6 |
| Auswählen-Modus, Stapeln, Teilen, Duplizieren, Löschen | ✅ | Überstreichen zum Mehrfachwählen | P3 | WP6 |
| Wischen nach links: Teilen/Duplizieren/Löschen | ✅ | – | – | – |
| Umbenennen | ✅ | (über Menü; Tippen auf Titel direkt fehlt) | P3 | WP6 |
| Stapel per Ziehen auf ein Werk / in Stapel ziehen | ❌ | | P2 | WP6 |
| Umsortieren per Halten & Ziehen | ❌ | | P2 | WP6 |
| Vorschau per Aufziehen, Blättern | ❌ | | P2 | WP6 |
| Miniatur drehen | ❌ | | P3 | WP6 |
| Import Datei/Foto/PSD | ✅ | PDF-Import (Seiten als Ebenen) fehlt | P3 | WP6 |

### B.9 Malen/Wischen/Radieren (§9)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Tippen wählt, erneut tippen = Bibliothek | ✅ | – | – | – |
| Je Werkzeug eigener Pinsel/Größe/Deckkraft | ✅ | – | – | – |
| Halten auf inaktives Werkzeug = „mit aktuellem Pinsel“ | ❌ | `BrushToolButton` in EditorScreen | P2 | WP1 |
| Radierer-Ende des Stifts | ✅ | (→ A pflegt) | – | – |

### B.10 Pinselbibliothek (§10)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Zweispaltig Sets/Pinsel mit Strichvorschau | ✅ | – | – | – |
| Tippen auf aktiven Pinsel = Studio | ✅ | – | – | – |
| Wischen: Teilen/Duplizieren/Löschen; Halten = Menü | ✅ | Umbenennen im Menü fehlt (nur im Studio) | P2 | WP3 |
| Zuletzt verwendet | 🟡 | max. 12 statt 8; **Anheften/Finden/Entfernen** fehlen; stattdessen „Favoriten“ (Farbwerk-Eigenheit, ok) | P3 | WP3 |
| Eigene Sets anlegen/umbenennen/löschen/duplizieren/Symbol | ❌ | nur festes Set „Eigene Pinsel“ | P2 | WP3 |
| Umsortieren, Pinsel in Sets verschieben/kopieren (Drag) | ❌ | | P2 | WP3 |
| Suche (nach unten ziehen) | ❌ | | P3 | WP3 |
| Mehrere Bibliotheken | ❌ | | P3 | WP3 |

### B.11 Pinsel-Studio (§11)

| Gruppe / Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Layout 3 Spalten, Fertig/Abbrechen, Testfläche | ✅ | Zeichenflächen-Einstellungen (8 Vorschaufarben, Größe), 3-Finger-Schrubben leert | P3 | WP3 |
| Zahlenwert tippen → Eingabe + Druck/Neigung-Kopplung | ❌ | | P2 | WP3 |
| 1 Strichpfad | 🟡 | Abstand, Streuung, Abklingen ✅; **Abstandsstreuung**, getrennt **seitlich/längs** fehlen | P2 | WP3 |
| 2 Stabilisierung | 🟡 | StreamLine, Stabilisierung, Bewegungsfilter ✅; StreamLine-Druck, Filter-Ausdruck fehlen | P3 | WP3 |
| 3 Verjüngung | 🟡 | Länge Anfang/Ende, Größe, Deckkraft, Spitze, „nur Finger“ ✅; **Druck-/Touch-Verjüngung getrennt**, Spitzengrößen verknüpfen, Druck-Kopplung, Spitzen-Animation fehlen | P2 | WP3 |
| 4 Form | 🟡 | 17 prozedurale Formen, Rundheit, Drehung, Ausrichtungsmodus, Streuung, Anzahl, Spiegeln, Neigung→Rundheit ✅; **eigenes Bild als Form** (Foto/Datei/Einfügen + Editor), Druck-Rundheit, Rundheits-Streuung H/V, **Rundheits-Diagramm**, Formfilterung, „Zufällig je Strich“ fehlen | P2 | WP3 |
| 5 Körnung | 🟡 | 9 prozedurale Körnungen, Texturiert/Bewegt, Maßstab, Tiefe, Invertieren ✅; **eigenes Bild + Körnungs-Editor (Kacheln)**, Bewegung, Zoom (Folgt Größe), Drehung, Tiefe Min/Streuung, Versatz-Streuung, Mischmodus, Helligkeit/Kontrast, Filterung fehlen | P2 | WP3 |
| 6 Rendering | 🟡 | 6 Modi, Fluss, Mischmodus ✅; **Nasse Kanten, Verbrannte Kanten (+Modus), Luminanz-Mischen, Alpha-Schwelle** fehlen | P2 | WP3 |
| 7 Nasse Farbe | 🟡 | Verdünnung, Ladung, Ziehen, Nässe-Streuung ✅; Anhaftung, Körnigkeit, Unschärfe (+Streuung) fehlen | P3 | WP3 |
| 8 Farbdynamik | 🟡 | Tupfer-Farbton/Sättigung/Helligkeit, Strich-Farbton/Helligkeit, Druck/Neigung → Sekundärfarbe ✅; Dunkelheit, Strich-Sättigung, Sekundärfarbe-Streuung, Farbdruck/-neigung je H/S/B fehlen | P3 | WP3 |
| 9 Dynamik | 🟡 | Tempo→Größe (±), Tempo→Deckkraft (nur +), Streuung Größe/Deckkraft ✅; Tempo→Deckkraft negativ, **Tempo→Abstand** fehlen | P3 | WP3 |
| 10 Stift | 🟡 | Druck→Größe/Deckkraft/Fluss, Druckglättung, Neigung→Größe/Deckkraft ✅; **Druckdiagramm pro Pinsel**, Druck→Ausbluten, Neigungsdiagramm, Neigung→Abstufung/Ausbluten, Größenkompression, Cursor-Umriss, Schwebe-Optionen fehlen | P2 | WP3 (+ A für Hover) |
| 11 Eigenschaften | 🟡 | Max/Min Größe/Deckkraft, Vorschaugröße, Wischen-Aufnahme ✅; Stempelvorschau, An Bildschirm ausrichten fehlen | P3 | WP3 |
| 12 Materialien | ❌ | nur 3D – nicht vorgesehen | – | – |
| 13 Vorschau-Optionen | ❌ | | P3 | WP3 |
| 14 Über diesen Pinsel | 🟡 | Name, Set, Datum, Zurücksetzen ✅; Autor, Bild, Unterschrift, **Rücksetzpunkt erstellen** fehlen | P3 | WP3 |

### B.12 Doppelpinsel, Import/Teilen (§12, §13)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Doppelpinsel kombinieren/trennen, Kombinationsmodus | ❌ | Engine muss zwei Stempelströme rendern und mischen | P3 | WP3 |
| Pinsel teilen/importieren | 🟡 | als JSON-Text; kein Dateiformat mit Form/Körnung-Bildern, keine Sets, kein ABR | P3 | WP3 |

### B.13 Farben (§14) – **→ B (ausgeklammert)**

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| 5 Tabs Scheibe/Klassisch/Harmonie/Werte/Paletten | ✅ | (Reparatur läuft bei B) | – | → B |
| Primär-/Sekundärfarbe | ❌ | `EditorState` hat nur `color`/`previousColor`; Sekundärfarbe für Farbdynamik fehlt | P2 | → B (+ WP0) |
| Verlauf 10 Farben, Leeren | 🟡 | Liste vorhanden; „Leeren“/pro Werk prüfen | P3 | → B |
| Schwebender Farbbegleiter | ❌ | | P3 | → B |
| Scheibe aufziehen (Zoom), Doppeltippen einrasten | ❌? | von B zu prüfen | P2 | → B |
| Paletten: Karten-Ansicht mit Namen, aus Kamera, ASE/ACO/.swatches | 🟡 | Kompakt/Karten, aus Foto ✅; Kamera, Dateiformate fehlen | P3 | → B |
| Halten auf Farbkreis = vorherige Farbe | ❌? | | P2 | → B |

### B.14 ColorDrop & Pipette (§15) – **→ B (ausgeklammert)**

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| ColorDrop aus der oberen Leiste mit Schwellenwert | 🟡 | Engine ✅ (`color/ColorDrop.kt`, Sitzung + Referenz), Ziehen nur aus dem Panel-Kopf | P1 | → B |
| „Weiter füllen“ | ❌ | braucht Hinweisleisten-Aktion (WP0/WP1) | P1 | → B + WP1 |
| SwatchDrop | ❌? | | P2 | → B |
| Pipette Ring-Lupe | ✅ | – | – | – |
| Pipette: 2. Finger = Ebene/alle | ❌ | | P2 | → B |

### B.15 Ebenen (§16)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Panel: Zeilen mit Miniatur, Name, Modus-Kürzel, Häkchen; Hintergrundfarbe | ✅ | – | – | – |
| + neue Ebene über aktiver | ✅ | – | – | – |
| Primär/Sekundär (Tippen / rechts wischen) | ✅ | – | – | – |
| Wischen links: Sperren/Duplizieren/Löschen | ✅ | – | – | – |
| Umsortieren per Halten & Ziehen, auf Ebene = Gruppe | ✅ | Stapel mit 2. Finger; auf Galerie ziehen = Export | P3 | WP2 |
| Kneifen = Zusammenfügen | ✅ | – | – | – |
| Gruppen (bilden, ein-/ausklappen, Optionen) | ✅ | – | – | – |
| Ebenenmenü (Titel tippen: Alle wählen, Gruppieren, Zusammenfügen, Teilen, Duplizieren, Löschen) | 🟡 | nur Knöpfe Gruppieren/Löschen bei Mehrfachauswahl; Alle auswählen, Mehrfach-Duplizieren/-Teilen fehlen | P2 | WP2 |
| **2-Finger-Tippen auf Ebene = Deckkraft per Leinwand-Ziehen** | ❌ | braucht Leinwand-Modus „Ebenendeckkraft“ (Hook in CanvasView → A/WP0) + Hinweisleiste | P1 | WP2 + WP0 |
| **2 Finger rechts wischen = Alpha-Sperre** | ❌ | | P1 | WP2 |
| **2 Finger halten = Inhalt auswählen** | ❌ | (Funktion `selectLayerContents` existiert) | P2 | WP2 |
| **Solo-Sichtbarkeit (Häkchen halten)** | ❌ | | P2 | WP2 |
| Ebenenoptionen: Umbenennen, Auswählen, Kopieren, Füllen, Leeren, Alpha-Sperre, Maske, Schnittmaske, Umkehren, Referenz, Nach unten zusammenfügen/kombinieren | ✅ | – | – | – |
| Ebenenoption **Zeichenassistent** | ❌ | Assistent ist global (`GuideSettings.assisted`) statt pro Ebene | P2 | WP0 + WP6 |
| Ebenenoption Rastern (Text) | ❌ | hängt an Vektor-Text | P3 | WP6 |
| Zusätze „Assistiert“, „Privat“, Text-„A“ | ❌ | | P3 | WP2 |
| Private Ebenen | ❌ | Layer-Feld + Ausschluss in Miniatur/Export | P3 | WP0 + WP6 |
| Ebenen teilen: PDF, PNG-Dateien | ✅ | animiertes GIF/PNG/MP4 fehlen | P3 | WP6 |
| Drag & Drop einer Ebene in andere Apps | ❌ | | P3 | WP2 |
| Ebenenlimit + Hinweis | ✅ | – | – | – |
| Transform/Auswahl auf alle markierten Ebenen | ❌ | Transform wirkt nur auf aktive Ebene | P2 | WP5 |

### B.16 Mischmodi (§17)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Kürzel tippen → Leiste mit Deckkraft + Modi | ✅ | als horizontale Chip-Liste; Gruppen-Trenner und große scrollbare Liste mit Live-Vorschau beim Durchblättern wären näher am Vorbild | P2 | WP2 |
| 18 Modi | 🟡 | **fehlen:** Schattieren, Dunklere Farbe, Hellere Farbe, Strahlendes Licht, Lineares Licht, Lichtpunkt, Harte Mischung, Subtrahieren, Dividieren (9). `LINEAR_BURN` wird aktuell als `MULTIPLY` gerendert (falsch). Android `BlendMode` deckt diese nicht ab → eigener Shader/RuntimeShader (API 33) bzw. CPU-Pfad | P2 | WP0 (Enum) + WP2 (Compositor) |

### B.17 Masken (§18)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Alpha-Sperre inkl. Schachbrett-Miniatur | ✅ | (Miniatur prüfen) | – | – |
| Ebenenmaske (hinzufügen, anwenden, entfernen) | 🟡 | Maske als **eigene Zeile** über der Ebene (wählbar/bemalbar als Primär), Farbwahl ignoriert Farbton bei Maske, Maske umkehren fehlen; Status nur als Text „Maske“ | P2 | WP2 |
| Schnittmaske | ✅ | – | – | – |

### B.18 Text (§19)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Text hinzufügen | 🟡 | Dialog (Text, Schrift, Ausrichtung, Größe, fett, kursiv) → wird sofort Pixel + Transform. **Vektor-Textebene** (nachträglich editierbar, Rahmenbreite/Umbruch, Kerning/Tracking/Zeilenabstand/Grundlinie/Deckkraft, Unterstreichen, Kontur, vertikal, Großbuchstaben), Rastern, Schrift-Import (TTF/OTF) fehlen | P2 | WP0 + WP6 |

### B.19 Zeichenhilfen (§20)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| 2D-Raster, Isometrisch, Symmetrie (4 Modi + rotierend) | ✅ | – | – | – |
| Editor als Vollbild mit Steuerpunkten (blau verschieben, grün drehen), Farbton-Leiste, Dicke | 🟡 | Dialog mit Segment-Tabs, Größe, Farbe/Deckkraft; Symmetrie-Mittelpunkt-Griff im Overlay. **Raster verschieben/drehen**, Dicke, Live-Editor-Vollbild fehlen | P2 | WP6 |
| **Perspektive (1/2/3 Fluchtpunkte, Horizont)** | ❌ | `GuideKind` ohne PERSPECTIVE (Kern) | P2 | WP0 + WP6 |
| Zeichenassistent für Raster/Isometrie/Perspektive (Striche einrasten) | ❌ | Assistent wirkt nur als Symmetrie-Spiegelung | P2 | WP6 (+ Hook in BrushEngine, WP3) |
| Assistent pro Ebene | ❌ | siehe B.15 | P2 | WP0 + WP6 |

### B.20 QuickShape (§21)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Erkennen Linie, Bogen, Polylinie, Ellipse, Rechteck, Polygon | ✅ | Dreieck → gleichseitig prüfen | – | – |
| Weiter ziehen = skalieren/drehen; 2. Finger = perfekt | ✅ | – | – | – |
| **„Form bearbeiten“ mit Steuerpunkten** | ❌ | braucht Hinweisleisten-Aktion + Overlay-Modus | P2 | WP3 (+ WP1-API) |
| Auslösen auf letzten Strich (Taste/Modifizierer) | ❌ | | P3 | WP3 + A |

### B.21 Animationsassistent / Seitenassistent (§22, §23)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Zeitleiste, Abspielen, Bild hinzufügen, Bildoptionen, Einstellungen, Zwiebelschicht | ❌ | komplett; braucht Slot unten im EditorScreen und Compositor-Modus (nur aktuelles Bild + Zwiebelschichten) | P3 | WP0 + WP6 |
| Export GIF/MP4/APNG | ❌ | | P3 | WP6 |
| Seitenassistent | ❌ | (teilt Zeitleisten-Infrastruktur) | P3 | WP6 |

### B.22 Aktionen (§24)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| 6 Tabs | ✅ | – | – | – |
| Hinzufügen: Datei, Foto, Kamera, Text, Zwischenablage-Aktionen | ✅ | „privat einfügen“ (Wischen) fehlt | P3 | WP6 |
| Zuschneiden & Größe ändern | 🟡 | Dialog mit Zahlen, Seitenverhältnis, Skalieren, Ankerpunkt. **Interaktives Raster-Overlay** (Kanten ziehen, Einrasten), Drehregler, DPI-Feld fehlen | P2 | WP6 |
| Zeichenhilfe-Schalter, -bearbeiten, Assistent | ✅ | (siehe B.19) | – | – |
| Leinwand spiegeln | ✅ | – | – | – |
| Leinwandinformationen | 🟡 | vorhanden; Statistik (Striche, Zeit), Autor/Unterschrift prüfen | P3 | WP6 |
| Referenzfenster | ❌ | | P2 | WP6 |
| Teilen: Projekt, PSD, PDF, JPEG, PNG, TIFF; Ebenen als PNG | ✅ | PDF pro Ebene, animierte Formate | P3 | WP6 |
| Video/Zeitraffer | ❌ | Platzhalter „nicht verfügbar“ | P3 | WP6 |
| Einstellungen: Hell, rechts, Cursor, dyn. Skalierung, Projizieren, Finger, Druckkurve, Glättung, Gesten, Verzögerungen | 🟡 | Erweiterter Cursor (Sichtbarkeit/Stil), Auswahlmasken-Sichtbarkeit, Vereinfachtes Rückgängig, Seitenleiste ausblenden, Gestensteuerungs-Matrix fehlen; „Leinwand projizieren“ ohne Funktion? | P2 | WP0 (Prefs) + WP1 |
| Hilfe: Gesten/Kürzel/Tipps | ✅ | – | – | – |

### B.23 Auswahl (§25)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Leiste unten, Modi Automatisch/Freihand/Rechteck/Ellipse | ✅ | – | – | – |
| Automatisch mit Schwellenwert-Ziehen | ✅ | Schwellenwert als **blaue Leiste oben** statt Pille; invertierte Farbdarstellung des gewählten Bereichs | P2 | WP5 |
| Freihand: Linien + Polygonpunkte, Startpunkt schließt | ✅ | – | – | – |
| Hinzufügen/Entfernen/Umkehren/Kopieren & Einfügen/Weiche Kante/Speichern & Laden/Farbfüllung/Löschen | ✅ | Weiche Kante als Dialog statt Live-Regler; Farbfüllung: Umfärben nach Farbwechsel prüfen | P2 | WP5 |
| **Diagonale Streifen** außerhalb nach dem Übernehmen | ❌ | aktuell Abdunklung nur im Auswahlmodus + Ameisenlinie; Streifen fehlen | P1 | WP5 |
| **Halten auf Auswahl-Symbol = letzte Auswahl neu laden** | ❌ | braucht Long-Press am Symbol (EditorScreen, WP1) + API in WP5 | P2 | WP5 + WP1 |
| Sichtbarkeit der Auswahlmaske (Einstellung) | ❌ | | P3 | WP5 + WP0 |

### B.24 Transformieren (§26)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Freiform, Gleichmäßig, Verzerren, Verkrümmen | ✅ | – | – | – |
| Spiegeln H/V, 45° drehen, An Bildschirm anpassen, Interpolation, Zurücksetzen | ✅ | – | – | – |
| Einrasten-Popup (Magnetisch, Einrasten, Abstand) | 🟡 | Geschwindigkeit-Regler; blaue (Objekt-) vs. gelbe (Leinwand-)Hilfslinien; Magnetisch-Achse per 2. Finger fixieren; Magnetisch verschiebt in 45°- statt 15°-Schritten | P2 | WP5 |
| Begrenzungsrahmen exakt um den Inhalt | ✅ | – | – | – |
| Grüner Drehpunkt | ✅ | Winkelanzeige beim Drehen | P2 | WP5 |
| **Gelber „Rahmen anpassen“-Punkt** | ❌ | | P3 | WP5 |
| **Größenanzeige B×H beim Ziehen** | ❌ | | P2 | WP5 |
| **Punkt antippen → Zahleneingabe** | ❌ | | P2 | WP5 |
| **Kneifen innerhalb = skalieren/drehen** (außerhalb = Leinwand) | ❌ | CanvasView leitet 2-Finger-Gesten nur an die Ansicht; Hook nötig (→ A/WP0) | P1 | WP5 + WP0 |
| Tippen außerhalb = schubsen | ✅ | (Op.NUDGE) | – | – |
| Eckpunkt halten = vorübergehend Verzerren | ❌ | | P3 | WP5 |
| Verkrümmen: Punkt Vorne/Mitte/Hinten, Erweitertes Gitter | ❌ | | P3 | WP5 |
| Mehrere markierte Ebenen gemeinsam | ❌ | | P2 | WP5 |
| Vereinfachtes Rückgängig | 🟡 | prüfen, ob Einzelschritte rückgängig sind | P3 | WP5 |

### B.25 Anpassungen (§27)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Menü mit Abschnitten Anpassungen/Filter, alle 15 Einträge | ✅ | Symbole je Zeile prüfen | – | – |
| Modus Ebene/Stift | 🟡 | Stift-Modus mit eigener runder Maske + Größenregler; **soll aktuellen Pinsel + Seitenleiste nutzen** | P2 | WP4 (+ WP1 Seitenleiste) |
| **Blaue „Zum Anpassen wischen“-Leiste oben** mit % | ❌ | Werte stehen in der unteren Steuerkarte | P1 | WP4 (+ WP1-Statusleiste) |
| **Tippen auf Leinwand → Aktionsmenü** (Vorschau, Anwenden, Zurücksetzen, Rückgängig, Abbrechen) | 🟡 | Knöpfe Zurücksetzen/Rückgängig/Abbrechen/Anwenden in der Karte ✅; **Vorschau-Umschalter** und Tipp-Menü fehlen | P1 | WP4 |
| Anpassungen-Symbol erneut tippen = übernehmen & verlassen | 🟡 | Symbol öffnet/schließt nur das Menü; Übernehmen erfolgt über Werkzeugwechsel/„Anwenden“ | P1 | WP1 (EditorScreen) + WP4 |
| HSB, Farbbalance (3 Tonbereiche), Kurven | ✅ | Kurven: Kanäle Gamma/R/G/B und Histogramm prüfen, max. 11 Punkte | P3 | WP4 |
| Verlaufsumsetzung | 🟡 | 8 eigene Vorgaben + Stärke; **Verlaufseditor** (Stopps hinzufügen/löschen/Farbe), eigene Verläufe speichern, Bibliothek sortieren fehlen | P2 | WP4 |
| Gauß, Bewegung (Richtung per Ziehen), Perspektivisch (positions-/richtungsbezogen) | ✅ | – | – | – |
| Rauschen | 🟡 | Art, Größe, Oktaven, Turbulenz ✅; Kanäle Einfach/Mehrfach, Additiv fehlen | P3 | WP4 |
| Scharfzeichnen, Bloom | ✅ | – | – | – |
| Glitch | 🟡 | 4 Modi; Parameter generisch (Größe/Variation) statt modusabhängig (Amplitude/Frequenz, R/G/B-Verschiebung) | P3 | WP4 |
| Halbton | 🟡 | Vollfarbe, Zeitung; **Siebdruck** fehlt | P3 | WP4 |
| Chromatische Aberration | 🟡 | ein Modus; Perspektive/Verschieben-Modus, Abfall, Unschärfe, Transparenz fehlen | P3 | WP4 |
| Verflüssigen | 🟡 | 8 Modi, Größe/Druck/Verzerrung/Dynamik ✅; **Anpassen (Menge-Regler)** fehlt | P3 | WP4 |
| Klonen | 🟡 | Quellkreis, Größe/Deckkraft/Härte ✅; **Halten = Quelle sperren** (Puls+Hinweis), beliebiger Pinsel, Seitenleiste fehlen | P2 | WP4 |
| Umfärben (QuickMenu-Aktion) | ❌ | | P3 | WP4 |

### B.26 Referenzfenster, 3D (§28, §29)

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Referenzfenster (Leinwand-Liveansicht, Bild) | ❌ | | P2 | WP6 |
| 3D | – | außer Umfang | – | – |

### B.27 Stift (§31) – **→ A (ausgeklammert)**

| Funktion | Status | Was fehlt | Prio | Paket |
|---|---|---|---|---|
| Druck mit Kurve, historische Punkte | ✅ | – | – | → A |
| Neigung/Ausrichtung | ✅ | (Konvention prüfen) | – | → A |
| Hover-Cursor, Schwebe-Gesten | ❌ | | P1 | → A |
| Seitentaste-Belegung | ❌ | | P1 | → A (+ WP1 für Einstellungs-UI) |
| Handballenerkennung über „Stift aktiv → Finger malen nicht“ | 🟡 | nur „zweiter Finger während Stiftstrich ignorieren“ | P1 | → A |
| Unbuffered Dispatch, Vorhersage | ❌ | | P1 | → A |

---

## C. Arbeitspakete (parallel umsetzbar)

### C.0 WP0 – Kern-Änderungen (Koordinator, **vor** den Paketen, klein halten)

Diese Dateien gehören dem Koordinator; die Pakete bauen auf folgenden Erweiterungen auf. Alles rein additiv
(neue Felder/Enums/Hooks mit Standardwerten), damit bestehender Code unverändert kompiliert:

| Datei | Erweiterung | für |
|---|---|---|
| `model/Document.kt` | `LayerBlend` um SHADE, DARKER_COLOR, LIGHTER_COLOR, VIVID_LIGHT, LINEAR_LIGHT, PIN_LIGHT, HARD_MIX, SUBTRACT, DIVIDE (Reihenfolge wie §17 + `group`-Index); `Layer.assisted: Boolean`, `Layer.isPrivate: Boolean`, `Layer.text: Any?` (bzw. `TextData`-Platzhalterklasse); `GuideKind.PERSPECTIVE`; `GuideSettings`: `thickness`, `offsetX/Y`, `rotation`, `vanishingPoints: MutableList<PointF>`; `ArtDocument.animation: AnimationSettings?` (Platzhalter) | WP2, WP6 |
| `model/Tools.kt` | `Panel.QUICKMENU` (oder separater State), `ToolInputHandler.onTwoFingerTransform(...)`-Default-Methode für Kneifen innerhalb des Transform-Rahmens | WP1, WP5 |
| `EditorState.kt` | `flash(msg, actionLabel: String? = null, onAction: (() -> Unit)? = null)`; `statusBar` (dauerhafte Leiste: Text + Prozent + Aktion, z. B. Schwellenwert/Ebenendeckkraft/Filterstärke); `lastPaintPoint` (für schwebende Pipette); `sidebarSource` (wer die Seitenleiste steuert: Pinsel / Klonen / Verflüssigen / Stift-Anpassung); `canvasOverrideMode` (z. B. LAYER_OPACITY: 1-Finger-Ziehen ändert Ebenendeckkraft) | WP1, WP2, WP4, B |
| `prefs/Prefs.kt` | Gestensteuerungs-Zuordnung (`Map<GestureFunction, Set<Trigger>>` als JSON), `selectionMaskOpacity`, `simplifiedUndo`, `cursorVisibility/cursorStyle`, `sidebarVisible`, `rotateWithPinch`, `autoFullscreen(+Delay)`, QuickMenu-Profile (JSON), Größen-/Deckkraftmarken je Pinsel | WP1, WP5 |
| `canvas/CanvasView.kt` | **nur in Absprache mit A:** Hooks (a) `state.canvasOverrideMode` auswerten, (b) 2-Finger-Gesten an `transformTool` weiterreichen, wenn innerhalb des Rahmens, (c) QuickMenu-Auslöser, (d) Ebene-wählen-Auslöser | WP2, WP5, WP1 |
| `ui/EditorScreen.kt` | **nur Slots** (sonst WP1): `bottomTimeline`-Slot (WP6), Referenzfenster-Slot (WP6), QuickMenu-Overlay-Slot (WP1). Farbkreis-Bereich gehört B | WP1, WP6 |

### C.1 WP1 – Oberflächen-Shell, Seitenleiste, QuickMenu, Tastatur

**Darf ändern:** `ui/EditorScreen.kt` (außer Farbkreis-/ColorDrop-Bereich → B), `ui/Sidebar.kt`, `ui/common/**`,
`ui/theme/**`, `ui/icons/AppIcons.kt`, `input/**`, `ui/actions/SettingsTab.kt`, **neu** `ui/quickmenu/**`,
`ui/shell/**` (Popover-Host, Statusleiste). *Kern-Dateien EditorScreen/Sidebar/Common/Theme/AppIcons gehören formal
dem Koordinator – WP1 übernimmt sie als Bevollmächtigter; B wird über den Farbkreis-Bereich informiert.*

**Inhalt (Reihenfolge):**
1. P1 Popover-Host: am Symbol verankert (Position aus `onGloballyPositioned` der Symbole), Pfeil, Öffnen/Schließen-
   Animation (§30.5), einheitliche Breiten pro Panel.
2. P1 Statusleiste/Hinweisleiste mit Aktions-Knopf und Prozentanzeige (nutzt WP0-API), Ein-/Ausblendanimation.
3. P1 Seitenleiste: Vorschaublase mit Pinselabdruck, Rückgängig/Wiederholen halten = wiederholen, Modifizierer
   Tippen (schwebende Pipette an `lastPaintPoint`) vs. Halten (+ Leinwand), Sichtbarkeit in allen Werkzeugen mit
   `sidebarSource`.
4. P1 Anpassungen-Symbol erneut tippen = übernehmen & verlassen (Aufruf `adjustmentTool.applyAndClose()`).
5. P2 Größen-/Deckkraftmarken (4+4 je Pinsel & Werkzeug).
6. P2 Halten auf inaktives Werkzeugsymbol = „mit aktuellem Pinsel“; Halten auf Auswahl-Symbol = letzte Auswahl
   (ruft API aus WP5).
7. P2 QuickMenu (radial, Schnellwahl, Belegung, Profile, Auslöser Leertaste + Hook A).
8. P2 Tastenkürzel an Vorbild angleichen (§4 inkl. B/E → Bibliothek, Alt = Modifizierer, Strg+K, Pfeile im Transform).
9. P2 Einstellungen: Gestensteuerungs-Matrix mit Konfliktwarnung, Cursor-Optionen, Seitenleiste ausblenden,
   Drehen beim Zoomen, Verzögerung 0–1,5 s.
10. P3 Automatisches Vollbild, verschiebbare Seitenleiste, Theme-Tokens (Destruktiv/Warnung/Sekundärblau),
    Barrierefreiheits-Begleitfenster.

### C.2 WP2 – Ebenen & Mischmodi

**Darf ändern:** `ui/layers/**`, `model/LayerOps.kt`, `render/**` (Compositor, LayerGroups).

**Inhalt:**
1. P1 2-Finger-Gesten in Zeilen: Tippen = Deckkraft-Modus (setzt `canvasOverrideMode`, Statusleiste „Deckkraft NN %“),
   rechts wischen = Alpha-Sperre; P2 Halten = Inhalt auswählen.
2. P2 Solo-Sichtbarkeit (Häkchen halten), Ebenenmenü über Titel (Alle wählen/abwählen, Gruppieren, Zusammenfügen,
   Duplizieren, Teilen, Löschen für Mehrfachauswahl).
3. P2 9 fehlende Mischmodi im Compositor (AGSL-`RuntimeShader` ab API 33, CPU-Fallback für API 29–32), `LINEAR_BURN`
   korrekt; Modusliste gruppiert, scrollbar, Live-Vorschau.
4. P2 Maske als eigene Zeile (wählbar/bemalbar), Maske umkehren; Zusätze „Assistiert“/„Privat“/„A“; Ebenenoption
   „Zeichenassistent“ (setzt `Layer.assisted`).
5. P2 Ebene wählen auf Leinwand (Popup-Komponente; Auslöser-Hook von A).
6. P2 Schrubben leert nur Auswahlbereich (`clearActiveLayer` mit Selection).
7. P3 Drag & Drop von Ebenen in andere Apps, Stapelziehen mit 2. Finger.

### C.3 WP3 – Pinselbibliothek, Pinsel-Studio, QuickShape-Bearbeitung

**Darf ändern:** `brush/**`, `ui/brush/**`.

**Inhalt:**
1. P2 Eigene Sets (anlegen, umbenennen, löschen, duplizieren, Symbol), Umsortieren, Pinsel per Drag in Sets
   verschieben/kopieren, Umbenennen im Menü.
2. P2 Studio: Zahlenwert tippen → Eingabe + Druck/Neigung-Kopplung; Druckdiagramm pro Pinsel; Strichpfad
   (Abstandsstreuung, seitlich/längs); Verjüngung (Druck/Touch getrennt, verknüpfen, Druckkopplung).
3. P2 Form/Körnung aus eigenem Bild (Foto/Datei/Einfügen, Graustufen, Invertieren) inkl. einfachem Kachel-Editor;
   Rundheits-Diagramm, Druck-Rundheit; Körnung Bewegung/Zoom/Drehung/Helligkeit/Kontrast/Mischmodus.
4. P2 Rendering: Nasse Kanten, Verbrannte Kanten, Luminanz-Mischen, Alpha-Schwelle.
5. P2 QuickShape „Form bearbeiten“ (Overlay mit Punkten, Knöpfe in Statusleiste über WP0-API).
6. P2 Zeichenassistent-Einrasten für Raster/Isometrie/Perspektive in `BrushEngine` (liest `Layer.assisted` und
   `GuideSettings`; Geometrie-Hilfsfunktionen kommen aus WP6 `guides/Assist.kt` – Schnittstelle vorab abstimmen:
   `fun constrain(doc, startX, startY, x, y): PointF`).
7. P3 Restliche Parameter (Nasse Farbe, Farbdynamik, Dynamik, Vorschau, Über diesen Pinsel/Rücksetzpunkt), Suche,
   „Zuletzt“ mit Anheften, Zeichenflächen-Einstellungen, Doppelpinsel, Pinsel-Dateiformat mit Bildern, ABR-Import,
   Speicherung der Größen-/Deckkraftmarken (Datenmodell für WP1).

### C.4 WP4 – Anpassungen

**Darf ändern:** `adjust/**`, `ui/adjust/**`.

**Inhalt:**
1. P1 Statusleiste oben „<Filter> NN %“ für alle Wisch-Anpassungen (WP0-API), untere Karte nur noch für Zusatzregler.
2. P1 Tippen auf Leinwand → Aktionsmenü (Vorschau umschalten, Anwenden, Zurücksetzen, Rückgängig, Abbrechen);
   `applyAndClose()` für das Symbol (WP1 ruft auf).
3. P2 Stift-Modus mit aktuellem Pinsel (Maske über `BrushEngine`/`StrokeRenderer` aus WP3 – nur lesend nutzen) und
   Seitenleiste (`sidebarSource`).
4. P2 Verlaufseditor + eigene Verläufe (Speichern, Löschen, Duplizieren, Sortieren, Standards wiederherstellen).
5. P2 Klonen: Halten = Quelle sperren (Puls, Hinweis), Seitenleiste Größe/Stärke.
6. P3 Rauschen (Kanäle, Additiv), Glitch-Parameter je Modus, Halbton Siebdruck, Chromatische Aberration
   (Perspektive/Verschieben, Abfall, Unschärfe, Transparenz), Verflüssigen „Anpassen“, Kurven-Feinschliff, Umfärben.

### C.5 WP5 – Auswahl & Transformieren

**Darf ändern:** `selection/**`, `ui/selection/**`, `transform/**`, `ui/transform/**`.

**Inhalt:**
1. P1 Diagonale, wandernde Streifen außerhalb der übernommenen Auswahl (Deckkraft aus Pref).
2. P1 Kneifen innerhalb des Rahmens = skalieren/drehen (über WP0-Hook `onTwoFingerTransform`).
3. P2 Größenanzeige B×H, Winkelanzeige, Punkt antippen → Zahleneingabe; Magnetisch in 15°-Schritten + Achse per
   2. Finger; Einrasten mit Objekt- (blau) und Leinwand-Hilfslinien (gelb), Geschwindigkeit.
4. P2 Transform/Auswahl auf alle markierten Ebenen (Sekundärauswahl).
5. P2 „Letzte Auswahl neu laden“ (`selectionTool.reloadLast()` für WP1), Schwellenwert als Statusleiste, invertierte
   Darstellung bei Automatisch, Weiche Kante als Live-Regler, Farbfüllung färbt nach Farbwechsel um.
6. P2 API `transformTool.nudge(dx, dy)` für Pfeiltasten (WP1).
7. P3 Gelber Rahmen-anpassen-Punkt, Eckpunkt halten = Verzerren, Verkrümmen Vorne/Mitte/Hinten, Erweitertes Gitter,
   Vereinfachtes Rückgängig, System-Zwischenablage (Bild-Clips ein/aus).

### C.6 WP6 – Aktionen, Zeichenhilfen, Text, Galerie, Animation/Seiten, Referenz

**Darf ändern:** `ui/actions/**` (außer `SettingsTab.kt` → WP1), `guides/GuidesOverlay.kt` und **neue** Dateien unter
`guides/**` (nicht `guides/Symmetry.kt` = Kern), `ui/gallery/**`, `io/**`, **neu** `text/**`, `ui/text/**`,
`animation/**`, `ui/animation/**`, `ui/reference/**`.

**Inhalt:**
1. P2 Zeichenhilfe-Editor als Vollbild-Modus mit Steuerpunkten (blau/grün, Zurücksetzen), Farbton-Leiste, Dicke,
   Deckkraft; Raster verschieben/drehen.
2. P2 Perspektiv-Hilfe (1–3 Fluchtpunkte, Horizont) + `guides/Assist.kt` (Einrast-Geometrie für Raster/Isometrie/
   Perspektive, Schnittstelle für WP3); Assistent pro Ebene (Aktionen-Schalter setzt `activeLayer.assisted`).
3. P2 Referenzfenster (schwebend, verschieb-/skalierbar; Leinwand-Liveansicht mit eigenem Zoom, Bild).
4. P2 Zuschneiden als interaktives Overlay (Kanten ziehen, Einrasten, Drehregler, DPI).
5. P2 Galerie: Drag-Umsortieren, Ziehen auf Werk/Stapel = stapeln, Vorschau per Aufziehen mit Blättern.
6. P2 Vektor-Text (Textebene mit `TextData`, Stil-Panel, Rahmenbreite/Umbruch, erneutes Bearbeiten per Doppeltippen,
   Rastern; Schrift-Import TTF/OTF); Speicherung in `io/ProjectFormat.kt`.
7. P3 Animationsassistent (Zeitleiste-Slot, Bildoptionen, Einstellungen, Zwiebelschicht – Compositor-Modus nur über
   neue Funktion in `animation/**`, die WP2s Compositor aufruft; Schnittstelle mit WP2 abstimmen), Seitenassistent,
   Export GIF/APNG/MP4 (MediaCodec), private Ebenen einfügen, PDF-Import, Zeitraffer (Aufnahme via Strich-Log +
   MediaCodec), Vorlagen bearbeiten, Miniatur drehen.

### C.7 Abhängigkeiten und Konfliktvermeidung

```
WP0 (Kern-Felder/Hooks) ──┬─> WP1 ──> (ruft APIs von WP4/WP5 auf: applyAndClose, reloadLast, nudge)
                          ├─> WP2 ──> (Compositor; WP6-Animation nutzt ihn nur lesend)
                          ├─> WP3 <── (guides/Assist.kt aus WP6, Schnittstelle vorab fix)
                          ├─> WP4 ──> (nutzt StrokeRenderer aus WP3 nur lesend)
                          ├─> WP5
                          └─> WP6
A (S Pen) ── CanvasView-Hooks für WP1 (QuickMenu, Stifttaste), WP2 (Deckkraft-Modus, Ebene wählen), WP5 (Kneifen)
B (Farbe) ── Statusleisten-Aktion „Weiter füllen“ über WP0-API; Sekundärfarbe in EditorState über WP0
```

- Jede Datei hat genau einen Eigentümer (Tabellen oben). Fremde öffentliche APIs nur aufrufen, nicht ändern;
  fehlende APIs als Wunsch an den Eigentümer melden.
- Gemeinsame Schnittstellen vorab festlegen: `guides/Assist.kt` (WP6 → WP3), `sidebarSource` (WP0 → WP1/WP4),
  `flash/statusBar` (WP0 → alle), `selectionTool.reloadLast()` / `transformTool.nudge()` (WP5 → WP1),
  `adjustmentTool.applyAndClose()` (WP4 → WP1), Compositor-Einstieg für Zwiebelschichten (WP2 → WP6).
- Build-Regel aus `agent_common.md` gilt: nur eigene Dateien reparieren, bei fremden Compile-Fehlern warten.
