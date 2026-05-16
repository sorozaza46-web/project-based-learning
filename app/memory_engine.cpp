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

std::vector<MemoryRegion> getValidWritableRegions(pid_t pid) {
    std::vector<MemoryRegion> regions;
    std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
    FILE* fp = fopen(mapsPath.c_str(), "r");
    
    if (fp) {
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            // Sadece rw-p ve donanımsal sürücü bağımlılığı olmayan temiz RAM blokları
            if (strstr(line, "rw-p") && !strstr(line, "/dev/")) {
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

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_example_mycustomapk_MainActivity_searchMemory(JNIEnv* env, jobject thiz, jint pid, jint target_value) {
    std::vector<MemoryRegion> regions = getValidWritableRegions(pid);
    std::vector<jlong> foundAddresses;

    const size_t maxBlockSize = 25 * 1024 * 1024; // 25 MB güvenlik sınırı

    for (const auto& region : regions) {
        size_t size = region.endAddress - region.startAddress;
        if (size <= 0 || size > maxBlockSize) continue;
        
        std::vector<char> buffer(size);
        if (readProcessMemory(pid, region.startAddress, buffer.data(), size)) {
            for (size_t i = 0; i <= size - sizeof(int); i += sizeof(int)) {
                int* current_val = (int*)(buffer.data() + i);
                if (*current_val == target_value) {
                    foundAddresses.push_back(region.startAddress + i);
                }
            }
        }
    }

    jlongArray result = env->NewLongArray(foundAddresses.size());
    env->SetLongArrayRegion(result, 0, foundAddresses.size(), foundAddresses.data());
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mycustomapk_MainActivity_editMemory(JNIEnv* env, jobject thiz, jint pid, jlong address, jint new_value) {
    int value = new_value;
    return writeProcessMemory(pid, address, &value, sizeof(int)) ? JNI_TRUE : JNI_FALSE;
}
