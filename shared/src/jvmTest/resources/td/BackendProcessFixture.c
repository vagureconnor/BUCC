/* Windows-only test fixture: writes its PID, then waits for the test to terminate it.
 * Rebuild from the repository root using MinGW-w64 14.0.0_3 / GCC 16.2.0:
 * x86_64-w64-mingw32-gcc -Os -s -Wl,--no-insert-timestamp \
 *   shared/src/jvmTest/resources/td/BackendProcessFixture.c \
 *   -o shared/src/jvmTest/resources/td/BackendProcessFixture.exe
 */
#include <windows.h>
#include <stdio.h>

int main(void) {
    FILE *pid_file = fopen("../backend.pid", "w");
    if (pid_file == NULL) return 1;
    fprintf(pid_file, "%lu\n", (unsigned long)GetCurrentProcessId());
    if (fclose(pid_file) != 0) return 1;
    Sleep(INFINITE);
    return 0;
}
