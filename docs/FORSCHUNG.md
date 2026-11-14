# Forschungsprojekt KI-Zoom – wie die Kamera funktioniert

*Stand: Version 1.0 · 14.11.2026 · Idee und Richtung: John · Umsetzung mit Claude*

Dieses Dokument erklärt in verständlicher Sprache, was die App mit einem Foto macht,
warum sie es so macht und was wir dabei gelernt haben. Es wird mit jeder Version
fortgeschrieben und hängt jeder Version als lesbare Datei an.

---

## 1. Die Ausgangslage

Das Poco X7 Pro hat keinen optischen Zoom. Wer „heranzoomt“, schneidet nur einen Teil
aus dem Sensorbild aus – bei 8× bleibt etwa ein Sechzigstel der Pixel übrig. Ziel des
Projekts: aus diesem kleinen Ausschnitt so viel **echtes** Detail wie möglich
herausholen, statt es nur hübsch zu erfinden.

### Was das Handy freigibt (Messung mit „Check“)
- Höchste Freigabestufe (LEVEL 3), RAW-Daten, manuelle Belichtung, Serienbilder
- Hauptsensor eigentlich 50 MP, freigegeben werden nur 12,6 MP (je 4 Pixel zusammengefasst)
- Versteckte Kameras: Ultraweitwinkel und eine logische Mehrfachkamera, die normalen Apps nicht gemeldet werden

---

## 2. Die Rechenkette (Version 1.0)

Ein Druck auf den Auslöser löst diese Schritte aus:

### Schritt 1 – Scharfstellen mit eigenem Autofokus
Xiaomis Autofokus bevorzugt das nächste Objekt. Deshalb fährt die App den Fokus selbst
von „fern“ bis „nah“ durch und misst dabei die **Schärfe nur im Fokusrahmen**
(Kontrast = Summe der Helligkeitssprünge). Die beste Entfernung wird festgehalten und
alle 5 Sekunden fein nachgeprüft. Der Rahmen verfolgt das Objekt per Bildvergleich.

### Schritt 2 – Bildserie
Bis zu 30 Bilder als **RAW** (rohe Sensordaten, unbearbeitet) plus bis zu 10 normale
Kamerabilder (für Farbe und Helligkeit). Die Kamera-eigene Schärfung und Rauschminderung
ist ausgeschaltet, damit nichts verfälscht wird.

### Schritt 3 – Lagesensor
Während der Serie misst das Gyroskop, wie stark die Hand sich dreht. Daraus folgt für
jedes Bild die **Verwacklung in Pixeln**:
> Verwacklung = Drehgeschwindigkeit × Belichtungszeit × Brennweite in Pixeln

Verwackelte Bilder zählen weniger, stark verwackelte werden aussortiert.

### Schritt 4 – Mehrbild-Superauflösung
Die Hand zittert immer ein wenig. Dadurch liegt jedes Bild um Bruchteile eines Pixels
versetzt. Die App
1. richtet alle Bilder aufeinander aus – erst als Ganzes, dann **kachelweise**
   (gleicht auch leichte Drehungen aus; bewegte Stellen zählen weniger),
2. verteilt **jeden einzelnen Farbpunkt** des Sensors an seiner exakten Position auf
   ein bis zu doppelt so feines Raster.

So entsteht echtes zusätzliches Detail – das gleiche Prinzip wie bei Googles Pixel-Kameras.
Helligkeit und Farbe werden danach an das normale Kamerabild angeglichen.

### Schritt 5 – Unschärfe messen
An den kräftigsten Kanten wird gemessen, wie breit ein Hell-Dunkel-Übergang verschmiert ist:
> Ein scharfer Sprung der Höhe A wird durch Unschärfe σ zu einer Rampe mit
> maximaler Steigung A / (σ · √2π). Aus Höhe und Steigung folgt σ.

- **Schrift als Eichmaß (Johns Idee):** Eine Texterkennung auf dem Gerät sucht Buchstaben.
  Gedruckte Schrift ist schwarz-weiß, ihre Kanten sind echte Sprünge – ideal zum Messen.
  Die Buchstaben selbst werden **nicht** verändert.
- **Je Richtung:** Senkrechte Kanten verraten die waagerechte Unschärfe und umgekehrt –
  so wird einseitige Verwacklung erkannt.
- **Je Bereich:** Das Bild wird in 6 Felder geteilt. Bei einem ebenen Motiv (Karton,
  Fassade) müssen alle gleich scharf sein. Ist ein Feld deutlich unschärfer, meldet die
  App das – es deutet auf einen Rechenfehler hin, nicht auf die Optik.

### Schritt 6 – Kameramodell (Physik)
Die App berechnet, wie unscharf das Bild **mindestens** sein muss:

| Quelle | Formel | Größenordnung |
|---|---|---|
| Beugung an der Blende | σ ≈ 0,42 · λ · Blende (λ = 0,55 µm) | ≈ 0,2 Pixel |
| Pixelfläche | σ = 1/√12 Pixel | 0,29 Pixel |
| Mehrbild-Raster | Verteilkern | ≈ 0,35 Pixel |
| Verwacklung | Strecke / √12 | je nach Hand |
| Objektivfehler | Annahme | ≈ 0,4 Pixel |

Die Quellen addieren sich quadratisch. Die gemessene Unschärfe wird auf den Bereich
zwischen diesem Minimum und „Minimum + 2,5 Pixel“ begrenzt – was außerhalb liegt, ist
physikalisch unplausibel und wohl ein Messfehler.

### Schritt 7 – Physikalisch entschärfen
Mit der gemessenen Unschärfe wird das Bild **zurückgerechnet** (Richardson-Lucy-Verfahren):
> „Wenn mein geschätztes scharfes Bild mit dieser Unschärfe verwischt würde – stimmt es
> dann mit dem Foto überein?“ – und die Schätzung wird so lange korrigiert, bis es passt.

Ein **Kantenschutz** verhindert Lichthöfe und Ringe. Das Verfahren erfindet nichts: Es
holt nur zurück, was im Bild gedämpft noch vorhanden ist. Gerechnet wird in Kacheln
auf allen Prozessorkernen, mit Zeitbudget (schnell ≈ 25 s, stark ≈ 60 s).

### Schritt 8 – KI, nur noch sanft
Ein KI-Modell (Real-ESRGAN, frei nutzbar, läuft komplett auf dem Handy) rekonstruiert
feine Kanten. Weil die KI **erfindet**, was sie für plausibel hält, wird sie
standardmäßig nur zu **30 %** untergemischt. In der Bildansicht lässt sich stufenlos
zwischen „Echt“ (0 %) und „KI“ (100 %) wählen.

---

## 3. Was wir gelernt haben

| Erkenntnis | Wie belegt |
|---|---|
| Das normale Kamerabild ist schon stark vorgeglättet – Mehrbild aus diesen Bildern bringt fast nichts | Version 0.2: Einzel- und Mehrbild sahen gleich aus |
| KI auf einem Einzelbild erfindet falsche Buchstaben | Simulation: „Gottesdienst sonntags“ wurde Buchstabensalat |
| KI auf dem RAW-Mehrbild liegt viel näher an der Wahrheit | Simulation: 28,8 dB statt 26,9 dB |
| Die KI kennt nur ≈ 1 Pixel Unschärfe; bei mehr erzeugt sie einen Saum um Kanten | Johns Foto „DREAME“, 12× Zoom |
| Verkleinern vor der KI beseitigt den Saum, wirkt aber künstlich (Relief, Schatten in Punzen, glatte Pappe) | Johns Urteil am echten Foto (Version 0.9) – die Simulation hatte das nicht gezeigt |
| **Simulation ersetzt nicht das echte Foto** – Grafiker-Augen finden, was Zahlen übersehen | daraus: Version 1.0 setzt auf Physik statt Raten |
| Bei ebenem Motiv muss alles gleich scharf sein; Ungleichmäßigkeit = Rechenfehler | Johns Hinweis; jetzt automatische Prüfung |

---

## 4. Offene Fragen und nächste Ideen
- **Objektiv vermessen:** einmalig ein Testmuster auf dem Monitor fotografieren und
  die echte Unschärfe des Objektivs je Bildbereich und Zoomstufe bestimmen
- **Entfernung nutzen:** Fokusentfernung, bekannte Größen (Buchstabenhöhe), zwei Kameras
  (Stereo) oder GPS + Karte (Kirchen in Hamburg) – für das Kameramodell
- **Fokusreihe:** mehrere Fokusstufen kombinieren
- Unschärfe je Bildbereich auch beim Entschärfen berücksichtigen (nicht nur prüfen)
- Starke KI auf den Grafikchip bringen (heute oft nur Prozessor → langsam)

---

## 5. Versionsgeschichte (kurz)
| Version | Neu |
|---|---|
| 0.1 | Kamera-Freigaben auslesen |
| 0.2 | Bildserie, eigener Zoom |
| 0.3 | KI auf dem Gerät, Live-KI-Lupe |
| 0.4 | Bildansicht mit Vergleich, natürlichere KI-Mischung |
| 0.5 | RAW-Mehrbild-Superauflösung |
| 0.6 | Fokusrahmen mit Objektverfolgung, Auslöse-Ring |
| 0.7 | Eigener Autofokus, manueller Fokus, kachelweises Ausrichten, starke KI |
| 0.8 | Unschärfe messen, große Regler |
| 0.9 | Schrift als Eichmaß, Unschärfe je Richtung, Lagesensor |
| 1.0 | Physik statt Raten: Kameramodell, Entschärfen, Gleichmäßigkeitsprüfung, KI nur 30 %, Infozeile bleibt stehen |
