package fixtures.gen;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.test.platform.app.InstrumentationRegistry;

import net.zetetic.database.sqlcipher.SupportOpenHelperFactory;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

/**
 * Writes zetetic_raw_v4.db the way an app using net.zetetic:sqlcipher-android through
 * androidx.sqlite's SupportSQLiteOpenHelper does. The passphrase handed to the factory is
 * the ASCII bytes of the raw-key literal x'<64 hex>'.
 */
public class GenerateFixtureTest {
    static final String RAW_KEY_LITERAL =
            "x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'";
    static final String NAME = "zetetic_raw_v4.db";

    @Test
    public void generate() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        System.loadLibrary("sqlcipher");
        File f = ctx.getDatabasePath(NAME);
        f.getParentFile().mkdirs();
        for (String suffix : new String[] {"", "-wal", "-shm", "-journal"}) {
            new File(f.getPath() + suffix).delete();
        }

        byte[] passphrase = RAW_KEY_LITERAL.getBytes(StandardCharsets.US_ASCII);
        SupportOpenHelperFactory factory = new SupportOpenHelperFactory(passphrase);
        SupportSQLiteOpenHelper.Configuration cfg = SupportSQLiteOpenHelper.Configuration
                .builder(ctx)
                .name(NAME)
                .callback(new SupportSQLiteOpenHelper.Callback(7) {
                    @Override public void onCreate(SupportSQLiteDatabase db) {
                        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY, title TEXT, body BLOB)");
                        insert(db, 1, "hello", new byte[] {0x00, (byte) 0xFF, 0x10});
                        byte[] blob = new byte[4096];
                        for (int i = 0; i < blob.length; i++) blob[i] = (byte) (i % 251);
                        insert(db, 2, "Привіт, світ 🌍", blob);
                        insert(db, 3, "empty body", new byte[0]);
                    }
                    @Override public void onUpgrade(SupportSQLiteDatabase db, int o, int n) {}
                })
                .build();
        SupportSQLiteOpenHelper helper = factory.create(cfg);
        SupportSQLiteDatabase db = helper.getWritableDatabase();
        try (Cursor c = db.query("PRAGMA journal_mode")) { c.moveToFirst(); System.out.println("FIXTURE journal_mode=" + c.getString(0)); }
        try (Cursor c = db.query("PRAGMA cipher_version")) { c.moveToFirst(); System.out.println("FIXTURE cipher_version=" + c.getString(0)); }
        try (Cursor c = db.query("PRAGMA wal_checkpoint(TRUNCATE)")) { c.moveToFirst(); }
        assertEquals(7, db.getVersion());
        try (Cursor c = db.query("SELECT count(*) FROM notes")) { c.moveToFirst(); assertEquals(3, c.getInt(0)); }
        helper.close();
        System.out.println("FIXTURE wrote " + f + " size=" + f.length()
                + " wal=" + new File(f.getPath() + "-wal").exists());
    }

    private static void insert(SupportSQLiteDatabase db, long id, String title, byte[] body) {
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("title", title);
        v.put("body", body);
        db.insert("notes", 0, v);
    }
}
