package com.ninja.splitlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tests unitaires de la persistance du profil (Robolectric fournit le Context).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ProfileManagerTest {

    private Context ctx;
    private ProfileManager profile;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        profile = new ProfileManager(ctx);
    }

    @Test
    public void saveAndRestore_appsAndFlags() {
        AppInfo a = new AppInfo("YouTube", "com.google.android.youtube", "com.google.android.youtube.Main", null);
        AppInfo b = new AppInfo("Messages", "com.google.android.apps.messaging", "com.google.android.apps.messaging.Main", null);

        profile.save(a, b, true, false, true, true);

        AppInfo ra = profile.getAppA(ctx);
        AppInfo rb = profile.getAppB(ctx);
        assertEquals("com.google.android.youtube", ra.packageName);
        assertEquals("com.google.android.apps.messaging", rb.packageName);
        assertEquals("YouTube", ra.label);

        assertTrue(profile.isLockOrientation());
        assertFalse(profile.isLandscape());
        assertTrue(profile.isFloatingBar());
        assertTrue(profile.isAutoStart());
        assertTrue(profile.hasProfile());
    }

    @Test
    public void individualSetters_persist() {
        profile.setLockOrientation(true);
        profile.setLandscape(true);
        profile.setFloatingBar(true);
        profile.setAutoStart(true);

        assertTrue(profile.isLockOrientation());
        assertTrue(profile.isLandscape());
        assertTrue(profile.isFloatingBar());
        assertTrue(profile.isAutoStart());
    }

    @Test
    public void emptyProfile_hasNoApps() {
        assertNull(profile.getAppA(ctx));
        assertNull(profile.getAppB(ctx));
        assertFalse(profile.hasProfile());
    }

    // ------------------------------------------------------ MULTI-PROFILS

    @Test
    public void multipleProfiles_areStoredAndSwitchable() {
        // Le profil par défaut existe déjà.
        int initial = profile.getProfiles().size();

        SplitProfile second = new SplitProfile("Jeu + Chat");
        profile.addProfile(second);
        assertEquals(initial + 1, profile.getProfiles().size());

        // On bascule sur le second et on y enregistre une paire.
        profile.setActiveProfile(second.id);
        AppInfo a = new AppInfo("Maps", "com.google.android.apps.maps", "com.google.android.apps.maps.Main", null);
        AppInfo b = new AppInfo("Chat", "com.example.chat", "com.example.chat.Main", null);
        profile.saveActiveApps(a, b);

        assertEquals(second.id, profile.getActiveProfileId());
        assertEquals("com.google.android.apps.maps", profile.getAppA(ctx).packageName);
        assertEquals("com.example.chat", profile.getAppB(ctx).packageName);
    }

    @Test
    public void switchingProfiles_restoresTheirOwnApps() {
        SplitProfile p1 = profile.getActiveProfile();
        profile.saveActiveApps(
                new AppInfo("A1", "pkg.a1", "pkg.a1.Main", null),
                new AppInfo("B1", "pkg.b1", "pkg.b1.Main", null));

        SplitProfile p2 = new SplitProfile("Profil 2");
        profile.addProfile(p2);
        profile.setActiveProfile(p2.id);
        profile.saveActiveApps(
                new AppInfo("A2", "pkg.a2", "pkg.a2.Main", null),
                new AppInfo("B2", "pkg.b2", "pkg.b2.Main", null));

        // Retour au premier profil : on retrouve SES applis.
        profile.setActiveProfile(p1.id);
        assertEquals("pkg.a1", profile.getAppA(ctx).packageName);
        assertEquals("pkg.b1", profile.getAppB(ctx).packageName);
    }

    @Test
    public void deleteProfile_switchesActiveToRemaining() {
        SplitProfile p1 = profile.getActiveProfile();
        SplitProfile p2 = new SplitProfile("Profil 2");
        profile.addProfile(p2);
        profile.setActiveProfile(p2.id);

        profile.deleteProfile(p2.id);

        // Le profil actif est retombé sur un profil existant.
        assertFalse(p2.id.equals(profile.getActiveProfileId()));
        assertNotNull(profile.getActiveProfile());
    }

    @Test
    public void ratio_persists() {
        profile.setRatio(0.7f);
        assertEquals(0.7f, profile.getRatio(), 0.0001f);
    }
}
