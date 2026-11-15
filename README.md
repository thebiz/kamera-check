# kamera-check

Experimentelle KI-Zoom-Kamera für Android (getestet auf Poco X7 Pro).

- Live-Vorschau mit Zoom bis 10× (Regler, Schnellwahl, zwei Finger)
- **KI-Lupe:** Die Bildmitte wird live von der KI ×4 rekonstruiert
- **Mehrbild-Superauflösung aus RAW** (ab 2×): 12 RAW-Bilder werden ausgerichtet und ihre Bayer-Pixel auf ein bis zu doppelt so feines Raster verteilt – echte zusätzliche Details statt geratener. Helligkeit und Farbe werden an das Kamerabild angeglichen.
- **KI obendrauf:** Real-ESRGAN läuft auf dem Mehrbild-Ergebnis, nicht auf dem Einzelbild – dadurch deutlich weniger falsch erfundene Buchstaben
- **Bildansicht:** Normal ↔ Mehrbild/KI mit verschiebbarer Linie, Zoom und KI-Anteil-Regler; in der Galerie landet die Mischung (60 % KI)
- **Eigener Autofokus:** Nach dem Antippen fährt die App den Fokus selbst durch und misst die Schärfe genau im Rahmen – so gewinnt nicht mehr automatisch der Vordergrund. Die Entfernung wird festgehalten und alle 5 s fein nachgeprüft. „Manueller Fokus“ blendet einen Regler nah ↔ ∞ ein.
- **Unschärfe-Messung vor der KI:** Die KI kennt nur leichte Unschärfe. Bei starkem Zoom misst die App die Breite der unscharfen Übergänge und verkleinert das Bild vorher passend – so rekonstruiert die KI echte Kanten statt eines braunen Saums um Schrift
- **Schrift als Eichmaß, Unschärfe je Richtung:** Erkannte Buchstaben liefern echte Sprungkanten zum Messen; einseitige Verwacklung wird erkannt und vor der KI je Richtung ausgeglichen
- **Lagesensor:** misst die Verwacklung jedes Bildes der Serie – verwackelte Bilder zählen weniger oder fliegen raus
- **KI: schnell / stark:** stark = großes Modell (RealESRGAN_x4plus), deutlich sauberere Kanten, rechnet bis ca. 3 Minuten
- **Bis zu 30 RAW-Bilder** pro Serie (je nach Ausschnitt), kachelweise ausgerichtet – auch leichte Drehungen der Hand und Bewegung im Bild werden sauber verrechnet
- **Kalibrierung (Mehr › Kalibrieren):** Testmuster auf dem iPad (QR-Code in der App) – die App vermisst das eigene Objektiv (Mitte, Ecken, je Farbe) und nutzt das Schärfeprofil beim Entschärfen
- **Fokuspunkt mit Verfolgung:** Tippen setzt einen Rahmen, der am Objekt klebt und laufend nachstellt (weiß = sucht, grün = scharf, orange = unscharf, grau gestrichelt = Ziel verloren). Rahmen ziehen verschiebt ihn, Doppeltippen = Autofokus
- **Auslöse-Ring:** füllt sich, solange die Serie läuft; Vibration + „Fertig – du kannst loslassen“, sobald gerechnet wird
- **50 MP über die Xiaomi-Kamera:** Knopf „50 MP“ öffnet die Kamera-App; beim Zurückkommen holt die App die neuen Fotos, legt sie übereinander und rechnet weiter (auch per *Teilen* oder *Mehr › Fotos importieren*)
- **Diagnosebild:** jeder Rechenschritt am selben Ausschnitt nebeneinander, landet in der Galerie
- „Check“ zeigt, welche Kamera-Funktionen das Handy freigibt; der **50-MP-Test** probiert alle Wege zur vollen Sensorauflösung
- Die App wählt beim Start automatisch den schnellsten Rechenweg (KI-Chip oder Prozessor)

**Testmuster fürs iPad:** in der App unter *Mehr › Kalibrieren* (QR-Code) · Quelle: [docs/kalibrierung.html](docs/kalibrierung.html)

**Forschungsdokument (lesbar):** [Forschung.html](https://github.com/thebiz/kamera-check/releases/latest/download/Forschung.html) · Quelle: [docs/FORSCHUNG.md](docs/FORSCHUNG.md)

**Neueste APK:** [kamera-check.apk](https://github.com/thebiz/kamera-check/releases/latest/download/kamera-check.apk)

## KI-Modelle

- `drunet256.onnx` – *DRUNet* (Graustufen) aus [DPIR/KAIR](https://github.com/cszn/KAIR) von Kai Zhang (MIT-Lizenz), umgewandelt mit `tools/export_drunet.py` – für das KI-Entschärfen mit bekannter Unschärfe

Die Hochskalier-Modelle stammen von [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) (BSD-3-Clause, © Xintao Wang):

- `sr128.onnx` – *realesr-general-x4v3*, 50/50 gemischt mit *realesr-general-wdn-x4v3*, umgewandelt mit `tools/export_model.py`
- `sr_strong.onnx` – *RealESRGAN_x4plus* (fp16-Gewichte), umgewandelt mit `tools/export_x4plus.py`

## Simulation

`tools/sim/gen.py` erzeugt eine verwackelte RAW-Serie eines entfernten Schildes, `tools/sim/Test.kt` lässt den Rechenkern (`Align.kt`, `RawSR.kt`) darauf laufen.

Ideen und Entwicklungsschritte mit Datum: [docs/ERFINDUNGSPROTOKOLL.md](docs/ERFINDUNGSPROTOKOLL.md)
