/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 pasichdev */

/*
 * libFuzzer harness for the part of Sealite that is C: SQLCipher keyed the way the library keys it,
 * then SQL, bound text and bound blobs taken from the input. No JNI and no JVM, so it builds and runs
 * anywhere clang and libFuzzer do (ClusterFuzzLite, see .clusterfuzzlite/ and docs in CONTRIBUTING.md).
 *
 * Input layout (every field is optional; a short input reads as zeros):
 *   byte 0       open flags: bit 0 in-memory database, bit 1 skip the key, bit 2 rekey with the same key
 *   byte 1       cipher_compatibility: 0 = default, 1 to 4 = SQLCipher 1 to 4
 *   byte 2       cipher_page_size selector (index into kPageSizes, 0 = default)
 *   byte 3       key length (0 to 64), then that many key bytes
 *   byte  ...    parameter count (0 to 8); per parameter one kind byte, a length byte and the bytes
 *   rest         the SQL text
 *
 * Bounded on purpose: key derivation runs with one iteration, the SQL is capped, a progress handler
 * interrupts runaway queries, ATTACH is refused, and the heap is limited.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "sqlite3.h"

#define MAX_SQL 4096
#define MAX_STATEMENTS 8
#define MAX_ROWS 64
#define MAX_PARAMS 8
#define OPS_PER_STATEMENT 20000

static const int kPageSizes[] = {0, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536};

typedef struct {
    const uint8_t *data;
    size_t size;
    size_t at;
} Reader;

static uint8_t read_byte(Reader *r) {
    return r->at < r->size ? r->data[r->at++] : 0;
}

static size_t read_bytes(Reader *r, size_t want, const uint8_t **out) {
    size_t left = r->size - r->at;
    size_t n = want < left ? want : left;
    *out = r->data + r->at;
    r->at += n;
    return n;
}

static int deny_attach(void *arg, int action, const char *a, const char *b, const char *c, const char *d) {
    (void) arg;
    (void) a;
    (void) b;
    (void) c;
    (void) d;
    return action == SQLITE_ATTACH ? SQLITE_DENY : SQLITE_OK;
}

static int too_long(void *counter) {
    return ++*(int *) counter > OPS_PER_STATEMENT;
}

static void exec_quiet(sqlite3 *db, const char *sql) {
    sqlite3_exec(db, sql, NULL, NULL, NULL);
}

static void run_statement(sqlite3_stmt *stmt, const uint8_t *kinds, const uint8_t *lengths,
                          const uint8_t *const *values, int param_count) {
    int slots = sqlite3_bind_parameter_count(stmt);
    for (int i = 1; i <= slots; i++) {
        int p = (i - 1) % (param_count > 0 ? param_count : 1);
        if (param_count == 0) {
            sqlite3_bind_null(stmt, i);
            continue;
        }
        switch (kinds[p] % 5) {
        case 0:
            sqlite3_bind_null(stmt, i);
            break;
        case 1:
            sqlite3_bind_int64(stmt, i, (sqlite3_int64) lengths[p] * 0x0101010101010101LL);
            break;
        case 2:
            sqlite3_bind_blob(stmt, i, values[p], lengths[p], SQLITE_TRANSIENT);
            break;
        case 3:
            sqlite3_bind_text(stmt, i, (const char *) values[p], lengths[p], SQLITE_TRANSIENT);
            break;
        default:
            sqlite3_bind_text16(stmt, i, values[p], lengths[p] & ~1, SQLITE_TRANSIENT);
            break;
        }
    }
    for (int row = 0; row < MAX_ROWS && sqlite3_step(stmt) == SQLITE_ROW; row++) {
        int columns = sqlite3_column_count(stmt);
        for (int c = 0; c < columns; c++) {
            switch (sqlite3_column_type(stmt, c)) {
            case SQLITE_BLOB:
                (void) sqlite3_column_blob(stmt, c);
                (void) sqlite3_column_bytes(stmt, c);
                break;
            case SQLITE_TEXT:
                (void) sqlite3_column_text16(stmt, c);
                (void) sqlite3_column_bytes16(stmt, c);
                (void) sqlite3_column_text(stmt, c);
                break;
            default:
                (void) sqlite3_column_int64(stmt, c);
                (void) sqlite3_column_double(stmt, c);
                break;
            }
        }
    }
    sqlite3_reset(stmt);
    sqlite3_clear_bindings(stmt);
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    static int initialized = 0;
    if (!initialized) {
        sqlite3_initialize();
        sqlite3_config(SQLITE_CONFIG_LOG, NULL, NULL);
        sqlite3_hard_heap_limit64(256 * 1024 * 1024);
        initialized = 1;
    }
    Reader r = {data, size, 0};
    uint8_t flags = read_byte(&r);
    uint8_t compatibility = read_byte(&r);
    uint8_t page_size = read_byte(&r);
    uint8_t key_length = read_byte(&r) % 65;
    const uint8_t *key;
    size_t key_size = read_bytes(&r, key_length, &key);

    int param_count = read_byte(&r) % (MAX_PARAMS + 1);
    uint8_t kinds[MAX_PARAMS] = {0};
    uint8_t lengths[MAX_PARAMS] = {0};
    const uint8_t *values[MAX_PARAMS] = {0};
    for (int i = 0; i < param_count; i++) {
        kinds[i] = read_byte(&r);
        size_t want = read_byte(&r);
        lengths[i] = (uint8_t) read_bytes(&r, want, &values[i]);
    }
    const uint8_t *sql_bytes;
    size_t sql_size = read_bytes(&r, MAX_SQL, &sql_bytes);
    char *sql = (char *) malloc(sql_size + 1);
    if (sql == NULL) return 0;
    memcpy(sql, sql_bytes, sql_size);
    sql[sql_size] = '\0';

    char path[64] = ":memory:";
    int on_disk = !(flags & 1);
    if (on_disk) {
        snprintf(path, sizeof path, "/tmp/sealite-fuzz-%d.db", (int) getpid());
        unlink(path);
    }
    sqlite3 *db = NULL;
    if (sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, NULL) != SQLITE_OK) {
        sqlite3_close_v2(db);
        free(sql);
        return 0;
    }
    sqlite3_set_authorizer(db, deny_attach, NULL);
    sqlite3_limit(db, SQLITE_LIMIT_LENGTH, 1 << 20);
    sqlite3_limit(db, SQLITE_LIMIT_SQL_LENGTH, MAX_SQL);
    int ops = 0;
    sqlite3_progress_handler(db, 100, too_long, &ops);

    /* No fsync per input: durability is not under test, speed is. */
    exec_quiet(db, "PRAGMA synchronous = OFF");

    /* The order the library uses (OpenSequence): key first (SQLCipher derives it lazily, at the first page
     * access), then compatibility, overrides and memory security. */
    if (!(flags & 2) && key_size > 0) sqlite3_key_v2(db, "main", key, (int) key_size);
    if (compatibility % 5 != 0) {
        char pragma[64];
        snprintf(pragma, sizeof pragma, "PRAGMA cipher_compatibility = %d", compatibility % 5);
        exec_quiet(db, pragma);
    }
    exec_quiet(db, "PRAGMA kdf_iter = 1");
    if (page_size % 9 != 0) {
        char pragma[64];
        snprintf(pragma, sizeof pragma, "PRAGMA cipher_page_size = %d", kPageSizes[page_size % 9]);
        exec_quiet(db, pragma);
    }
    exec_quiet(db, "PRAGMA cipher_memory_security = ON");
    if ((flags & 4) && !(flags & 2) && key_size > 0) sqlite3_rekey_v2(db, "main", key, (int) key_size);

    const char *tail = sql;
    for (int n = 0; n < MAX_STATEMENTS && *tail != '\0'; n++) {
        sqlite3_stmt *stmt = NULL;
        const char *next = NULL;
        ops = 0;
        if (sqlite3_prepare_v2(db, tail, -1, &stmt, &next) != SQLITE_OK) break;
        if (stmt != NULL) {
            run_statement(stmt, kinds, lengths, values, param_count);
            sqlite3_finalize(stmt);
        }
        if (next == NULL || next <= tail) break;
        tail = next;
    }

    sqlite3_close_v2(db);
    if (on_disk) {
        char side[80];
        unlink(path);
        snprintf(side, sizeof side, "%s-journal", path);
        unlink(side);
        snprintf(side, sizeof side, "%s-wal", path);
        unlink(side);
        snprintf(side, sizeof side, "%s-shm", path);
        unlink(side);
    }
    free(sql);
    return 0;
}
