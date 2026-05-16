#include <jni.h>
#include <string>
#include <vector>
#include <sys/uio.h>
#include <unistd.h>
#include <fcntl.h>

struct MemoryRegion {
    unsigned long startAddress;
    unsigned long endAddress;
};

// Global adres havuzu (Sonraki aramalar için sonuçları burada saklıyoruz)
std::vector<unsigned long> globalFoundAddresses;

std::vector<MemoryRegion> getValidWritableRegions(pid_t pid) {
    std::vector<MemoryRegion> regions;
    std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
    FILE* fp = fopen(mapsPath.c_str(), "r");
    
    if (fp) {
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            // Sadece rw-p olan ve gereksiz sistem/sürücü bağımlılığı olmayan alanlar
            if (strstr(line, "rw-p") && !strstr(line, "/dev/") && !strstr(line, "[anon:dalvik")) {
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

bool readProcessMemory(pid_t pid, unsigned long address, void* buffer, size_t size) {
    struct iovec local[1];
    struct iovec remote[1];
    local[0].iov_base = buffer;
    local[0].iov_len = size;
    remote[0].iov_base = (void*)address;
    remote[0].iov_len = size;
    return process_vm_readv(pid, local, 1, remote, 1, 0) == size;
}

bool writeProcessMemory(pid_t pid, unsigned long address, void* buffer, size_t size) {
    struct iovec local[1];
    struct iovec remote[1];
    local[0].iov_base = buffer;
    local[0].iov_len = size;
    remote[0].iov_base = (void*)address;
    remote[0].iov_len = size;
    return process_vm_writev(pid, local, 1, remote, 1, 0) == size;
}

// 1. İLK ARAMA (First Scan)
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mycustomapk_OverlayService_firstScan(JNIEnv* env, jobject thiz, jint pid, jint target_value) {
    globalFoundAddresses.clear();
    std::vector<MemoryRegion> regions = getValidWritableRegions(pid);
    const size_t maxBlockSize = 30 * 1024 * 1024; // 30 MB Güvenlik Limiti

    for (const auto& region : regions) {
        size_t size = region.endAddress - region.startAddress;
        if (size <= 0 || size > maxBlockSize) continue;
        
        std::vector<char> buffer(size);
        if (readProcessMemory(pid, region.startAddress, buffer.data(), size)) {
            for (size_t i = 0; i <= size - sizeof(int); i += sizeof(int)) {
                int* current_val = (int*)(buffer.data() + i);
                if (*current_val == target_value) {
                    globalFoundAddresses.push_back(region.startAddress + i);
                }
            }
        }
    }
    return globalFoundAddresses.size();
}

// 2. SONRAKİ ARAMA / FİLTRELEME (Next Scan)
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mycustomapk_OverlayService_nextScan(JNIEnv* env, jobject thiz, jint pid, jint target_value) {
    if (globalFoundAddresses.empty()) return 0;

    std::vector<unsigned long> filteredList;
    int tempValue = 0;

    for (unsigned long addr : globalFoundAddresses) {
        if (readProcessMemory(pid, addr, &tempValue, sizeof(int))) {
            if (tempValue == target_value) {
                filteredList.push_back(addr);
            }
        }
    }
    globalFoundAddresses = filteredList;
    return globalFoundAddresses.size();
}

// 3. DEĞERLERİ DEĞİŞTİRME (Write Memory)
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mycustomapk_OverlayService_writeNewValue(JNIEnv* env, jobject thiz, jint pid, jint new_value) {
    if (globalFoundAddresses.empty()) return 0;

    int successCount = 0;
    int value = new_value;

    for (unsigned long addr : globalFoundAddresses) {
        if (writeProcessMemory(pid, addr, &value, sizeof(int))) {
            successCount++;
        }
    }
    return successCount;
}
