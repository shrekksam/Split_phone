package com.ninja.splitlauncher;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;

/**
 * Détient l'état du Split et l'expose via LiveData (la vue n'observe que ce
 * qu'elle affiche). Toute modification est PERSISTÉE immédiatement : plus
 * d'incohérence entre l'UI et le profil utilisé par la barre flottante / le boot.
 */
public class SplitViewModel extends AndroidViewModel {

    private final ProfileManager profile;

    private final MutableLiveData<AppInfo> appA = new MutableLiveData<>();
    private final MutableLiveData<AppInfo> appB = new MutableLiveData<>();
    private final MutableLiveData<Boolean> lockOrientation = new MutableLiveData<>();
    private final MutableLiveData<Boolean> landscape = new MutableLiveData<>();
    private final MutableLiveData<Boolean> floatingBar = new MutableLiveData<>();
    private final MutableLiveData<Boolean> autoStart = new MutableLiveData<>();
    private final MutableLiveData<Float> ratio = new MutableLiveData<>();
    private final MutableLiveData<List<SplitProfile>> profiles = new MutableLiveData<>();
    private final MutableLiveData<String> activeProfileId = new MutableLiveData<>();
    private final MutableLiveData<Boolean> useRoot = new MutableLiveData<>();

    public SplitViewModel(@NonNull Application application) {
        super(application);
        profile = new ProfileManager(application);
        load();
    }

    /** Charge l'état depuis le profil persistant. */
    public void load() {
        appA.setValue(profile.getAppA(getApplication()));
        appB.setValue(profile.getAppB(getApplication()));
        lockOrientation.setValue(profile.isLockOrientation());
        landscape.setValue(profile.isLandscape());
        floatingBar.setValue(profile.isFloatingBar());
        autoStart.setValue(profile.isAutoStart());
        useRoot.setValue(profile.isUseRoot());
        ratio.setValue(profile.getRatio());
        refreshProfiles();
    }

    public void refreshProfiles() {
        profiles.setValue(profile.getProfiles());
        activeProfileId.setValue(profile.getActiveProfileId());
    }

    // ------------------------------------------------------------- GETTERS

    public LiveData<AppInfo> appA() { return appA; }
    public LiveData<AppInfo> appB() { return appB; }
    public LiveData<Boolean> lockOrientation() { return lockOrientation; }
    public LiveData<Boolean> landscape() { return landscape; }
    public LiveData<Boolean> floatingBar() { return floatingBar; }
    public LiveData<Boolean> autoStart() { return autoStart; }
    public LiveData<Float> ratio() { return ratio; }
    public LiveData<List<SplitProfile>> profiles() { return profiles; }
    public LiveData<String> activeProfileId() { return activeProfileId; }
    public LiveData<Boolean> useRoot() { return useRoot; }

    public AppInfo getAppA() { return appA.getValue(); }
    public AppInfo getAppB() { return appB.getValue(); }
    public boolean isLockOrientation() { return Boolean.TRUE.equals(lockOrientation.getValue()); }
    public boolean isLandscape() { return Boolean.TRUE.equals(landscape.getValue()); }
    public boolean isFloatingBar() { return Boolean.TRUE.equals(floatingBar.getValue()); }
    public boolean isAutoStart() { return Boolean.TRUE.equals(autoStart.getValue()); }
    public boolean isUseRoot() { return Boolean.TRUE.equals(useRoot.getValue()); }
    public float getRatio() { return ratio.getValue() == null ? 0.5f : ratio.getValue(); }

    // ------------------------------------------------------------- SETTERS
    // Sauvegarde immédiate à chaque sélection.

    public void setAppA(AppInfo app) {
        appA.setValue(app);
        profile.saveActiveApps(app, getAppB());
    }

    public void setAppB(AppInfo app) {
        appB.setValue(app);
        profile.saveActiveApps(getAppA(), app);
    }

    public void swapApps() {
        AppInfo tmp = getAppA();
        appA.setValue(getAppB());
        appB.setValue(tmp);
        profile.saveActiveApps(getAppA(), getAppB());
    }

    // ------------------------------------------------------------- TOGGLES

    public void toggleLock() {
        boolean v = !isLockOrientation();
        lockOrientation.setValue(v);
        if (v) { landscape.setValue(false); profile.setLandscape(false); }
        profile.setLockOrientation(v);
    }

    public void toggleLandscape() {
        boolean v = !isLandscape();
        landscape.setValue(v);
        if (v) { lockOrientation.setValue(false); profile.setLockOrientation(false); }
        profile.setLandscape(v);
    }

    public void toggleFloating() {
        boolean v = !isFloatingBar();
        floatingBar.setValue(v);
        profile.setFloatingBar(v);
    }

    public void toggleAutostart() {
        boolean v = !isAutoStart();
        autoStart.setValue(v);
        profile.setAutoStart(v);
    }

    public void toggleUseRoot() {
        boolean v = !isUseRoot();
        useRoot.setValue(v);
        profile.setUseRoot(v);
    }

    public void setFloatingBar(boolean value) {
        floatingBar.setValue(value);
        profile.setFloatingBar(value);
    }

    public void setRatio(float value) {
        ratio.setValue(value);
        profile.setRatio(value);
    }

    // ------------------------------------------------------------- PROFILS

    public void selectProfile(String id) {
        profile.setActiveProfile(id);
        load();
    }

    public void addProfile(String name) {
        SplitProfile p = new SplitProfile(name);
        profile.addProfile(p);
        profile.setActiveProfile(p.id);
        load();
    }

    public void renameProfile(String id, String name) {
        List<SplitProfile> list = profile.getProfiles();
        for (SplitProfile p : list) {
            if (p.id != null && p.id.equals(id)) {
                p.name = name;
                profile.updateProfile(p);
                break;
            }
        }
        refreshProfiles();
    }

    public void deleteProfile(String id) {
        profile.deleteProfile(id);
        load();
    }

    public void saveProfile() {
        profile.saveActiveApps(getAppA(), getAppB());
        refreshProfiles();
    }

    public boolean hasBothApps() {
        return getAppA() != null && getAppB() != null;
    }

    public boolean sameApps() {
        AppInfo a = getAppA();
        return a != null && a.isSame(getAppB());
    }

    /**
     * Vérifie que les applis enregistrées sont toujours installées.
     * Vide les slots concernés et renvoie un message utilisateur, ou null si OK.
     */
    public String validateInstalledApps() {
        StringBuilder missing = new StringBuilder();
        AppInfo a = getAppA();
        AppInfo b = getAppB();
        boolean changed = false;

        if (a != null && !IntentLauncher.isLaunchable(getApplication(), a)) {
            missing.append("A (").append(a.label).append(") ");
            appA.setValue(null);
            changed = true;
        }
        if (b != null && !IntentLauncher.isLaunchable(getApplication(), b)) {
            missing.append("B (").append(b.label).append(") ");
            appB.setValue(null);
            changed = true;
        }
        if (changed) {
            profile.saveActiveApps(getAppA(), getAppB());
            return "Application désinstallée : " + missing.toString().trim()
                    + " — veuillez la resélectionner.";
        }
        return null;
    }
}
