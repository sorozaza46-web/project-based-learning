package com.example.mycustomapk;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Spinner spnApps;
    private EditText edtManualPackage, edtSearch, edtNewValue;
    private Button btnApply;
    
    private List<String> spinnerItems = new ArrayList<>();
    private List<String> packageNames = new ArrayList<>();

    static {
        System.loadLibrary("memory_engine");
    }
    public native long[] searchMemory(int pid, int targetValue);
    public native boolean editMemory(int pid, long address, int newValue);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeRootCheck();

        spnApps = findViewById(R.id.spnApps);
        edtManualPackage = findViewById(R.id.edtManualPackage);
        edtSearch = findViewById(R.id.edtSearch);
        edtNewValue = findViewById(R.id.edtNewValue);
        btnApply = findViewById(R.id.btnApply);

        loadApplications();

        // Seçime göre manuel giriş alanını aktif/pasif yapma kontrolü
        spnApps.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    edtManualPackage.setEnabled(true);
                    edtManualPackage.setHint("Manuel Paket Adı Girin (Örn: com.example)");
                } else {
                    edtManualPackage.setEnabled(false);
                    edtManualPackage.setText("");
                    edtManualPackage.setHint("Seçilen Uygulama Otomatik Çözümlenecek");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                executeMemoryPatchRoutine();
            }
        });
    }

    private void loadApplications() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        spinnerItems.add("[Manuel Paket İsmi Gir (.com)]");
        packageNames.add("manual");

        List<ApplicationInfo> userApps = new ArrayList<>();
        for (ApplicationInfo app : packages) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                userApps.add(app);
            }
        }

        // Alfabetik sıralama
        Collections.sort(userApps, new Comparator<ApplicationInfo>() {
            @Override
            public int compare(ApplicationInfo o1, ApplicationInfo o2) {
                return o1.loadLabel(pm).toString().compareToIgnoreCase(o2.loadLabel(pm).toString());
            }
        });

        for (ApplicationInfo appInfo : userApps) {
            String name = appInfo.loadLabel(pm).toString();
            spinnerItems.add(name + " (" + appInfo.packageName + ")");
            packageNames.add(appInfo.packageName);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, spinnerItems);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnApps.setAdapter(adapter);
    }

    private void executeMemoryPatchRoutine() {
        try {
            String targetPackage = "";
            int selectedPosition = spnApps.getSelectedItemPosition();

            if (selectedPosition == 0) {
                // Kullanıcı manuel string girdiğinde
                targetPackage = edtManualPackage.getText().toString().trim();
                if (targetPackage.isEmpty()) {
                    Toast.makeText(this, "Lütfen geçerli bir paket adı girin!", Toast.LENGTH_SHORT).show();
                    return;
                }
            } else {
                // Listeden seçildiğinde
                targetPackage = packageNames.get(selectedPosition);
            }

            // Paket ismini işletim sistemi sürecine (PID) dönüştür
            int targetPid = findPidFromPackageName(targetPackage);

            if (targetPid <= 0) {
                Toast.makeText(this, "Uygulama arka planda çalışmıyor! Önce oyunu başlatın.", Toast.LENGTH_LONG).show();
                return;
            }

            int searchValue = Integer.parseInt(edtSearch.getText().toString());
            int newValue = Integer.parseInt(edtNewValue.getText().toString());

            startMemoryOperation(targetPid, searchValue, newValue);

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Değer alanlarını kontrol edin!", Toast.LENGTH_SHORT).show();
        }
    }

    // İsme göre Linux alt sisteminden süreç kimliğini çeken komut mimarisi
    private int findPidFromPackageName(String packageName) {
        int pid = -1;
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "pidof " + packageName});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            if (line != null && !line.trim().isEmpty()) {
                // Eğer uygulama birden fazla alt sürece sahipse ana süreci izole ediyoruz
                pid = Integer.parseInt(line.trim().split(" ")[0]);
            }
            reader.close();
            process.waitFor();
        } catch (Exception e) {
            pid = -1;
        }
        return pid;
    }

    private void initializeRootCheck() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("exit\n");
            os.flush();
            p.waitFor();
        } catch (Exception e) {
            Toast.makeText(this, "Root yetkisi alınamadı, hafıza fonksiyonları çalışmayabilir!", Toast.LENGTH_LONG).show();
        }
    }

    private void startMemoryOperation(int pid, int searchVal, int newVal) {
        Toast.makeText(this, "Hafıza taranıyor (Süreç: " + pid + ")...", Toast.LENGTH_SHORT).show();

        long[] addresses = searchMemory(pid, searchVal);
        
        if (addresses == null || addresses.length == 0) {
            Toast.makeText(this, "Belirtilen değer bellek haritasında bulunamadı!", Toast.LENGTH_SHORT).show();
            return;
        }

        int modifiedCount = 0;
        for (long address : addresses) {
            if (editMemory(pid, address, newVal)) {
                modifiedCount++;
            }
        }

        Toast.makeText(this, modifiedCount + " adreste veri başarıyla güncellendi!", Toast.LENGTH_LONG).show();
    }
}
