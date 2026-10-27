/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 pasichdev */

#ifndef SEALITE_KEY_H
#define SEALITE_KEY_H

#include <jni.h>
#include "sqlite3.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Applies (rekey == 0) or changes (rekey != 0) the key of the "main" database of db from a Java
 * byte[]. Returns the SQLite result code; the caller turns a non-OK code into SQLiteException.
 * The key bytes never outlive this call in native memory.
 */
int sealite_key_from_java(JNIEnv *env, sqlite3 *db, jbyteArray key, int rekey);

#ifdef __cplusplus
}
#endif

#endif
