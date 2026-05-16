#include <jni.h>
#include <string>
#include <vector>
#include <sys/uio.h>
#include <unistd.h>
#include <fcntl.h>
#include <iostream>

// Hafıza bölgesini temsil eden yapı
struct MemoryRegion {
    unsigned long startAddress;
    unsigned long endAddress;
};

// Hedef sürecin hafıza haritasını (/proc/pid/maps) okuyan fonksiyon
std::vector<MemoryRegion> getWritableRegions(pid_t pid) {
    std::vector<MemoryRegion> regions;
    std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
    FILE* fp = fopen(mapsPath.c_str(), "r");
    
    if (fp) {
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            // Sadece okunabilir ve yazılabilir (rw-) hafıza alanlarını filtrele
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

// Belirli bir adresten veri okuma (process_vm_readv)
bool readMemory(pid_t pid, unsigned long address, void* buffer, size_t size) {
    struct iovec local[1];
    struct iovec remote[1];

    local[0].iov_base = buffer;
    local[0].iov_len = size;
    remote[0].iov_base = (void*)address;
    remote[0].iov_len = size;

    ssize_t nread = process_vm_readv(pid, local, 1, remote, 1, 0);
    return nread == size;
}

// Belirli bir adrese veri yazma/değiştirme (process_vm_writev)
bool writeMemory(pid_t pid, unsigned long address, void* buffer, size_t size) {
    struct iovec local[1];
    struct iovec remote[1];

    local[0].iov_base = buffer;
    local[0].iov_len = size;
    remote[0].iov_base = (void*)address;
    remote[0].iov_len = size;

    ssize_t nwrite = process_vm_writev(pid, local, 1, remote, 1, 0);
    return nwrite == size;
}

// --- JNI Bağlantıları ---

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_example_mycustomapk_MemoryService_searchMemory(JNIEnv* env, jobject thiz, jint pid, jint target_value) {
    std::vector<MemoryRegion> regions = getWritableRegions(pid);
    std::vector<jlong> foundAddresses;

    for (const auto& region : regions) {
        size_t size = region.endAddress - region.startAddress;
        // Performans için büyük blokları parça parça taramak gerekir
        std::vector<char> buffer(size);
        
        if (readMemory(pid, region.startAddress, buffer.data(), size)) {
            // 4-baytlık tam sayı (Integer) araması
            for (size_t i = 0; i <= size - sizeof(int); i += sizeof(int)) {
                int* current_val = (int*)(buffer.data() + i);
                if (*current_val == target_value) {
                    foundAddresses.push_back(region.startAddress + i);
                }
            }
        }
    }

    // Sonuçları Java katmanına dizi olarak dönme
    jlongArray result = env->NewLongArray(foundAddresses.size());
    env->SetLongArrayRegion(result, 0, foundAddresses.size(), foundAddresses.data());
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mycustomapk_MemoryService_editMemory(JNIEnv* env, jobject thiz, jint pid, jlong address, jint new_value) {
    int value = new_value;
    return writeMemory(pid, address, &value, sizeof(int)) ? JNI_TRUE : JNI_FALSE;
}

