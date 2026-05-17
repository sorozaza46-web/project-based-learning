#define _LARGEFILE64_SOURCE
#include <iostream>
#include <string>
#include <vector>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <cstring>

struct MemoryRegion {
    unsigned long startAddress;
    unsigned long endAddress;
};

std::vector<unsigned long> globalAddresses;

std::vector<MemoryRegion> getRegions(pid_t pid) {
    std::vector<MemoryRegion> regions;
    std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
    FILE* fp = fopen(mapsPath.c_str(), "r");
    if (fp) {
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
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

int handleFirstScan(int pid, int val) {
    globalAddresses.clear();
    std::vector<MemoryRegion> regions = getRegions(pid);
    std::string memPath = "/proc/" + std::to_string(pid) + "/mem";
    int memFd = open(memPath.c_str(), O_RDONLY);
    if (memFd < 0) return -1;

    const size_t maxBlock = 50 * 1024 * 1024;
    for (const auto& r : regions) {
        size_t size = r.endAddress - r.startAddress;
        if (size <= 0 || size > maxBlock) continue;
        std::vector<char> buf(size);
        if (pread64(memFd, buf.data(), size, r.startAddress) == (ssize_t)size) {
            for (size_t i = 0; i <= size - sizeof(int); i += 1) {
                int cur;
                std::memcpy(&cur, buf.data() + i, sizeof(int));
                if (cur == val) {
                    globalAddresses.push_back(r.startAddress + i);
                }
            }
        }
    }
    close(memFd);
    return globalAddresses.size();
}

int handleNextScan(int pid, int val) {
    if (globalAddresses.empty()) return 0;
    std::vector<unsigned long> filtered;
    std::string memPath = "/proc/" + std::to_string(pid) + "/mem";
    int memFd = open(memPath.c_str(), O_RDONLY);
    if (memFd >= 0) {
        int temp = 0;
        for (unsigned long addr : globalAddresses) {
            if (pread64(memFd, &temp, sizeof(int), addr) == sizeof(int)) {
                if (temp == val) filtered.push_back(addr);
            }
        }
        close(memFd);
    }
    globalAddresses = filtered;
    return globalAddresses.size();
}

int handleWrite(int pid, int val) {
    if (globalAddresses.empty()) return 0;
    int count = 0;
    std::string memPath = "/proc/" + std::to_string(pid) + "/mem";
    int memFd = open(memPath.c_str(), O_WRONLY);
    if (memFd >= 0) {
        for (unsigned long addr : globalAddresses) {
            if (pwrite64(memFd, &val, sizeof(int), addr) == sizeof(int)) {
                count++;
            }
        }
        close(memFd);
    }
    return count;
}

int main() {
    int serverFd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (serverFd < 0) return 1;

    struct sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, "\0stealth_mem_socket", sizeof(addr.sun_path) - 1);

    bind(serverFd, (struct sockaddr*)&addr, sizeof(addr));
    listen(serverFd, 1);

    while (true) {
        int clientFd = accept(serverFd, nullptr, nullptr);
        if (clientFd >= 0) {
            int cmd[3]; // [MODE, PID, VALUE]
            if (read(clientFd, cmd, sizeof(cmd)) == sizeof(cmd)) {
                int res = 0;
                if (cmd[0] == 1) res = handleFirstScan(cmd[1], cmd[2]);
                else if (cmd[0] == 2) res = handleNextScan(cmd[1], cmd[2]);
                else if (cmd[0] == 3) res = handleWrite(cmd[1], cmd[2]);
                write(clientFd, &res, sizeof(res));
            }
            close(clientFd);
        }
    }
    close(serverFd);
    return 0;
}

