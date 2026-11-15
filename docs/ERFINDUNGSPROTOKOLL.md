# Erfindungs- und Ideenprotokoll – Kamera-Check / KI-Zoom

**Urheber der Ideen und Produktrichtung:** John (Repository `thebiz/kamera-check`)
**Umsetzung:** gemeinsam mit Claude (Anthropic) als Programmierwerkzeug

Dieses Dokument hält fest, welche Ansätze wann entstanden und umgesetzt wurden.
Jede Änderung ist zusätzlich durch die Git-Historie (Commit-Datum, Inhalt) belegt.
Es ersetzt keine rechtliche Beratung.

---

## 14.11.2026

### Ausgangsidee (John)
Eine Handy-Kamera-App für das Poco X7 Pro, die ohne optischen Zoom durch Rechnen –
Mehrbild-Technik und KI – scharfe Zoom-Aufnahmen entfernter Motive liefert
(Kirchen, Gebäude, Statuen in Hamburg) und auch Makro kann. Ziel: Grenzen der
Hersteller-Software austesten und überwinden.

### Umgesetzte Bausteine (Version, Kurzbeschreibung)

| Version | Ansatz |
|---|---|
| 0.1 | Kamera-Freigaben auslesen, inkl. Suche nach vom Hersteller nicht gemeldeten Kameras (gefunden: Ultraweitwinkel und logische Mehrfachkamera) |
| 0.2 | Bildserie (Burst) aus dem vollen Sensorbild, eigener Zoom-Ausschnitt, Ausrichten und Verrechnen |
| 0.3 | KI-Hochskalierung auf dem Gerät (Real-ESRGAN) mit automatischer Wahl KI-Chip/Prozessor; **Live-KI-Lupe** in der Vorschau |
| 0.4 | Gewichtsmischung zweier KI-Modellvarianten für natürlichere Schrift; Vergleichsansicht mit verschiebbarer Linie und KI-Anteil-Regler |
| 0.5 | **Mehrbild-Superauflösung direkt aus RAW-Bayer-Daten**: Abtastwerte aller Bilder an ihrer exakten Position auf ein feineres Raster verteilt; **Angleichung an das Kamera-Farbbild** über geschätzte Tonkurve und großflächige Verstärkungskarte (keine eigene Farbentwicklung nötig); KI läuft erst auf dem Mehrbild |
| 0.6 | Fokusrahmen, der per Bildvergleich am Objekt „klebt“; Auslöse-Ring mit Vibration |
| 0.7 | **Eigener Kontrast-Autofokus** (Durchlauf grob/fein, Schärfe nur im Rahmen gemessen, Entfernung festhalten, periodisch fein nachprüfen) statt Hersteller-Autofokus; kachelweises Ausrichten mit Ausreißer-Abwertung und Übergang lokal→global; bis zu 30 RAW-Bilder nach Speicherbudget; großes KI-Modell als Stark-Modus mit Zeitbudget |
| 0.8 | **Unschärfe messen und KI-Eingang anpassen**: Aus Kanten (Sprunghöhe / maximale Steigung) wird die Unschärfe σ berechnet; das Bild wird vor der KI so verkleinert, dass die Rest-Unschärfe im Trainingsbereich der KI liegt (≈ 1–1,8 px, abhängig von σ). Beseitigt den „Saum“ um Schrift |
| 0.9 | **Schrift als Eichmaß** (Johns Idee): erkannte Buchstaben liefern echte Sprungkanten, an denen die Unschärfe bevorzugt gemessen wird; **richtungsabhängige Unschärfe** (σx/σy) und getrenntes Verkleinern je Richtung vor der KI; **Lagesensor** misst die Verwacklung jedes Bildes der Serie (Drehrate × Belichtungszeit × Brennweite in Pixeln) – verwackelte Bilder werden abgewertet oder aussortiert |

## 15.11.2026

| Version | Ansatz |
|---|---|
| 1.0 | **Physik statt Raten** (nach Johns Kritik am künstlichen Look): Kameramodell (Beugung, Pixelfläche, Mehrbild-Kern, Verwacklung) begrenzt die gemessene Unschärfe; physikalisches Entschärfen (Richardson-Lucy mit Kantenschutz) ohne Verkleinern; KI nur noch zu 30 %; automatische Gleichmäßigkeitsprüfung (ebenes Motiv ⇒ überall gleich scharf, Johns Hinweis) |
| 1.1 | **Kalibrierung mit Testmuster auf dem iPad** (Johns Idee „Schärfeprofil der eigenen Kamera“, inkl. Graustufen als Referenzwerte): geführte Aufnahme an 5 Sensorpositionen bei festem Zoom mit automatischem Scharfstellen; Messung der Unschärfe je Farbe und Richtung in den linearen RAW-Daten des Messrahmens; Herausrechnen der Verwacklung (Lagesensor); Graustufen in gleichen Lichtschritten zur Prüfung der Linearität und Messung des Streulichts; Profil fließt je Bildkachel ins Entschärfen ein (σ² = Profil² + Zusatz²). Dabei Messfehler der Kantenmethode (+0,5 px²) entdeckt und korrigiert |
| 1.17 | **Tiefenabhängige Nachbearbeitung** eines Mehrbild-Zoomergebnisses: aus einem Bild geschätzte relative Tiefe steuert Mikrokontrast (nah) und Luftperspektive (fern) stufenlos; Parallaxen-Ansicht aus kumulativen, weich maskierten Tiefenschichten ohne Lochfüllung (Johns Idee „Plastizität“) |
| 1.16 | Kantenschonende großflächige Kontrastanhebung (Klarheit) als getrennte, stufenlos regelbare Stufe nach der unschärfebasierten Rekonstruktion, mit unverändertem Vergleichsbild (Johns Beobachtung in der Galerie-Bearbeitung) |
| 1.15 | Aufnahme-Fortschritt direkt am Fokusrahmen statt nur am Auslöser (Johns Idee: der Blick liegt beim Fotografieren auf dem Motiv) |
| 1.14 | Prüfverfahren für den begrenzenden Faktor: Unschärfe in echten Pixeln vor/nach Rasterverfeinerung gemessen (Raster ist nicht der Engpass), danach Test der Bildanzahl innerhalb derselben Serie (Rauschen als Engpass) |
| 1.13 | **Mehrbild-Eintragen auf feineres Raster aus bereits vom Bildprozessor gezoomten Bildern** (hochauflösender Mittelteil des Sensors statt zusammengefasster Rohpixel), mit gemeinsamer Ausrichtung; fairer Vergleich durch zweifache Berechnung derselben Serie (Johns Frage nach dem nächsten Qualitätshebel) |
| 1.12.1 | **Rausch-Untergrenze abhängig von der Rasterverfeinerung** für die Plug-and-Play-Rekonstruktion: auf einem durch Mehrbild-Eintragen verfeinerten Raster unterschätzt die Nachbarpixel-Schätzung das Rauschen; ohne Korrektur verstärkt der Datenschritt das Eintragungsmuster (Befund aus Johns A/B-Vergleich). Direktes Teilen des Diagnosebildes aus der Ansicht (Johns Wunsch) |
| 1.11 | **Kantenabhängige Mischung** physikalischer und KI-Rekonstruktion (Johns Beobachtung: große Schrift mit Physik randschärfer, feine Schrift mit KI besser); **Kalibrierung über Fotos der Hersteller-Kamera** mit automatischer Mustersuche je Foto, Profil in Pixeln der 50-MP-Aufnahme |
| 1.9 | **Zugang zur vollen Sensorauflösung ohne Root** über eine nicht öffentlich gelistete Hersteller-Ausgabegröße in Kombination mit Remosaic-Schaltern (absturzfester, selbst fortsetzender Test); Messverfahren „echte Auflösung vs. hochgerechnet“ über Feinstruktur-Energie jenseits des halben Rasters, getrennt nach Kanten und Flächen |
| 1.8.1 | Systematischer Test der Hersteller-Freigaben über alternative Paketnamen (parallel installierbare Testversionen) |
| 1.8 | **Gegenprobe mit stärkegleichem Nachschärfen** als Prüfstein für echte Detailgewinne (Johns Frage „nur schärfer oder wirklich besser?“); farbgetrennte Rekonstruktion mit am Bild gemessener Unschärfe je Farbkanal; Überspringen strukturloser Kacheln |
| 1.7 | **Kalibrierte Unschärfe als Eingabe einer KI-Rekonstruktion** (Johns Forderung, die KI-Verbesserung zu überarbeiten): Plug-and-Play-Verfahren im Wechsel aus exaktem Frequenzraum-Schritt mit der je Bildbereich gemessenen bzw. kalibrierten Unschärfe und neuronalem Entrauscher, gekachelt auf dem Handy mit Zeitbudget (Bildmitte zuerst, Rest physikalisch) |
| 1.6 | Mehrstufiges Ausrichten großer Verschiebungen (1/16-Auflösung → Viertel → voll, kachelweise mit einseitigen Rändern) und **Passprobe per Restfehler** gegen Doppelbilder bei Fotoserien aus der Hand; relatives Verschieben des Ausschnitts (Johns Hinweis) |
| 1.5 | **Hersteller-Kamera als Quelle** (Johns Entscheidung): die gesperrte volle Sensorauflösung über die System-Kamera-App beziehen und die Fotos automatisch zurückholen, ausrichten und physikalisch weiterverarbeiten, mit Vergleichskachel „wie 12 MP“; systematischer Test aller Wege zur vollen Auflösung mit echter Aufnahme; gebremstes Entschärfen (weiche Deckelung + Saumbegrenzung) für gleichmäßige Wirkung (nach Johns Befund „500 g sticht heraus“) |
| 1.4 | Zweistufiges kachelweises Ausrichten der YUV-Serie aus dem Kamera-Zoom (grob/fein, Nachbar-Median gegen Ausreißer), Schärfe-Auslese der Serie, automatisches Abschalten der KI bei erkannter Schrift |
| 1.3 | **Kamera-Zoom als Quelle**: Diagnose zeigt, dass das Rohmaterial (zusammengefasste 12-MP-Pixel, selbst ausgeschnitten) der Engpass ist; Umweg über den Zoom des Bildprozessors bis 2×, um an die hochauflösende Bildmitte des 50-MP-Sensors zu kommen, obwohl die volle Auflösung für fremde Apps gesperrt ist; eigener Ausschnitt nur noch für den Rest-Zoom |
| 1.2.1 | **Diagnosebild** (nach Johns Befund, dass die Live-Vorschau besser aussah als das berechnete Foto): derselbe Bildausschnitt nach jedem Rechenschritt in einem Übersichtsbild, inkl. Vorschau im Moment des Auslösens – zeigt, welcher Schritt verschlechtert |
| 1.2 | Nach Johns erstem Praxistest: **robuste Stufenerkennung** (schmale Mess-Streifen an mehreren Stellen gegen Schräglage, Toleranz für perspektivisch ungleiche Stufenbreiten, Erkennen und Auslassen verschmolzener ausgebrannter Stufen); **Messung nur im Zielrahmen** (Sensor-Ausschnitt auf Rahmen plus Rand verkleinert) für schnelle Kalibrier-Aufnahmen; Belichtungskorrektur −1 Blende beim Kalibrieren; Bedienung unterhalb der Vorschau |

### Ideen für später (Stand 14.11.2026)
- Schrift-Eichmaß weiterdenken: Form des erkannten Buchstabens als Vorlage für die
  Unschärfe-Form (Punktbildfunktion), ohne die Buchstaben selbst zu „verschönern“
- Ohne Schrift: gerade Linien (Fensterrahmen, Mauerfugen) gezielt als Eichkanten nutzen
- Einmalige **Vermessung des Objektivs** mit einem Testmuster auf dem Monitor
  (Unschärfe je Bildbereich und Zoomstufe) – gerätespezifisches Eigenwissen
- **Fokusreihe** (mehrere Fokusentfernungen) und **zweite Kamera** als zusätzliche Information
- Lagesensor auch für die Wahl des Referenzbildes und zur Vorhersage der Verschiebung beim Ausrichten

### Messbelege (Simulation, reproduzierbar mit `tools/sim/`)
- RAW-Mehrbild vs. Kamera-Einzelbild (kleine Schrift): PSNR 29,2 dB vs. 27,4 dB
- KI auf Mehrbild statt Einzelbild: 28,8 dB vs. 26,9 dB, Buchstaben deutlich richtiger
- Kachelweises Ausrichten bei Handdrehung: 28,7 dB vs. 28,5 dB, ohne Drehung kein Verlust
- Unschärfe-angepasster KI-Eingang bei σ = 4 px: Fehler 0,025 vs. 0,050 (halbiert)
- Richtungsabhängig bei einseitiger Verwacklung (σx = 5, σy = 1,5): Fehler 0,028 vs. 0,037
