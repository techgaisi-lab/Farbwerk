# Farbwerk – Optik-Vorgabe (verbindlich)

Abgeleitet aus Referenz-Screenshots des Vorbilds (iPad, Dunkel-Modus). Maße in **dp** (1 iPad-pt ≈ 1 dp).
Ziel: Form, Position, Größe, Farben, Typografie und Zustände entsprechen den Screenshots. **Ausnahmen:**
App-Name „Farbwerk“ statt Original-Name, eigene Icons (gleiche Bedeutung, gleiche Größe/Strichstärke/Position),
eigene Pinselnamen und -vorschauen.

Referenzbilder (nur zum Ansehen, nicht ins Repo kopieren): `/mnt/user-data/uploads/*.webp`

---

## 0. Globale Tokens

| Token | Wert | Verwendung |
|---|---|---|
| `bar` | `#1C1C1E` @ 92 % | Obere Leiste |
| `panel` | `#2A2A2C` @ 96 % (leicht transluzent, Leinwand schimmert durch) | Alle Popover/Panels |
| `panelInset` | `#3A3A3C` | Gruppierte Listen-Container (Aktionen), Set-Kacheln (Pinsel), Badges |
| `rowPressed` | `#48484A` | Gedrückt-Zustand |
| `separator` | `#FFFFFF` @ 8 % | Haarlinien zwischen Zeilen (0.5 dp) |
| `accent` | `#0A84FF` | Aktive Symbole, Toggle an, Auswahl |
| `selectRow` | `#0A84FF` (Pinsel), `#5E7CFF`/hellblau-violett (Ebene primär), `#2E4C8F` (Ebene sekundär) | Markierte Zeilen |
| `textPrimary` | `#FFFFFF` | Titel, Zeilentexte |
| `textSecondary` | `#FFFFFF` @ 55 % | Untertitel, Maße, Badges |
| `iconIdle` | `#FFFFFF` @ 85 % | Symbole inaktiv |
| `workspace` | `#1E1E1E` mit sehr feinem Rauschen/Raster (optional) | Hintergrund hinter der Leinwand |
| `galleryBg` | `#000000` – `#0D0D0D` | Galerie |

**Schrift:** System-Sans (Roboto auf Android); Gewichte: Titel 700, Zeilen 400/500.
**Schatten Panels:** weich, `0 8dp 24dp` Schwarz @ 45 %.
**Eckenradius:** Panels 13 dp, Zeilen/Kacheln 6 dp, Badges/Pills 5 dp, Leinwand 0.

---

## 1. Obere Leiste (Editor)

- Höhe **44 dp** (ohne Statusleiste), volle Breite, Farbe `bar`. Kein Rand, kein Schatten.
- **Links**, ab x = 12 dp:
  - Text „Galerie“ – 15 sp, Gewicht 400, weiß, vertikal zentriert.
  - Danach 4 Symbole im Abstand von **36 dp** (Mitte zu Mitte), erstes Symbol 20 dp rechts vom Text:
    Aktionen (Schraubenschlüssel), Anpassungen (Zauberstab), Auswahl (S-Schleife), Transformieren (Pfeil).
  - Symbolgröße **22 dp**, Strich 1.8 dp, Farbe `iconIdle`.
  - **Aktiv/geöffnet:** blaue gefüllte Kreisscheibe Ø **30 dp** (`accent`) hinter dem Symbol, Symbol weiß.
- **Rechts**, rechter Rand 14 dp, Abstand **44 dp** Mitte zu Mitte:
  - Malen (Pinsel), Wischen (Finger), Radieren (Radierer), Ebenen (zwei versetzte Quadrate), Farbkreis.
  - Symbolgröße **24 dp**. **Aktives Werkzeug:** Symbol einfach in `accent` eingefärbt (KEIN Kreis dahinter).
  - Ebenen-Symbol aktiv (Panel offen): ebenfalls `accent`-Tönung.
  - **Farbkreis:** Ø **28 dp**, gefüllt mit aktueller Farbe, 1.5 dp weißer Rand @ 90 %.
- Hinter offenen Panels bleibt die Leiste sichtbar.

## 2. Seitenleiste

- **Bündig am linken Bildschirmrand** (x = 0), vertikal so, dass ihre Mitte bei ~45 % der Höhe liegt.
- Breite **34 dp**, Höhe ~ **330 dp**; Form: Rechteck mit **rechten** Ecken 7 dp rund, linke Ecken 0.
  Farbe `#2C2C2E`.
- Inhalt von oben:
  1. **Größen-Regler**: Rille 12 dp breit × 120 dp hoch, `#1A1A1A`, Radius 6 dp, mittig.
     Daumen: Rechteck **20 × 11 dp**, Radius 3 dp, Farbe `#CFCFCF`, über die Rille hinausragend.
  2. Abstand 16 dp, **Modifizierer**: Quadrat **13 × 13 dp**, 1.2 dp Kontur `#FFFFFF` @ 70 %, Radius 2.5 dp, keine Füllung.
  3. Abstand 16 dp, **Deckkraft-Regler**: wie oben.
  4. Abstand 18 dp, **Rückgängig** (Pfeil zurück), darunter 10 dp **Wiederholen**; 18 dp Symbole, `iconIdle`, inaktiv @ 30 %.
- Beim Ziehen: Vorschaublase rechts neben der Leiste (Panel-Stil, Radius 10 dp), zeigt Prozent + Pinselabdruck.
- „Rechtshändige Oberfläche“ spiegelt alles an den rechten Rand.

## 3. Popover (gemeinsam)

- Hängt an der oberen Leiste: Oberkante **6 dp** unter der Leiste, **Pfeil** (Dreieck 18 × 9 dp, gleiche Farbe)
  zeigt mittig auf das auslösende Symbol. Panel-Kante richtet sich am Symbol aus, bleibt ≥ 8 dp vom Bildschirmrand.
- Farbe `panel`, Radius 13 dp, Schatten wie oben.
- **Titel:** 22 sp, Gewicht 700, weiß, Innenabstand links 20 dp, oben 16 dp; Titelzeile 52 dp hoch.
- **„+“** rechts im Titel: 22 dp, dünn (1.5 dp), weiß, rechter Abstand 18 dp.
- Öffnen: Skalierung 0.94 → 1 + Einblenden, Ursprung = Pfeilspitze, 180 ms, ease-out. Schließen 120 ms.

## 4. Pinselbibliothek

- Breite **400 dp** (neuer Stil, Screenshot mit Kacheln) bzw. bis 470 dp, Höhe bis ~ 600 dp.
- **Linke Spalte (Sets)** Breite **118 dp**, Innenabstand links 12 dp:
  - Jede Set-Zeile ist eine **Kachel**: Höhe 32 dp, Radius 7 dp, Hintergrund `panelInset`, vertikaler Abstand 6 dp.
  - Inhalt: Icon 14 dp (weiß @ 80 %) bei x = 10 dp, Label 13 sp, Gewicht 500, weiß, 8 dp rechts vom Icon.
  - **Ausgewählt:** Kachel `accent`, Text/Icon weiß.
  - (Alter Stil ohne Kacheln ebenso zulässig: nur Icon + Label, ausgewähltes Icon in `accent`.)
- **Rechte Spalte (Pinsel)**: Innenabstand rechts 12 dp, Zeilenhöhe **62 dp**, Abstand 2 dp.
  - Name oben links: 11 sp, Gewicht 600, weiß, Abstand oben 6 dp / links 10 dp.
  - Darunter Strichvorschau: weißer Pinselstrich (S-Kurve leicht ansteigend, dick→dünn), Höhe ~ 28 dp, über volle Breite.
  - **Ausgewählt:** ganze Zeile `accent` (Radius 6 dp), Strich weiß.
  - Rechts oben in der Zeile optional kleines Symbol (eigener Pinsel / Häkchen) 12 dp.
- Sets-Spalte und Pinsel-Spalte scrollen unabhängig; beide ohne sichtbare Scrollbar.

## 5. Ebenen-Panel

- Breite **300 dp**, Titel „Ebenen“, „+“ rechts.
- Zeile Höhe **52 dp**, volle Breite, Innenabstand 10 dp:
  - Miniatur 40 × 40 dp (Seitenverhältnis der Leinwand, Schachbrett für Transparenz), Radius 3 dp.
  - Name 14 sp weiß, 12 dp rechts von Miniatur.
  - Rechts: Mischmodus-Buchstabe („N“, „M“, …) 14 sp weiß @ 85 %, dann Kontrollkästchen **20 × 20 dp**,
    Radius 4 dp, 1.2 dp weißer Rand; angehakt = weißes Häkchen auf weißer Kontur (kein blau).
  - Haarlinie `separator` zwischen Zeilen.
  - **Primär ausgewählt:** Zeile `#5E7CFF`-ähnliches Blau (heller als accent), Radius 6 dp, Text weiß.
  - **Gruppe:** „>“-Pfeil statt Mischmodus.
- Unterste Zeile „Hintergrundfarbe“ mit Farbfläche als Miniatur.

## 6. Aktionen-Panel

- Breite **320 dp**, Titel „Aktionen“.
- **Tab-Reihe** unter dem Titel: 6 Tabs gleich verteilt, je Icon-Kachel **28 × 22 dp**, Radius 6 dp,
  Hintergrund `panelInset`, Icon weiß 16 dp; Label darunter 10 sp weiß @ 85 %.
  **Aktiver Tab:** Kachel `accent`.
  Tabs: Hinzufügen · Leinwand · Teilen · Video · Einstellungen · Hilfe.
- Inhalt: **gruppierte Container** (`panelInset`, Radius 9 dp, seitlicher Abstand 12 dp, Abstand zwischen Gruppen 14 dp).
  - Zeile 38 dp hoch, Text 15 sp weiß, Innenabstand links 12 dp, Haarlinien zwischen Zeilen.
  - Schalter rechts: iOS-artiger Toggle **44 × 26 dp**, an = `accent`, aus = `#5A5A5E`, Knopf weiß Ø 22 dp.
  - Deaktivierte Zeilen Text @ 35 %.
- Navigationszeilen (z. B. „Druck und Glättung“, „Gestensteuerung“) als eigene Gruppe ohne Pfeil.

## 7. Farben

- Panel-Variante **schwebend** (wie Screenshot): Breite 300 dp; oben mittig Greifleiste 36 × 4 dp `#FFFFFF` @ 30 %,
  Radius 2 dp; Titel „Farben“ 18 sp 700 links, darunter Modusname 12 sp @ 55 %;
  rechts oben Schließen-Knopf: Kreis Ø 18 dp `#FFFFFF` @ 20 % mit × weiß.
- Angedockt (am Farbkreis): normales Popover 320 dp.
- **Tab-Leiste unten**: 5 Symbole (Scheibe = Kreisring, Klassisch = gefülltes Quadrat, Harmonie = Y-Gabel,
  Werte = Schiebereglerlinien, Paletten = 3×3 Punkteraster), 22 dp, gleich verteilt, aktiv = `accent`.
- Über der Tab-Leiste: Helligkeitsregler (dünne Linie 2 dp, Daumen Ø 12 dp weiß).
- Harmonie-Rad: Farbtonkreis mit Sättigung nach innen zu Weiß, Griffe = Kreise mit 2 dp weißem Rand, Hauptgriff Ø 22 dp, weitere Ø 12 dp.

## 8. Kopieren & Einfügen / Referenz (schwebende Fenster)

- Schwebendes Fenster: `panel`, Radius 13 dp, Greifleiste oben mittig, Titel 18 sp 700, Schließen-Kreis rechts oben.
- **Kopieren & Einfügen:** Breite ~ 310 dp; Reihe aus 6 Aktionen (Icon 18 dp + Label 10 sp darunter), gleich verteilt.
- **Referenz:** Breite ~ 270 dp, Bildbereich, unten Segment-Leiste (`panelInset`, Radius 8 dp) mit
  „Leinwand“ (aktiv = `accent` Kachel), „Bild“, „Gesicht“ (bei uns weglassen oder ausgegraut).

## 9. Galerie

- Hintergrund `galleryBg`.
- Kopfzeile 64 dp: links App-Name **„Farbwerk“** 28 sp 700 weiß (x = 28 dp);
  rechts Textknöpfe „Auswählen“, „Importieren“, „Foto“ 15 sp weiß, Abstand 22 dp, dann „+“ 26 dp dünn.
- Raster: 4 Spalten (Querformat Tablet), Kachelbreite ~ 150 dp, Spaltenabstand ~ 46 dp, Zeilenabstand ~ 34 dp.
  Miniatur mit leichtem Schatten, Ecken 2 dp, Seitenverhältnis wie Werk, unten ausgerichtet.
  Darunter: Name 12 sp 600 weiß (6 dp Abstand), Maße 10 sp @ 55 % („2048 × 2048px“).
  **Stapel:** 2–3 leicht gedrehte, versetzte Karten; Untertitel „N Werke“.
- **„Neue Leinwand“-Popover** (am „+“): Breite 320 dp, Titel 20 sp 700 + Ordner-Icon rechts (eigene Leinwand).
  Zeilen 34 dp: Name 14 sp weiß links; rechts **Farbprofil-Text** („sRGB“, „P3“) 11 sp @ 55 % und daneben
  **Maß-Pill** (`panelInset`, Radius 5 dp, Text 11 sp weiß, z. B. „2048 × 2048px“). Haarlinien zwischen Zeilen.
  Erste Zeile „Bildschirmgröße“ abgesetzt.

## 10. Leinwand

- Leinwand ohne Rand/Schatten auf `workspace`, eingepasst mit Rand ~ 40 dp, mittig.
- Wenn Panels offen sind, bleibt die Leinwand unverändert dahinter (Panels schweben).

## 11. Interaktion & Bewegung

- Tippen auf Leinwand außerhalb schließt Popover (ohne zu malen).
- Werkzeugsymbol-Tönung wechselt sofort (keine Animation), Panel-Öffnen wie in §3.
- Zeilen-Auswahl: Hintergrund wechselt sofort; Wischaktionen rasten federnd ein.
- Toggles animieren 150 ms.
