#include <jni.h>
#include <string>
#include <vector>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>

struct MemoryRegion {
    unsigned long startAddress;
    unsigned long endAddress;
};

// Global adres havuzu (Sonraki filtrelemeler için hafızada tutulur)
std::vector<unsigned long> globalFoundAddresses;

// Hedef sürecin okunabilir ve yazılabilir (rw-p) bellek alanlarını haritalandırır
std::vector<MemoryRegion> getValidWritableRegions(pid_t pid) {
    std::vector<MemoryRegion> regions;
    std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
    FILE* fp = fopen(mapsPath.c_str(), "r");
    
    if (fp) {
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            // Sadece rw-p olan, korumasız ve oyun verilerinin saklandığı anonim alanlar
            if (strstr(line, "rw-p") && !strstr(line, "/dev/") && !strstr(line, "[anon:dalvik") && !strstr(line, ".apk")) {
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

// 1. İLK ARAMA (First Scan) - /proc/[PID]/mem Dosyasını Root Yetkisiyle Doğrudan Okur
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mycustomapk_OverlayService_firstScan(JNIEnv* env, jobject thiz, jint pid, jint target_value) {
    globalFoundAddresses.clear();
    std::vector<MemoryRegion> regions = getValidWritableRegions(pid);
    
    // Root yetkisiyle ham bellek dosyasını açıyoruz (SELinux engelini aşmak için kilit nokta)
    std::string memPath = "/proc/" + std::to_string(pid) + "/mem";
    int memFd = open(memPath.c_str(), O_RDONLY);
    if (memFd < 0) {
        return 0; // Dosya açılamadıysa izin hatası veya süreç sonlanmıştır
    }

    const size_t maxBlockSize = 20 * 1024 * 1024; // Cihazın şişmesini önlemek için 20MB güvenlik sınırı

    for (const auto& region : regions) {
        size_t size = region.endAddress - region.startAddress;
        if (size <= 0 || size > maxBlockSize) continue;
        
        std::vector<char> buffer(size);
        // Hedef adrese git ve ham veriyi oku
        if (lseek64(memFd, region.startAddress, SEEK_SET) != -1) {
            if (read(memFd, buffer.data(), size) == size) {
                // Bellek bloğunun içinde 4'er byte kayarak tamsayı arıyoruz (Dword standardı)
                for (size_t i = 0; i <= size - sizeof(int); i += sizeof(int)) {
                    int* current_val = (int*)(buffer.data() + i);
                    if (*current_val == target_value) {
                        globalFoundAddresses.push_back(region.startAddress + i);
                    }
                }
            }
        }
    }
    close(memFd);
    return globalFoundAddresses.size();
}

// 2. SONRAKİ ARAMA / FİLTRELEME (Next Scan)
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mycustomapk_OverlayService_nextScan(JNIEnv* env, jobject thiz, jint pid, jint target_value) {
    if (globalFoundAddresses.empty()) return 0;

    std::vector<unsigned long> filteredList;
    std::string memPath = "/proc/" + std::to_string(pid) + "/mem";
    int memFd = open(memPath.c_str(), O_RDONLY);
    
    if (memFd >= 0) {
        int tempValue = 0;
        for (unsigned long addr : globalFoundAddresses) {
            if (lseek64(memFd, addr, SEEK_SET) != -1) {
                if (read(memFd, &tempValue, sizeof(int)) == sizeof(int)) {
                    if (tempValue == target_value) {
                        filteredList.push_back(addr);
                    }
                }
            }
        }
        close(memFd);
    }
    
    globalFoundAddresses = filteredList;
    return globalFoundAddresses.size();
}

// 3. DEĞERLERİ DEĞİŞTİRME (Write Memory)
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mycustomapk_OverlayService_writeNewValue(JNIEnv* env, jobject thiz, jint pid, jint new_value) {
    if (globalFoundAddresses.empty()) return 0;

    int successCount = 0;
    std::string memPath = "/proc/" + std::to_string(pid) + "/mem";
    
    // Yazma işlemi için O_WRONLY veya O_RDWR modunda açıyoruz
    int memFd = open(memPath.c_str(), O_WRONLY);
    
    if (memFd >= 0) {
        int value = new_value;
        for (unsigned long addr : globalFoundAddresses) {
            if (lseek64(memFd, addr, SEEK_SET) != -1) {
                if (write(memFd, &value, sizeof(int)) == sizeof(int)) {
                    successCount++;
                }
            }
        }
        close(memFd);
    }
    return successCount;
}
