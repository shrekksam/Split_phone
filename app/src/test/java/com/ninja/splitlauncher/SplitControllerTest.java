package com.ninja.splitlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tests unitaires de la géométrie du Split (fonction pure computeHalves).
 * Utilise Robolectric uniquement pour fournir l'implémentation de Rect.
 *
 * v1.2 : la découpe dépend de l'ORIENTATION RÉELLE (width > height) et d'un
 * RATIO réglable (0.5 = 50/50, 0.7 = 70/30).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SplitControllerTest {

    // ------------------------------------------------------- ORIENTATION

    @Test
    public void landscape_splitsLeftRight() {
        Rect[] halves = SplitController.computeHalves(1000, 800, true);
        assertNotNull(halves);
        assertEquals(2, halves.length);

        assertEquals(0, halves[0].left);
        assertEquals(0, halves[0].top);
        assertEquals(500, halves[0].right);
        assertEquals(800, halves[0].bottom);

        assertEquals(500, halves[1].left);
        assertEquals(0, halves[1].top);
        assertEquals(1000, halves[1].right);
        assertEquals(800, halves[1].bottom);
    }

    @Test
    public void portrait_splitsTopBottom() {
        // Écran portrait : hauteur > largeur.
        Rect[] halves = SplitController.computeHalves(800, 1000, false);
        assertEquals(2, halves.length);

        assertEquals(0, halves[0].left);
        assertEquals(0, halves[0].top);
        assertEquals(800, halves[0].right);
        assertEquals(500, halves[0].bottom);

        assertEquals(0, halves[1].left);
        assertEquals(500, halves[1].top);
        assertEquals(800, halves[1].right);
        assertEquals(1000, halves[1].bottom);
    }

    /**
     * Correctif v1.2 : même si le réglage « paysage permanent » est OFF, une
     * largeur > hauteur (appareil physiquement en paysage) doit produire une
     * découpe gauche/droite — on se base sur l'orientation RÉELLE.
     */
    @Test
    public void wideScreen_splitsLeftRight_evenWhenLandscapeSettingOff() {
        Rect[] halves = SplitController.computeHalves(1920, 1080, false, 0.5f);
        // Découpe verticale : la 1re moitié est à gauche, la 2e à droite.
        assertEquals(0, halves[0].left);
        assertEquals(960, halves[0].right);
        assertEquals(960, halves[1].left);
        assertEquals(1920, halves[1].right);
        // Toute la hauteur est conservée.
        assertEquals(1080, halves[0].bottom);
    }

    @Test
    public void landscapeSetting_forcesLeftRight_evenOnTallScreen() {
        // Le réglage « paysage permanent » est un override explicite : il force
        // la découpe gauche/droite même si les dimensions courantes sont portrait.
        Rect[] halves = SplitController.computeHalves(1080, 1920, true, 0.5f);
        assertEquals(0, halves[0].left);
        assertEquals(540, halves[0].right);
        assertEquals(540, halves[1].left);
        assertEquals(1080, halves[1].right);
        assertEquals(1920, halves[0].bottom);
    }

    // ------------------------------------------------------------ RATIO

    @Test
    public void ratio70_30_splitsAsymmetric() {
        Rect[] halves = SplitController.computeHalves(1000, 800, true, 0.7f);
        // 70 % pour A, 30 % pour B.
        assertEquals(0, halves[0].left);
        assertEquals(700, halves[0].right);
        assertEquals(700, halves[1].left);
        assertEquals(1000, halves[1].right);
    }

    @Test
    public void ratio50_50_isSymmetric() {
        Rect[] halves = SplitController.computeHalves(1000, 800, true, 0.5f);
        assertEquals(500, halves[0].right);
        assertEquals(500, halves[1].left);
    }

    @Test
    public void invalidRatio_fallsBackToFiftyFifty() {
        Rect[] zero = SplitController.computeHalves(1000, 800, true, 0f);
        assertEquals(500, zero[0].right);

        Rect[] one = SplitController.computeHalves(1000, 800, true, 1f);
        assertEquals(500, one[0].right);
    }

    // ------------------------------------------------------------ TILING

    @Test
    public void halves_tileTheWholeScreen_withoutGapOrOverlap() {
        Rect[] halves = SplitController.computeHalves(1080, 1920, true);
        assertEquals(halves[0].right, halves[1].left);
        assertEquals(0, halves[0].left);
        assertEquals(1080, halves[1].right);
    }

    @Test
    public void oddDimensions_doNotLoseTheCenterRow() {
        // Portrait (hauteur > largeur) : découpe sur la hauteur.
        // Math.round(999 * 0.5) = 500 : les deux moitiés se rejoignent sans trou.
        Rect[] halves = SplitController.computeHalves(801, 999, false);
        int expected = Math.round(999 * 0.5f);
        assertEquals(expected, halves[0].bottom);
        assertEquals(expected, halves[1].top);
        assertEquals(halves[0].bottom, halves[1].top);
    }

    @Test
    public void ratio_keepsCoverage() {
        Rect[] halves = SplitController.computeHalves(1000, 800, true, 0.7f);
        // Aucun trou, aucune superposition : la borne partagée est identique.
        assertTrue(halves[0].right == halves[1].left);
        assertEquals(0, halves[0].left);
        assertEquals(1000, halves[1].right);
    }
}
