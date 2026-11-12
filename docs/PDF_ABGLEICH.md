# Abgleich: Einsteiger-Tutorial (PDF des Nutzers) ↔ Farbwerk

Zusammenfassung der im Tutorial beschriebenen Funktionen (in eigenen Worten) und Stand in Farbwerk.
Die PDF selbst liegt außerhalb des Repos (`/home/claude/pdfref/`, Seitenbilder `page-NN.png`).

| Bereich | Beschriebenes Verhalten | Stand | Zuständig |
|---|---|---|---|
| Gesten | 2-Finger-Tippen rückgängig, 2 Finger halten = schnell rückgängig, 3-Finger-Tippen/halten wiederholen, Kneifen zoomt, Drehen dreht | ✅ | – |
| QuickShape | Linie/Kreis zeichnen und halten → wird perfekt | ✅ | – |
| Aktionen: 6 Tabs | Hinzufügen, Leinwand, Teilen, Video, Einstellungen, Hilfe | 🟡 Video nur Platzhalter | **Zeitraffer-Agent** |
| Hinzufügen | Datei, Foto, Text einfügen; Ausschneiden/Kopieren/Einfügen | ✅ | – |
| Leinwand | Zuschneiden & Größe, mehrseitiges PDF (Seitenassistent), Animation, Zeichenhilfe, **Referenz-Fenster** abnehmbar | ✅ (Referenz vorhanden; Schalter muss in Aktionen → Leinwand sichtbar sein) | UI-Aktionen |
| Video | Zeitraffer-Wiedergabe des gesamten Malprozesses, Export komplett oder als ~30-s-Zusammenfassung | ❌ | **Zeitraffer-Agent** |
| Einstellungen | Helle/Dunkle Oberfläche, Rechts-/Linkshänder | ✅ | – |
| Hilfe | Handbuch/Support | ✅ (eigene Hilfe-Seite) | – |
| Zeichenhilfen | 2D-Raster, Isometrisch, Perspektive (Fluchtpunkte + Horizont selbst setzen), Symmetrie | ✅ | – |
| Anpassungen | Farbton/Sättigung/Helligkeit (3 Regler unten), Farbbalance, Kurven, Verlaufsumsetzung | ✅ | – |
| Unschärfen | Gauß, Bewegung, Perspektive – Stärke per Wischen, **blaue Leiste oben** | ✅ | – |
| Rauschen, Scharfzeichnen, Bloom | Rauschen = farbige Pünktchen, Scharfzeichnen betont Kanten, Bloom lässt helle Bereiche leuchten | ✅ | – |
| Glitch | 4 Stile | ✅ | – |
| Halbton | 3 Varianten (rund/Linien/Kreuz bzw. Vollfarbe/Siebdruck/Zeitung) | 🟡 prüfen, dass 3 Varianten wählbar sind | Feinschliff |
| Chromatische Aberration | 2 Varianten (Perspektive/radial, Verschieben/linear) | ✅ | – |
| Verflüssigen, Klonen | Formkorrektur; runder Quell-Cursor | ✅ | – |
| Auswahl | Automatisch (Halten + seitlich ziehen = Schwelle), Freihand-Lasso, Rechteck/Ellipse, **zweiter Finger halten = Quadrat/Kreis** | 🟡 Quadrat/Kreis per zweitem Finger prüfen | Feinschliff |
| Auswahl füllen | Auswahl erstellen, Farbkreis antippen/ziehen → Auswahl wird gefüllt | ✅ (ColorDrop füllt Auswahl) | – |
| Pinsel/Wischen/Radieren | gleiche Bibliothek für alle drei; Pinsel importieren/anpassen | ✅ | – |
| Ebenen | Tippen → Optionsmenü links neben Panel (Kopieren, Füllen …); nach links wischen → Sperren/Duplizieren/Löschen; „N“ → Mischmodi | ✅ | – |
| Maske / Schnittmaske | Maske in Graustufen bemalen; Schnittmaske malt nur innerhalb der darunterliegenden Ebene | ✅ | – |
| Ebenen zusammenkneifen | Ebenen per Kneifgeste zusammenfügen | ✅ | – |
| Mischmodi | Multiplizieren, Überlagern, Hinzufügen, Weiches Licht … | ✅ | – |
| Farben | 5 Ansichten | ✅ | – |
| Ebenenlimit | abhängig von Leinwandgröße/Gerät | ✅ | – |
| Workflow-Probe | Kreis-Auswahl → füllen → Schnittmasken-Ebene → Gauß 100 % → zusammenkneifen → Freihand-Auswahl → Airbrush-Nebel → Multiplizieren 40 % → Hinzufügen 30 % | Abnahmetest nach dem nächsten Build | Prüfung |
