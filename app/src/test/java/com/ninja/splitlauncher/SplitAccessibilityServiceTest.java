package com.ninja.splitlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Tests du DEUXIÈME critère de vérification du split (v1.5) : deux fenêtres
 * d'application occupant deux RÉGIONS DISTINCTES de l'écran.
 *
 * Le premier critère (présence d'un TYPE_SPLIT_SCREEN_DIVIDER) n'est pas
 * testable hors appareil ; le second, lui, est une fonction PURE de géométrie :
 * {@link SplitAccessibilityService#hasTwoAppWindowsInDistinctRegions}.
 * Robolectric n'est utilisé que pour fournir l'implémentation de {@link Rect}.
 *
 * Repères utilisés :
 *   - portrait  : 1080 × 2400 (barre d'état 90 px, barre de navigation 120 px) ;
 *   - paysage   : 2400 × 1080 ;
 *   - diviseur  : ~40 px.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SplitAccessibilityServiceTest {

    private static final Rect PORTRAIT = new Rect(0, 0, 1080, 2400);
    private static final Rect LANDSCAPE = new Rect(0, 0, 2400, 1080);

    private static boolean split(Rect screen, Rect... windows) {
        return SplitAccessibilityService.hasTwoAppWindowsInDistinctRegions(
                Arrays.asList(windows), screen);
    }

    // ----------------------------------------------------------- VRAIS SPLITS

    @Test
    public void portraitSplit_topAndBottom_isDetected() {
        Rect top = new Rect(0, 90, 1080, 1180);      // 45,4 % de l'écran
        Rect bottom = new Rect(0, 1220, 1080, 2280); // 44,2 % de l'écran
        assertTrue(split(PORTRAIT, top, bottom));
    }

    @Test
    public void landscapeSplit_leftAndRight_isDetected() {
        Rect left = new Rect(0, 0, 1170, 1080);
        Rect right = new Rect(1230, 0, 2400, 1080);
        assertTrue(split(LANDSCAPE, left, right));
    }

    @Test
    public void unevenSplit_70_30_isDetected() {
        // Ratio 70/30 (chemin shell) : les moitiés ne sont pas égales.
        Rect top = new Rect(0, 90, 1080, 1700);
        Rect bottom = new Rect(0, 1740, 1080, 2280);
        assertTrue(split(PORTRAIT, top, bottom));
    }

    @Test
    public void splitWithKeyboardOpen_shrunkBottomHalf_isStillDetected() {
        // Clavier ouvert : la moitié basse est fortement réduite (la fenêtre du
        // clavier est de type INPUT_METHOD, elle n'arrive donc jamais ici).
        Rect top = new Rect(0, 90, 1080, 1180);
        Rect bottom = new Rect(0, 1220, 1080, 1560);
        assertTrue(split(PORTRAIT, top, bottom));
    }

    @Test
    public void splitPlusFloatingWindow_stillDetected() {
        // Une petite fenêtre supplémentaire (bulle, PiP non signalé…) ne doit
        // pas empêcher la détection : on cherche un COUPLE valide.
        Rect top = new Rect(0, 90, 1080, 1180);
        Rect bottom = new Rect(0, 1220, 1080, 2280);
        Rect floating = new Rect(700, 1500, 1050, 1700); // 2,7 % : ignorée
        assertTrue(split(PORTRAIT, top, bottom, floating));
    }

    @Test
    public void screenUnknown_fallsBackOnUnionOfWindows() {
        // Sans bornes d'écran, l'union des fenêtres sert de référence.
        Rect top = new Rect(0, 90, 1080, 1180);
        Rect bottom = new Rect(0, 1220, 1080, 2280);
        assertTrue(split(null, top, bottom));
        assertTrue(split(new Rect(), top, bottom)); // écran dégénéré = inconnu
    }

    // -------------------------------------------------------- FAUX POSITIFS

    @Test
    public void singleFullScreenWindow_isNotSplit() {
        assertFalse(split(PORTRAIT, new Rect(0, 90, 1080, 2280)));
    }

    @Test
    public void twoStackedFullScreenWindows_isNotSplit() {
        // Lanceur + application par-dessus : même région, pas un split.
        Rect launcher = new Rect(0, 90, 1080, 2280);
        Rect app = new Rect(0, 90, 1080, 2280);
        assertFalse(split(PORTRAIT, launcher, app));
    }

    @Test
    public void fullScreenAppPlusDialog_isNotSplit() {
        Rect app = new Rect(0, 90, 1080, 2280);
        Rect dialog = new Rect(100, 900, 980, 1400); // contenue dans l'app
        assertFalse(split(PORTRAIT, app, dialog));
    }

    @Test
    public void twoDistantWindows_areNotSplitHalves() {
        // Régions distinctes mais séparées par un vide énorme : ce n'est pas
        // un split (aucun diviseur ne fait 70 % de l'écran).
        Rect top = new Rect(0, 0, 1080, 700);
        Rect bottom = new Rect(0, 1700, 1080, 2400);
        assertFalse(split(PORTRAIT, top, bottom));
    }

    @Test
    public void misalignedNeighbours_areNotSplitHalves() {
        // Côte à côte mais l'une ne couvre qu'un tiers de la hauteur.
        Rect left = new Rect(0, 0, 1170, 1080);
        Rect right = new Rect(1230, 700, 2400, 1080);
        assertFalse(split(LANDSCAPE, left, right));
    }

    @Test
    public void twoSmallWindows_areNotSplitHalves() {
        // Deux petites fenêtres flottantes : surface cumulée trop faible.
        Rect a = new Rect(0, 100, 1080, 500);
        Rect b = new Rect(0, 540, 1080, 940);
        assertFalse(split(PORTRAIT, a, b));
    }

    @Test
    public void overlappingHalves_areNotDistinctRegions() {
        // Deux fenêtres crédibles en taille mais qui se recouvrent largement.
        Rect a = new Rect(0, 90, 1080, 1400);
        Rect b = new Rect(0, 1000, 1080, 2280);
        assertFalse(split(PORTRAIT, a, b));
    }

    // ------------------------------------------------------------- ROBUSTESSE

    @Test
    public void emptyOrSingleInput_isNeverSplit() {
        assertFalse(SplitAccessibilityService.hasTwoAppWindowsInDistinctRegions(null, PORTRAIT));
        assertFalse(SplitAccessibilityService.hasTwoAppWindowsInDistinctRegions(
                Collections.<Rect>emptyList(), PORTRAIT));
        assertFalse(split(PORTRAIT, new Rect(0, 90, 1080, 1180)));
    }

    @Test
    public void degenerateWindows_areIgnored() {
        Rect empty = new Rect();
        Rect flat = new Rect(0, 500, 1080, 500); // hauteur nulle
        Rect top = new Rect(0, 90, 1080, 1180);
        Rect bottom = new Rect(0, 1220, 1080, 2280);

        assertFalse(split(PORTRAIT, empty, flat));
        assertTrue(split(PORTRAIT, empty, top, flat, bottom));
    }

    @Test
    public void nullEntries_areIgnored() {
        List<Rect> windows = new ArrayList<>();
        windows.add(null);
        windows.add(new Rect(0, 90, 1080, 1180));
        windows.add(null);
        windows.add(new Rect(0, 1220, 1080, 2280));
        assertTrue(SplitAccessibilityService.hasTwoAppWindowsInDistinctRegions(windows, PORTRAIT));
    }

    // -------------------------------------------------------------- ÉTAT

    @Test
    public void splitState_eitherCriterionIsEnough() {
        SplitAccessibilityService.SplitState dividerOnly =
                new SplitAccessibilityService.SplitState(true, false, 1, true);
        SplitAccessibilityService.SplitState regionsOnly =
                new SplitAccessibilityService.SplitState(false, true, 2, true);
        SplitAccessibilityService.SplitState neither =
                new SplitAccessibilityService.SplitState(false, false, 1, true);

        assertTrue(dividerOnly.isSplit());   // critère n°1 seul
        assertTrue(regionsOnly.isSplit());   // critère n°2 seul
        assertFalse(neither.isSplit());
        assertTrue(neither.isVerifiable());
        assertEquals(2, regionsOnly.appWindowCount);
    }

    @Test
    public void splitState_unverifiable_isNeitherSplitNorTrusted() {
        SplitAccessibilityService.SplitState unknown =
                SplitAccessibilityService.SplitState.UNVERIFIABLE;

        // Aucune fenêtre lisible : on ne prétend PAS que le split est actif…
        assertFalse(unknown.isSplit());
        // …mais on sait que la réponse n'est pas fiable.
        assertFalse(unknown.isVerifiable());
    }
}
