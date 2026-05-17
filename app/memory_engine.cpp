#define _LARGEFILE64_SOURCE // 64-bit ofset desteğini etkinleştirir (Kritik)
#include <jni.h>
#include <string>
#include <vector>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <cstring>

struct MemoryRegion {
    unsigned long startAddress;
    unsigned long endAddress;
};

// Global adres havuzu (Filtrelemeler için hafızada tutulur)
std::vector<unsigned long> globalFoundAddresses;

// Hedef sürecin geçerli ve güvenli rw-p (Read-Write-Private) alanlarını haritalandırır
std::vector<MemoryRegion> getValidWritableRegions(pid_t pid) {
    std::vector<MemoryRegion> regions;
    std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
    FILE* fp = fopen(mapsPath.c_str(), "r");
    
    if (fp) {
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            // Sadece rw-p olan, çökme riski yaratmayan anonim ve oyun verisi alanları
            if (strstr(line, "rw-p") && 
                !strstr(line, "/dev/") && 
                !strstr(line, "[anon:dalvik") && 
                !strstr(line, ".apk") &&
                !strstr(line, "[stack]") &&
                !strstr(line, "[vectors]")) {
                
                unsigned long start, end;
                if (sscanf(line, "%lx-%lx", &start, &end) == 2) {
                    regions.push_back({start, end});
                }
            }
        }
        fclose(fp);
    }
    return regions;
}

// 1. İLK ARAMA (First Scan) - Güvenli ve Sızdırmaz Sürüm
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mycustomapk_OverlayService_firstScan(JNIEnv* env, jobject thiz, jint pid, jint target_value) {
    globalFoundAddresses.clear();
    std::vector<MemoryRegion> regions = getValidWritableRegions(pid);
    
    std::string memPath = "/proc/" + std::to_string(pid) + "/mem";
    int memFd = open(memPath.c_str(), O_RDONLY);
    if (memFd < 0) {
        return 0; // Kök yetkisi eksikliği veya SELinux engeli
    }

    // Olası bir bellek taşmasını önlemek için makul bir maksimum blok sınırı (Örn: 50MB)
    const size_t maxBlockSize = 50 * 1024 * 1024; 

    for (const auto& region : regions) {
        size_t size = region.endAddress - region.startAddress;
        if (size <= 0 || size > maxBlockSize) continue;
        
        std::vector<char> buffer(size);
        
        // lseek + read yerine thread-safe ve atomik olan pread64 kullanımı
        if (pread64(memFd, buffer.data(), size, region.startAddress) == static_cast<ssize_t>(size)) {
            
            // ÖNEMLİ: i += 1 yaparsak bellek hizalamasından bağımsız KESİN sonuç buluruz (GameGuardian mantığı).
            // Eğer oyun verilerinin kesinlikle 4-byte aligned (hizalı) olduğunu biliyorsan i += 4 yapabilirsin.
            // Risk almamak adına varsayılan olarak en güvenli yöntem olan i++ (1 byte kaydırma) ayarlandı.
            for (size_t i = 0; i <= size - sizeof(int); i += 1) {
                int current_val;
                std::memcpy(&current_val, buffer.data() + i, sizeof(int)); // alignment hatasını önlemek için güvenli kopyalama
                
                if (current_val == target_value) {
                    globalFoundAddresses.push_back(region.startAddress + i);
                }
            }
        }
    }
    close(memFd);
    return globalFoundAddresses.size();
}

// 2. SONRAKİ ARAMA / FİLTRELEME (Next Scan) - Ultra Hızlı Sürüm
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mycustomapk_OverlayService_nextScan(JNIEnv* env, jobject thiz, jint pid, jint target_value) {
    if (globalFoundAddresses.empty()) return 0;

    std::vector<unsigned long> filteredList;
    std::string memPath = "/proc/" + std::to_string(pid) + "/mem";
    int memFd = open(memPath.c_str(), O_RDONLY);
    
    if (memFd >= 0) {
        int tempValue = 0;
        for (unsigned long addr : globalFoundAddresses) {
            // Döngü içinde lseek maliyetini sıfırlayan doğrudan adresten okuma
            if (pread64(memFd, &tempValue, sizeof(int), addr) == sizeof(int)) {
                if (tempValue == target_value) {
                    filteredList.push_back(addr);
                }
            }
        }
        close(memFd);
    }
    
    globalFoundAddresses = filteredList;
    return globalFoundAddresses.size();
}

// 3. DEĞERLERİ DEĞİŞTİRME (Write Memory) - Kararlı Sürüm
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mycustomapk_OverlayService_writeNewValue(JNIEnv* env, jobject thiz, jint pid, jint new_value) {
    if (globalFoundAddresses.empty()) return 0;

    int successCount = 0;
    std::string memPath = "/proc/" + std::to_string(pid) + "/mem";
    
    // Sadece yazma modunda açıyoruz
    int memFd = open(memPath.c_str(), O_WRONLY);
    
    if (memFd >= 0) {
        int value = new_value;
        for (unsigned long addr : globalFoundAddresses) {
            // Döngü içinde kilitlenmeyi önleyen pwrite64 kullanımı
            if (pwrite64(memFd, &value, sizeof(int), addr) == sizeof(int)) {
                successCount++;
            }
        }
        close(memFd);
    }
    return successCount;
}
