/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Modified for Sealite: ported from androidx.sqlite:sqlite-bundled `sqlite_bindings.cpp` to C,
 * registered against Sealite's own Kotlin classes, extension loading removed, key / rekey natives
 * added (the key itself is handled in sealite_key.c), and a failed open no longer leaks the handle.
 *
 * Every sqlite3_* name below is renamed to sealite_sqlite3_* by the generated sealite_rename.h,
 * which the build force-includes.
 */
#include <jni.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sealite_key.h"
#include "sqlite3.h"

/*
 * Throws SQLiteException with the given error code and message.
 *
 * @return 1 if the exception was thrown, otherwise 0.
 */
static int throwSQLiteException(JNIEnv *env, int errorCode, const char *errorMsg) {
    jclass exceptionClass = (*env)->FindClass(env, "androidx/sqlite/SQLiteException");
    if (exceptionClass == NULL) {
        /* If androidx's exception isn't found we are likely in Android's native where the
         * actual exception is type aliased. Clear the ClassNotFoundException and instead find
         * and throw Android's exception. */
        (*env)->ExceptionClear(env);
        exceptionClass = (*env)->FindClass(env, "android/database/SQLException");
        if (exceptionClass == NULL) return 0;
    }
    int codeLength = snprintf(NULL, 0, "%d", errorCode);
    size_t prefixLength = strlen("Error code: ");
    size_t msgLength = 0;
    if (errorMsg != NULL) {
        msgLength = strlen(", message: ") + strlen(errorMsg);
    }
    size_t totalSize = prefixLength + (size_t) codeLength + msgLength + 1;
    char *message = (char *) malloc(totalSize);
    if (message == NULL) return 0;
    if (errorMsg != NULL) {
        snprintf(message, totalSize, "Error code: %d, message: %s", errorCode, errorMsg);
    } else {
        snprintf(message, totalSize, "Error code: %d", errorCode);
    }
    int throwResult = (*env)->ThrowNew(env, exceptionClass, message);
    free(message);
    return throwResult == 0;
}

static int throwIfNoRow(JNIEnv *env, sqlite3_stmt *stmt) {
    if (sqlite3_stmt_busy(stmt) == 0) {
        return throwSQLiteException(env, SQLITE_MISUSE, "no row");
    }
    return 0;
}

static int throwIfInvalidColumn(JNIEnv *env, sqlite3_stmt *stmt, int index) {
    if (index < 0 || index >= sqlite3_column_count(stmt)) {
        return throwSQLiteException(env, SQLITE_RANGE, "column index out of range");
    }
    return 0;
}

static int throwOutOfMemoryError(JNIEnv *env) {
    jclass exceptionClass = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
    if (exceptionClass == NULL) return 0;
    return (*env)->ThrowNew(env, exceptionClass, NULL) == 0;
}

static int throwIfOutOfMemory(JNIEnv *env, sqlite3_stmt *stmt) {
    int lastRc = sqlite3_errcode(sqlite3_db_handle(stmt));
    if (lastRc == SQLITE_NOMEM) {
        return throwOutOfMemoryError(env);
    }
    return 0;
}

/* ---- connection ---------------------------------------------------------------------------- */

/*
 * Opens the database named by a NUL-terminated UTF-8 byte array. The path arrives as bytes, not as a
 * jstring: GetStringUTFChars would return modified UTF-8 (surrogate pairs as two 3-byte sequences,
 * U+0000 as C0 80), which names a different file than the one the caller asked for.
 */
static jlong JNICALL nativeOpen(JNIEnv *env, jclass clazz, jbyteArray name, jint openFlags) {
    jsize nameLength = (*env)->GetArrayLength(env, name);
    if (nameLength < 1 || nameLength > INT_MAX / 2) {
        throwSQLiteException(env, nameLength < 1 ? SQLITE_MISUSE : SQLITE_TOOBIG, "invalid database path");
        return 0;
    }
    char *path = (char *) malloc((size_t) nameLength + 1);
    if (path == NULL) {
        throwOutOfMemoryError(env);
        return 0;
    }
    (*env)->GetByteArrayRegion(env, name, 0, nameLength, (jbyte *) path);
    if ((*env)->ExceptionCheck(env)) {
        free(path);
        return 0;
    }
    path[nameLength] = '\0';
    /* Exactly one NUL, and it is the last byte: an embedded NUL would silently truncate the path. */
    if (path[nameLength - 1] != '\0' || strlen(path) != (size_t) nameLength - 1) {
        free(path);
        throwSQLiteException(env, SQLITE_MISUSE, "database path must be NUL-terminated and contain no NUL");
        return 0;
    }
    sqlite3 *db = NULL;
    int rc = sqlite3_open_v2(path, &db, openFlags, NULL);
    free(path);
    if (rc != SQLITE_OK) {
        throwSQLiteException(env, rc, db != NULL ? sqlite3_errmsg(db) : NULL);
        if (db != NULL) sqlite3_close_v2(db);
        return 0;
    }

    /* Enable extended error codes */
    rc = sqlite3_extended_result_codes(db, 1);
    if (rc != SQLITE_OK) {
        throwSQLiteException(env, rc, NULL);
        sqlite3_close_v2(db);
        return 0;
    }

    return (jlong) (intptr_t) db;
}

static void JNICALL nativeKey(JNIEnv *env, jclass clazz, jlong dbPointer, jbyteArray key) {
    sqlite3 *db = (sqlite3 *) (intptr_t) dbPointer;
    int rc = sealite_key_from_java(env, db, key, 0);
    if (rc != SQLITE_OK && !(*env)->ExceptionCheck(env)) {
        throwSQLiteException(env, rc, sqlite3_errmsg(db));
    }
}

static void JNICALL nativeRekey(JNIEnv *env, jclass clazz, jlong dbPointer, jbyteArray key) {
    sqlite3 *db = (sqlite3 *) (intptr_t) dbPointer;
    int rc = sealite_key_from_java(env, db, key, 1);
    if (rc != SQLITE_OK && !(*env)->ExceptionCheck(env)) {
        throwSQLiteException(env, rc, sqlite3_errmsg(db));
    }
}

static jboolean JNICALL nativeInTransaction(JNIEnv *env, jclass clazz, jlong dbPointer) {
    sqlite3 *db = (sqlite3 *) (intptr_t) dbPointer;
    return sqlite3_get_autocommit(db) == 0 ? JNI_TRUE : JNI_FALSE;
}

static jlong JNICALL nativePrepare(JNIEnv *env, jclass clazz, jlong dbPointer, jstring sqlString) {
    sqlite3 *db = (sqlite3 *) (intptr_t) dbPointer;
    sqlite3_stmt *stmt = NULL;
    jsize sqlLength = (*env)->GetStringLength(env, sqlString);
    /* The byte length (2 per char) must fit in the int sqlite3 takes. */
    if (sqlLength > INT_MAX / (jsize) sizeof(jchar)) {
        throwSQLiteException(env, SQLITE_TOOBIG, "SQL text too long");
        return 0;
    }
    /* Java / jstring represents a string in UTF-16 encoding. */
    const jchar *sql = (*env)->GetStringCritical(env, sqlString, NULL);
    if (sql == NULL) return 0;
    int rc = sqlite3_prepare16_v2(db, sql, (int) (sqlLength * sizeof(jchar)), &stmt, NULL);
    (*env)->ReleaseStringCritical(env, sqlString, sql);
    if (rc != SQLITE_OK) {
        throwSQLiteException(env, rc, sqlite3_errmsg(db));
        return 0;
    }
    return (jlong) (intptr_t) stmt;
}

static void JNICALL nativeConnectionClose(JNIEnv *env, jclass clazz, jlong dbPointer) {
    sqlite3 *db = (sqlite3 *) (intptr_t) dbPointer;
    sqlite3_close_v2(db);
}

/* ---- statement ----------------------------------------------------------------------------- */

static void JNICALL nativeBindBlob(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index, jbyteArray value) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    jsize valueLength = (*env)->GetArrayLength(env, value);
    if (valueLength < 0) {
        throwSQLiteException(env, SQLITE_TOOBIG, "blob length out of range");
        return;
    }
    jbyte *blob = (jbyte *) (*env)->GetPrimitiveArrayCritical(env, value, NULL);
    if (blob == NULL && valueLength > 0) return;
    int rc = sqlite3_bind_blob(stmt, index, blob, valueLength, SQLITE_TRANSIENT);
    if (blob != NULL) (*env)->ReleasePrimitiveArrayCritical(env, value, blob, JNI_ABORT);
    if (rc != SQLITE_OK) {
        throwSQLiteException(env, rc, sqlite3_errmsg(sqlite3_db_handle(stmt)));
    }
}

static void JNICALL nativeBindDouble(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index, jdouble value) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    int rc = sqlite3_bind_double(stmt, index, value);
    if (rc != SQLITE_OK) {
        throwSQLiteException(env, rc, sqlite3_errmsg(sqlite3_db_handle(stmt)));
    }
}

static void JNICALL nativeBindLong(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index, jlong value) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    int rc = sqlite3_bind_int64(stmt, index, value);
    if (rc != SQLITE_OK) {
        throwSQLiteException(env, rc, sqlite3_errmsg(sqlite3_db_handle(stmt)));
    }
}

static void JNICALL nativeBindText(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index, jstring value) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    jsize valueLength = (*env)->GetStringLength(env, value);
    if (valueLength > INT_MAX / (jsize) sizeof(jchar)) {
        throwSQLiteException(env, SQLITE_TOOBIG, "text value too long");
        return;
    }
    const jchar *text = (*env)->GetStringCritical(env, value, NULL);
    if (text == NULL) return;
    int rc = sqlite3_bind_text16(stmt, index, text, (int) (valueLength * sizeof(jchar)), SQLITE_TRANSIENT);
    (*env)->ReleaseStringCritical(env, value, text);
    if (rc != SQLITE_OK) {
        throwSQLiteException(env, rc, sqlite3_errmsg(sqlite3_db_handle(stmt)));
    }
}

static void JNICALL nativeBindNull(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    int rc = sqlite3_bind_null(stmt, index);
    if (rc != SQLITE_OK) {
        throwSQLiteException(env, rc, sqlite3_errmsg(sqlite3_db_handle(stmt)));
    }
}

static jboolean JNICALL nativeStep(JNIEnv *env, jclass clazz, jlong stmtPointer) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    int rc = sqlite3_step(stmt);
    if (rc == SQLITE_ROW) {
        return JNI_TRUE;
    }
    if (rc == SQLITE_DONE) {
        return JNI_FALSE;
    }
    throwSQLiteException(env, rc, sqlite3_errmsg(sqlite3_db_handle(stmt)));
    return JNI_FALSE;
}

static jbyteArray JNICALL nativeGetBlob(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    if (throwIfNoRow(env, stmt)) return NULL;
    if (throwIfInvalidColumn(env, stmt, index)) return NULL;
    const void *blob = sqlite3_column_blob(stmt, index);
    if (blob == NULL && throwIfOutOfMemory(env, stmt)) return NULL;
    int size = sqlite3_column_bytes(stmt, index);
    if (size == 0 && throwIfOutOfMemory(env, stmt)) return NULL;
    jbyteArray byteArray = (*env)->NewByteArray(env, size);
    if (byteArray != NULL && size > 0) {
        (*env)->SetByteArrayRegion(env, byteArray, 0, size, (const jbyte *) blob);
    }
    return byteArray;
}

static jdouble JNICALL nativeGetDouble(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    if (throwIfNoRow(env, stmt)) return 0.0;
    if (throwIfInvalidColumn(env, stmt, index)) return 0.0;
    return sqlite3_column_double(stmt, index);
}

static jlong JNICALL nativeGetLong(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    if (throwIfNoRow(env, stmt)) return 0;
    if (throwIfInvalidColumn(env, stmt, index)) return 0;
    return sqlite3_column_int64(stmt, index);
}

static jstring JNICALL nativeGetText(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    if (throwIfNoRow(env, stmt)) return NULL;
    if (throwIfInvalidColumn(env, stmt, index)) return NULL;
    /* Java / jstring represents a string in UTF-16 encoding. */
    const jchar *text = (const jchar *) sqlite3_column_text16(stmt, index);
    if (text == NULL && throwIfOutOfMemory(env, stmt)) return NULL;
    size_t length = (size_t) sqlite3_column_bytes16(stmt, index) / sizeof(jchar);
    if (length == 0 && throwIfOutOfMemory(env, stmt)) return NULL;
    return (*env)->NewString(env, text, (jsize) length);
}

static jint JNICALL nativeGetColumnCount(JNIEnv *env, jclass clazz, jlong stmtPointer) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    return sqlite3_column_count(stmt);
}

static jstring JNICALL nativeGetColumnName(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    if (throwIfInvalidColumn(env, stmt, index)) return NULL;
    const char *name = sqlite3_column_name(stmt, index);
    if (name == NULL) {
        throwOutOfMemoryError(env);
        return NULL;
    }
    return (*env)->NewStringUTF(env, name);
}

static jint JNICALL nativeGetColumnType(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    if (throwIfNoRow(env, stmt)) return 0;
    if (throwIfInvalidColumn(env, stmt, index)) return 0;
    return sqlite3_column_type(stmt, index);
}

static void JNICALL nativeReset(JNIEnv *env, jclass clazz, jlong stmtPointer) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    int rc = sqlite3_reset(stmt);
    if (rc != SQLITE_OK) {
        throwSQLiteException(env, rc, sqlite3_errmsg(sqlite3_db_handle(stmt)));
    }
}

static void JNICALL nativeClearBindings(JNIEnv *env, jclass clazz, jlong stmtPointer) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    int rc = sqlite3_clear_bindings(stmt);
    if (rc != SQLITE_OK) {
        throwSQLiteException(env, rc, sqlite3_errmsg(sqlite3_db_handle(stmt)));
    }
}

static void JNICALL nativeStatementClose(JNIEnv *env, jclass clazz, jlong stmtPointer) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    sqlite3_finalize(stmt);
}

/* ---- registration -------------------------------------------------------------------------- */

static const JNINativeMethod sConnectionMethods[] = {
    {"nativeOpen", "([BI)J", (void *) nativeOpen},
    {"nativeKey", "(J[B)V", (void *) nativeKey},
    {"nativeRekey", "(J[B)V", (void *) nativeRekey},
    {"nativeInTransaction", "(J)Z", (void *) nativeInTransaction},
    {"nativePrepare", "(JLjava/lang/String;)J", (void *) nativePrepare},
    {"nativeClose", "(J)V", (void *) nativeConnectionClose},
};

static const JNINativeMethod sStatementMethods[] = {
    {"nativeBindBlob", "(JI[B)V", (void *) nativeBindBlob},
    {"nativeBindDouble", "(JID)V", (void *) nativeBindDouble},
    {"nativeBindLong", "(JIJ)V", (void *) nativeBindLong},
    {"nativeBindText", "(JILjava/lang/String;)V", (void *) nativeBindText},
    {"nativeBindNull", "(JI)V", (void *) nativeBindNull},
    {"nativeStep", "(J)Z", (void *) nativeStep},
    {"nativeGetBlob", "(JI)[B", (void *) nativeGetBlob},
    {"nativeGetDouble", "(JI)D", (void *) nativeGetDouble},
    {"nativeGetLong", "(JI)J", (void *) nativeGetLong},
    {"nativeGetText", "(JI)Ljava/lang/String;", (void *) nativeGetText},
    {"nativeGetColumnCount", "(J)I", (void *) nativeGetColumnCount},
    {"nativeGetColumnName", "(JI)Ljava/lang/String;", (void *) nativeGetColumnName},
    {"nativeGetColumnType", "(JI)I", (void *) nativeGetColumnType},
    {"nativeReset", "(J)V", (void *) nativeReset},
    {"nativeClearBindings", "(J)V", (void *) nativeClearBindings},
    {"nativeClose", "(J)V", (void *) nativeStatementClose},
};

static int register_methods(JNIEnv *env, const char *className, const JNINativeMethod *methods, int methodCount) {
    jclass clazz = (*env)->FindClass(env, className);
    if (clazz == NULL) {
        return JNI_ERR;
    }
    int result = (*env)->RegisterNatives(env, clazz, methods, methodCount);
    (*env)->DeleteLocalRef(env, clazz);
    if (result != 0) {
        return JNI_ERR;
    }
    return JNI_OK;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6)) {
        return JNI_ERR;
    }

    const int connectionMethodCount = (int) (sizeof(sConnectionMethods) / sizeof(sConnectionMethods[0]));
    if (register_methods(env, "io/github/pasichdev/sealite/internal/JniRawDbKt", sConnectionMethods,
                         connectionMethodCount) != JNI_OK) {
        return JNI_ERR;
    }
    const int statementMethodCount = (int) (sizeof(sStatementMethods) / sizeof(sStatementMethods[0]));
    if (register_methods(env, "io/github/pasichdev/sealite/internal/JniStatementKt", sStatementMethods,
                         statementMethodCount) != JNI_OK) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
