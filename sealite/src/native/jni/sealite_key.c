/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 pasichdev */

/*
 * Key handling for the JNI binding: the only C code that touches key material.
 *
 * The key arrives as a Java byte[] (raw-key literal x'..' or a UTF-8 passphrase, built in Kotlin).
 * It is copied with GetByteArrayRegion into memory we own, handed to sqlite3_key_v2 /
 * sqlite3_rekey_v2, and wiped before the buffer is freed. GetByteArrayElements is never used: the
 * VM may return a copy that is released without being wiped.
 */
#define __STDC_WANT_LIB_EXT1__ 1 /* memset_s on Apple platforms */
#include "sealite_key.h"

#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#include <windows.h>
#endif

/*
 * A wipe the compiler may not elide. explicit_bzero is not usable everywhere we ship (glibc 2.19
 * sysroot for Linux x64, older Android API levels), so non-Windows/Apple targets call memset
 * through a volatile function pointer and add a compiler barrier.
 */
#if !defined(_WIN32) && !defined(__APPLE__)
static void *(*const volatile sealite_memset)(void *, int, size_t) = memset;
#endif

static void sealite_wipe(void *p, size_t n) {
    if (p == NULL || n == 0) return;
#if defined(_WIN32)
    SecureZeroMemory(p, n);
#elif defined(__APPLE__)
    memset_s(p, n, 0, n);
#else
    sealite_memset(p, 0, n);
    __asm__ __volatile__("" : : "r"(p) : "memory");
#endif
}

int sealite_key_from_java(JNIEnv *env, sqlite3 *db, jbyteArray key, int rekey) {
    if (key == NULL) return SQLITE_MISUSE;
    jsize len = (*env)->GetArrayLength(env, key);
    if (len <= 0) return SQLITE_MISUSE;

    unsigned char *buf = (unsigned char *) malloc((size_t) len);
    if (buf == NULL) return SQLITE_NOMEM;

    (*env)->GetByteArrayRegion(env, key, 0, len, (jbyte *) buf);
    int rc;
    if ((*env)->ExceptionCheck(env)) {
        rc = SQLITE_MISUSE;
    } else if (rekey) {
        rc = sqlite3_rekey_v2(db, "main", buf, (int) len);
    } else {
        rc = sqlite3_key_v2(db, "main", buf, (int) len);
    }
    sealite_wipe(buf, (size_t) len);
    free(buf);
    return rc;
}
