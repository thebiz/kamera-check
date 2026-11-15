# Forschungsprojekt KI-Zoom – wie die Kamera funktioniert

*Stand: Version 1.1 · 15.11.2026 · Idee und Richtung: John · Umsetzung mit Claude*

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

## 2. Die Rechenkette (Version 1.2)

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

### Schritt 6b – Schärfeprofil des eigenen Objektivs (Kalibrierung)
Liegt eine Kalibrierung vor (siehe Abschnitt 3), weiß die App, wie unscharf **dieses**
Objektiv an jeder Stelle des Sensors abbildet – die Ecken sind fast immer weicher als
die Mitte. Für jede Kachel des Bildes wird die Unschärfe dann so zusammengesetzt:
> σ² = σ²(Profil an dieser Stelle) + σ²(Zusatz dieses Fotos)

Der Zusatz (Fokus-Restfehler, Verwacklung) ist das, was das Foto über das Profil hinaus
unscharf ist – gemessen an der Bildmitte.

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

## 3. Kalibrierung mit Testmuster (ab Version 1.1)

**Idee (John):** Statt die Unschärfe in jedem Foto nur zu schätzen, misst die App einmal
genau, wie das eigene Objektiv abbildet – ein „Schärfeprofil“.

**Ablauf:** Das iPad zeigt eine Testmuster-Seite (Link per QR-Code in der App, *Mehr ›
Kalibrieren*). Das Handy fotografiert das Muster aus etwa 1,5 m bei 2× Zoom an fünf
Stellen des Bildes (Mitte, vier Ecken) und zum Schluss eine Graustufen-Treppe.
Vor jeder Aufnahme stellt die App selbst auf die Rahmenmitte scharf.

**Warum das iPad-Display nicht stört:** Aus 1,5 m sieht ein Kamerapixel etwa fünf
iPad-Pixel – das Pixelraster des Displays verschwimmt vollständig, jede Kante ist für die
Kamera ein perfekter Sprung. Gemessen wird nur die *Breite* des Übergangs, nicht die
absolute Helligkeit; Farbstich oder leichte Spiegelung verfälschen das kaum.

**Was gemessen wird:**

| Muster | Messung | Wozu |
|---|---|---|
| Schräge schwarze Quadrate | Unschärfe σ je **Farbe** (R, G, B) und **Richtung** (x, y), in den linearen RAW-Daten | Schärfeprofil über den Sensor; Farben zeigen, ob das Objektiv Rot und Blau anders abbildet als Grün |
| 11 Graustufen in gleichen Lichtschritten | Gerade durch Messwert ↔ Licht | **Linearität** des Sensors (Voraussetzung für Mehrbild und Entschärfen) und **Streulicht** (Rest-Helligkeit bei Schwarz) |

Schwarz-weiße Kanten genügen für die Farbmessung: An jeder Kante springt jeder der drei
Farbkanäle – so wird jede Farbe an exakt derselben Kante vermessen.

Die Verwacklung während der Kalibrier-Aufnahme (Lagesensor) wird herausgerechnet.
Das Profil wird auf dem Handy gespeichert; zwischen den fünf Messpunkten wird nach
Abstand gewichtet interpoliert.

**Messfehler der Kantenmessung entdeckt und korrigiert:** Beim Testen mit bekannter
Unschärfe zeigte sich, dass die Steigungsmessung (Differenz über 2 Pixel) jede Kante um
eine feste Varianz von ≈ 0,5 px² verbreitert. Seit Version 1.1 wird das herausgerechnet:
> σ = √(σ²gemessen − 0,5)

Ergebnis im Test: Soll 0,65/0,59 px → gemessen 0,64/0,60 px (Grün), ebenso genau für
Rot und Blau. Davon profitiert auch jedes normale Foto.

**Simulation (`tools/sim/CalTest.kt`):** Streulicht 2,91 % vorgegeben → 2,91 % gemessen,
Linearität R² = 1,0000; Graustufen werden waagerecht wie senkrecht und in beiden
Richtungen erkannt, auch wenn die äußeren Stufen angeschnitten sind.

**Erster echter Test (15.11.2026, Johns Rückmeldung) und Version 1.2:**

| Problem beim echten Kalibrieren | Ursache | Lösung in 1.2 |
|---|---|---|
| Anweisungsfeld lag über dem Zielrahmen | Feld oben über der Vorschau | Anweisungen jetzt unten im Bedienfeld; Zoom-Regler, Chips und Fokusrahmen sind während der Kalibrierung ausgeblendet – im Bild ist nur noch der grüne Rahmen |
| „oben links“ passte nicht zum Rahmen | Das Feld verdeckte den echten Rahmen oben, sichtbar war nur der grüne Fokusrahmen der vorigen Aufnahme | Fokusrahmen unsichtbar, Text nennt die Lage („im grünen Rahmen oben links“) |
| „Graustufen nicht erkannt“ (4×) | Die Erkennung erwartete gerade, gleich breite Stufen über die ganze Rahmenbreite und kein Ausbrennen | schmale Mess-Streifen an 5 Stellen (Schräglage egal), Stufenbreite darf bis Faktor 2,2 schwanken (Perspektive), verschmolzene helle Stufen werden erkannt und weggelassen; Belichtung beim Kalibrieren 1 Blende knapper; Schritt lässt sich überspringen |
| Dauerte zu lange | Ganzer 2×-Ausschnitt, bis zu 30 RAW-Bilder | nur der Rahmen plus Rand wird ausgelesen und verrechnet, 12 Bilder – etwa 5–10× weniger Rechnung |

Simulation der Graustufen-Erkennung (`tools/sim/WedgeTest.kt`), Streulicht vorgegeben 2,91 %:

| Fall | erkannt | Streulicht gemessen | Stufen genutzt |
|---|---|---|---|
| gerade | ✓ | 2,92 % | 11 |
| 4° schräg | ✓ | 2,90 % | 11 |
| überbelichtet (2 Stufen ausgebrannt) | ✓ | 2,91 % | 8 |
| Perspektive 1 : 1,6 | ✓ | 2,91 % | 11 |
| alles zusammen | ✓ | 2,91 % | 8 |

Version 1.1 hatte schon „4° schräg“ und „überbelichtet“ nicht mehr erkannt – genau das,
was in der Hand passiert. Lehre: **Testfälle müssen aus der echten Handhabung kommen**,
nicht aus dem Idealfall.

**Zweiter Praxistest (15.11.2026, nachts):** Kalibrierung lief durch, deutlich schneller.
Die Graustufen wurden erst erkannt, als John die iPad-Helligkeit stark reduziert hatte –
das Weiß des Displays war trotz −1 Blende ausgebrannt. Nach dem Speichern blieben alle
Knöpfe verschwunden (Fehler: Ansicht wurde aus dem Rechen-Thread geändert) – behoben in 1.2.1.

**Wichtigster Befund bisher:** Beim Glas „500 g“ sah die **Live-Vorschau besser aus als das
berechnete Foto** (Johns Vergleich per Screenshot). Im Foto: verlaufene Gesichter auf der
Flasche, wellig verzogene Kleinschrift, helle Säume um „500 g“. Vermutete Ursachen:
kachelweises Ausrichten (Wellen) und zu starkes Entschärfen (Säume). Statt weiter zu raten,
erzeugt Version 1.2.1 nach jedem Foto ein **Diagnosebild**: derselbe Ausschnitt nach jedem
Rechenschritt nebeneinander (Vorschau · Einzelbild · YUV-Mittel · RAW-Mehrbild · entschärft ·
KI · Galerie) – so wird sichtbar, welcher Schritt das Bild verschlechtert.

---

## 4. Was wir gelernt haben

| Erkenntnis | Wie belegt |
|---|---|
| Das normale Kamerabild ist schon stark vorgeglättet – Mehrbild aus diesen Bildern bringt fast nichts | Version 0.2: Einzel- und Mehrbild sahen gleich aus |
| KI auf einem Einzelbild erfindet falsche Buchstaben | Simulation: „Gottesdienst sonntags“ wurde Buchstabensalat |
| KI auf dem RAW-Mehrbild liegt viel näher an der Wahrheit | Simulation: 28,8 dB statt 26,9 dB |
| Die KI kennt nur ≈ 1 Pixel Unschärfe; bei mehr erzeugt sie einen Saum um Kanten | Johns Foto „DREAME“, 12× Zoom |
| Verkleinern vor der KI beseitigt den Saum, wirkt aber künstlich (Relief, Schatten in Punzen, glatte Pappe) | Johns Urteil am echten Foto (Version 0.9) – die Simulation hatte das nicht gezeigt |
| **Simulation ersetzt nicht das echte Foto** – Grafiker-Augen finden, was Zahlen übersehen | daraus: Version 1.0 setzt auf Physik statt Raten |
| Bei ebenem Motiv muss alles gleich scharf sein; Ungleichmäßigkeit = Rechenfehler | Johns Hinweis; jetzt automatische Prüfung |
| Die eigene Messmethode hatte einen festen Fehler (+0,5 px²) – sichtbar erst mit bekannten Testkanten | Kalibrier-Simulation, Version 1.1 |
| Die Handy-Vorschau ist ein starker Gegner: Bildprozessor schärft und mittelt bereits – unsere Kette muss sich daran messen, Schritt für Schritt | Johns Screenshot-Vergleich, Version 1.2 |
| Eine Erkennung, die nur den Idealfall kennt, scheitert in der Hand (Schräglage, Ausbrennen) | Johns 4 Fehlversuche mit den Graustufen; Version 1.2 |

---

## 5. Offene Fragen und nächste Ideen
- **Lernen nebenbei:** das Schärfeprofil mit jedem normalen Foto verfeinern – erst, wenn
  sich die Kalibrierung bewährt hat (sonst lernt das Profil Verwacklung und Fehlfokus mit)
- **Farben getrennt entschärfen:** heute fließen R, G, B gewichtet in die Helligkeits-Schärfe
  ein; besser wäre, jede Farbebene im RAW-Rechenkern mit ihrem eigenen σ zu entschärfen
- Ausgedrucktes Muster auf mattem Papier als Alternative zum iPad (kein Display-Einfluss)
- **Entfernung nutzen:** Fokusentfernung, bekannte Größen (Buchstabenhöhe), zwei Kameras
  (Stereo) oder GPS + Karte (Kirchen in Hamburg) – für das Kameramodell
- **Fokusreihe:** mehrere Fokusstufen kombinieren
- Unschärfe je Bildbereich auch beim Entschärfen berücksichtigen (nicht nur prüfen)
- Starke KI auf den Grafikchip bringen (heute oft nur Prozessor → langsam)

---

## 6. Versionsgeschichte (kurz)
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
| 1.0.1 | Vorschau passt sich an, Auslöser nie abgeschnitten |
| 1.2.1 | Diagnosebild aller Rechenschritte; Kalibrierung ansehen; Fehler „Knöpfe weg“ nach dem Kalibrieren behoben |
| 1.2 | Kalibrierung überarbeitet: Anweisungen unten, robuste Graustufen-Erkennung (schräg, Perspektive, ausgebrannt), überspringbar, schneller (nur Rahmen, 12 Bilder), Belichtung −1 Blende |
| 1.1 | Kalibrierung mit Testmuster auf dem iPad: Schärfeprofil je Bildbereich, Farbe und Richtung; Graustufen-Prüfung (Linearität, Streulicht); Messfehler der Kantenmessung korrigiert |
