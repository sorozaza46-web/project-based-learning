#include <jni.h>
#include <string>
#include <vector>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/uio.h> // process_vm_readv ve process_vm_writev için gerekli makrolar
#include <cstring>

struct MemoryRegion {
    unsigned long startAddress;
    unsigned long endAddress;
};

// Global bulunan adresler havuzu
std::vector<unsigned long> globalFoundAddresses;

// Hedef sürecin taranabilir tüm rw-p bellek alanlarını listeler
std::vector<MemoryRegion> getValidWritableRegions(pid_t pid) {
    std::vector<MemoryRegion> regions;
    std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
    FILE* fp = fopen(mapsPath.c_str(), "r");
    
    if (fp) {
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            // Dalvik heap, libc_malloc ve anonim tüm yazılabilir alanları kapsama alıyoruz
            if (strstr(line, "rw-p")) {
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

// 1. İLK ARAMA (First Scan) - process_vm_readv ile Ultra Hızlı Tarama
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mycustomapk_OverlayService_firstScan(JNIEnv* env, jobject thiz, jint pid, jint target_value) {
    globalFoundAddresses.clear();
    std::vector<MemoryRegion> regions = getValidWritableRegions(pid);
    
    const size_t maxBlockSize = 50 * 1024 * 1024; // Çok büyük bloklarda şişmeyi önleme sınırı

    for (const auto& region : regions) {
        size_t size = region.endAddress - region.startAddress;
        if (size <= 0 || size > maxBlockSize) continue;
        
        std::vector<char> buffer(size);
        
        // Local ve Remote I/O vektör yapılarını hazırlıyoruz
        struct iovec localVec;
        localVec.iov_base = buffer.data();
        localVec.iov_len = size;
        
        struct iovec remoteVec;
        remoteVec.iov_base = reinterpret_cast<void*>(region.startAddress);
        remoteVec.iov_len = size;
        
        // Doğrudan çekirdek seviyesinde bellek kopyalaması tetikleniyor
        ssize_t bytesRead = process_vm_readv(pid, &localVec, 1, &remoteVec, 1, 0);
        
        if (bytesRead == static_cast<ssize_t>(size)) {
            // Byte byte (i += 1) hassas arama mantığı
            for (size_t i = 0; i <= size - sizeof(int); i += 1) {
                int current_val;
                std::memcpy(&current_val, buffer.data() + i, sizeof(int));
                
                if (current_val == target_value) {
                    globalFoundAddresses.push_back(region.startAddress + i);
                }
            }
        }
    }
    return globalFoundAddresses.size();
}

// 2. SONRAKİ ARAMA / FİLTRELEME (Next Scan) - process_vm_readv Kullanımı
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mycustomapk_OverlayService_nextScan(JNIEnv* env, jobject thiz, jint pid, jint target_value) {
    if (globalFoundAddresses.empty()) return 0;

    std::vector<unsigned long> filteredList;
    int tempValue = 0;

    for (unsigned long addr : globalFoundAddresses) {
        struct iovec localVec;
        localVec.iov_base = &tempValue;
        localVec.iov_len = sizeof(int);
        
        struct iovec remoteVec;
        remoteVec.iov_base = reinterpret_cast<void*>(addr);
        remoteVec.iov_len = sizeof(int);
        
        if (process_vm_readv(pid, &localVec, 1, &remoteVec, 1, 0) == sizeof(int)) {
            if (tempValue == target_value) {
                filteredList.push_back(addr);
            }
        }
    }
    
    globalFoundAddresses = filteredList;
    return globalFoundAddresses.size();
}

// 3. DEĞERLERİ DEĞİŞTİRME (Write Memory) - process_vm_writev Kullanımı
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mycustomapk_OverlayService_writeNewValue(JNIEnv* env, jobject thiz, jint pid, jint new_value) {
    if (globalFoundAddresses.empty()) return 0;

    int successCount = 0;
    int valueToWrite = new_value;

    for (unsigned long addr : globalFoundAddresses) {
        struct iovec localVec;
        localVec.iov_base = &valueToWrite;
        localVec.iov_len = sizeof(int);
        
        struct iovec remoteVec;
        remoteVec.iov_base = reinterpret_cast<void*>(addr);
        remoteVec.iov_len = sizeof(int);
        
        // Hedef sürecin hafızasına doğrudan yazma işlemi
        if (process_vm_writev(pid, &localVec, 1, &remoteVec, 1, 0) == sizeof(int)) {
            successCount++;
        }
    }
    return successCount;
}
