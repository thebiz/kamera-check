# kamera-check

Experimentelle KI-Zoom-Kamera für Android (getestet auf Poco X7 Pro).

- Live-Vorschau mit Zoom bis 10× (Regler, Schnellwahl, zwei Finger)
- **KI-Lupe:** Die Bildmitte wird live von der KI ×4 rekonstruiert
- **KI-Aufnahme:** Bildserie (6–10 Bilder) → ausrichten und mitteln → KI-Hochskalierung ×4. Zum Vergleich landen Normal- und KI-Bild unter *Bilder/Kamera-Check*
- Tippen zum Fokussieren, Doppeltippen für Autofokus, Makro-Schalter
- „Check“ zeigt, welche Kamera-Funktionen das Handy freigibt
- Die App wählt beim Start automatisch den schnellsten Rechenweg (KI-Chip oder Prozessor)

**Neueste APK:** [kamera-check.apk](https://github.com/thebiz/kamera-check/releases/latest/download/kamera-check.apk)

## KI-Modell

`app/src/main/assets/sr128.onnx` ist [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) *realesr-general-x4v3* (BSD-3-Clause, © Xintao Wang), umgewandelt mit `tools/export_model.py`.
