# kamera-check

Experimentelle KI-Zoom-Kamera für Android (getestet auf Poco X7 Pro).

- Live-Vorschau mit Zoom bis 10× (Regler, Schnellwahl, zwei Finger)
- **KI-Lupe:** Die Bildmitte wird live von der KI ×4 rekonstruiert
- **Mehrbild-Superauflösung aus RAW** (ab 2×): 12 RAW-Bilder werden ausgerichtet und ihre Bayer-Pixel auf ein bis zu doppelt so feines Raster verteilt – echte zusätzliche Details statt geratener. Helligkeit und Farbe werden an das Kamerabild angeglichen.
- **KI obendrauf:** Real-ESRGAN läuft auf dem Mehrbild-Ergebnis, nicht auf dem Einzelbild – dadurch deutlich weniger falsch erfundene Buchstaben
- **Bildansicht:** Normal ↔ Mehrbild/KI mit verschiebbarer Linie, Zoom und KI-Anteil-Regler; in der Galerie landet die Mischung (60 % KI)
- **Fokuspunkt mit Verfolgung:** Tippen setzt einen Rahmen, der am Objekt klebt und laufend nachstellt (weiß = sucht, grün = scharf, orange = unscharf, grau gestrichelt = Ziel verloren). Rahmen ziehen verschiebt ihn, Doppeltippen = Autofokus
- **Auslöse-Ring:** füllt sich, solange die Serie läuft; Vibration + „Fertig – du kannst loslassen“, sobald gerechnet wird
- „Check“ zeigt, welche Kamera-Funktionen das Handy freigibt
- Die App wählt beim Start automatisch den schnellsten Rechenweg (KI-Chip oder Prozessor)

**Neueste APK:** [kamera-check.apk](https://github.com/thebiz/kamera-check/releases/latest/download/kamera-check.apk)

## KI-Modell

`app/src/main/assets/sr128.onnx` ist [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) *realesr-general-x4v3* (BSD-3-Clause, © Xintao Wang), umgewandelt mit `tools/export_model.py`.

## Simulation

`tools/sim/gen.py` erzeugt eine verwackelte RAW-Serie eines entfernten Schildes, `tools/sim/Test.kt` lässt den Rechenkern (`Align.kt`, `RawSR.kt`) darauf laufen.
