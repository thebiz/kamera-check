# kamera-check

Experimentelle KI-Zoom-Kamera für Android (getestet auf Poco X7 Pro).

- Live-Vorschau mit Zoom bis 10× (Regler, Schnellwahl, zwei Finger)
- **KI-Lupe:** Die Bildmitte wird live von der KI ×4 rekonstruiert
- **Mehrbild-Superauflösung aus RAW** (ab 2×): 12 RAW-Bilder werden ausgerichtet und ihre Bayer-Pixel auf ein bis zu doppelt so feines Raster verteilt – echte zusätzliche Details statt geratener. Helligkeit und Farbe werden an das Kamerabild angeglichen.
- **KI obendrauf:** Real-ESRGAN läuft auf dem Mehrbild-Ergebnis, nicht auf dem Einzelbild – dadurch deutlich weniger falsch erfundene Buchstaben
- **Bildansicht:** Normal ↔ Mehrbild/KI mit verschiebbarer Linie, Zoom und KI-Anteil-Regler; in der Galerie landet die Mischung (60 % KI)
- **Eigener Autofokus:** Nach dem Antippen fährt die App den Fokus selbst durch und misst die Schärfe genau im Rahmen – so gewinnt nicht mehr automatisch der Vordergrund. Die Entfernung wird festgehalten und alle 5 s fein nachgeprüft. „Manueller Fokus“ blendet einen Regler nah ↔ ∞ ein.
- **Unschärfe-Messung vor der KI:** Die KI kennt nur leichte Unschärfe. Bei starkem Zoom misst die App die Breite der unscharfen Übergänge und verkleinert das Bild vorher passend – so rekonstruiert die KI echte Kanten statt eines braunen Saums um Schrift
- **KI: schnell / stark:** stark = großes Modell (RealESRGAN_x4plus), deutlich sauberere Kanten, rechnet bis ca. 3 Minuten
- **Bis zu 30 RAW-Bilder** pro Serie (je nach Ausschnitt), kachelweise ausgerichtet – auch leichte Drehungen der Hand und Bewegung im Bild werden sauber verrechnet
- **Fokuspunkt mit Verfolgung:** Tippen setzt einen Rahmen, der am Objekt klebt und laufend nachstellt (weiß = sucht, grün = scharf, orange = unscharf, grau gestrichelt = Ziel verloren). Rahmen ziehen verschiebt ihn, Doppeltippen = Autofokus
- **Auslöse-Ring:** füllt sich, solange die Serie läuft; Vibration + „Fertig – du kannst loslassen“, sobald gerechnet wird
- „Check“ zeigt, welche Kamera-Funktionen das Handy freigibt
- Die App wählt beim Start automatisch den schnellsten Rechenweg (KI-Chip oder Prozessor)

**Neueste APK:** [kamera-check.apk](https://github.com/thebiz/kamera-check/releases/latest/download/kamera-check.apk)

## KI-Modell

Beide Modelle stammen von [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) (BSD-3-Clause, © Xintao Wang):

- `sr128.onnx` – *realesr-general-x4v3*, 50/50 gemischt mit *realesr-general-wdn-x4v3*, umgewandelt mit `tools/export_model.py`
- `sr_strong.onnx` – *RealESRGAN_x4plus* (fp16-Gewichte), umgewandelt mit `tools/export_x4plus.py`

## Simulation

`tools/sim/gen.py` erzeugt eine verwackelte RAW-Serie eines entfernten Schildes, `tools/sim/Test.kt` lässt den Rechenkern (`Align.kt`, `RawSR.kt`) darauf laufen.
