# Farbwerk – Funktions- und Verhaltensanleitung (Bauplan)

Dieses Dokument beschreibt Funktionsumfang, Bedienlogik und Verhalten der Vorbild-App (iPad-Mal-App, Version 5.x)
als **Bauplan für Farbwerk**. Es ist in eigenen Worten verfasst und enthält keine Bilder, Icons oder Texte des
Originals. Wo Farbwerk bewusst abweicht (Android, S Pen, Tastatur), steht das als **Farbwerk-Hinweis** dabei.

**Quellen:** offizielles Online-Handbuch des Vorbilds (Kapitel Interface & Gesten, Galerie, Pinsel, Farben, Ebenen,
Text, Zeichenhilfen, Animation, Seiten, Aktionen, Auswahl, Transformieren, Anpassungen), ergänzt um Beobachtungen.
Maße, Farben und Zeiten im Abschnitt *Visuelles Design & Animation* sind **Richtwerte** (aus Bildschirmfotos und
Bedienung abgeleitet, nicht offiziell dokumentiert) und als solche markiert.

**Schreibweise:** „Tippen“ = kurzer Kontakt ohne Bewegung (< ca. 300 ms, < Touch-Slop). „Halten“ = Kontakt ohne
Bewegung über die eingestellte Halteverzögerung. „Primär“/„Sekundär“ bei Ebenen = aktive bzw. zusätzlich markierte
Ebene. Alle UI-Texte in Farbwerk deutsch.

---

## Inhalt

1. Grundaufbau der Oberfläche
2. Gesten (Leinwand)
3. Seitenleiste
4. Tastenkürzel
5. QuickMenu (Schnellmenü)
6. Kopieren-&-Einfügen-Menü
7. Barrierefreiheit
8. Galerie
9. Pinsel: Malen, Wischen, Radieren
10. Pinselbibliothek
11. Pinsel-Studio (alle Einstellungen)
12. Doppelpinsel
13. Pinsel importieren/teilen
14. Farben (Panel, Scheibe, Klassisch, Harmonie, Werte, Paletten)
15. ColorDrop & Pipette
16. Ebenen
17. Mischmodi
18. Masken (Alpha-Sperre, Ebenenmaske, Schnittmaske)
19. Text
20. Zeichenhilfen & Zeichenassistent
21. QuickShape
22. Animationsassistent
23. Seitenassistent
24. Aktionen (Hinzufügen, Leinwand, Teilen, Video, Einstellungen, Hilfe)
25. Auswahl
26. Transformieren
27. Anpassungen (alle)
28. Referenzfenster
29. 3D-Malen (nur Erwähnung)
30. Visuelles Design & Animation
31. Stift-Verhalten (S Pen auf Android)

---

## 1. Grundaufbau der Oberfläche

Die Oberfläche besteht aus drei festen Bereichen über der vollflächigen Leinwand:

| Bereich | Position | Inhalt (von links nach rechts / oben nach unten) |
|---|---|---|
| Obere Leiste links („Bearbeiten“) | oben links | **Galerie** (Textknopf) · **Aktionen** (Schraubenschlüssel) · **Anpassungen** (Zauberstab) · **Auswahl** (Band/S-Form) · **Transformieren** (Pfeil/Cursor) |
| Obere Leiste rechts („Malen“) | oben rechts | **Malen** (Pinsel) · **Wischen** (Finger) · **Radieren** (Radiergummi) · **Ebenen** (zwei überlappende Quadrate) · **Aktive Farbe** (runder Farbkreis, ganz rechts) |
| Seitenleiste | standardmäßig links, mittig | Größenregler · Modifizierer-Taste (Quadrat) · Deckkraftregler · Rückgängig · Wiederholen |

Verhalten:
- Ein Tipp auf ein Symbol der oberen Leiste öffnet das zugehörige **Popover** direkt unterhalb des Symbols; ein
  zweiter Tipp auf dasselbe Symbol oder ein Tipp auf die Leinwand schließt es. Ein Tipp auf die Leinwand, der ein
  Popover schließt, **malt nicht**.
- Es ist immer höchstens ein Popover offen; Tippen auf ein anderes Symbol wechselt direkt.
- Aktive Werkzeuge (Malen/Wischen/Radieren, Auswahl, Transformieren, Anpassen) werden durch die Akzentfarbe (Blau)
  des Symbols angezeigt.
- Werkzeugmodi Auswahl, Transformieren und Anpassungen blenden die Seitenleiste nicht aus (die Seitenleiste bleibt
  sichtbar, außer im Vollbild). In Klonen/Verflüssigen steuern die Seitenleisten-Regler Größe/Stärke des Werkzeugs.
  *Farbwerk-Hinweis:* aktuell wird die Seitenleiste außerhalb von Malen/Wischen/Radieren ausgeblendet – siehe Lücken.
- **Hinweisleiste oben Mitte**: kurze Statusmeldungen (z. B. „Rückgängig: Pinselstrich“, „Alpha-Sperre an“) und
  kontextabhängige Schaltflächen (z. B. „Form bearbeiten“ nach QuickShape, „Weiter füllen“ nach ColorDrop).
- **Vollbild** (4-Finger-Tippen) blendet alle Leisten aus; oben links bleibt ein kleiner halbtransparenter
  Vollbild-Indikator, dessen Antippen die Oberfläche zurückholt.
- **Helle Oberfläche** (Einstellungen): hellere Variante mit mehr Kontrast; Standard ist dunkles Anthrazit.
- **Rechtshändige Oberfläche** (Einstellungen): Seitenleiste wechselt nach rechts.

---

## 2. Gesten (Leinwand)

### 2.1 Grundgesten

| Geste | Wirkung | Details / Sonderfälle |
|---|---|---|
| 1 Finger/Stift ziehen | aktuelles Werkzeug (Malen, Wischen, Radieren, Auswahl …) | Finger-Malen abschaltbar („Touch-Aktionen deaktivieren“). |
| 2 Finger kneifen | Zoomen | Zentrum = Mittelpunkt der Finger. Zoombereich sinnvoll ca. 2 %–6400 %. |
| 2 Finger kneifen + drehen | Leinwand drehen | Abschaltbar („Drehen beim Zoomen“, Standard an). Drehung ohne Einrasten; beim Loslassen nahe 0°/90°/… *kein* Einrasten im Original. |
| 2 Finger ziehen | Leinwand verschieben | gleichzeitig mit Zoom/Drehung. |
| **Schnelles Zusammenkneifen** | Leinwand einpassen (Fit to Screen) | Kneifbewegung deutlich schneller als normales Zoomen, Finger am Ende abheben. Die umgekehrte schnelle Spreizbewegung kehrt zur vorherigen Ansicht zurück. Animierter Übergang. |
| 2-Finger-Tippen | Rückgängig | Finger zusammen oder auseinander. Hinweis oben: „Rückgängig: <Aktion>“. Bis zu 250 Schritte. |
| 2 Finger halten | Schnelles Rückgängig | Nach der *Verzögerung für schnelles Rückgängig* (Einstellung 0–1,5 s) wiederholt sich Rückgängig fortlaufend, bis Finger abheben. |
| 3-Finger-Tippen | Wiederholen | Halten = schnelles Wiederholen. |
| 3 Finger hin und her schrubben | Ebene leeren | Mehrere Richtungswechsel nötig; leert die Primärebene (rückgängig machbar). Innerhalb einer Auswahl nur den Auswahlbereich. |
| 3 Finger nach unten wischen | Kopieren-&-Einfügen-Menü | Menü bleibt offen, bis eine Option gewählt oder auf die Leinwand getippt wird. |
| 4-Finger-Tippen | Vollbild ein/aus | |
| Berühren & halten (Finger) | Pipette | siehe 15.2. Verzögerung einstellbar. |
| Zeichnen & halten | QuickShape | siehe Kap. 21. |
| Regler greifen, seitlich wegziehen | Präzise Reglersteuerung | Je weiter der Finger seitlich vom Regler entfernt ist, desto feiner die Änderung pro Bildschirmpunkt. |

Wichtig für Rückgängig: Verlassen des Dokuments (Galerie) oder Beenden löscht den Verlauf; Änderungen sind dann
endgültig.

### 2.2 Ebenen-Gesten (im Ebenen-Panel)

| Geste | Wirkung |
|---|---|
| Tippen auf Ebene | wird Primärebene (hellblau). Erneutes Tippen auf die Primärebene öffnet die Ebenenoptionen. |
| Nach rechts wischen (1 Finger) | Ebene zusätzlich als Sekundärebene markieren (dunkelblau). Beliebig viele. Malen wirkt nur auf die Primärebene. |
| Nach links wischen | Schaltflächen **Sperren · Duplizieren · Löschen** einblenden. |
| 2 Finger zusammenkneifen über mehrere Ebenen | Ebenen zusammenfügen (alle dazwischen liegenden mit). |
| 2-Finger-Tippen auf Ebene | Ebene wird primär, **Deckkraft-Modus**: Hinweisleiste oben zeigt Deckkraft in %, horizontales Ziehen irgendwo auf der Leinwand ändert die Deckkraft. Beenden durch Tippen auf ein Werkzeug. Mit aktiver Auswahl wirkt sie nur innerhalb der Auswahl. |
| 2 Finger nach rechts wischen | Alpha-Sperre umschalten (Miniatur bekommt Schachbrett-Hintergrund). |
| 2 Finger halten auf Ebene | Ebeneninhalt auswählen (Auswahl wird sofort übernommen; Alpha-gewichtet). |
| Halten & ziehen | Ebene/Gruppe umsortieren; Sekundärebenen ziehen mit. Mit anderem Finger weitere Ebenen antippen = Stapel ziehen. Auf eine Ebene fallen lassen = Gruppe bilden. Auf die Galerie-Taste ziehen = in ein anderes Werk/neues Werk exportieren. |
| Halten auf Sichtbarkeits-Häkchen | **Solo-Sichtbarkeit**: nur diese Ebene sichtbar; Hinweis oben. Erneut halten beendet Solo; zuvor ausgeblendete Ebenen bleiben ausgeblendet. |

### 2.3 Schwebe-Gesten (Stift schwebt über dem Display)

| Geste | Wirkung |
|---|---|
| Stift schwebt + 2 Finger kneifen | Pinselgröße ändern (zusammen = kleiner, auseinander = größer). |
| Stift schwebt + Finger nach links/rechts (oder auf/ab) | Pinseldeckkraft ändern. |
| Stift schwebt | Pinselcursor zeigt Größe, Form, Textur, Neigung und Ausrichtung als Vorschau (Malen/Wischen/Radieren). |

### 2.4 Gestensteuerung (Einstellungen → Gestensteuerung)

Jede Funktion hat eine eigene Seite mit einer Liste möglicher Auslöser (Schalter). Mehrere Auslöser pro Funktion
sind möglich, **ein Auslöser darf aber nur einer Funktion gehören**; Konflikte zeigen ein gelbes Warnsymbol neben dem
Schalter. Halte-basierte Auslöser haben einen **Verzögerungsregler**.

Funktionen und typische Auslöser:

| Funktion | Auslöser-Optionen | Besonderheit |
|---|---|---|
| Wischen | Berühren (Finger), Modifizierer+Berühren, Stift-Doppeltippen … | Striche mit diesem Auslöser wischen immer, egal welches Werkzeug. |
| Radieren | wie oben | Striche radieren immer. |
| Zeichenassistent | Modifizierer, Stift-Doppeltippen, weitere | schaltet Assistent auf aktueller Ebene. |
| Pipette | Berühren & halten (Standard), Modifizierer tippen, Modifizierer+Berühren, Stift-Doppeltippen/Drücken | per Modifizierer/Stift erscheint die Pipette an der zuletzt bemalten Stelle und nimmt beim Abheben auf. **Verzögerung** einstellbar. |
| QuickShape | Zeichnen & halten (Standard), Modifizierer, Stift-Doppeltippen/Drücken … | andere Auslöser wandeln den *letzten* Strich um. Verzögerung einstellbar. |
| QuickMenu | Tippen (1 Finger), Berühren & halten, Modifizierer, Stift … | standardmäßig aus. Schließt durch Wegtippen, Weitermalen oder Wahl. |
| Vollbild | 4-Finger-Tippen u. a.; zusätzlich **Automatisches Vollbild** (Oberfläche verschwindet beim Malen, kehrt nach einstellbarer Verzögerung zurück) | |
| Ebene leeren | Schrubben (Standard, fest), weitere | |
| Kopieren & Einfügen | 3-Finger-Wischen u. a. | |
| Ebene wählen | Berühren & halten bzw. Modifizierer+Berühren, Stift-Drücken | Halten/Ziehen auf der Leinwand: Popup mit den Ebenen unter dem Finger, die nicht-transparente Pixel haben; Auswahl macht sie primär. Stift-Drücken-Modus endet nach 5 s. Standard aus. |
| Allgemein | „Touch-Aktionen deaktivieren“ (Finger nur Gesten), „Rückgängig/Wiederholen deaktivieren“, „Drehen beim Zoomen“ (an), Handballen-Unterstützung (siehe 31) | |

---

## 3. Seitenleiste

Aufbau (oben → unten), Standard links, vertikal mittig:

1. **Größenregler** (oben): nach oben = größer. Tippen auf eine Stelle springt dorthin; Ziehen mit seitlichem Abstand =
   feine Steuerung (siehe 2.1). Während des Ziehens erscheint neben dem Regler eine **Vorschaublase** mit dem
   Pinsel in aktueller Größe (Form-Abdruck) und dem Prozentwert.
2. **Modifizierer-Taste** (kleines Quadrat): Standard = Pipette.
   - *Tippen*: Pipette erscheint schwebend an der zuletzt bemalten Stelle; ziehen zur Farbe, loslassen übernimmt.
   - *Halten + mit anderer Hand die Leinwand berühren*: Pipette an der Berührstelle.
   - Kann per Gestensteuerung andere Funktionen auslösen (Wischen, Radieren, QuickShape, QuickMenu, Ebene wählen …).
3. **Deckkraftregler** (unten): nach oben = deckender. Vorschaublase mit Prozentwert.
4. **Rückgängig** (Pfeil oben) und **Wiederholen** (Pfeil unten). Halten wiederholt fortlaufend.

**Größen-/Deckkraft-Speicher:** In der Vorschaublase gibt es ein **+**: speichert den aktuellen Wert als Markierung
(kleiner Strich) auf dem Regler. Bis zu 4 Größen und 4 Deckkraftwerte **pro Pinsel und pro Werkzeug**
(Malen/Wischen/Radieren getrennt). Tippen auf eine Markierung oder Schieben in ihre Nähe rastet ein; Markierung
antippen → **−** erscheint → entfernt sie.

**Verschieben der Seitenleiste:** Finger vom Bildschirmrand über die Modifizierer-Taste ziehen, dann die ganze Leiste
nach oben/unten verschieben und loslassen (in beiden Seitenlagen).

Die Regler sind auf den Min/Max-Bereich des Pinsels (Pinsel-Studio → Eigenschaften) abgebildet. Getrennte Werte je
Werkzeug: Malen, Wischen und Radieren haben eigene Größe, Deckkraft und eigenen Pinsel.

Einstellung „Größe & Deckkraft-Seitenleiste“ blendet die Leiste ganz aus.

---

## 4. Tastenkürzel (Hardware-Tastatur)

Vorbild-Belegung (⌘ = in Farbwerk **Strg**, ⌥ = **Alt**):

| Taste | Funktion |
|---|---|
| Strg+Z / Strg+Umschalt+Z | Rückgängig / Wiederholen |
| Strg+X / C / V | Ebene bzw. Auswahl ausschneiden / kopieren / einfügen |
| Strg+A | **Alles kopieren** (sichtbarer Inhalt zusammengeführt) |
| Strg+J | Ebene/Auswahl auf neue Ebene darüber duplizieren |
| Strg+D | Auswahl aufheben |
| Strg+Rücktaste | Ebene bzw. Auswahlbereich leeren |
| X | zwischen aktueller und vorheriger Farbe wechseln |
| B | Malen wählen; ist Malen aktiv → Pinselbibliothek öffnen |
| E | Radieren wählen; ist Radieren aktiv → Pinselbibliothek öffnen |
| [ / ] | Pinselgröße −/+ 5 % |
| Strg+[ / ] | Pinselgröße −/+ 1 % |
| Umschalt+[ / ] | Pinselgröße −/+ 10 % |
| Strg+U | Farbton/Sättigung/Helligkeit |
| Strg+B | Farbbalance |
| S | Auswahlwerkzeug |
| V | Transformieren |
| L | Ebenen-Panel |
| C | Farben-Panel |
| Alt (gehalten) | Modifizierer-Taste (ältere Systeme: M) |
| Leertaste | QuickMenu |
| Pfeiltasten (in Transformieren) | Inhalt um 1 px verschieben |
| Strg+0 | Vollbild umschalten |
| Strg+; | Perspektiv-Hilfe ein/aus |
| Strg+K | Aktionen-Menü |
| Strg+Komma | Einstellungen |
| Strg (halten) | Übersicht der Kürzel |

**Farbwerk-Hinweis:** Farbwerk hat bewusst Erweiterungen (Leertaste halten = Leinwand verschieben, Strg+S speichern,
Strg+T Transformieren, Strg+E zusammenfügen). Bei Konflikten (S, Leertaste, Strg+A, Strg+B) soll die
Vorbild-Belegung Standard sein und die Farbwerk-Variante über eine Option/zweites Kürzel erreichbar bleiben
(Vorschlag: Leertaste kurz = QuickMenu, Leertaste halten + ziehen = verschieben; Strg+Umschalt+A = alles auswählen;
Wischen auf W statt S).

---

## 5. QuickMenu (Schnellmenü)

- **Aufruf:** über einen in der Gestensteuerung gewählten Auslöser (Standard aus; empfohlen: Finger-Tippen, wenn mit
  Stift gemalt wird, oder Stift-Seitentaste).
- **Form:** kreisförmiges Menü mit **6 Schaltflächen** rund um einen **Mittelknopf** (zeigt den Profilnamen),
  erscheint zentriert an der Auslöseposition.
- **Bedienung:** (a) Tippen öffnet, Menü bleibt offen, bis eine Schaltfläche getippt wird; (b) Schnellwahl: Finger
  halten, Richtung Schaltfläche ziehen, loslassen = auslösen. Die überfahrene Schaltfläche wird hervorgehoben.
- **Standardbelegung:** Neue Ebene · Horizontal spiegeln · Kopieren · Nach unten zusammenfügen · Ebene leeren ·
  Vertikal spiegeln.
- **Anpassen:** Schaltfläche halten → Liste „Aktion festlegen“ (~60 Aktionen) → Auswahl wird gespeichert.
- **Aktionsliste (Auszug):** Ebenen (Neu, Duplizieren, Löschen, Nach unten zusammenfügen, Alpha-Sperre, Ebenendeckkraft,
  Solo-Sichtbarkeit, Ebene wählen), Bearbeiten (Kopieren, Ausschneiden, Einfügen, Rückgängig, Wiederholen, Radieren,
  Wischen, Transformieren, Auswahl), Werkzeuge/Panels (Malen, Pipette, Umfärben, Farben, Ebenen, Anpassungen,
  Verflüssigen, Klonen), Hilfen/Ansicht (2D-/Isometrie-/Perspektive-/Symmetrie-Hilfe sichtbar, Zeichenassistent,
  QuickShape, Einpassen, Vollbild, Referenzfenster), Pinsel/Farbe (Pinsel wählen, Vorheriger Pinsel, Vorherige Farbe),
  Animation (Animationsassistent, Nächstes/Vorheriges Bild, Zwiebelschicht), „Keine Aktion“.
- **Ebene wählen** im QuickMenu nimmt die oberste Ebene mit Pixeln unter dem Menümittelpunkt.
- **Profile:** beliebig viele QuickMenu-Profile mit je eigenen 6 Belegungen. Mittelknopf tippen → Profilliste, **+**
  legt ein neues an, Doppeltippen auf den Namen benennt um. Wechsel über Mittelknopf.
- Animations-Aktionen funktionieren nur im Animationsassistenten.

---

## 6. Kopieren-&-Einfügen-Menü

Aufruf: 3 Finger nach unten wischen (oder QuickMenu/Aktionen → Hinzufügen). Erscheint als kleines Panel mit
6 Kacheln:

| Kachel | Wirkung |
|---|---|
| Ausschneiden | Primärebene bzw. Auswahl entfernen und in die Zwischenablage. |
| Kopieren | in die Zwischenablage, Original bleibt. |
| Alles kopieren | alle sichtbaren Ebenen zusammengeführt (bzw. Auswahlbereich daraus). |
| Duplizieren | Inhalt/Auswahl auf neue Ebene direkt darüber, Original unverändert. |
| Ausschneiden & Einfügen | in einem Schritt herauslösen und als neue Ebene einfügen. |
| Einfügen | Zwischenablage als neue Ebene, an Originalposition bzw. Mitte, Transformieren wird aktiv. |

Die Zwischenablage ist die System-Zwischenablage (Einfügen in andere Apps möglich).
*Farbwerk-Hinweis:* Bild in die Android-Zwischenablage als `content://`-URI (PNG) legen; Einfügen akzeptiert
Bild-Clips anderer Apps.

---

## 7. Barrierefreiheit

- **Einfinger-Begleitfenster:** schwebende Leiste mit Rückgängig, Wiederholen, Zoom, Verschieben, Einpassen. Zoom-Modus
  (Knopf blau): Ziehen von der Mitte weg = hinein, zur Mitte = hinaus, blaue radiale Linie zeigt den Betrag; am Rand
  ansetzen = feiner; Ziehen am Rand dreht. Verschieben-Modus: Ziehen verschiebt. Solange Zoom/Verschieben aktiv sind,
  malt der Finger nicht.
- **Farbkarten** (größere Farbfelder mit Farbnamen), **Farbnamen-Hinweis** beim Pipettieren/Feldwahl,
  **Rückmeldetöne**, **Tipp-Assistenz** (nur die erste Berührung zählt).
- **Strich-Stabilisierung global:** Stabilisierung (Mittelwert, stärker bei schnellen Strichen), Bewegungsfilterung
  (schneidet Zitter-Spitzen ab, tempounabhängig), Bewegungsfilter-Ausdruck (gibt Lebendigkeit zurück, nur mit
  Filterung), **Spitzenbindung** (an: Strich beginnt exakt an der Spitze; aus: Startpunkt wird gemittelt).
- Größere Systemschrift → Menüs skalieren; bei großem Text zeigt Halten auf kleinen Knöpfen eine vergrößerte
  Beschriftung.

---

## 8. Galerie

### 8.1 Aufbau
- Kopfzeile rechts: **Auswählen · Importieren · Foto · +** (Neue Leinwand). Titel „Galerie“ links groß.
- Raster aus Werk-Miniaturen (Seitenverhältnis gewahrt, mit Schatten), darunter Titel („Unbenanntes Werk“) und
  Pixelmaße. Stapel erscheinen als Stapel aus versetzten Miniaturen mit Anzahl.

### 8.2 Neue Leinwand
- **+** öffnet Menü „Neue Leinwand“ mit Vorlagen (Bildschirmgröße, Quadrat, 4K, A4, 4×6 Foto, US-Brief …) und einem
  Knopf für **Eigene Leinwand**. Vorlage nach links wischen → Bearbeiten/Löschen.
- **Eigene Leinwand:** Name; Abmessungen (Breite, Höhe, DPI; Einheiten mm/cm/Zoll/px; Größe 1×1 bis ca. 16k je Seite,
  geräteabhängig; Anzeige der dadurch möglichen **maximalen Ebenenzahl**, die mit der Größe sinkt); Farbprofil
  (RGB/CMYK, mehrere Profile, Import eigener); Zeitraffer-Einstellungen (1080p–4K, Qualität Niedrig–Verlustfrei, HEVC);
  Leinwandeigenschaften (Hintergrundfarbe, Hintergrund ausgeblendet). „Erstellen“ speichert optional als Vorlage.

### 8.3 Organisieren
- **Auswählen-Modus:** Antippen markiert (blauer Haken); leichtes Überstreichen markiert mehrere. Leiste: Stapeln,
  Vorschau, Teilen, Duplizieren, Löschen.
- **Wischen nach links** auf Miniatur: Teilen · Duplizieren · Löschen (Löschen endgültig, mit Rückfrage).
- **Stapel bilden:** Miniatur halten und auf eine andere ziehen – das Ziel leuchtet blau → loslassen. In einen
  vorhandenen Stapel: über dem Stapel halten, bis er zweimal blau blinkt und sich öffnet, dann ablegen.
- **Aus Stapel entfernen:** im geöffneten Stapel Werk aufnehmen und mit anderem Finger auf „Zurück“ tippen bzw. darauf
  ziehen.
- **Umbenennen:** Tippen auf den Titel unter der Miniatur.
- **Drehen der Miniatur:** zwei Finger auf der Miniatur drehen.
- **Umsortieren:** halten und ziehen; weitere Werke mit zweitem Finger antippen = Mehrfachziehen.

### 8.4 Vorschau
- Miniatur mit zwei Fingern **aufziehen** → Vollbild-Vorschau. Wischen links/rechts blättert, Tippen blendet
  Pfeile ein/aus, Tippen an den Rändern blättert. Zusammenkneifen schließt. Doppeltippen öffnet das Werk zum Malen.
- Vorschau einer Auswahl: Auswählen → Werke markieren → Vorschau.

### 8.5 Import/Export
- **Importieren** (Dateien): Bilder, PSD (mit Ebenen), eigenes Projektformat, PDF (jede Seite eine Ebene, weißer
  Seitenhintergrund wird transparent, mit Zeitleiste).
- **Foto:** aus der Fotomediathek als neues Werk.
- **Drag & Drop** von Dateien in die Galerie; Werke heraus in andere Apps ziehen.
- Teilen einzeln (Wischen) oder mehrfach (Auswählen) mit Formatwahl (siehe 24.3).

---

## 9. Pinsel: Malen, Wischen, Radieren

- Tippen auf Malen/Wischen/Radieren wählt das Werkzeug; **erneutes Tippen** auf das aktive Werkzeug öffnet die
  Pinselbibliothek.
- Alle drei Werkzeuge nutzen **dieselbe Bibliothek**; jedes merkt sich seinen eigenen Pinsel, seine Größe und Deckkraft.
- **Mit aktuellem Pinsel**: Halten auf ein *nicht aktives* Werkzeugsymbol übernimmt den Pinsel des gerade aktiven
  Werkzeugs (z. B. mit dem Malpinsel radieren). Hinweis oben: „<Werkzeug> mit aktuellem Pinsel“.
- **Malen** trägt Farbe auf und reagiert auf Druck, Neigung, Ausrichtung und ggf. Tempo.
- **Wischen** verschiebt/vermischt vorhandene Pixel. Deckkraftregler = Wischstärke: hoch = nass, starkes Verschmieren,
  ggf. Schlieren; niedrig = weiches Verblenden.
- **Radieren** entfernt Pixel; Deckkraft < 100 % radiert teilweise.

---

## 10. Pinselbibliothek

### 10.1 Aufbau
Popover unter dem Werkzeugsymbol, zweispaltig: **links Pinselsets** (Liste mit Symbol/Emoji und Name, aktives Set
blau hinterlegt), **rechts Pinsel des Sets** (Name + Strichvorschau, aktiver Pinsel blau hinterlegt). Kopf: Titel
„Pinselbibliothek“, rechts **+**/Kontextmenü (Neuer Pinsel, Neues Set, Aus Dateien importieren).

### 10.2 Bedienung
| Aktion | Auslösung |
|---|---|
| Pinsel wählen | Tippen. Popover bleibt offen; Tippen auf Leinwand schließt (ohne zu malen). |
| Pinsel-Studio öffnen | Tippen auf den bereits aktiven Pinsel. |
| Pinsel umbenennen | halten → Umbenennen. |
| Duplizieren / Teilen / Löschen | nach links wischen → Schaltflächen (Löschen mit Rückfrage), oder halten → Menü. |
| Zurücksetzen | Pinsel-Studio → Info → Pinsel zurücksetzen. |
| Umsortieren | Pinsel oder Set halten und ziehen. |
| In anderes Set verschieben | Pinsel halten, über ein Set ziehen (Set öffnet sich), innerhalb ablegen = **verschieben**; auf den Set-*Titel* ablegen = **kopieren**. |
| Mehrfachauswahl | einen halten, weitere mit anderem Finger antippen → Stapel ziehen. |
| Suchen | in der Bibliothek nach unten ziehen → Suchfeld; Treffer über alle Sets; Treffer halten → kopieren/zum Set/teilen. |

**Sets:** + → „Neues Set“ (Name, Anwenden/Abbrechen). Set zweimal tippen oder halten → Umbenennen, Teilen,
Duplizieren (Kopie mit Nummer darunter), Löschen, Symbol festlegen (ohne Symbol: erstes Emoji im Namen).

**Zuletzt verwendet:** oberstes Set, bis zu 8 Pinsel. Nach links wischen: **Finden** (zum Original-Set springen),
**Anheften** (bleibt oben, Nadel-Symbol; „Lösen“ entfernt), **Entfernen** (nur aus „Zuletzt“).

**Bibliotheken:** mehrere Bibliotheken; Zusammenkneifen in der Bibliothek zeigt die Bibliotheksübersicht.

---

## 11. Pinsel-Studio

### 11.1 Aufbau
Vollbild mit drei Spalten: **links Attributliste** (14 Gruppen), **Mitte Einstellungen** der gewählten Gruppe
(Regler, Schalter, Diagramme), **rechts Zeichenfläche** zum Testen in Echtzeit. Kopf rechts **Fertig** (speichert),
links **Abbrechen** (verwirft).

- Tippen auf den Zahlenwert eines Reglers öffnet erweiterte Optionen: Zahleneingabe sowie Verknüpfung mit **Druck**,
  **Neigung** und ggf. Rollen (jeweils mit eigenem Anteil/Kurve).
- **Zeichenflächen-Einstellungen** (Symbol oben links an der Fläche): Fläche leeren, alle Einstellungen des Pinsels
  zurücksetzen, Vorschaugröße, eine von 8 Vorschaufarben. Leeren auch per 3-Finger-Schrubben auf der Fläche.

### 11.2 Einstellungen je Gruppe (vollständig)

**1. Strichpfad**
- Abstand (Stempeldichte; 0 = durchgehend), Abstandsstreuung (auch druckgekoppelt; Mindestabstand bleibt),
  Streuung seitlich (quer zum Strich), Streuung längs (entlang, auch über Anfang/Ende hinaus), Abklingen (Strich blendet
  über die Länge aus; aus = kein Abklingen).

**2. Stabilisierung**
- StreamLine Stärke (Nachzieh-Glättung), StreamLine Druck (wie schnell sich Druck aufbaut), Stabilisierung Stärke
  (Mittelwertbildung, stärker bei Tempo), Bewegungsfilterung Stärke, Bewegungsfilterung Ausdruck. Globale Werte unter
  Einstellungen → Druck & Glättung haben Vorrang, wenn gesetzt.

**3. Verjüngung**
- *Druck-Verjüngung* (Stift): Doppelregler Anfang/Ende, „Spitzengrößen verknüpfen“, Größe (wie stark dünner),
  Deckkraft (wie transparent die Enden), Druck (Verjüngung reagiert auf Druck), Spitze (fein ↔ dick),
  Spitzen-Animation (an/aus).
- *Touch-Verjüngung* (Finger): Anfang/Ende, verknüpfen, Größe, Deckkraft, Spitze (ohne Druck).
- *Klassische Verjüngung* (Altverhalten).

**4. Form**
- Formquelle: eigenes Bild (Foto, Datei, Einfügen, Quellbibliothek); im Form-Editor Zwei-Finger-Tippen = invertieren.
- Eingabestil: nur Berührung / Ausrichtung (Azimut, kalligrafisch) / Ausrichtung + Rollen.
- Drehung −100 %…+100 % (0 = fest, 100 = folgt Strich, −100 = gegenläufig), Streuung (Zufallsdrehung je Stempel),
  Anzahl 1–16, Anzahl-Streuung 0–100 %, Zufällig (Zufallsdrehung je Strichbeginn), Horizontal/Vertikal spiegeln.
- **Rundheits-Diagramm**: grüner Punkt = Grunddrehung, blaue Punkte = Stauchung.
- Druck-Rundheit, Neigungs-Rundheit, Rundheit Streuung vertikal/horizontal.
- Formfilterung: Keine / Klassisch / Verbessert (Kantenglättung).

**5. Körnung**
- Körnungsquelle (Körnungs-Editor, Quellbibliothek ~100 Texturen, eigenes Bild).
- Verhalten: **Bewegt** (Körnung wandert mit, schlierig) / **Texturiert** (feststehend wie Schablone).
- Bewegt-Optionen: Bewegung (Mitzug ↔ Rolleneffekt), Maßstab, Zoom (Abgeschnitten / Folgt Größe), Drehung (folgt
  Strich / fest / gegenläufig), Tiefe, Tiefe Minimum, Tiefen-Streuung, Versatz-Streuung (aus für Muster).
- Mischmodus der Körnung, Helligkeit, Kontrast, Körnungsfilterung (wie Form).
- Körnungs-Editor „Automatisch wiederholen“: Körnungsmaßstab, Drehen, Randüberlappung, Maskenhärte, Spiegelüberlappung,
  Pyramiden-Mischung.

**6. Rendering**
- Modus (leicht → stark): Leichte Lasur (Standard), Gleichmäßige Lasur, Intensive Lasur, Starke Lasur, Gleichmäßiges
  Mischen, Intensives Mischen.
- Fluss, Nasse Kanten, Verbrannte Kanten (+ eigener Mischmodus), Mischmodus des Strichs, Luminanz-Mischen,
  Alpha-Schwelle (jedes Pixel ganz deckend oder ganz transparent, Schwellwert-Regler; für Pixelkunst),
  Klassischer Normal-Kombinationsmodus.

**7. Nasse Farbe**
- Verdünnung (Wasser, transparenter), Ladung (Farbmenge am Strichbeginn, wird verbraucht; Abheben lädt neu),
  Anhaftung/Attack (wie viel haftet; erweitert mit Ausrichtung/Neigung/Rollen koppelbar), Ziehen (zieht vorhandene
  Farbe mit), Körnigkeit/Grade, Unschärfe (+ Streuung), Nässe-Streuung.

**8. Farbdynamik**
- Stempel-Farbstreuung: Farbton, Sättigung, Helligkeit, Dunkelheit, Sekundärfarbe.
- Strich-Farbstreuung: dieselben fünf.
- Farbdruck: Farbton, Sättigung, Helligkeit, Sekundärfarbe (100 % = volle Spanne über den Druck).
- Farbneigung: Farbton, Sättigung, Helligkeit, Sekundärfarbe.
- Farb-Rollen (nur bei Stiften mit Rotationssensor).

**9. Dynamik**
- Tempo → Größe (−100…+100 %; positiv: schneller = dünner, negativ: langsamer = dünner), Tempo → Deckkraft (−100…+100 %),
  Tempo → Abstand, Streuung → Größe, Streuung → Deckkraft.

**10. Stift-Eigenschaften**
- **Druckdiagramm** (Druck rein → Wirkung raus, bis zu 4 Zusatzpunkte; Punkt antippen löscht; zurücksetzen).
- Druck → Größe, Deckkraft, Fluss, Ausbluten (je %, je eigene Kurve und Reaktionsgeschwindigkeit/Glättung).
- **Neigung** 0–90° (0–15° ohne Wirkung, 16–30° ungenau, 30–90° typisch); Neigungsdiagramm (Auslösewinkel);
  Neigung → Deckkraft, Abstufung (weiche Schattierung bei flachem Stift), Ausbluten, Größe; Größenkompression
  (Textur wächst nicht mit).
- Rollen → Größe/Deckkraft/Ausbluten (falls Sensor).
- Cursor-Umriss: Keiner / Kontrast / Aktive Farbe (wirkt bei Einstellung „pro Pinsel“).
- Schweben: geschätzter Druck für die Schwebevorschau (Deaktiviert…Max), Schwebefüllung: Keine / Form / Alles.

**11. Eigenschaften**
- Stempelvorschau verwenden, An Bildschirm ausrichten, Vorschaugröße, Wischen-Stärke (wenn als Wischwerkzeug),
  Max./Min. Größe, Max./Min. Deckkraft (begrenzen die Seitenleisten-Regler).

**12. Materialien** (nur 3D): Metallisch Menge/Quelle, Rauheit Menge/Quelle.

**13. Vorschau** (Bibliotheksvorschau): Stempelvorschau, Größe, Druck-Minimum, Druck-Maßstab, Nasse Farbe in Vorschau,
Neigungswinkel.

**14. Über diesen Pinsel**
- Titel (Umbenennen nur in der Bibliothek), Autorenbild, „Erstellt von“, Erstelldatum (automatisch), Unterschrift
  (mit Stift/Finger, ⓧ löscht), **Neuen Rücksetzpunkt erstellen** (einer pro Pinsel), **Pinsel zurücksetzen** (auf
  Rücksetzpunkt bzw. Original, mit Rückfrage).

**Neuer Pinsel:** Bibliothek → + → Neuer Pinsel → Studio mit Grundeinstellungen.

---

## 12. Doppelpinsel

- Zwei Einzelpinsel **desselben Sets** zu einem neuen Pinsel kombinieren (kein Doppelpinsel als Bestandteil).
- Ablauf: Bibliothek öffnen → ersten Pinsel tippen (Primär, hellblau) → zweiten nach rechts wischen (Sekundär,
  dunkelblau) → oben rechts **Kombinieren**. Name = Primärpinsel.
- Im Studio oben links zwei Vorschauen; die gewählte (blaue Linie) wird bearbeitet. **Kombinationsmodus** (wie
  Mischmodi, Standard Normal) mit Live-Vorschau.
- **Trennen:** im Studio Sekundärvorschau tippen → Trennen; ergibt wieder zwei Pinsel.

---

## 13. Pinsel importieren/teilen

- Formate des Vorbilds: Einzelpinsel, Pinselset, Pinselbibliothek; zusätzlich Photoshop-ABR-Import.
- Import: Bibliothek → + → Aus Dateien importieren; Datei antippen/öffnen mit; Drag & Drop.
  Einzelpinsel landen im Set „Importiert“, Sets oben in der Bibliothek.
- Export: Pinsel/Set nach links wischen → Teilen, oder aufnehmen und in andere App ziehen.
- *Farbwerk-Hinweis:* eigenes Format (z. B. `.fwbrush` = ZIP aus JSON + Form/Körnung-PNG, `.fwbrushset`); optional
  ABR-Import (Form-Bitmaps) als P3.

---

## 14. Farben

### 14.1 Farben-Panel (Aufbau)
- Öffnen: Tippen auf den **Farbkreis** ganz rechts in der oberen Leiste. Schließen: Wegtippen.
- Kopf: Titel „Farben“, rechts zwei nebeneinanderliegende Rechtecke: **Primärfarbe** (links) und **Sekundärfarbe**
  (rechts, für Farbdynamik). Antippen wählt, welche bearbeitet wird.
- **5 Tabs unten:** Scheibe (Standard), Klassisch, Harmonie, Werte, Paletten. Der zuletzt gewählte Tab bleibt.
- Unter Scheibe/Klassisch/Harmonie/Werte: **Verlauf** (letzte 10 Farben, neu leer pro Werk, „Leeren“) und
  **Standardpalette** (aktive Palette). Auf kleinen Geräten Umschalter (…) zwischen Verlauf und Palette.
- **Anhängen/Lösen:** am grauen Griff oben ziehen → Panel wird zum kleinen schwebenden **Farbbegleiter** (freie
  Position über der Leinwand); ⓧ dockt wieder an.
- **Aktive Farbe (Kreis):** Tippen = Panel; **Halten** = zwischen aktueller und vorheriger Farbe wechseln; **Ziehen**
  = ColorDrop (Kap. 15).

### 14.2 Scheibe
- Äußerer **Farbtonring** (Fadenkreuz-Ring), innere **Sättigungs-/Helligkeitsscheibe** (links→rechts Sättigung,
  unten→oben Helligkeit).
- Großer ziehbarer Ring = Auswahlmarke. Ein zweiter kleinerer geteilter Ring zeigt links die letzte Verlaufsfarbe und
  rechts die Farbe darunter zum Vergleich.
- **Aufziehen (Pinch)** der inneren Scheibe vergrößert sie auf volle Panelgröße für Feineinstellung; beim Schließen
  wieder normal.
- **Doppeltippen** in der Scheibe rastet auf den nächsten Sonderwert ein (reines Weiß, Schwarz, Mittelgrau, halbe
  oder volle Sättigung, Ränder).

### 14.3 Klassisch
Quadratisches Sättigung/Helligkeit-Feld mit Marke + drei Regler (Farbton, Sättigung, Helligkeit).

### 14.4 Harmonie
- Modus oben links antippbar: **Komplementär** (2 Marken gegenüber), **Split-Komplementär** (3, schmales Dreieck),
  **Analog** (3 benachbart), **Triadisch** (3, gleichseitig), **Tetradisch** (4, Quadrat).
- Farbkreis: Farbton (Winkel) + Sättigung (Radius, Rand = voll). Primärmarke ziehen; Sekundärmarken folgen.
- Tippen auf eine Marke macht deren Farbe sofort aktiv. Farben einzeln in eine Palette ziehen.
- Schwarz-Weiß-Regler darunter = Helligkeit.

### 14.5 Werte
- HSB: Farbton 0–360° (eine Nachkommastelle), Sättigung %, Helligkeit %.
- RGB: je 0–255.
- **Hex-Feld** (6 Zeichen), halten = kopieren/einfügen.
- Alle Felder per Zahleneingabe; alles synchron.

### 14.6 Paletten
- Ansichten: **Kompakt** (kleine Felder, 10 pro Reihe) und **Karten** (große Felder, 3 pro Reihe, mit Farbnamen,
  umbenennbar).
- Aktive (Standard-)Palette mit Haken in blauem Kreis; Tippen auf ein Feld macht die Palette aktiv.
- Feld: Tippen = Farbe übernehmen; Tippen auf leeres Feld = aktuelle Farbe speichern; halten = Menü (Löschen,
  Festlegen/Ersetzen, Als aktuelle Farbe); halten + ziehen = umsortieren; **ziehen auf die Leinwand = SwatchDrop**
  (Füllen wie ColorDrop).
- **+ (oben rechts):** Neue Palette („Unbenannt“), **Neu aus Kamera** (Visuell: Bereich vor der Palette; Indiziert:
  ganzes Bild, kontrastreich; Auslöser speichert), **Neu aus Datei** (JPG/PNG, oder ASE/ACO), **Neu aus Fotos**.
- Palettenmenü (…): Als Standard, Duplizieren (ans Ende), Teilen, Löschen (mit Rückfrage, endgültig).
  Palettentitel halten + ziehen = umsortieren. Import/Export als `.swatches` per Drag & Drop.

---

## 15. ColorDrop & Pipette

### 15.1 ColorDrop (Farbe ziehen und fallen lassen)
1. Den **Farbkreis** (aktive Farbe) aus der oberen Leiste mit dem Finger/Stift auf die Leinwand ziehen. Ein kleiner
   Farbtropfen folgt dem Finger.
2. **Loslassen** füllt den zusammenhängenden Bereich unter der Spitze (Flutfüllung bis zu Linien/Farbgrenzen) auf der
   Primärebene. Gibt es eine **Referenzebene**, bestimmen deren Linien die Grenzen.
3. **Schwellenwert:** statt loszulassen kurz **halten** → nach einem Moment erscheint oben eine **blaue Leiste**
   „ColorDrop-Schwellenwert: NN %“; Füllung wird live angezeigt. **Nach links ziehen** = weniger (genauer),
   **nach rechts** = mehr (läuft über Farbgrenzen). Loslassen übernimmt. Der Wert wird gemerkt. 100 % wird intern auf
   ca. 97,6 % begrenzt, damit nicht die ganze Ebene geflutet wird.
4. **Weiter füllen:** nach dem ersten ColorDrop erscheint in der Hinweisleiste „Weiter füllen“. Antippen → jeder
   weitere Tipp auf die Leinwand füllt mit derselben Farbe und demselben Schwellenwert. Beenden durch Antippen eines
   beliebigen Symbols der oberen Leiste.
5. **SwatchDrop:** dasselbe mit einem Palettenfeld.
6. **ColorDrop mit Schweben:** Stift über dem Farbkreis schweben + Doppeltippen → Farbtropfen löst sich, Tipp auf die
   Leinwand füllt.
- Füllung respektiert Auswahl (nur innerhalb), Alpha-Sperre und Kantenglättung (weicher Rand unter Linien).
- Auf einer leeren Ebene ohne Referenz füllt ColorDrop die ganze Ebene.

### 15.2 Pipette
- **Berühren & halten** auf der Leinwand (Verzögerung einstellbar) → **Ring-Lupe** erscheint über dem Finger: obere
  Hälfte = neue Farbe unter dem Fadenkreuz, untere Hälfte = bisherige Farbe. Ziehen ändert, **Loslassen übernimmt**.
- **Zweiter Finger tippen** während der Pipette: umschalten zwischen „nur aktive Ebene“ und „alle Ebenen“
  (bleibt bis zur Rückkehr in die Galerie).
- **Modifizierer tippen:** schwebende Pipette an der letzten Malposition; **Modifizierer halten + Leinwand berühren**.
- Optional (Barrierefreiheit): Farbname als Hinweis.

---

## 16. Ebenen

### 16.1 Panel-Aufbau
- Popover unter dem Ebenen-Symbol. Kopf: Titel „Ebenen“ (antippbar → **Ebenenmenü**: Alle auswählen/abwählen,
  Gruppieren, Zusammenfügen, Teilen, Duplizieren, Löschen – wirken auf markierte Ebenen) und **+** (neue Ebene über der
  aktiven).
- Zeile: **Miniatur** · **Name** · **Mischmodus-Kürzel** (z. B. „N“) · **Sichtbarkeits-Häkchen**. Primär = hellblau,
  sekundär = dunkelblau. Zusatzzeilen unter dem Namen: „Gesperrt“, „Referenz“, „Assistiert“, „Privat“. Schnittmasken
  sind eingerückt mit kleinem Pfeil nach unten. Masken erscheinen als eigene, eingerückte Zeile über ihrer Ebene.
  Alpha-Sperre: Schachbrett-Hintergrund in der Miniatur. Textebenen: „A“-Markierung in der Miniatur.
- Unterste Zeile: **Hintergrundfarbe** (Tippen = Farbwahl; Häkchen = transparenter Hintergrund).
- Gruppen: Zeile mit Pfeil (ein-/ausklappen), Name, Häkchen; enthaltene Ebenen eingerückt.

### 16.2 Erstellen
- **+**: neue Ebene über der aktiven, Name „Ebene N“. Ebenenlimit hängt von Leinwandgröße/RAM ab; bei Erreichen
  Hinweis.
- **Private Ebene** (Aktionen → Hinzufügen, nach links wischen): verhält sich normal, erscheint aber nicht in
  Galerie-Vorschau und Zeitraffer.

### 16.3 Ebenenoptionen (Primärebene erneut antippen)
Seitliches Menü neben der Zeile:

| Option | Wirkung |
|---|---|
| Umbenennen | Texteingabe. |
| Auswählen | Ebeneninhalt als Auswahl (deckkraft-gewichtet). |
| Kopieren | in die Zwischenablage. |
| Ebene füllen | ganze Ebene mit aktueller Farbe (mit Alpha-Sperre nur vorhandene Pixel). |
| Löschen (Leeren) | Inhalt entfernen, Name/Modus/Deckkraft bleiben. |
| Alpha-Sperre | an/aus. |
| Maske | Ebenenmaske hinzufügen. |
| Schnittmaske | an/aus (nicht für unterste Ebene). |
| Zeichenassistent | Assistent für diese Ebene an/aus (aktiviert die zuletzt genutzte Hilfe). |
| Umkehren | Farben invertieren. |
| Referenz | als Referenzebene für ColorDrop markieren. |
| Nach unten zusammenfügen | mit darunterliegender Ebene verschmelzen (Mischmodi werden eingerechnet, Name der unteren). |
| Nach unten kombinieren | mit darunterliegender zu einer Gruppe zusammenfassen bzw. in Gruppe darunter aufnehmen. |
| Rastern | (nur Text) in Pixel umwandeln. |

**Gruppenoptionen:** Umbenennen, Zusammenfügen (Gruppe → eine Ebene), Gruppierung aufheben (Ebenen eine Stufe hoch),
Nach unten kombinieren.

### 16.4 Organisieren
- Siehe Ebenen-Gesten (2.2). Mehrfachauswahl: Auswahl und Transformieren wirken auf alle markierten Ebenen;
  Anpassungen nicht; Ausschneiden/Kopieren nur auf die Primärebene.
- Gruppen: Mehrere markieren → Ebenenmenü → Gruppieren. Gruppe tippen = primär; Aktionen außer Malen/Anpassungen
  wirken auf alle Ebenen der Gruppe.
- Wischen nach links: Sperren (Schloss-Symbol; Entsperren), Duplizieren (inkl. Maske, Modus), Löschen.
- **Ebene wählen auf der Leinwand** (optional): Halten/Ziehen → Popup mit Ebenen an dieser Stelle.

### 16.5 Teilen
- Einzelne Ebene/mehrere per Drag & Drop als Bild in andere Apps.
- Aktionen → Teilen → Ebenen teilen: PDF (eine Seite je Ebene), PNG-Dateien (nummeriert ab unterster),
  animiertes GIF/PNG/MP4/HEVC (Ebenen als Bilder; Bildrate, Optionen). Nur sichtbare Ebenen (bei Solo: die vorher
  sichtbaren).

---

## 17. Mischmodi

- Öffnen: Tippen auf das Kürzel rechts in der Ebenenzeile → Ausklapp-Leiste unter der Zeile mit **Deckkraftregler**
  (0–100 %, wirkt auf die ganze Ebene) und dem **Namen des Modus**. Tippen auf den Namen → scrollbare Liste; jeder
  Modus wird beim Antippen **live** auf der Leinwand angezeigt. Wegtippen schließt und behält die Wahl.
- Reihenfolge (Gruppen durch Trennlinien):
  1. Normal
  2. Abdunkeln-Gruppe: Multiplizieren, Abdunkeln, Schattieren, Farbig nachbelichten, Linear nachbelichten, Dunklere Farbe
  3. Aufhellen-Gruppe: Aufhellen, Negativ multiplizieren, Farbig abwedeln, Hinzufügen, Hellere Farbe
  4. Kontrast-Gruppe: Ineinanderkopieren, Weiches Licht, Hartes Licht, Strahlendes Licht, Lineares Licht, Lichtpunkt, Harte Mischung
  5. Differenz-Gruppe: Differenz, Ausschluss, Subtrahieren, Dividieren
  6. Farb-Gruppe: Farbton, Sättigung, Farbe, Luminanz
- Kürzel in der Zeile: Normal „N“, sonst 1–2 Buchstaben.

---

## 18. Masken

- **Alpha-Sperre:** Malen nur auf vorhandenen Pixeln; Transparenz bleibt. Indikator: Schachbrett in der Miniatur.
  Schnell: 2 Finger nach rechts wischen.
- **Ebenenmaske:** Graustufen-Ebene, angehängt über der Ebene. Weiß zeigt, Schwarz verbirgt, Grau teilweise.
  Farbwahl ignoriert Farbton, solange die Maske primär ist. Maske und Ebene sind gekoppelt (die jeweils andere wird
  sekundär → transformieren gemeinsam). Wischen nach links → Sperren/Löschen. Duplizieren kopiert die Maske.
  Optionen der Maske: Umkehren, Anwenden (in Ebene einrechnen), Löschen.
- **Schnittmaske:** eigene Ebene, die nur dort sichtbar ist, wo die Ebene darunter Deckkraft hat. Bleibt eigenständig
  verschiebbar; ganz unten → inaktiv, wieder darüber → aktiv. Mehrere Schnittmasken übereinander möglich.

---

## 19. Text

- **Hinzufügen:** Aktionen → Hinzufügen → **Text hinzufügen** → Textrahmen mit markiertem Wort „Text“ in aktueller
  Farbe und Standardschrift erscheint, Tastatur öffnet. Rahmen wächst beim Tippen.
- **Text-Begleitleiste** (über der Tastatur): Ausrichtung, Auswahl, Ausschneiden/Kopieren/Einfügen, Schriftname,
  „Aa“/„Stil bearbeiten“. Doppeltippen auf Text öffnet sie erneut.
- **Rahmen:** ziehen = verschieben (darf über den Rand hinaus, wird nicht abgeschnitten); blaue Punkte links/rechts =
  Breite → Umbruch. Mit Transformieren skalieren/drehen/verzerren, bleibt Vektor.
- **Stil bearbeiten** (Panel): Schriftenliste mit Vorschau, Stil (Normal/Kursiv/Fett …), **Design-Regler**: Größe,
  Unterschneidung (Kerning, zwischen zwei Zeichen), Laufweite (Tracking), Zeilenabstand, Grundlinie, Deckkraft;
  **Attribute**: Ausrichtung links/Mitte/rechts/Blocksatz, Unterstrichen, Kontur, Ausrichtung horizontal/vertikal,
  Großbuchstaben. Fertig/Abbrechen.
- **Ebene:** Textebene zeigt „A“; Name folgt dem Text (bis man umbenennt). **Rastern** über Ebenenoptionen.
- **Schriften importieren:** TTF, OTF, TTC über „Schrift importieren“ im Stil-Panel, Drag & Drop, Ordner.

---

## 20. Zeichenhilfen & Zeichenassistent

### 20.1 Allgemein
- Aktionen → Leinwand → **Zeichenhilfe** (Schalter: Hilfe sichtbar) und **Zeichenhilfe bearbeiten** → Vollbild-
  Editor „Zeichenhilfen“:
  - oben: **Farbton-Leiste** (Linienfarbe), Abbrechen/Fertig;
  - Leinwand mit Live-Hilfe und Steuerpunkten;
  - unten: Typ-Tabs **2D-Raster · Isometrisch · Perspektive · Symmetrie** und Regler **Deckkraft**, **Dicke**,
    (Raster) **Rastergröße**, Schalter **Zeichenassistent**, (Symmetrie) **Optionen**.
- Steuerpunkte: **blauer Punkt** verschiebt die Hilfe, **grüner Punkt** dreht sie. Punkt antippen → „Zurücksetzen“.
- **Zeichenassistent** ist **pro Ebene**: Ebene bekommt den Zusatz „Assistiert“. Auf assistierten Ebenen folgen Striche
  der Hilfe (Raster: Strich rastet auf die nächste Rasterrichtung ein – horizontal/vertikal bzw. die drei Isometrie-
  Achsen; Perspektive: Strich läuft auf den passenden Fluchtpunkt bzw. parallel; Symmetrie: Spiegelkopien).
  Freihand: Assistent aus oder andere Ebene. Symmetrie schaltet den Assistenten automatisch an.

### 20.2 2D-Raster / Isometrisch
- Quadratisches bzw. isometrisches (30°/90°/150°) Raster; Rastergröße skaliert; verschieben/drehen per Punkten.
- Assistent: Striche werden auf die Rasterachsen gerade gezogen (Richtung wird zu Strichbeginn bestimmt).

### 20.3 Perspektive
- **Fluchtpunkte:** Tippen ins Leere legt einen an (max. 3); ziehen verschiebt; antippen → Löschen.
  1 Punkt = Zentralperspektive (Senkrechte/Waagerechte bleiben parallel), 2 Punkte = Übereckperspektive (auf dem
  Horizont), 3 Punkte = Dreipunkt (dritter für die Vertikale).
- **Horizontlinie** zieht automatisch durch die Fluchtpunkte 1/2; blaue Punkte verschieben/neigen sie.
- Assistent: Strich rastet auf die Linie zum nächstliegenden Fluchtpunkt bzw. auf die Senkrechte/Waagerechte ein.

### 20.4 Symmetrie
- Optionen: **Vertikal**, **Horizontal**, **Quadrant** (4 Felder), **Radial** (8 Segmente); Schalter
  **Rotationssymmetrie** (gedreht statt gespiegelt). Mittelpunkt (blau) verschieben, Drehpunkt (grün) drehen.
- Assistent standardmäßig an.

---

## 21. QuickShape

- **Auslösen:** Strich zeichnen und am Ende **halten** (Standard ca. 0,5–0,8 s, einstellbar) → Strich wird durch die
  erkannte Form ersetzt: **Linie, Bogen, Polylinie, Ellipse, Dreieck, Viereck** (Rechteck), Polygon.
- Hinweis oben: z. B. „Ellipse erstellt“.
- **Weiter halten und ziehen:** Form skalieren/drehen um den Ankerpunkt (Startpunkt bzw. Mittelpunkt).
- **Zweiten Finger auflegen** (während gehalten wird): perfekte Form – Ellipse → Kreis, Rechteck → Quadrat,
  unregelmäßiges Dreieck → gleichseitig, Linie/Drehung → 15°-Schritte. Hinweis z. B. „Kreis“.
- **Form bearbeiten:** Nach dem Loslassen erscheint oben die Schaltfläche **„Form bearbeiten“**. Antippen → Steuerpunkte
  auf der Form (blau), formabhängige Knöpfe in der Hinweisleiste (Ellipse: „Ellipse“/„Kreis“; Viereck: „Rechteck“/
  „Polygon“; Linie: Endpunkte; Polylinie: Eckpunkte). Punkt ziehen ändert die Form, Ziehen zwischen den Punkten
  skaliert gleichmäßig. Tippen auf die Leinwand übernimmt. Weitermalen oder Wegtippen verwirft die Schaltfläche.
- Andere Auslöser (Modifizierer, Stift-Taste) wandeln den letzten Strich um.
- Mit Zeichenassistent/Symmetrie werden auch die Spiegelkopien ersetzt.

---

## 22. Animationsassistent

- Ein/aus: Aktionen → Leinwand → Animationsassistent. Unten erscheint eine **Zeitleiste** (über die volle Breite).
- **Zeitleiste:** jede Ebene bzw. Gruppe = ein Bild (Frame), von links (unterste Ebene) nach rechts. Tippen springt,
  ziehen scrollt, schnippen scrollt schnell. Aktuelles Bild blau unterstrichen. Gehaltene Bilder grau.
- Leiste: **Abspielen/Pause** (stoppt auch bei Tippen auf Leinwand/Zeitleiste), **Einstellungen**, **Bild hinzufügen**
  (leer, rechts neben aktuellem), Rückgängig/Wiederholen.
- **Bildoptionen** (Bild antippen, dann erneut): **Haltedauer** (0/„Keine“ bis 120 Bilder), **Duplizieren**,
  **Löschen**, **Hintergrund** (nur ganz linkes Bild, liegt unter allen), **Vordergrund** (nur ganz rechtes, liegt über
  allen).
- **Einstellungen:** Wiedergabe **Schleife / Ping-Pong / Einmal**, **Bilder pro Sekunde 1–60**, **Zwiebelschicht-
  Bilder** (keine bis 12), **Zwiebelschicht-Deckkraft** 0–100 %, **Primärbild mischen** (aktuelles Bild halbtransparent),
  **Zwiebelschicht-Farben** (folgende Bilder standardmäßig grün, vorherige rot; änderbar).
- Leinwand zeigt nur das aktuelle Bild voll deckend, Nachbarn als eingefärbte Zwiebelschicht (nähere deckender).
- Export: Aktionen → Teilen → Animiertes GIF/PNG/MP4/HEVC (Bildrate, Max. Auflösung/Web, Transparenz, Dithering).

---

## 23. Seitenassistent

- Aktionen → Leinwand → Seitenassistent: jede Ebene/Gruppe wird eine **Seite**; Zeitleiste unten mit
  Seitenminiaturen; „Neue Seite“ rechts neben der aktuellen.
- Seitenoptionen (Seite antippen, erneut): Duplizieren, Löschen, **Hintergrund** (nur ganz linke Seite; liegt unter
  allen Seiten, neue Seiten nicht links davon).
- QuickMenu-Aktionen: Seitenassistent, Nächste Seite (auf der letzten: neue Seite), Vorherige Seite.
- Mehrseitige PDFs öffnen als Seiten; Export als PDF.

---

## 24. Aktionen

Popover unter dem Schraubenschlüssel. Oben Tab-Leiste mit 6 Tabs: **Hinzufügen · Leinwand · Teilen · Video ·
Einstellungen · Hilfe** (Symbol + Beschriftung, aktiver Tab blau). Darunter Liste.

### 24.1 Hinzufügen
- Datei einfügen (PNG/JPEG/PSD → als eine Ebene, Transformieren aktiv), Foto einfügen, Foto aufnehmen.
  Jeweils nach links wischen → **privat** einfügen (private Ebene).
- Text hinzufügen.
- Ausschneiden, Kopieren, Alles kopieren, Duplizieren, Ausschneiden & Einfügen, Einfügen (bzw. „Privat einfügen“).

### 24.2 Leinwand
- **Zuschneiden & Größe ändern:** Raster-Overlay auf der Leinwand; Kanten/Ecken ziehen (Breite/Höhe live, Einrasten an
  Leinwandkanten, Mittellinien und Bildinhalt); unten **Drehregler** (Leinwand gegen Zuschnitt drehen),
  **Seitenverhältnis-Schloss**, **Zahleneingabe** (Einheit wie bei Erstellung), **DPI**, **Neu berechnen** (Inhalt
  bikubisch skalieren, schaltet Schloss an), Zurücksetzen/Abbrechen/Fertig.
- **Animationsassistent**, **Seitenassistent**, **Zeichenhilfe** (Schalter), **Zeichenhilfe bearbeiten**,
  **Referenz** (Referenzfenster), **Leinwand horizontal/vertikal spiegeln** (nur Ansicht/ganze Leinwand),
  **Leinwandinformationen**: Über dieses Werk (Titel, Erstellt von, Bild, Unterschrift, Daten), Abmessungen (px,
  physisch, DPI), Ebenen (max./benutzt/frei, Anzahl Assistiert/Schnittmasken/Masken/Gruppen), Farbprofil (änderbar),
  Video-Einstellungen, Statistik (Striche, Zeit, Dateigröße).

### 24.3 Teilen
- **Bild teilen:** Projektformat (alles), PSD (Ebenen, Namen, Deckkraft, Sichtbarkeit, Modi), PDF (Gut/Besser/Beste),
  JPEG, PNG (Transparenz), TIFF.
- **Ebenen teilen:** PDF, PNG-Dateien, animiertes GIF/PNG/MP4/HEVC.
- Ziel über das System-Teilen-Menü.

### 24.4 Video (Zeitraffer)
- **Zeitraffer-Wiedergabe:** Vorschau in der App, Schleife mit 30 fps, Laufzeit oben rechts, horizontal ziehen =
  spulen, Zoomen/Verschieben möglich, „Fertig“.
- **Zeitraffer-Aufnahme** (Schalter, Standard an, 1080p/Gut): aus → Rückfrage „Video verwerfen?“ (Verwerfen / Nicht
  verwerfen).
- **Zeitraffer exportieren:** Volle Länge / 30 Sekunden (wichtige Bilder bevorzugt).
- Qualität/Auflösung bei Erstellung festgelegt.

### 24.5 Einstellungen
| Einstellung | Werte |
|---|---|
| Helle Oberfläche | an/aus (Standard dunkel) |
| Rechtshändige Oberfläche | an/aus |
| Pinselcursor | an/aus; *Erweitert*: Sichtbarkeit (beim Schweben / beim Malen / beides), Umrissstil (Hoher Kontrast / Aktive Farbe / Pro Pinsel) |
| Dynamische Pinselskalierung | Pinselgröße relativ zur Leinwand (wächst mit Zoom) vs. relativ zum Bildschirm |
| Leinwand projizieren | Leinwand allein auf externem Bildschirm |
| Seitenleiste Größe & Deckkraft | ein-/ausblenden |
| Druck & Glättung | Stabilisierung, Bewegungsfilterung (+Ausdruck), **Druckkurve der App** (Diagonale, bis 6 Punkte, Zurücksetzen), Spitzenbindung |
| Gestensteuerung | siehe 2.4 |
| Verzögerung schnelles Rückgängig | 0–1,5 s |
| Sichtbarkeit der Auswahlmaske | Regler (Deckkraft der Schraffur) |
| Vereinfachtes Rückgängig (Transform) | alle Transform-Schritte als ein Schritt |

### 24.6 Hilfe
Hilfecenter, Kontakt, Community, Einsteiger-Videos, Pinsel-Upgrade-Funktionen, Erweiterte Einstellungen.
*Farbwerk-Hinweis:* stattdessen Gesten-/Kürzelübersicht, Version, Speicherort.

---

## 25. Auswahl

### 25.1 Grundlagen
- Auswahl-Symbol tippen → unten **Auswahl-Werkzeugleiste**. Erneut tippen = Auswahl verwerfen/Modus verlassen.
- **Halten** auf das Auswahl-Symbol = **letzte Auswahl neu laden** (Hinweis „Maske neu geladen“, Auswahlmodus aktiv).
- Während des Zeichnens zeigt eine laufende gestrichelte Linie („Ameisen“) die Grenze.
- **Übernehmen:** Tippen auf ein anderes Werkzeug. Danach ist der Bereich *außerhalb* mit halbtransparenten, langsam
  wandernden **diagonalen Streifen** überzogen. Malen/Wischen/Radieren/ColorDrop/Transform wirken nur innen.
- 2-/3-Finger-Tippen = Rückgängig/Wiederholen der Auswahlschritte. Navigation (Zoom etc.) bleibt möglich.

### 25.2 Modi (linke Gruppe der Leiste)
- **Automatisch:** Tippen wählt zusammenhängenden ähnlichen Bereich (Flutfüllung). Tippen und **halten/ziehen** →
  oben „Auswahl-Schwellenwert NN %“, links/rechts ziehen. Ausgewählter Bereich wird **farblich invertiert**
  dargestellt. Weitere Bereiche durch weiteres Tippen hinzufügen.
- **Freihand** (Standard): Linie zeichnen *oder* Punkte tippen (gerade gestrichelte Segmente) – beliebig kombinierbar,
  Absetzen und weiterzeichnen möglich. Startpunkt zeigt einen **grauen Kreis**; Antippen schließt.
- **Rechteck / Ellipse:** aufziehen.

### 25.3 Optionen (rechte Gruppe)
| Knopf | Wirkung |
|---|---|
| Hinzufügen | übernimmt die aktuelle Form und startet eine weitere, die addiert wird (schließt offene Freihand-Schleife). In Automatisch ausgegraut. |
| Entfernen | nächste Form wird abgezogen. |
| Umkehren | Auswahl invertieren (erneut = zurück). |
| Kopieren & Einfügen | Auswahl auf neue Ebene „Aus Auswahl“. |
| Weiche Kante | Regler 0–100 % mit Live-Vorschau. |
| Speichern & Laden | Popup: **+** speichert („Auswahl N“ mit Miniatur), Antippen lädt, (links wischen löscht). |
| Farbfüllung | Farbeimer blau: jede neue Auswahl wird sofort mit aktueller Farbe gefüllt (Freihand: Antippen des Startpunkts schließt und füllt). Ändern der Farbe danach färbt die zuletzt gefüllte Auswahl um. Erneut tippen = aus. |
| Löschen | Auswahl verwerfen (2-Finger-Tippen holt sie zurück). |

- **Ebeneninhalt auswählen:** 2 Finger auf Ebene halten bzw. Ebenenoption „Auswählen“ (Auswahl sofort übernommen).
- Einstellung „Sichtbarkeit der Auswahlmaske“ regelt die Deckkraft der Streifen.

---

## 26. Transformieren

### 26.1 Start und Ende
- Transform-Symbol tippen: Inhalt der Primär- und Sekundärebenen (bzw. der Auswahl) wird schwebend; **Begrenzungsrahmen**
  (gestrichelt, laufend) umschließt den Inhalt **exakt** (nicht die Ebene). Unten die Transform-Leiste.
- Übernehmen: Transform-Symbol erneut tippen oder anderes Werkzeug. 2-/3-Finger-Tippen = Rückgängig/Wiederholen
  einzelner Transform-Schritte (bzw. alles als ein Schritt mit „Vereinfachtes Rückgängig“).

### 26.2 Leiste (von links)
**Einrasten** (Popup) · **Freiform** · **Gleichmäßig** · **Verzerren** · **Verkrümmen** · Trenner ·
**Horizontal spiegeln** · **Vertikal spiegeln** · **45° drehen** (im Uhrzeigersinn) · **An Bildschirm anpassen** ·
**Interpolation** (Popup) · **Zurücksetzen**.

### 26.3 Rahmen und Punkte
- **Blaue Punkte** an Ecken und Seitenmitten: Ecken = beide Achsen, Seiten = eine Achse (Freiform) bzw. proportional
  vom gegenüberliegenden Punkt aus (Gleichmäßig).
- **Grüner Drehpunkt** über dem Rahmen: dreht um die Mitte, zeigt den Winkel an.
- **Gelber Punkt** unter dem Rahmen: **Rahmen anpassen** – dreht nur den Rahmen, damit er schräg liegenden Inhalt eng
  umschließt.
- Beim Ziehen eines Punkts: **Größenanzeige** (B × H in px).
- **Punkt antippen** → Zahlenfeld für exakte Größe (px) bzw. Winkel (Drehpunkt, Dezimalstellen; positiv = links).

### 26.4 Gesten
- Ein Finger ziehen **innerhalb oder außerhalb** des Rahmens = verschieben.
- **Kneifen innerhalb** = gleichmäßig skalieren (um die Mitte) + drehen; **Kneifen außerhalb** = Leinwand zoomen/drehen.
  Halten des Transform-Symbols kehrt das um.
- **Tippen außerhalb** = schubsen in diese Richtung (Schrittweite wird bei stärkerem Zoom kleiner).
- Freiform: **Eckpunkt halten** → vorübergehend Verzerren (Hinweis „Verzerren“), Loslassen → zurück.
- Verzerren: Eckpunkt = Perspektive, Seitenpunkt = Scheren (mit Magnetisch auf eine Achse).
- Pfeiltasten (Tastatur): 1 px schubsen.

### 26.5 Verkrümmen
- 4×4-Gitter über dem Inhalt (durchgezogene Rahmenlinie statt gestrichelt); Ecken, Kanten und Innenflächen ziehen.
  Ecke zur Mitte ziehen = umklappen.
- **Punkt antippen** → Vorne / Mitte / Hinten (Überlappungsreihenfolge).
- **Erweitertes Gitter** (Knopf in der Leiste): Punkte an allen Gitterkreuzungen, einzeln steuerbar.

### 26.6 Einrasten
- Popup: Schalter **Magnetisch**, Schalter **Einrasten**, Regler **Abstand** (px, z. B. 1–50), **Geschwindigkeit**
  (Keine … Max: wie schnell bewegt werden darf, bevor Hilfslinien greifen).
- **Einrasten:** blaue Hilfslinien richten an anderen Objekten aus, gelbe an Leinwandmitte/-kanten.
- **Magnetisch:** Verschieben/Skalieren entlang einer Achse; Achse ergibt sich aus Zugrichtung in 15°-Schritten;
  zweiter Finger auf der Leinwand fixiert die Achse. Skalieren in festen Schritten, Drehen in 15°.
- **An Bildschirm anpassen** mit Magnetisch: füllt die Leinwand (Überstand wird beim Übernehmen abgeschnitten);
  ohne: passt ganz hinein.

### 26.7 Interpolation
Nächster Nachbar (scharf, pixelig; Standard), Bilinear (weicher), Bikubisch (am weichsten).

---

## 27. Anpassungen

### 27.1 Bedienung (alle)
- Anpassungen-Symbol → Menü (Popover) mit zwei Abschnitten: **Anpassungen** (Farbton/Sättigung/Helligkeit,
  Farbbalance, Kurven, Verlaufsumsetzung) und **Filter** (Gauß, Bewegungsunschärfe, Perspektivische Unschärfe, Rauschen,
  Scharfzeichnen, Bloom, Glitch, Halbton, Chromatische Aberration, Verflüssigen, Klonen). Jede Zeile hat ein Symbol.
- **Modus Ebene / Stift:** kleines Dreieck neben dem %-Wert oben → Auswahl. *Ebene* wirkt auf die ganze Ebene
  (innerhalb einer Auswahl nur dort); *Stift* malt den Effekt mit dem **aktuellen Pinsel** (Seitenleiste = Größe/
  Deckkraft) auf Teile der Ebene. Nicht für Verflüssigen/Klonen.
- **Wischen-Anpassungen** (Gauß, Bewegung, Rauschen, Scharfzeichnen, Bloom, Glitch, Halbton, Chrom. Aberration):
  oben erscheint die **blaue Leiste** „<Filter> NN %“ (beginnt bei 0 %). **Waagerecht über die Leinwand ziehen**
  (irgendwo): rechts = stärker, links = schwächer; die Leiste füllt sich mit. Zusatzregler (z. B. Bloom Übergang) in
  einem kleinen Panel unten.
- **Tippen auf die Leinwand** → Aktionsmenü: **Vorschau** (halten/umschalten Original ↔ Ergebnis), **Anwenden**
  (übernehmen, bleiben), **Zurücksetzen** (bleiben), **Rückgängig** (letzte Änderung), **Abbrechen** (verwerfen und
  verlassen).
- **Übernehmen und verlassen:** Anpassungen-Symbol erneut tippen (oder anderes Werkzeug).

### 27.2 Einzelne Anpassungen

| Anpassung | Bedienung / Parameter |
|---|---|
| Farbton, Sättigung, Helligkeit | 3 Regler unten (Mitte = neutral). Kürzel Strg+U. |
| Farbbalance | 3 Regler Cyan↔Rot, Magenta↔Grün, Gelb↔Blau; Tonbereich Schatten/Mitteltöne (Standard)/Lichter. Strg+B. |
| Kurven | Diagramm mit Histogramm; Kanäle **Gamma** (alle), Rot, Grün, Blau; bis 11 Punkte; Punkt antippen → Löschen. |
| Verlaufsumsetzung | Verlaufsbibliothek (8 Vorgaben); Tippen wendet an, wischen wechselt; halten = Löschen/Duplizieren; halten+ziehen = umsortieren; **+** neuer Verlauf; gewählten erneut tippen = **Verlaufseditor**: links Schatten, rechts Lichter, 2–12 Farbstopps, Tippen auf Stopp = Farbe, Tippen auf Leere = neuer Stopp, halten = löschen, Titel = Name. **+** halten → Standards wiederherstellen. Stärke-Regler. |
| Gaußscher Weichzeichner | Ziehen = Stärke. |
| Bewegungsunschärfe | Ziehen in beliebige Richtung: Richtung = Unschärferichtung, Länge = Stärke. |
| Perspektivische Unschärfe | Scheibe = Ursprung (jederzeit ziehbar); Ziehen außerhalb = Stärke; Modus **Positionsbezogen** (radial nach außen) / **Richtungsbezogen** (größere Scheibe mit drehbarem Pfeil, Unschärfe in eine Richtung). |
| Rauschen | Ziehen = Menge; Art **Wolken / Wogen / Kämme**; Regler Maßstab, Oktaven, Turbulenz; Kanäle **Einfach** (s/w) / **Mehrfach** (bunt); **Additiv** an = Rauschen transparent über dem Bild. |
| Scharfzeichnen | Ziehen = Menge. |
| Bloom | Ziehen = Menge (zuerst nur hellste Bereiche); Regler Übergang, Größe, Brennen. |
| Glitch | Modi **Artefakt** (Menge, Blockgröße, Zoom), **Welle** (Amplitude, Frequenz, Zoom; beide > 0 nötig), **Signal** (Menge, Blockgröße, Zoom), **Divergieren** (Rot-, Grün-, Blau-Verschiebung, Zoom). Ziehen = Gesamtstärke. |
| Halbton | Ziehen = Punktgröße; Modi **Vollfarbe**, **Siebdruck** (Weiß scheint durch), **Zeitung** (Graustufen). |
| Chromatische Aberration | **Perspektive**: Brennpunkt (weißer Punkt, auch außerhalb), Ziehen = Stärke, Regler Übergang, Abfall. **Verschieben**: Ziehen = Richtung+Distanz der Rot/Blau-Verschiebung, Regler Unschärfe, Transparenz. |
| Verflüssigen | Modus-Knopf links: **Schieben** (Standard), **Wirbel rechts/links**, **Zusammenziehen**, **Ausdehnen**, **Kristalle**, **Kante**, **Wiederherstellen**. Regler unten: Größe, Druck (max. bei vollem Druck, Finger = max.), Verzerrung (Zufall), Dynamik (Nachlaufen). **Anpassen**: Menge-Regler schwächt alles Bisherige ab. **Zurücksetzen** bleibt im Modus. Wirbel/Zusammenziehen wirken auch beim Halten. |
| Klonen | Quellscheibe in der Mitte; ziehen setzt Quelle; mit beliebigem Pinsel daneben malen; Scheibe folgt relativ. **Halten auf die Scheibe** sperrt sie (Puls + Hinweis „Klonen gesperrt“), erneut halten entsperrt. Seitenleiste: oben Größe, unten Stärke. |

---

## 28. Referenzfenster

- Aktionen → Leinwand → Referenz. Schwebendes Fenster über der Leinwand: Griff oben = verschieben, untere Ecken =
  Größe, darin kneifen/verschieben. Tippen blendet die Bedienelemente ein/aus.
- Unten Knöpfe: **Leinwand** (Live-Ansicht des ganzen Werks, eigener Zoom, Pipette möglich), **Bild** (Foto als
  Referenz importieren; „Bild löschen“), **Gesicht** (Frontkamera-Malen; für Farbwerk nicht vorgesehen).

---

## 29. 3D-Malen (nur Erwähnung)

Das Vorbild kann 3D-Modelle (OBJ/USDZ) importieren und mit Ebenen je Material bemalen, mit Licht-Studio und AR.
**Für Farbwerk nicht vorgesehen** (P3/außer Umfang).

---

## 30. Visuelles Design & Animation

> Alle Werte sind **Richtwerte** (aus der Bedienung und Bildschirmfotos abgeschätzt, 1 iPad-Punkt ≈ 1 dp). Ziel ist
> ein vergleichbares Gefühl, keine Pixel-Kopie. Eigene Icons und Farben verwenden.

### 30.1 Farben (dunkles Thema)
| Element | Richtwert |
|---|---|
| Arbeitsfläche um die Leinwand | sehr dunkles Neutralgrau, ca. `#1E1E20` |
| Obere Leiste | fast schwarz, leicht transparent, ca. `#141416` mit ~90 % Deckkraft; kein Rahmen, kein Schatten |
| Seitenleiste | gleiche Farbe wie obere Leiste, Pillenform |
| Popover-Hintergrund | dunkles Grau ca. `#262628`–`#2C2C2E`, nahezu deckend |
| Zeilen/Trenner | `#FFFFFF` mit 8–12 % Deckkraft |
| Symbole inaktiv | hellgrau ca. `#D8D8DC` |
| Symbole deaktiviert | ca. `#6E6E73` |
| Akzent (aktiv, Primärauswahl) | kräftiges Blau ca. `#2F7CF6`–`#0A84FF` |
| Sekundärauswahl (Ebenen/Pinsel) | dunkles Blau ca. `#1E3E78` |
| Hinweis-/Schwellenwertleiste | Blau (Akzent) mit weißer Schrift |
| Warnung (Gesten-Konflikt) | Gelb ca. `#FFCC00` |
| Löschen/Destruktiv | Rot ca. `#FF453A` |
| Sperren-Schaltfläche | Grau ca. `#5A5A60`; Duplizieren Blau; Löschen Rot |
| Auswahl-Schraffur | diagonale Streifen schwarz/weiß ~35 % (über Einstellung regelbar) |
| Transform: Punkte | Blau (Größe), Grün (Drehen), Gelb (Rahmen anpassen); Hilfslinien Blau (Objekt) / Gelb (Leinwand) |
| Zeichenhilfe-Standardfarbe | Hellblau/Cyan, Deckkraft ~50 % |
| Zwiebelschicht | vorherige Bilder Rot, folgende Grün |
| Helles Thema | Leisten ca. `#F2F2F4`, Popover `#FFFFFF`, Symbole `#1C1C1E`, Arbeitsfläche `#D9D9DD` |

### 30.2 Typografie
- Systemschrift (Farbwerk: Roboto/Systemstandard). Titel in Popovers 17 sp halbfett, Listen 15 sp, Nebeninfo 12–13 sp,
  Zahlen in Hinweisleiste 13–14 sp mit Ziffern fester Breite.

### 30.3 Maße
| Element | Richtwert |
|---|---|
| Obere Leiste Höhe | 44–48 dp (+ Statusleiste); Symbole 24–26 dp in 40–44 dp Trefferfläche; Abstand zwischen Symbolen ~8 dp |
| „Galerie“ | Text, 15–16 sp, links mit ~16 dp Rand |
| Farbkreis (aktive Farbe) | Ø 28–30 dp, 2 dp heller Ring |
| Seitenleiste | Breite 36–40 dp, Pillenradius = halbe Breite; jeder Regler ~180–200 dp hoch; Modifizierer-Quadrat ~26 dp, Radius 6 dp; Rückgängig/Wiederholen-Pfeile ~32 dp; Abstand zum Rand 6–8 dp |
| Regler-Vorschaublase | ~90×90 dp Kreis/abgerundetes Quadrat neben dem Regler, mit Pinselabdruck + % |
| Popover | Eckenradius 12–14 dp; Breite: Ebenen ~320 dp, Farben ~320–340 dp, Aktionen ~340 dp, Pinselbibliothek ~480 dp (Sets ~150 dp + Pinsel ~330 dp), Anpassungen ~280 dp; max. Höhe ~70–80 % des Bildschirms; Abstand unter der Leiste 6–8 dp |
| Popover-Pfeil | kleines Dreieck (~14×8 dp) oben, zeigt auf das auslösende Symbol |
| Ebenenzeile | Höhe ~56–64 dp, Miniatur ~44×44 dp (Seitenverhältnis gewahrt, Schachbrett für Transparenz), Häkchen-Kästchen 22 dp |
| Pinselzeile | Höhe ~72–80 dp, Name oben links 13 sp, Strichvorschau darunter über volle Breite |
| Untere Werkzeugleisten (Auswahl/Transform) | schwebende Leiste mittig unten, Höhe ~56–64 dp, Radius ~14 dp, Abstand unten ~16 dp; Knöpfe Symbol 24 dp + Beschriftung 10–11 sp |
| Hinweisleiste oben | Pillenform, Höhe ~30–32 dp, Radius = halbe Höhe, mittig unter der oberen Leiste |
| QuickMenu | Ø ~220–260 dp; 6 runde Knöpfe à ~64 dp auf Ring, Mittelknopf ~56 dp |
| Pipetten-Ring | Ø außen ~110–120 dp, Ringbreite ~22–26 dp, Fadenkreuz in der Mitte, 60–80 dp über dem Finger versetzt (Stift: direkt an der Spitze) |

### 30.4 Schatten und Ebenen
- Popovers: weicher Schatten (Blur ~20–30 dp, Y-Versatz ~6 dp, Schwarz ~40 %).
- Leinwand: leichter Schatten um die Dokumentfläche.
- Obere Leiste/Seitenleiste: kein Schatten.

### 30.5 Übergänge und Timing
| Ereignis | Richtwert |
|---|---|
| Popover öffnen | Einblenden + Skalieren von ~0,92 → 1,0 ausgehend vom Pfeil/Symbol, 180–220 ms, leicht gefedert (Federdämpfung ~0,8, kein sichtbares Überschwingen) |
| Popover schließen | Ausblenden + Skalieren auf ~0,95, 120–150 ms |
| Popover wechseln | altes sofort weg (≤ 100 ms), neues mit Öffnen-Animation |
| Ebenenoptionen-Menü | gleitet seitlich neben der Zeile ein, ~180 ms |
| Wischaktionen (Zeile) | folgt dem Finger 1:1; Einrasten mit Feder ~200 ms |
| Ebenen umsortieren | gehobene Zeile vergrößert ~1,03 mit Schatten; andere Zeilen weichen animiert aus (~150 ms) |
| Hinweisleiste | einblenden 150 ms, Anzeigedauer ~1,2–1,8 s, ausblenden 250 ms; neue Meldung ersetzt sofort |
| Vollbild | Leisten gleiten nach oben/seitlich heraus, ~200 ms |
| Einpassen (schnelles Kneifen) | animierte Ansicht ~250–300 ms, Ease-Out |
| Regler-Blase | erscheint sofort beim Berühren, verschwindet ~300 ms nach Loslassen |
| Schnelles Rückgängig | Start nach eingestellter Verzögerung (Standard ~0,5–0,65 s), dann ~8–10 Schritte/s |
| Pipette | Halteverzögerung Standard ~0,3–0,5 s (einstellbar 0–1,5 s); Ring erscheint mit kurzem Aufploppen (~120 ms) |
| QuickShape | Haltezeit ~0,5–0,8 s; Form ersetzt den Strich ohne Überblendung |
| ColorDrop-Schwellenwert | nach ~0,4–0,6 s Halten erscheint die blaue Leiste; Füllung live |
| Klonen-Sperre | Puls der Scheibe (Skalieren 1 → 1,15 → 1, ~300 ms) |
| Auswahl-Ameisen / Streifen | laufen kontinuierlich (~1 Muster-Periode pro Sekunde) |
| Mischmodus-Liste | Vorschau sofort beim Antippen eines Modus |

### 30.6 Rückmeldungen
- Jede strukturelle Aktion meldet sich in der Hinweisleiste (Rückgängig/Wiederholen mit Aktionsname, Alpha-Sperre,
  Ebene gesperrt, Solo, ColorDrop-Schwellenwert, Deckkraft der Ebene, Klonen gesperrt, Form erkannt).
- Verbotene Aktionen (gesperrte Ebene, kein Inhalt) → Hinweis statt stiller Ignoranz.
- Optional haptisches Feedback (Android `HapticFeedbackConstants`) beim Einrasten (QuickShape perfekt, Magnetisch,
  Reglermarken).

---

## 31. Stift-Verhalten (S Pen auf Android)

Was das Vorbild mit seinem Stift kann und wie Farbwerk es mit dem S Pen (Wacom-EMR, Android `MotionEvent`) abbildet.

### 31.1 Druck
- Vorbild: feiner Druck 0–1, globale **Druckkurve der App** (bis 6 Punkte) + pro Pinsel Druckdiagramm (bis 4 Punkte)
  und pro Parameter eigene Kurve/Glättung; Druck-Glättung („StreamLine Druck“).
- S Pen: `MotionEvent.getPressure()` (4096 Stufen), **historische Punkte** (`getHistorical*`) zwingend auswerten.
  Nullpunkt-Totzone kalibrierbar (Mindestdruck), Kurve wie oben.

### 31.2 Neigung und Ausrichtung
- Vorbild: Neigung 0–90° (Wirkung ab ~15°), Azimut für kalligrafische Formausrichtung und Cursorvorschau.
- S Pen: `AXIS_TILT` (Radiant, 0 = senkrecht) und `getOrientation()` (−π…π, Azimut). Viele Samsung-Geräte liefern
  Neigung erst ab neueren Modellen – bei Wert 0 Neigungs-Effekte neutral lassen. Umrechnung: Neigung in Grad =
  `tilt * 180/π`; Anzeige-Konvention „0° = flach, 90° = senkrecht“ wie im Vorbild beachten.
- Rollen (Barrel Roll): nicht verfügbar → Optionen ausblenden.

### 31.3 Schweben (Hover)
- Vorbild: Pinselcursor beim Schweben (Größe, Form, Textur, Neigung, Ausrichtung), Schwebe-Gesten (Kneifen = Größe,
  Wischen = Deckkraft), Schwebe-ColorDrop, geschätzter Schwebedruck, Schwebefüllung.
- S Pen: `onHoverEvent` / `ACTION_HOVER_ENTER/MOVE/EXIT` mit `TOOL_TYPE_STYLUS` (Air-Hover ~1–2 cm). Cursor im
  Overlay der Leinwand zeichnen; Gesten-Erkennung „Stift schwebt + Finger“ durch Merken des Schwebezustands
  (Zeitstempel letztes HOVER_MOVE < ~150 ms).

### 31.4 Seitentaste statt Doppeltippen/Drücken
- Vorbild: Doppeltippen (Wechsel zu Radierer/letztem Werkzeug/Farbpanel; oder in Gestensteuerung: Zeichenassistent,
  Pipette, QuickShape, QuickMenu, Vollbild, Ebene leeren, Kopieren-&-Einfügen) und Drücken (QuickShape, QuickMenu,
  Ebene wählen, Pipette).
- S Pen: **Seitentaste** (`BUTTON_STYLUS_PRIMARY`, bei manchen Stiften `BUTTON_SECONDARY`; Kontakt mit gedrückter Taste
  liefert `getButtonState()`), teils als `ACTION_BUTTON_PRESS` beim Schweben. Abbildung in der Gestensteuerung:
  - **Taste tippen beim Schweben** ≙ Doppeltippen (frei belegbar: Radierer umschalten, letztes Werkzeug, Pipette,
    QuickMenu, QuickShape auf letzten Strich, Vollbild, Farben-Panel …).
  - **Taste gedrückt halten + zeichnen** ≙ Modifizierer (z. B. Pipette oder temporär Radieren/Wischen).
  - **Taste doppelt tippen** (zweite Belegung, Zeitfenster ~300 ms).
  - Samsung-Luftbefehle/Air-Actions (Bluetooth-S-Pen) können Tasten-Ereignisse abfangen – App muss ohne sie
    funktionieren; optional Air-Action-Gesten als Tastaturereignisse.
- **Radierer-Ende** (`TOOL_TYPE_ERASER`, bei Stiften mit Radierspitze): radiert temporär mit dem Radier-Pinsel.

### 31.5 Handballenerkennung
- Vorbild: System-Handballenerkennung + Einstellung „Handballen-Unterstützung“ (Standard / Fein) in Gestensteuerung →
  Allgemein; „Touch-Aktionen deaktivieren“ (nur Stift malt).
- Farbwerk:
  - Sobald ein Stift in der Nähe/aktiv ist (Hover oder Kontakt in den letzten ~1–2 s), **Finger-Malen unterdrücken**
    (Finger nur für Gesten); große Kontaktflächen (`getTouchMajor()` über Schwelle, `TOOL_TYPE_PALM` bzw.
    `FLAG_CANCELED` bei `ACTION_CANCEL`/`ACTION_POINTER_UP` mit `FLAG_CANCELED`) verwerfen.
  - Ein bereits begonnener Fingerstrich, der durch Stiftkontakt oder Handballen abgebrochen wird, ist rückstandslos
    zurückzunehmen.
  - Gesten (2-Finger-Tippen usw.) nur, wenn kein Stift auf dem Display liegt; Handballen beim Malen darf kein
    Rückgängig auslösen.
  - Option „Nur Stift malt“ (= Touch-Aktionen deaktivieren), Standard automatisch an, sobald ein Stift erkannt wurde.

### 31.6 Latenz und Glätte
- Vorbild fühlt sich verzögerungsfrei an (Vorhersage).
- Farbwerk: `View.requestUnbufferedDispatch(MotionEvent)` beim Strichbeginn, Rendern pro VSync, **Bewegungsvorhersage**
  (`androidx.input:input-motionprediction`) für die Live-Spitze (nur Anzeige, nicht in den Strich), Low-Latency-Grafik
  (`androidx.graphics:graphics-core` Front-Buffer-Rendering) als Ausbaustufe.
- Keine Allokationen pro Punkt; historische Samples nutzen; Zeitstempel für Tempo-Dynamik.

### 31.7 Sonstiges
- Schreiben mit dem Stift in Textfelder (Vorbild „Scribble“): auf Android über die Systemtastatur/Handschrift-Eingabe;
  keine eigene Implementierung nötig.
- Stift-Hover zeigt in Panels optional Hover-Zustand der Knöpfe.
