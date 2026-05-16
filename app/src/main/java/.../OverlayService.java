package com.example.mycustomapk;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class OverlayService extends Service {

    private WindowManager windowManager;
    private View iconView, menuView;
    private WindowManager.LayoutParams iconParams, menuParams;

    private Spinner spnApps;
    private EditText edtSearch, edtNewValue;
    private Button btnFirstScan, btnNextScan, btnWrite, btnClose, btnIcon;

    private List<String> spinnerItems = new ArrayList<>();
    private List<String> packageNames = new ArrayList<>();

    static {
        System.loadLibrary("memory_engine");
    }
    public native int firstScan(int pid, int targetValue);
    public native int nextScan(int pid, int targetValue);
    public native int writeNewValue(int pid, int newValue);

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = LayoutInflater.from(this);

        iconView = inflater.inflate(R.layout.overlay_icon, null);
        menuView = inflater.inflate(R.layout.overlay_menu, null);

        int layoutFlag = (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

        // Küçük ikon pozisyon parametreleri
        iconParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        );
        iconParams.gravity = Gravity.TOP | Gravity.START;
        iconParams.x = 100;
        iconParams.y = 200;

        // Geniş menü parametreleri (Yazı yazabilmek için FOCUSABLE olmalı)
        menuParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag, WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM, PixelFormat.TRANSLUCENT
        );
        menuParams.gravity = Gravity.CENTER;

        windowManager.addView(iconView, iconParams);
        setupMenuComponents();
        setupTouchMovement();
    }

    private void setupMenuComponents() {
        spnApps = menuView.findViewById(R.id.spnOverlayApps);
        edtSearch = menuView.findViewById(R.id.edtOverlaySearch);
        edtNewValue = menuView.findViewById(R.id.edtOverlayNewValue);
        btnFirstScan = menuView.findViewById(R.id.btnFirstScan);
        btnNextScan = menuView.findViewById(R.id.btnNextScan);
        btnWrite = menuView.findViewById(R.id.btnOverlayWrite);
        btnClose = menuView.findViewById(R.id.btnCloseMenu);
        btnIcon = iconView.findViewById(R.id.btnFloatingIcon);

        loadInstalledApps();

        // İkona tıklayınca menüyü aç
        btnIcon.setOnClickListener(v -> {
            windowManager.removeView(iconView);
            windowManager.addView(menuView, menuParams);
        });

        // X butonuna basınca menüyü kapat, ikonu aç
        btnClose.setOnClickListener(v -> {
            windowManager.removeView(menuView);
            windowManager.addView(iconView, iconParams);
        });

        btnFirstScan.setOnClickListener(v -> runScanAction(1));
        btnNextScan.setOnClickListener(v -> runScanAction(2));
        btnWrite.setOnClickListener(v -> runScanAction(3));
    }

    private void runScanAction(int mode) {
        try {
            int pos = spnApps.getSelectedItemPosition();
            int pid = findPid(packageNames.get(pos));
            if (pid <= 0) {
                Toast.makeText(this, "Oyun açık değil veya bulunamadı!", Toast.LENGTH_SHORT).show();
                return;
            }

            if (mode == 1) { // İlk Arama
                int val = Integer.parseInt(edtSearch.getText().toString());
                int count = firstScan(pid, val);
                Toast.makeText(this, "Bulunan Adres: " + count, Toast.LENGTH_SHORT).show();
            } else if (mode == 2) { // Sonraki Filtreleme
                int val = Integer.parseInt(edtSearch.getText().toString());
                int count = nextScan(pid, val);
                Toast.makeText(this, "Filtrelendi: " + count, Toast.LENGTH_SHORT).show();
            } else if (mode == 3) { // Değer Değiştirme
                int val = Integer.parseInt(edtNewValue.getText().toString());
                int count = writeNewValue(pid, val);
                Toast.makeText(this, count + " Adres Güncellendi!", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Lütfen sayıları kontrol edin!", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadInstalledApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo app : apps) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                spinnerItems.add(app.loadLabel(pm).toString());
                packageNames.add(app.packageName);
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, spinnerItems);
        spnApps.setAdapter(adapter);
    }

    private int findPid(String pack) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "pidof " + pack});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l = r.readLine();
            if (l != null) return Integer.parseInt(l.trim().split(" ")[0]);
        } catch (Exception ignored) {}
        return -1;
    }

    private void setupTouchMovement() {
        iconView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = iconParams.x; initialY = iconParams.y;
                        initialTouchX = event.getRawX(); initialTouchY = event.getRawY();
                        return false; // OnClick tetiklenebilmesi için false kalmalı
                    case MotionEvent.ACTION_MOVE:
                        iconParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        iconParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(iconView, iconParams);
                        return true;
                }
                return false;
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { windowManager.removeView(iconView); } catch (Exception ignored) {}
        try { windowManager.removeView(menuView); } catch (Exception ignored) {}
    }
}

