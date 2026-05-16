package com.example.mycustomapk;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.DataOutputStream;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private EditText edtPid, edtSearch, edtNewValue;
    private Button btnApply;

    // C++ motoruyla doğrudan konuşacak olan yerel fonksiyonları tanımlıyoruz
    static {
        System.loadLibrary("memory_engine");
    }
    public native long[] searchMemory(int pid, int targetValue);
    public native boolean editMemory(int pid, long address, int newValue);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Not: Eğer XML arayüzü oluşturmadıysanız, kodun hata vermemesi için basit bir yerleşim düzeni kuruyoruz.
        setContentView(R.layout.activity_main);

        // Root izni kontrolü ve isteme
        checkRootAccess();

        edtPid = findViewById(R.id.edtPid);
        edtSearch = findViewById(R.id.edtSearch);
        edtNewValue = findViewById(R.id.edtNewValue);
        btnApply = findViewById(R.id.btnApply);

        btnApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    int pid = Integer.parseInt(edtPid.getText().toString());
                    int searchValue = Integer.parseInt(edtSearch.getText().toString());
                    int newValue = Integer.parseInt(edtNewValue.getText().toString());

                    // Hafıza tarama ve değiştirme işlemini başlat
                    processMemoryManipulation(pid, searchValue, newValue);

                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Lütfen geçerli sayılar girin!", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    // Uygulama açıldığında Magisk/KernelSU penceresini tetikler
    private void checkRootAccess() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("exit\n");
            os.flush();
            p.waitFor();
            if (p.exitValue() == 0) {
                Log.d("MemoryTool", "Root yetkisi onaylandı.");
            } else {
                Toast.makeText(this, "Root yetkisi reddedildi!", Toast.LENGTH_LONG).show();
            }
        } catch (IOException | InterruptedException e) {
            Toast.makeText(this, "Cihaz rootlu görünmüyor veya 'su' bulunamadı.", Toast.LENGTH_LONG).show();
        }
    }

    private void processMemoryManipulation(int pid, int searchValue, int newValue) {
        Toast.makeText(this, "Hafıza taranıyor...", Toast.LENGTH_SHORT).show();

        // 1. Arama yap
        long[] foundAddresses = searchMemory(pid, searchValue);
        
        if (foundAddresses == null || foundAddresses.length == 0) {
            Toast.makeText(this, "Değer hafızada bulunamadı!", Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. Değiştir
        int successCount = 0;
        for (long addr : foundAddresses) {
            if (editMemory(pid, addr, newValue)) {
                successCount++;
            }
        }

        Toast.makeText(this, successCount + " adreste değer başarıyla değiştirildi!", Toast.LENGTH_LONG).show();
    }
}

