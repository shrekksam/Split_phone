package com.ninja.splitlauncher;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Écran de sélection d'une application installée.
 *
 * v1.2 : RecyclerView (au lieu de ListView), chargement en arrière-plan via un
 * ExecutorService (au lieu d'un Thread brut), et icônes chargées À LA DEMANDE
 * dans l'adaptateur (plus de chargement massif de toutes les icônes d'un coup).
 */
public class AppSelector extends AppCompatActivity {

    public static final String EXTRA_PKG = "extra_pkg";
    public static final String EXTRA_ACT = "extra_act";
    public static final String EXTRA_LABEL = "extra_label";

    private final List<AppInfo> allApps = new ArrayList<>();
    private final List<AppInfo> filtered = new ArrayList<>();
    private AppAdapter adapter;
    private ProgressBar progress;
    private TextView emptyText;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_selector);
        setTitle(R.string.select_app);

        progress = findViewById(R.id.progress);
        emptyText = findViewById(R.id.emptyText);
        RecyclerView list = findViewById(R.id.appList);
        EditText search = findViewById(R.id.searchBox);

        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter();
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                applyFilter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadInstalledApps();
    }

    /** Charge la liste des applications lançables en arrière-plan. */
    private void loadInstalledApps() {
        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> resolved = pm.queryIntentActivities(main, 0);
            List<AppInfo> result = new ArrayList<>();
            String selfPkg = getPackageName();

            for (ResolveInfo ri : resolved) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (selfPkg.equals(pkg)) continue; // on ne se sélectionne pas soi-même
                String act = ri.activityInfo.name;
                String label;
                try {
                    label = ri.loadLabel(pm).toString();
                } catch (Exception e) {
                    label = pkg;
                }
                // Icône NON chargée ici : elle sera chargée à la demande (cache).
                result.add(new AppInfo(label, pkg, act, null));
            }

            Collections.sort(result, (x, y) -> x.label.compareToIgnoreCase(y.label));

            runOnUiThread(() -> {
                allApps.clear();
                allApps.addAll(result);
                applyFilter("");
                progress.setVisibility(View.GONE);
                emptyText.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void applyFilter(String query) {
        filtered.clear();
        String q = query == null ? "" : query.trim().toLowerCase();
        for (AppInfo app : allApps) {
            if (q.isEmpty()
                    || app.label.toLowerCase().contains(q)
                    || app.packageName.toLowerCase().contains(q)) {
                filtered.add(app);
            }
        }
        adapter.notifyDataSetChanged();
        if (emptyText != null) {
            emptyText.setVisibility(filtered.isEmpty() && !allApps.isEmpty()
                    ? View.VISIBLE : View.GONE);
        }
    }

    private void onAppPicked(AppInfo app) {
        Intent data = new Intent();
        data.putExtra(EXTRA_PKG, app.packageName);
        data.putExtra(EXTRA_ACT, app.activityName);
        data.putExtra(EXTRA_LABEL, app.label);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    /** Adaptateur RecyclerView de la liste des applications. */
    private class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            final TextView pkg;

            VH(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.appIcon);
                label = itemView.findViewById(R.id.appLabel);
                pkg = itemView.findViewById(R.id.appPackage);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(AppSelector.this)
                    .inflate(R.layout.item_app, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            AppInfo app = filtered.get(position);
            h.label.setText(app.label);
            h.pkg.setText(app.packageName);

            // Icône chargée à la demande (avec cache) : une copie indépendante.
            android.graphics.drawable.Drawable icon =
                    AppInfo.loadIcon(AppSelector.this, app.packageName, app.activityName);
            if (icon != null) h.icon.setImageDrawable(icon);
            else h.icon.setImageResource(R.mipmap.ic_launcher);

            h.itemView.setOnClickListener(v -> onAppPicked(app));
        }

        @Override
        public int getItemCount() {
            return filtered.size();
        }
    }
}
