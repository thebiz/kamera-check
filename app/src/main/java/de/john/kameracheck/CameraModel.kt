package de.john.kameracheck

import kotlin.math.sqrt

/**
 * Kameramodell: Wie unscharf MUSS das Bild mindestens sein? (in RAW-Pixeln, als Gauß-σ)
 *
 * Unabhängige Unschärfe-Quellen addieren sich quadratisch:
 * - Beugung an der Blende: Airy-Scheibe, als Gauß σ ≈ 0,42 · λ · N   (λ = 0,55 µm, grün)
 * - Pixelfläche: Ein Pixel mittelt über seine Fläche → σ = 1/√12 Pixel
 * - Mehrbild-Verrechnung: Verteil-Kern des Rasters (≈ 0,35 Pixel)
 * - Verwacklung während der Belichtung (Lagesensor): Strecke L → σ = L/√12
 * - Objektivfehler (unbekannt, Annahme ≈ 0,4 Pixel)
 *
 * Nutzen: Die gemessene Unschärfe darf nicht kleiner sein als dieses Minimum
 * (sonst ist die Messung falsch) und nicht beliebig größer (bei scharf gestelltem,
 * ebenem Motiv). So wird die Messung physikalisch eingegrenzt.
 */
object CameraModel {

    const val LAMBDA_UM = 0.55f
    const val LENS_RESIDUAL_PX = 0.4f
    const val MERGE_PX = 0.35f

    class Breakdown(
        val diffraction: Float, val pixel: Float, val merge: Float, val motion: Float, val lens: Float,
    ) {
        val total get() = sqrt(diffraction * diffraction + pixel * pixel + merge * merge + motion * motion + lens * lens)
    }

    /**
     * fNumber = Blendenzahl, pixelPitchUm = Pixelgröße in µm,
     * motionPx = Verwacklungsstrecke während der Belichtung in Pixeln (0 wenn unbekannt),
     * merged = true, wenn das Bild aus der Mehrbild-Verrechnung stammt.
     */
    fun minimum(fNumber: Float, pixelPitchUm: Float, motionPx: Float, merged: Boolean): Breakdown {
        val diff = 0.42f * LAMBDA_UM * fNumber / pixelPitchUm
        val pix = (1f / sqrt(12f))
        val merge = if (merged) MERGE_PX else 0f
        val motion = motionPx / sqrt(12f)
        return Breakdown(diff, pix, merge, motion, LENS_RESIDUAL_PX)
    }

    /** Wie viel mehr Unschärfe (RAW-Pixel) als das Minimum noch plausibel ist (Fokus-Restfehler, Objektiv am Rand). */
    const val PLAUSIBLE_EXTRA_PX = 2.5f
}
