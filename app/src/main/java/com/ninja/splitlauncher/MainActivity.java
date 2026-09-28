package com.ninja.splitlauncher;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Écran principal de SplitLauncher.
 *
 * v1.2 : l'état est exposé par {@link SplitViewModel} via LiveData ; l'activité
 * ne fait qu'OBSERVER ce qu'elle affiche (plus de rafraîchissement manuel
 * dispersé). Les résultats d'activités passent par l'Activity Result API (plus
 * de startActivityForResult/onActivityResult). Tous les textes viennent de
 * strings.xml (i18n) et chaque élément interactif a un contentDescription.
 *
 * Interface :
 *   ┌─────────────────────┐
 *   │ App 1               │
 *   ├──── poignée ────────┤
 *   │ App 2               │
 *   └─────────────────────┘
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_SHIZUKU = 2001;
    private static final int REQ_NOTIF = 2002;

    // Ratio : 50/50 (0.5) → 80/20 (0.8), par pas de 1/20.
    private static final float RATIO_MIN = 0.5f;
    private static final float RATIO_MAX = 0.8f;
    private static final int RATIO_STEPS = 20;

    private ImageView iconA, iconB;
    private TextView nameA, nameB, statusText, accessibilityStatus, ratioLabel, ratioNote;
    private MaterialButton btnLock, btnLandscape, btnFloating, btnAutostart, btnRoot;
    private Spinner profileSpinner;
    private SeekBar ratioSeek;

    private SplitViewModel vm;

    /** Adaptateur des profils (reconstruit à chaque changement de la liste). */
    private ArrayAdapter<String> profileAdapter;
    private final List<SplitProfile> profileList = new ArrayList<>();
    /** Empêche le spinner de rejouer une sélection lors d'un setSelection() programmatique. */
    private boolean suppressSpinnerCallback = false;

    // ----------------------------------------------------- ACTIVITY RESULT API

    private final ActivityResultLauncher<Intent> pickAppA =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> onAppPicked(result.getResultCode(), result.getData(), true));

    private final ActivityResultLauncher<Intent> pickAppB =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> onAppPicked(result.getResultCode(), result.getData(), false));

    private final ActivityResultLauncher<Intent> overlayPermission =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> onOverlayResult());

    /** Résultat de la demande de permission Shizuku. */
    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener shizukuListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQ_SHIZUKU) return;
                DeviceSupport.resetShellCache();
                updateAccessibilityStatus();
                updateRatioCapability();
                toast(getString(grantResult == PackageManager.PERMISSION_GRANTED
                        ? R.string.toast_shizuku_available
                        : R.string.toast_shizuku_unavailable));
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        vm = new ViewModelProvider(this).get(SplitViewModel.class);

        bindViews();
        wireListeners();
        observeViewModel();

        requestNotificationPermissionIfNeeded();
        updateAccessibilityStatus();
    }

    // ---------------------------------------------------------------- BINDING

    private void bindViews() {
        iconA = findViewById(R.id.iconA);
        iconB = findViewById(R.id.iconB);
        nameA = findViewById(R.id.nameA);
        nameB = findViewById(R.id.nameB);
        statusText = findViewById(R.id.statusText);
        accessibilityStatus = findViewById(R.id.accessibilityStatus);
        ratioLabel = findViewById(R.id.ratioLabel);
        ratioNote = findViewById(R.id.ratioNote);

        btnLock = findViewById(R.id.btnLock);
        btnLandscape = findViewById(R.id.btnLandscape);
        btnFloating = findViewById(R.id.btnFloating);
        btnAutostart = findViewById(R.id.btnAutostart);
        btnRoot = findViewById(R.id.btnRoot);

        profileSpinner = findViewById(R.id.profileSpinner);
        ratioSeek = findViewById(R.id.ratioSeek);
    }

    private void wireListeners() {
        // --- Sélection des applications (Activity Result API) ---
        findViewById(R.id.slotA).setOnClickListener(v ->
                pickAppA.launch(new Intent(this, AppSelector.class)));
        findViewById(R.id.slotB).setOnClickListener(v ->
                pickAppB.launch(new Intent(this, AppSelector.class)));

        // --- Poignée : inverser A et B ---
        findViewById(R.id.handle).setOnClickListener(v -> {
            vm.swapApps();
            toast(getString(R.string.toast_apps_swapped));
        });

        // --- Lancer ---
        findViewById(R.id.btnLaunch).setOnClickListener(v -> launchSplit());

        // --- Mémoire explicite (les sélections sont déjà persistées) ---
        findViewById(R.id.btnSave).setOnClickListener(v -> {
            vm.saveProfile();
            toast(getString(R.string.toast_profile_saved));
        });

        // --- Toggles ---
        btnLock.setOnClickListener(v -> {
            vm.toggleLock();
            applyOrientation();
            toast(getString(vm.isLockOrientation()
                    ? R.string.toast_lock_on : R.string.toast_lock_off));
        });
        btnLandscape.setOnClickListener(v -> {
            vm.toggleLandscape();
            applyOrientation();
            toast(getString(vm.isLandscape()
                    ? R.string.toast_landscape_on : R.string.toast_landscape_off));
        });
        btnFloating.setOnClickListener(v -> toggleFloating());
        btnAutostart.setOnClickListener(v -> {
            vm.toggleAutostart();
            toast(getString(vm.isAutoStart()
                    ? R.string.toast_autostart_on : R.string.toast_autostart_off));
        });

        // --- Profils ---
        setupProfileSpinner();
        findViewById(R.id.btnNewProfile).setOnClickListener(v -> promptNewProfile());
        findViewById(R.id.btnRenameProfile).setOnClickListener(v -> promptRenameProfile());
        findViewById(R.id.btnDeleteProfile).setOnClickListener(v -> promptDeleteProfile());

        // --- Accessibilité / Shizuku ---
        findViewById(R.id.btnAccessibility).setOnClickListener(v ->
                startActivity(SplitAccessibilityService.settingsIntent(this)));
        findViewById(R.id.btnShizuku).setOnClickListener(v -> requestShizuku());

        // --- Ratio ---
        setupRatioSeek();
    }

    private void setupProfileSpinner() {
        profileAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new ArrayList<>());
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(profileAdapter);

        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressSpinnerCallback) return;
                if (position < 0 || position >= profileList.size()) return;
                SplitProfile selected = profileList.get(position);
                String activeId = vm.activeProfileId().getValue();
                if (selected.id != null && !selected.id.equals(activeId)) {
                    vm.selectProfile(selected.id);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void setupRatioSeek() {
        ratioSeek.setMax(RATIO_STEPS);
        ratioSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float ratio = RATIO_MIN + (RATIO_MAX - RATIO_MIN) * progress / RATIO_STEPS;
                updateRatioLabel(ratio);
                if (fromUser) {
                    // Sauvegarde immédiate (cohérence UI ↔ profil).
                    vm.setRatio(ratio);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
    }

    // --------------------------------------------------------------- OBSERVERS

    private void observeViewModel() {
        vm.appA().observe(this, app -> refreshSlot(iconA, nameA, app));
        vm.appB().observe(this, app -> refreshSlot(iconB, nameB, app));

        vm.lockOrientation().observe(this, v -> {
            styleToggle(btnLock, Boolean.TRUE.equals(v));
            updateStatus();
        });
        vm.landscape().observe(this, v -> {
            styleToggle(btnLandscape, Boolean.TRUE.equals(v));
            updateStatus();
        });
        vm.floatingBar().observe(this, v -> {
            styleToggle(btnFloating, Boolean.TRUE.equals(v));
            updateStatus();
        });
        vm.autoStart().observe(this, v -> {
            styleToggle(btnAutostart, Boolean.TRUE.equals(v));
            updateStatus();
        });
        vm.useRoot().observe(this, v -> {
            styleToggle(btnRoot, Boolean.TRUE.equals(v));
            updateRatioCapability();
        });

        vm.ratio().observe(this, ratio -> {
            float r = ratio == null ? RATIO_MIN : ratio;
            int progress = Math.round((r - RATIO_MIN) / (RATIO_MAX - RATIO_MIN) * RATIO_STEPS);
            if (progress < 0) progress = 0;
            if (progress > RATIO_STEPS) progress = RATIO_STEPS;
            if (ratioSeek.getProgress() != progress) ratioSeek.setProgress(progress);
            updateRatioLabel(r);
        });

        vm.profiles().observe(this, this::renderProfiles);
        vm.activeProfileId().observe(this, this::selectSpinnerById);
    }

    private void renderProfiles(List<SplitProfile> profiles) {
        profileList.clear();
        if (profiles != null) profileList.addAll(profiles);

        List<String> names = new ArrayList<>();
        for (SplitProfile p : profileList) names.add(p.displayName());

        profileAdapter.clear();
        profileAdapter.addAll(names);
        profileAdapter.notifyDataSetChanged();

        selectSpinnerById(vm.activeProfileId().getValue());
    }

    private void selectSpinnerById(String id) {
        if (id == null) return;
        for (int i = 0; i < profileList.size(); i++) {
            if (id.equals(profileList.get(i).id)) {
                if (profileSpinner.getSelectedItemPosition() != i) {
                    suppressSpinnerCallback = true;
                    profileSpinner.setSelection(i);
                    suppressSpinnerCallback = false;
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------ APP PICKING

    private void onAppPicked(int resultCode, Intent data, boolean isSlotA) {
        if (resultCode != RESULT_OK || data == null) return;
        String pkg = data.getStringExtra(AppSelector.EXTRA_PKG);
        String act = data.getStringExtra(AppSelector.EXTRA_ACT);
        String label = data.getStringExtra(AppSelector.EXTRA_LABEL);
        AppInfo picked = AppInfo.fromProfile(this, pkg, act, label);
        if (picked == null) return;

        // Sauvegarde immédiate à la sélection (plus d'attente du bouton Enregistrer).
        if (isSlotA) vm.setAppA(picked);
        else vm.setAppB(picked);
    }

    // -------------------------------------------------------------- LIFECYCLE

    @Override
    protected void onResume() {
        super.onResume();

        // Les apps enregistrées sont-elles toujours installées ?
        String warning = vm.validateInstalledApps();
        if (warning != null) toast(warning);

        if (vm.isFloatingBar() && WindowManagerHelper.canDrawOverlays(this)) {
            startFloatingService();
        }

        // L'utilisateur a pu activer l'accessibilité ou accorder Shizuku entre-temps :
        // on réinitialise le cache shell et on rafraîchit l'état affiché.
        DeviceSupport.resetShellCache();
        updateAccessibilityStatus();
        updateRatioCapability();
        applyOrientation();

        // Raccourcis dynamiques par profil (épinglables).
        ShortcutHelper.publishProfileShortcuts(this);
    }

    // -------------------------------------------------------------- ACTIONS

    private void launchSplit() {
        if (!vm.hasBothApps()) {
            toast(getString(R.string.toast_select_two));
            return;
        }
        if (vm.sameApps()) {
            toast(getString(R.string.toast_same_app));
            return;
        }
        toast(getString(R.string.toast_launching));

        SplitController.launchSplit(this, vm.getAppA(), vm.getAppB(),
                vm.isLandscape(), vm.getRatio(),
                (outcome, msg) -> {
                    statusText.setText(msg);
                    if (outcome == SplitEngine.Outcome.ASSISTED
                            || outcome == SplitEngine.Outcome.FAILED) {
                        // Honnête : pas de faux « Split lancé ».
                        maybeOfferAssistedMode();
                    }
                });
    }

    /** Propose le mode assisté (guide vers le split natif). */
    private void maybeOfferAssistedMode() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.assisted_mode_title)
                .setMessage(R.string.assisted_mode_message)
                .setPositiveButton(R.string.assisted_mode_ok, null)
                .show();
    }

    private void toggleFloating() {
        vm.toggleFloating();
        if (vm.isFloatingBar()) {
            if (!WindowManagerHelper.canDrawOverlays(this)) {
                toast(getString(R.string.toast_overlay_needed));
                overlayPermission.launch(WindowManagerHelper.overlayPermissionIntent(this));
                return;
            }
            startFloatingService();
            toast(getString(R.string.toast_floating_on));
        } else {
            stopService(new Intent(this, FloatingBarService.class));
            toast(getString(R.string.toast_floating_off));
        }
    }

    private void onOverlayResult() {
        if (WindowManagerHelper.canDrawOverlays(this)) {
            startFloatingService();
        } else {
            vm.setFloatingBar(false);
            toast(getString(R.string.toast_overlay_needed));
        }
    }

    private void startFloatingService() {
        Intent svc = new Intent(this, FloatingBarService.class);
        svc.setAction(FloatingBarService.ACTION_SHOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    private void requestShizuku() {
        if (ShizukuBridge.isAvailable()) {
            DeviceSupport.resetShellCache();
            updateAccessibilityStatus();
            updateRatioCapability();
            toast(getString(R.string.toast_shizuku_available));
            return;
        }
        ShizukuBridge.requestPermission(REQ_SHIZUKU);
        toast(getString(R.string.toast_shizuku_prompt));
    }

    /**
     * Le ratio personnalisé n'est applicable QUE via le chemin shell
     * (Shizuku/root). Avec la seule accessibilité, le système impose 50/50 :
     * on masque alors le curseur et on affiche une note explicative.
     */
    private void updateRatioCapability() {
        boolean supported = DeviceSupport.canUseShellSplit(this);
        ratioSeek.setEnabled(supported);
        ratioSeek.setAlpha(supported ? 1f : 0.4f);
        ratioNote.setVisibility(supported ? View.GONE : View.VISIBLE);
    }

    private void applyOrientation() {
        WindowManagerHelper.applyOrientation(this, vm.isLandscape(), vm.isLockOrientation());
    }

    // -------------------------------------------------------------- PROFILES UI

    private void promptNewProfile() {
        EditText input = nameInput("");
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_new_profile_title)
                .setView(input)
                .setPositiveButton(R.string.btn_ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = getString(R.string.profile_default_name);
                    vm.addProfile(name);
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void promptRenameProfile() {
        String id = vm.activeProfileId().getValue();
        if (id == null) return;
        SplitProfile current = findProfile(id);
        String initial = current != null ? current.name : "";
        EditText input = nameInput(initial);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_rename_profile_title)
                .setView(input)
                .setPositiveButton(R.string.btn_ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) vm.renameProfile(id, name);
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void promptDeleteProfile() {
        String id = vm.activeProfileId().getValue();
        if (id == null) return;
        if (profileList.size() <= 1) {
            toast(getString(R.string.toast_cannot_delete_last));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_delete_profile_title)
                .setMessage(R.string.dialog_delete_profile_message)
                .setPositiveButton(R.string.btn_ok, (d, w) -> vm.deleteProfile(id))
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private EditText nameInput(String initial) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.dialog_profile_name_hint);
        input.setText(initial);
        return input;
    }

    private SplitProfile findProfile(String id) {
        for (SplitProfile p : profileList) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    // -------------------------------------------------------------- UI STATE

    private void refreshSlot(ImageView icon, TextView name, AppInfo app) {
        if (app != null) {
            android.graphics.drawable.Drawable drawable =
                    AppInfo.loadIcon(this, app.packageName, app.activityName);
            if (drawable != null) icon.setImageDrawable(drawable);
            else icon.setImageResource(R.mipmap.ic_launcher);
            name.setText(app.label);
        } else {
            icon.setImageDrawable(null);
            name.setText(R.string.tap_to_choose);
        }
    }

    private void styleToggle(MaterialButton b, boolean on) {
        b.setBackgroundTintList(ColorStateList.valueOf(
                on ? getColor(R.color.accent) : Color.TRANSPARENT));
        b.setStrokeColor(ColorStateList.valueOf(
                on ? getColor(R.color.accent) : getColor(R.color.divider)));
        b.setTextColor(on ? Color.WHITE : getColor(R.color.text_primary));
    }

    private void updateStatus() {
        AppInfo a = vm.getAppA();
        AppInfo b = vm.getAppB();
        String none = getString(R.string.status_none);
        String line1 = getString(R.string.status_apps_format,
                a != null ? a.label : none,
                b != null ? b.label : none);

        String orientation;
        if (vm.isLandscape()) orientation = getString(R.string.status_orientation_landscape);
        else if (vm.isLockOrientation()) orientation = getString(R.string.status_orientation_locked);
        else orientation = getString(R.string.status_orientation_free);

        String line2 = getString(R.string.status_line_format,
                orientation,
                getString(vm.isFloatingBar() ? R.string.status_on : R.string.status_off),
                getString(vm.isAutoStart() ? R.string.status_on : R.string.status_off));

        statusText.setText(line1 + "\n" + line2);
    }

    private void updateRatioLabel(float ratio) {
        int left = Math.round(ratio * 100f);
        int right = 100 - left;
        ratioLabel.setText(getString(R.string.ratio_format, left, right));
    }

    private void updateAccessibilityStatus() {
        boolean enabled = SplitAccessibilityService.isEnabled(this);
        accessibilityStatus.setText(enabled
                ? R.string.accessibility_enabled : R.string.accessibility_disabled);
        accessibilityStatus.setTextColor(getColor(
                enabled ? R.color.ok : R.color.text_secondary));
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
