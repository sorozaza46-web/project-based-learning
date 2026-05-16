package com.example.mycustomapk;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
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
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Spinner spnApps;
    private EditText edtSearch, edtNewValue;
    private Button btnApply;
    
    private List<String> appNames = new ArrayList<>();
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

        checkRootAccess();

        spnApps = findViewById(R.id.spnApps);
        edtSearch = findViewById(R.id.edtSearch);
        edtNewValue = findViewById(R.id.edtNewValue);
        btnApply = findViewById(R.id.btnApply);

        // Cihazdaki uygulamaları listele
        loadInstalledApplications();

        btnApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    // Seçilen uygulamanın paket adını al
                    int selectedIndex = spnApps.getSelectedItemPosition();
                    String targetPackage = packageNames.get(selectedIndex);

                    // Paket adından otomatik PID bul
                    int pid = getPidFromPackageName(targetPackage);

                    if (pid == -1) {
                        Toast.makeText(MainActivity.this, "Seçilen uygulama şu an çalışmıyor! Önce oyunu açın.", Toast.LENGTH_LONG).show();
                        return;
                    }

                    int searchValue = Integer.parseInt(edtSearch.getText().toString());
                    int newValue = Integer.parseInt(edtNewValue.getText().toString());

                    processMemoryManipulation(pid, searchValue, newValue);

                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Hata oluştu! Alanları kontrol edin.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    // Cihazdaki yüklü uygulamaları çeken fonksiyon
    private void loadInstalledApplications() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        for (ApplicationInfo packageInfo : packages) {
            // Sistem uygulamalarını eleyip sadece sonradan yüklenenleri listelemek için filtre
            if ((packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                String appName = packageInfo.loadLabel(pm).toString();
                appNames.add(appName + " (" + packageInfo.packageName + ")");
                packageNames.add(packageInfo.packageName);
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, appNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnApps.setAdapter(adapter);
    }

    // Root yetkisiyle çalışan uygulamalardan paket adına göre PID çeken kritik fonksiyon
    private int getPidFromPackageName(String packageName) {
        int pid = -1;
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "pidof " + packageName});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            if (line != null && !line.trim().isEmpty()) {
                // Eğer birden fazla alt süreç varsa ilk ana süreci alıyoruz
                pid = Integer.parseInt(line.trim().split(" ")[0]);
            }
            reader.close();
            process.waitFor();
        } catch (Exception e) {
            Log.e("MemoryTool", "PID bulunamadı", e);
        }
        return pid;
    }

    private void checkRootAccess() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("exit\n");
            os.flush();
            p.waitFor();
        } catch (Exception e) {
            Toast.makeText(this, "Root erişimi başarısız!", Toast.LENGTH_LONG).show();
        }
    }

    private void processMemoryManipulation(int pid, int searchValue, int newValue) {
        Toast.makeText(this, "PID: " + pid + " taranıyor...", Toast.LENGTH_SHORT).show();

        long[] foundAddresses = searchMemory(pid, searchValue);
        
        if (foundAddresses == null || foundAddresses.length == 0) {
            Toast.makeText(this, "Değer hafızada bulunamadı!", Toast.LENGTH_SHORT).show();
            return;
        }

        int successCount = 0;
        for (long addr : foundAddresses) {
            if (editMemory(pid, addr, newValue)) {
                successCount++;
            }
        }

        Toast.makeText(this, successCount + " adreste değişiklik yapıldı!", Toast.LENGTH_LONG).show();
    }
}
