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
 * Throws SQLiteException("Error code: <code>, message: <message>") from a UTF-16 message. The message
 * is built as a java.lang.String (NewString), never as UTF-8: JNI expects *modified* UTF-8 there, which
 * HotSpot decodes wrongly for characters outside the BMP (an emoji in a table name, say).
 *
 * @return 1 if the exception was thrown, otherwise 0.
 */
static int throwSQLiteException16(JNIEnv *env, int errorCode, const jchar *message, size_t messageLength) {
    jclass exceptionClass = (*env)->FindClass(env, "androidx/sqlite/SQLiteException");
    if (exceptionClass == NULL) {
        /* If androidx's exception isn't found we are likely in Android's native where the
         * actual exception is type aliased. Clear the ClassNotFoundException and instead find
         * and throw Android's exception. */
        (*env)->ExceptionClear(env);
        exceptionClass = (*env)->FindClass(env, "android/database/SQLException");
        if (exceptionClass == NULL) return 0;
    }
    jmethodID constructor = (*env)->GetMethodID(env, exceptionClass, "<init>", "(Ljava/lang/String;)V");
    if (constructor == NULL) return 0;
    char prefix[64];
    int prefixLength = snprintf(prefix, sizeof prefix, "Error code: %d", errorCode);
    static const char kSeparator[] = ", message: ";
    size_t separatorLength = message != NULL ? sizeof kSeparator - 1 : 0;
    size_t total = (size_t) prefixLength + separatorLength + (message != NULL ? messageLength : 0);
    if (total > (size_t) INT_MAX) return 0;
    jchar *units = (jchar *) malloc(total * sizeof(jchar) + sizeof(jchar));
    if (units == NULL) return 0;
    size_t at = 0;
    for (int i = 0; i < prefixLength; i++)
        units[at++] = (jchar) prefix[i];
    for (size_t i = 0; i < separatorLength; i++)
        units[at++] = (jchar) kSeparator[i];
    if (message != NULL && messageLength > 0) {
        memcpy(units + at, message, messageLength * sizeof(jchar));
        at += messageLength;
    }
    jstring text = (*env)->NewString(env, units, (jsize) at);
    free(units);
    if (text == NULL) return 0;
    jobject exception = (*env)->NewObject(env, exceptionClass, constructor, text);
    if (exception == NULL) return 0;
    return (*env)->Throw(env, (jthrowable) exception) == 0;
}

/* Throws with an ASCII message (every literal in this file), or none for NULL. */
static int throwSQLiteException(JNIEnv *env, int errorCode, const char *errorMsg) {
    if (errorMsg == NULL) return throwSQLiteException16(env, errorCode, NULL, 0);
    size_t length = strlen(errorMsg);
    jchar *units = (jchar *) malloc(length * sizeof(jchar) + sizeof(jchar));
    if (units == NULL) return 0;
    for (size_t i = 0; i < length; i++)
        units[i] = (jchar) (unsigned char) errorMsg[i];
    int thrown = throwSQLiteException16(env, errorCode, units, length);
    free(units);
    return thrown;
}

/* Throws with the connection's current message, read as UTF-16 (sqlite3_errmsg16). */
static int throwSQLiteExceptionFromDb(JNIEnv *env, int errorCode, sqlite3 *db) {
    const jchar *message = (const jchar *) sqlite3_errmsg16(db);
    size_t length = 0;
    if (message != NULL) {
        while (message[length] != 0)
            length++;
    }
    return throwSQLiteException16(env, errorCode, message, length);
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
        if (db != NULL) {
            throwSQLiteExceptionFromDb(env, rc, db);
        } else {
            throwSQLiteException(env, rc, NULL);
        }
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
        throwSQLiteExceptionFromDb(env, rc, db);
    }
}

static void JNICALL nativeRekey(JNIEnv *env, jclass clazz, jlong dbPointer, jbyteArray key) {
    sqlite3 *db = (sqlite3 *) (intptr_t) dbPointer;
    int rc = sealite_key_from_java(env, db, key, 1);
    if (rc != SQLITE_OK && !(*env)->ExceptionCheck(env)) {
        throwSQLiteExceptionFromDb(env, rc, db);
    }
}

static jboolean JNICALL nativeInTransaction(JNIEnv *env, jclass clazz, jlong dbPointer) {
    sqlite3 *db = (sqlite3 *) (intptr_t) dbPointer;
    return sqlite3_get_autocommit(db) == 0 ? JNI_TRUE : JNI_FALSE;
}

/*
 * Copies a java.lang.String out of the VM into memory from sqlite3_malloc64, as `length` UTF-16 units
 * plus one NUL unit. No Get*Critical region is held across a SQLite call: a prepare or a bind can
 * wait on the busy handler for seconds, and a critical region would stall the garbage collector (and
 * every allocating thread) for that long.
 *
 * @return the copy (release it with sqlite3_free, or hand it to SQLite with that destructor), or NULL
 *         with an exception pending.
 */
static jchar *copyString(JNIEnv *env, jstring string, jsize *length, const char *tooLongMessage) {
    jsize units = (*env)->GetStringLength(env, string);
    /* The byte length (2 per char) must fit in the int sqlite3 takes. */
    if (units > INT_MAX / (jsize) sizeof(jchar)) {
        throwSQLiteException(env, SQLITE_TOOBIG, tooLongMessage);
        return NULL;
    }
    jchar *copy = (jchar *) sqlite3_malloc64(((sqlite3_uint64) units + 1) * sizeof(jchar));
    if (copy == NULL) {
        throwOutOfMemoryError(env);
        return NULL;
    }
    (*env)->GetStringRegion(env, string, 0, units, copy);
    if ((*env)->ExceptionCheck(env)) {
        sqlite3_free(copy);
        return NULL;
    }
    copy[units] = 0;
    *length = units;
    return copy;
}

/* Replaces every unpaired surrogate with U+FFFD, as Java's own UTF-8 encoder does. */
static void replaceLoneSurrogates(jchar *text, jsize length) {
    for (jsize i = 0; i < length; i++) {
        jchar unit = text[i];
        if (unit >= 0xD800 && unit <= 0xDBFF) {
            if (i + 1 < length && text[i + 1] >= 0xDC00 && text[i + 1] <= 0xDFFF) {
                i++;
            } else {
                text[i] = 0xFFFD;
            }
        } else if (unit >= 0xDC00 && unit <= 0xDFFF) {
            text[i] = 0xFFFD;
        }
    }
}

static jlong JNICALL nativePrepare(JNIEnv *env, jclass clazz, jlong dbPointer, jstring sqlString) {
    sqlite3 *db = (sqlite3 *) (intptr_t) dbPointer;
    sqlite3_stmt *stmt = NULL;
    jsize sqlLength = 0;
    jchar *sql = copyString(env, sqlString, &sqlLength, "SQL text too long");
    if (sql == NULL) return 0;
    int rc = sqlite3_prepare16_v2(db, sql, (int) (sqlLength * sizeof(jchar)), &stmt, NULL);
    sqlite3_free(sql);
    if (rc != SQLITE_OK) {
        throwSQLiteExceptionFromDb(env, rc, db);
        return 0;
    }
    if (stmt == NULL) {
        /* Empty, whitespace-only or comment-only SQL compiles to nothing. A statement object around
         * a NULL pointer would crash in clearBindings (sqlite3_clear_bindings has no NULL check). */
        throwSQLiteException(env, SQLITE_MISUSE, "no SQL statement");
        return 0;
    }
    return (jlong) (intptr_t) stmt;
}

static void JNICALL nativeInterrupt(JNIEnv *env, jclass clazz, jlong dbPointer) {
    /* The one call SQLite documents as safe from any thread while others use the connection. */
    sqlite3_interrupt((sqlite3 *) (intptr_t) dbPointer);
}

static void JNICALL nativeConnectionClose(JNIEnv *env, jclass clazz, jlong dbPointer) {
    sqlite3 *db = (sqlite3 *) (intptr_t) dbPointer;
    sqlite3_close_v2(db);
}

/* ---- statement ----------------------------------------------------------------------------- */

static void JNICALL nativeBindBlob(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index, jbyteArray value) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    jsize valueLength = (*env)->GetArrayLength(env, value);
    int rc;
    if (valueLength == 0) {
        /* An empty BLOB, not NULL, and without touching the array. */
        rc = sqlite3_bind_zeroblob(stmt, index, 0);
    } else {
        /* Copy out of the VM first: no SQLite call inside a critical region. SQLite takes ownership of
         * the copy (and frees it itself, also when the bind fails). */
        void *blob = sqlite3_malloc64((sqlite3_uint64) valueLength);
        if (blob == NULL) {
            throwOutOfMemoryError(env);
            return;
        }
        (*env)->GetByteArrayRegion(env, value, 0, valueLength, (jbyte *) blob);
        if ((*env)->ExceptionCheck(env)) {
            sqlite3_free(blob);
            return;
        }
        rc = sqlite3_bind_blob(stmt, index, blob, valueLength, sqlite3_free);
    }
    if (rc != SQLITE_OK) {
        throwSQLiteExceptionFromDb(env, rc, sqlite3_db_handle(stmt));
    }
}

static void JNICALL nativeBindDouble(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index, jdouble value) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    int rc = sqlite3_bind_double(stmt, index, value);
    if (rc != SQLITE_OK) {
        throwSQLiteExceptionFromDb(env, rc, sqlite3_db_handle(stmt));
    }
}

static void JNICALL nativeBindLong(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index, jlong value) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    int rc = sqlite3_bind_int64(stmt, index, value);
    if (rc != SQLITE_OK) {
        throwSQLiteExceptionFromDb(env, rc, sqlite3_db_handle(stmt));
    }
}

static void JNICALL nativeBindText(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index, jstring value) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    jsize valueLength = 0;
    jchar *text = copyString(env, value, &valueLength, "text value too long");
    if (text == NULL) return;
    replaceLoneSurrogates(text, valueLength);
    /* SQLite takes ownership of the copy and frees it itself, also when the bind fails. */
    int rc = sqlite3_bind_text16(stmt, index, text, (int) (valueLength * sizeof(jchar)), sqlite3_free);
    if (rc != SQLITE_OK) {
        throwSQLiteExceptionFromDb(env, rc, sqlite3_db_handle(stmt));
    }
}

static void JNICALL nativeBindNull(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    int rc = sqlite3_bind_null(stmt, index);
    if (rc != SQLITE_OK) {
        throwSQLiteExceptionFromDb(env, rc, sqlite3_db_handle(stmt));
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
    throwSQLiteExceptionFromDb(env, rc, sqlite3_db_handle(stmt));
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
    static const jchar kEmpty[1] = {0};
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    if (throwIfNoRow(env, stmt)) return NULL;
    if (throwIfInvalidColumn(env, stmt, index)) return NULL;
    /* Java / jstring represents a string in UTF-16 encoding. The length is the byte count, not a NUL
     * scan, so a value with embedded U+0000 reads back whole. */
    const jchar *text = (const jchar *) sqlite3_column_text16(stmt, index);
    if (text == NULL && throwIfOutOfMemory(env, stmt)) return NULL;
    size_t length = (size_t) sqlite3_column_bytes16(stmt, index) / sizeof(jchar);
    if (length == 0 && throwIfOutOfMemory(env, stmt)) return NULL;
    /* SQL NULL has no text: it reads as the empty string, like the upstream bundled driver. */
    return (*env)->NewString(env, text != NULL ? text : kEmpty, (jsize) length);
}

static jint JNICALL nativeGetColumnCount(JNIEnv *env, jclass clazz, jlong stmtPointer) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    return sqlite3_column_count(stmt);
}

static jstring JNICALL nativeGetColumnName(JNIEnv *env, jclass clazz, jlong stmtPointer, jint index) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    if (throwIfInvalidColumn(env, stmt, index)) return NULL;
    /* UTF-16 into NewString: NewStringUTF wants modified UTF-8 and mangles characters outside the BMP. */
    const jchar *name = (const jchar *) sqlite3_column_name16(stmt, index);
    if (name == NULL) {
        throwOutOfMemoryError(env);
        return NULL;
    }
    size_t length = 0;
    while (name[length] != 0)
        length++;
    return (*env)->NewString(env, name, (jsize) length);
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
        throwSQLiteExceptionFromDb(env, rc, sqlite3_db_handle(stmt));
    }
}

static void JNICALL nativeClearBindings(JNIEnv *env, jclass clazz, jlong stmtPointer) {
    sqlite3_stmt *stmt = (sqlite3_stmt *) (intptr_t) stmtPointer;
    int rc = sqlite3_clear_bindings(stmt);
    if (rc != SQLITE_OK) {
        throwSQLiteExceptionFromDb(env, rc, sqlite3_db_handle(stmt));
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
    {"nativeInterrupt", "(J)V", (void *) nativeInterrupt},
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
